package com.treode.store

import scala.util.Random

private trait TimedTestTools {

  implicit class RichBytes (v: Bytes) {
    def ## (time: Int) = TimedCell (v, TxClock (time), None)
    def ## (time: TxClock) = TimedCell (v, time, None)
    def :: (time: Int) = Value (TxClock (time), Some (v))
    def :: (time: TxClock) = Value (time, Some (v))
    def :: (cell: TimedCell) = TimedCell (cell.key, cell.time, Some (v))
  }

  implicit class RichOption (v: Option [Bytes]) {
    def :: (time: Int) = Value (TxClock (time), v)
    def :: (time: TxClock) = Value (time, v)
  }

  implicit class RichTxClock (v: TxClock) {
    def + (n: Int) = TxClock (v.time + n)
    def - (n: Int) = TxClock (v.time - n)
  }

  def nextTable = TableId (Random.nextLong)

  def Get (id: TableId, key: Bytes): ReadOp =
    ReadOp (id, key)
}

private object TimedTestTools extends TimedTestTools
