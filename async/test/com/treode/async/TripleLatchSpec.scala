package com.treode.async

import org.scalatest.FlatSpec

class TripleLatchSpec extends FlatSpec {

  class DistinguishedException extends Exception

  "The TripleLatch" should "release after a, b and c are set" in {
    val cb = CallbackCaptor [(Int, Int, Int)]
    val (la, lb, lc) = Latch.triple (cb)
    cb.expectNotInvoked()
    la.pass (1)
    cb.expectNotInvoked()
    lb.pass (2)
    cb.expectNotInvoked()
    lc.pass (3)
    expectResult ((1, 2, 3)) (cb.passed)
  }

  it should "reject two sets on a" in {
    val cb = CallbackCaptor [(Int, Int)]
    val (la, lb) = Latch.pair (cb)
    la.pass (1)
    intercept [Exception] (la.pass (0))
  }

  it should "reject two sets on b" in {
    val cb = CallbackCaptor [(Int, Int)]
    val (la, lb) = Latch.pair (cb)
    lb.pass (2)
    intercept [Exception] (lb.pass (0))
  }

  it should "reject two sets on c" in {
    val cb = CallbackCaptor [(Int, Int, Int)]
    val (la, lb, lc) = Latch.triple (cb)
    lc.pass (4)
    intercept [Exception] (lc.pass (0))
  }

  it should "release after two passes but a fail on a" in {
    val cb = CallbackCaptor [(Int, Int, Int)]
    val (la, lb, lc) = Latch.triple (cb)
    cb.expectNotInvoked()
    la.fail (new DistinguishedException)
    cb.expectNotInvoked()
    lb.pass (2)
    cb.expectNotInvoked()
    lc.pass (3)
    cb.failed [DistinguishedException]
  }

  it should "release after two passes but a fail on b" in {
    val cb = CallbackCaptor [(Int, Int, Int)]
    val (la, lb, lc) = Latch.triple (cb)
    cb.expectNotInvoked()
    la.pass (1)
    cb.expectNotInvoked()
    lb.fail (new DistinguishedException)
    cb.expectNotInvoked()
    lc.pass (3)
    cb.failed [DistinguishedException]
  }

  it should "release after two passes but a fail on c" in {
    val cb = CallbackCaptor [(Int, Int, Int)]
    val (la, lb, lc) = Latch.triple (cb)
    cb.expectNotInvoked()
    la.pass (1)
    cb.expectNotInvoked()
    lb.pass (2)
    cb.expectNotInvoked()
    lc.fail (new DistinguishedException)
    cb.failed [DistinguishedException]
  }}