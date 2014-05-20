package com.treode.disk.stubs

import scala.collection.immutable.Queue
import scala.util.Random

import com.treode.async.{Async, Callback, Fiber, Scheduler}
import com.treode.async.implicits._
import com.treode.disk.CheckpointRegistry

import Callback.{fanout, ignore}

private class StubCheckpointer (implicit
    random: Random,
    scheduler: Scheduler,
    disk: StubDiskDrive,
    config: StubDiskConfig
) {

  val fiber = new Fiber
  var checkpoints: CheckpointRegistry = null
  var entries = 0
  var checkreqs = List.empty [Callback [Unit]]
  var engaged = true

  private def reengage() {
    fanout (checkreqs) .pass()
    checkreqs = List.empty
    entries = 0
    engaged = false
  }

  private def _checkpoint() {
    engaged = true
    val mark = disk.mark()
    checkpoints .checkpoint() .map { _ =>
      disk.checkpoint (mark)
      fiber.execute (reengage())
    } .run (ignore)
  }

  def launch (checkpoints: CheckpointRegistry): Unit =
    fiber.execute {
      this.checkpoints = checkpoints
      if (!checkreqs.isEmpty || config.checkpoint (entries))
        _checkpoint()
      else
        engaged = false
    }

  def checkpoint(): Async [Unit] =
    fiber.async { cb =>
      checkreqs ::= cb
      if (!engaged)
        _checkpoint()
    }

  def tally(): Unit =
    fiber.execute {
      entries += 1
      if (!engaged && config.checkpoint (entries))
        _checkpoint()
    }}
