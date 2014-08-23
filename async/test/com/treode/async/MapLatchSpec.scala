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

package com.treode.async

import com.treode.async.implicits._
import com.treode.async.stubs.CallbackCaptor
import org.scalatest.FlatSpec

class MapLatchSpec extends FlatSpec {

  class DistinguishedException extends Exception

  "The MapLatch" should "release immediately for count==0" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    Latch.map [Int, Int] (0, cb)
    assertResult (Map [Int, Int] ()) (cb.assertPassed())
  }

  it should "reject extra releases" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (0, cb)
    assertResult (Map [Int, Int] ()) (cb.assertPassed())
    intercept [Exception] (ltch.pass (0, 0))
  }

  it should "release after one pass for count==1" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (1, cb)
    cb.assertNotInvoked()
    ltch.pass (0, 1)
    assertResult (Map ((0, 1))) (cb.assertPassed())
  }

  it should "release after one fail for count==1" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (1, cb)
    cb.assertNotInvoked()
    ltch.fail (new DistinguishedException)
    cb.assertFailed [DistinguishedException]
  }

  it should "release after two passes for count==2" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (2, cb)
    cb.assertNotInvoked()
    ltch.pass (0, 1)
    cb.assertNotInvoked()
    ltch.pass (1, 2)
    assertResult (Map ((0, 1), (1, 2))) (cb.assertPassed())
  }

  it should "release after two reversed passes for count==2" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (2, cb)
    cb.assertNotInvoked()
    ltch.pass (1, 2)
    cb.assertNotInvoked()
    ltch.pass (0, 1)
    assertResult (Map ((0, 1), (1, 2))) (cb.assertPassed())
  }

  it should "release after a pass and a fail for count==2" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (2, cb)
    cb.assertNotInvoked()
    ltch.pass (0, 0)
    cb.assertNotInvoked()
    ltch.fail (new DistinguishedException)
    cb.assertFailed [DistinguishedException]
  }

  it should "release after two fails for count==2" in {
    val cb = CallbackCaptor [Map [Int, Int]]
    val ltch = Latch.map [Int, Int] (2, cb)
    cb.assertNotInvoked()
    ltch.fail (new Exception)
    cb.assertNotInvoked()
    ltch.fail (new Exception)
    cb.assertFailed [MultiException]
  }}
