/*
 * Copyright 2014 Treode, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.treode.disk

import com.treode.async.{Async, Scheduler}, Async.guard
import com.treode.async.io.File
import com.treode.async.misc.EpochReleaser
import com.treode.buffer.PagedBuffer

private class BootstrapDisk (
  common: SuperBlock.Common,
  drives: Map [Int, BootstrapDrive],
  logBatch: Long,
  logBytes: Long,
  logEntries: Long
) (implicit
  files: FileSystem,
  scheduler: Scheduler,
  config: DiskConfig,
  events: DiskEvents
) extends Disk {

  private val logdsp = new LogDispatcher
  private val pagdsp = new PageDispatcher
  private val compactor = new Compactor
  private val releaser = new EpochReleaser
  private val compactions = new GenerationDocket
  private val releases = new GenerationDocket

  logdsp.batch = logBatch

  def record [R] (desc: RecordDescriptor [R], record: R): Async [Unit] =
    logdsp.record (desc, record)

  def read [P] (desc: PageDescriptor [P], pos: Position): Async [P] =
    drives (pos.disk) fetch (desc, pos.offset, pos.length)

  def write [P] (desc: PageDescriptor [P], obj: ObjectId, gen: Long, page: P): Async [Position] =
    pagdsp.write (desc, obj, gen, page)

  def compact (desc: PageDescriptor[_], obj: ObjectId): Unit =
    compactor.compact (desc.id, obj)

  def release (desc: PageDescriptor [_], obj: ObjectId, gens: Set [Long]): Unit =
    synchronized (releases.add (desc.id, obj, gens))

  def join [A] (task: Async [A]): Async [A] =
    releaser.join (task)

  def result (ledger: SegmentLedger): (DriveGroup, DiskAgent) = {
    val group = new DriveGroup (logdsp, pagdsp, compactor, ledger, drives, common, logBytes, logEntries)
    val cache = new PageCache (group)
    val agent = new DiskAgent (logdsp, pagdsp, compactor, releaser, ledger, group, cache)
    (group, agent)
  }}
