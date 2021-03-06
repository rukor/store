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

package com.treode.store.atomic

import scala.util.{Failure, Success}

import com.treode.async.Async, Async.supply
import com.treode.cluster.RequestDescriptor
import com.treode.store._

private class ScanDeputy (kit: AtomicKit) {
  import kit.{cluster, disk, tstore}

  def scan (params: ScanParams): Async [(Seq [Cell], Option [Key])] =
    disk.join {
      tstore
      .scan (params)
      .rebatch (params.end, params.batch)
    }

  def attach() {

    ScanDeputy.scan.listen { case (params, from) =>
      scan (params)
    }}}

private object ScanDeputy {

  val scan = {
    import AtomicPicklers._
    RequestDescriptor (0x709C4497F017AA86L, scanParams, tuple (seq (cell), option (key)))
  }}
