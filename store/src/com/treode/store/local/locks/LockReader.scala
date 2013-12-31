package com.treode.store.local.locks

import com.treode.async.toRunnable
import com.treode.store.TxClock

// Tracks the acquisition of locks and invokes the callback when they have all been granted.
private class LockReader (_rt: TxClock, cb: Runnable) {

  // For testing mocks.
  def this() = this (TxClock.zero, toRunnable(()))

  private var needed = 0

  private def finish() {
    cb.run()
  }

  // Attempt to acquire the locks.  Some of them will be granted immediately.  For others, we
  // will receive a callback via grant().
  def init (space: LockSpace, ids: Set [Int]) {
    val ready = synchronized {
      for (id <- ids)
        if (!space.read (id, this))
          needed += 1
      needed == 0
    }
    if (ready)
      finish()
  }

  def rt = _rt

  def grant(): Unit = {
    val ready = synchronized {
      needed -= 1
      needed == 0
    }
    if (ready)
      finish()
  }

  override def toString = s"LockReader (rt=$rt, ready=${needed == 0})"

}