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

package com.treode.store

/** The parameters for a scan.
 *
 * @param table The table to scan.
 * @param start The key at which to begin the scan; use `whilst` of
 *   [[com.treode.async.AsyncIterator AsyncIterator]] to control when it ends.
 * @param window Specify which versions of the row to include in the scan.  A scan can iterate
 *   only the most recent update or all updates over some period.  See [[Window$ Window]] for
 *   details.
 * @param slice A slice of the rows to scan, where that slice respects replica placement; see
 *   [[Slice]] for details.
 * @param batch A hint how to chop the scan into batches; see [[Batch]] for details.
 */
class ScanParams (
  val table: TableId,
  val key: Bytes,
  val time: TxClock,
  val end: Option [Bytes],
  val window: Window,
  val slice: Slice,
  val batch: Batch
) {

  def copy (
    key: Bytes = Bytes.empty,
    time: TxClock = TxClock.MaxValue,
    end: Option [Bytes] = end,
    window: Window = window,
    slice: Slice = slice,
    batch: Batch = batch
  ): ScanParams =
    new ScanParams (table, key, time, end, window, slice, batch)
}

object ScanParams {

  def apply (
    table: TableId,
    key: Bytes = Bytes.empty,
    time: TxClock = TxClock.MaxValue,
    end: Option [Bytes] = None,
    window: Window = Window.all,
    slice: Slice = Slice.all,
    batch: Batch = Batch.suggested
  ): ScanParams =
    new ScanParams (table, key, time, end, window, slice, batch)

  val pickler = {
    import StorePicklers._
    wrap (tableId, bytes, txClock, option (bytes), window, slice, batch)
    .build (v => new ScanParams (v._1, v._2, v._3, v._4, v._5, v._6, v._7))
    .inspect (v => (v.table, v.key, v.time, v.end, v.window, v.slice, v.batch))
  }}
