package com.treode.disk

import java.nio.file.Path
import scala.collection.JavaConversions._
import scala.collection.mutable.{ArrayBuffer, UnrolledBuffer}

import com.treode.async
import com.treode.async.{Async, Callback, Fiber, Latch}
import com.treode.async.io.File
import com.treode.buffer.PagedBuffer

import Async.guard
import DiskDrive.offset
import RecordHeader._

private class DiskDrive (
    val id: Int,
    val path: Path,
    val file: File,
    val geometry: DiskGeometry,
    val alloc: Allocator,
    val disks: DiskDrives,
    var draining: Boolean,
    var logSegs: ArrayBuffer [Int],
    var logHead: Long,
    var logTail: Long,
    var logLimit: Long,
    var logBuf: PagedBuffer,
    var pageSeg: SegmentBounds,
    var pageHead: Long,
    var pageLedger: PageLedger,
    var pageLedgerDirty: Boolean
) {
  import disks.{checkpointer, compactor, config, scheduler}

  val fiber = new Fiber (scheduler)
  val logmp = new Multiplexer [PickledRecord] (disks.logd)
  val logr: UnrolledBuffer [PickledRecord] => Unit = (receiveRecords _)
  val pagemp = new Multiplexer [PickledPage] (disks.paged)
  val pager: UnrolledBuffer [PickledPage] => Unit = (receivePages _)

  def record (entry: RecordHeader, cb: Callback [Unit]): Unit =
    logmp.send (PickledRecord (id, entry, cb))

  def added() {
    logmp.receive (logr)
    pagemp.receive (pager)
  }

  def mark(): Async [Unit] =
    fiber.supply {
      logHead = logTail
    }

  def checkpoint (boot: BootBlock, cb: Callback [Unit]): Unit =
    fiber.defer (cb) {
      val superb = SuperBlock (
          id, boot, geometry, draining, alloc.free, logSegs.head, logHead, pageSeg.num, pageHead)
      if (pageLedgerDirty) {
        pageLedgerDirty = false
        PageLedger.write (pageLedger.clone(), file, pageSeg.pos, async.continue (cb) { _: Unit =>
          SuperBlock.write (boot.bootgen, superb, file, cb)
        })
      } else {
        SuperBlock.write (boot.bootgen, superb, file, cb)
      }}

  private def _cleanable: Iterator [SegmentPointer] = {
      val skip = new ArrayBuffer [Int] (logSegs.size + 1)
      skip ++= logSegs
      if (!draining)
        skip += pageSeg.num
      for (seg <- alloc.cleanable (skip))
        yield SegmentPointer (this, geometry.segmentBounds (seg))
  }

  def cleanable(): Async [Iterator [SegmentPointer]] =
    fiber.supply {
      _cleanable
    }

  def free (segs: Seq [SegmentPointer]): Unit =
    fiber.execute {
      val nums = IntSet (segs.map (_.num) .sorted: _*)
      alloc.free (nums)
      record (SegmentFree (nums), Callback.ignore)
      if (draining && alloc.drained (logSegs))
        disks.detach (this)
    }

  def drain(): Async [Iterator [SegmentPointer]] = // TODO: async
    fiber.async { cb =>
      draining = true
      val ready = fiber.callback (cb) { _: Unit =>
        _cleanable
      }
      val latch = Latch.unit (2, ready)
      val pagesClosed = fiber.continue (latch) { _: Unit =>
        if (pageLedgerDirty) {
          pageLedgerDirty = false
          PageLedger.write (pageLedger, file, pageSeg.pos, latch)
        } else {
          latch.pass()
        }}
      pagemp.close (pagesClosed)
      record (DiskDrain, latch)
    }

  def detach(): Unit =
    fiber.execute {
      val recordsClosed = fiber.callback (Callback.ignore) { _: Unit =>
        file.close()
      }
      logmp.close (recordsClosed)
    }

  private def splitRecords (entries: UnrolledBuffer [PickledRecord]) = {
    val accepts = new UnrolledBuffer [PickledRecord]
    val rejects = new UnrolledBuffer [PickledRecord]
    var pos = logHead
    var realloc = false
    for (entry <- entries) {
      if (entry.disk.isDefined && entry.disk.get != id) {
        rejects.add (entry)
      } else if (draining && entry.disk.isEmpty) {
        rejects.add (entry)
      } else if (pos + entry.byteSize + RecordHeader.trailer < logLimit) {
        accepts.add (entry)
        pos += entry.byteSize
      } else {
        rejects.add (entry)
        realloc = true
      }}
    (accepts, rejects, realloc)
  }

  private def writeRecords (buf: PagedBuffer, entries: UnrolledBuffer [PickledRecord]) = {
    val callbacks = new UnrolledBuffer [Callback [Unit]]
    for (entry <- entries) {
      entry.write (buf)
      callbacks.add (entry.cb)
    }
    callbacks
  }

  def receiveRecords (entries: UnrolledBuffer [PickledRecord]): Unit =
    fiber.execute {

      val (accepts, rejects, realloc) = splitRecords (entries)
      logmp.replace (rejects)
      if (accepts.isEmpty) {
        logmp.receive (logr)
        return
      }

      val callbacks = writeRecords (logBuf, accepts)
      val cb = Callback.fanout (callbacks, scheduler)

      checkpointer.tally (logBuf.readableBytes, accepts.size)

      if (realloc) {

        val newBuf = PagedBuffer (12)
        val newSeg = alloc.alloc (geometry, config)
        RecordHeader.pickler.frame (LogEnd, newBuf)
        RecordHeader.pickler.frame (LogAlloc (newSeg.num), logBuf)
        val oldFlushed = fiber.callback (cb) { _: Unit =>
          logSegs.add (newSeg.num)
          logTail = newSeg.pos
          logLimit = newSeg.limit
          logBuf.clear()
          logmp.receive (logr)
        }
        val newFlushed = fiber.continue (cb) { _: Unit =>
          file.flush (logBuf, logTail, oldFlushed)
        }
        file.flush (newBuf, newSeg.pos,  newFlushed)

      } else {

        val len = logBuf.readableBytes
        RecordHeader.pickler.frame (LogEnd, logBuf)
        val flushed = fiber.callback (cb) { _: Unit =>
          logTail += len
          logBuf.clear()
          logmp.receive (logr)
        }
        file.flush (logBuf, logTail, flushed)
      }}

  private def splitPages (pages: UnrolledBuffer [PickledPage]) = {

    val projector = pageLedger.project
    val limit = (pageHead - pageSeg.pos).toInt
    val accepts = new UnrolledBuffer [PickledPage]
    val rejects = new UnrolledBuffer [PickledPage]
    var totalBytes = 0
    var realloc = false
    for (page <- pages) {
      projector.add (page.id, page.group)
      val pageBytes = geometry.blockAlignLength (page.byteSize)
      val ledgerBytes = geometry.blockAlignLength (projector.byteSize)
      if (ledgerBytes + pageBytes < limit) {
        accepts.add (page)
        totalBytes += pageBytes
      } else {
        rejects.add (page)
        realloc = true
      }}
    (accepts, rejects, realloc)
  }

  private def writePages (pages: UnrolledBuffer [PickledPage]) = {
    val buffer = PagedBuffer (12)
    val callbacks = new UnrolledBuffer [Callback [Long]]
    val ledger = new PageLedger
    for (page <- pages) {
      val start = buffer.writePos
      page.write (buffer)
      buffer.writeZeroToAlign (geometry.blockBits)
      val length = buffer.writePos - start
      callbacks.add (offset (id, start, length, page.cb))
      ledger.add (page.id, page.group, length)
    }
    (buffer, callbacks, ledger)
  }

  def receivePages (pages: UnrolledBuffer [PickledPage]) {

    val (accepts, rejects, realloc) = splitPages (pages)
    pagemp.replace (rejects)
    if (accepts.isEmpty) {
      pagemp.receive (pager)
      return
    }

    val (buffer, callbacks, ledger) = writePages (pages)
    val pos = pageHead - buffer.readableBytes
    val cb = Callback.fanout (callbacks, scheduler)

    val flushed = fiber.continue (cb) { _: Unit =>
      if (realloc) {

        compactor.tally (1)
        val logged = fiber.callback (cb) { _: Unit =>
          pagemp.receive (pager)
          pos
        }
        val newWritten = fiber.continue (cb) { _: Unit =>
          pageLedgerDirty = false
          record (PageAlloc (pageSeg.num, ledger.zip), logged)
        }
        val oldWritten = fiber.continue (cb) { _: Unit =>
          pageSeg = alloc.alloc (geometry, config)
          pageHead = pageSeg.limit
          pageLedger = new PageLedger
          pageLedgerDirty = true
        }
        pageLedger.add (ledger)
        pageLedgerDirty = true
        PageLedger.write (pageLedger, file, pageSeg.pos, oldWritten)

      } else {

        val logged = fiber.callback (cb) { _: Unit =>
          pageHead = pos
          pageLedger.add (ledger)
          pageLedgerDirty = true
          pagemp.receive (pager)
          pos
        }
        record (PageWrite (pos, ledger.zip), logged)
      }}

    file.flush (buffer, pos, flushed)
  }}

private object DiskDrive {

  def offset (id: Int, offset: Long, length: Int, cb: Callback [Position]): Callback [Long] =
    new Callback [Long] {
      def pass (base: Long) = cb.pass (Position (id, base + offset, length))
      def fail (t: Throwable) = cb.fail (t)
    }

  def read [P] (file: File, desc: PageDescriptor [_, P], pos: Position): Async [P] =
    guard {
      val buf = PagedBuffer (12)
      for (_ <- file.fill (buf, pos.offset, pos.length))
        yield desc.ppag.unpickle (buf)
    }

  def init (
      id: Int,
      path: Path,
      file: File,
      geometry: DiskGeometry,
      boot: BootBlock,
      disks: DiskDrives,
      cb: Callback [DiskDrive]
  ): Unit =

    async.defer (cb) {
      import disks.{config}

      val alloc = Allocator (geometry, config)
      val logSeg = alloc.alloc (geometry, config)
      val pageSeg = alloc.alloc (geometry, config)
      val logSegs = new ArrayBuffer [Int]
      logSegs += logSeg.num

      val superb = SuperBlock (
          id, boot, geometry, false, alloc.free, logSeg.num, logSeg.pos, pageSeg.num, pageSeg.limit)

      val latch = Latch.unit (3, async.callback (cb) { _: Unit =>
        new DiskDrive (
            id, path, file, geometry, alloc, disks, false, logSegs, logSeg.pos, logSeg.pos,
            logSeg.limit, PagedBuffer (12), pageSeg, pageSeg.limit, new PageLedger, false)
      })

      SuperBlock.write (0, superb, file, latch)
      RecordHeader.write (LogEnd, file, logSeg.pos, latch)
      PageLedger.write (PageLedger.Zipped.empty, file, pageSeg.pos, latch)
    }}
