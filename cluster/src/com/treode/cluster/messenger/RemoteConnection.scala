package com.treode.cluster.messenger

import java.nio.channels.AsynchronousChannelGroup
import java.util
import scala.collection.JavaConversions._
import scala.language.postfixOps
import scala.util.Random

import com.treode.cluster.{ClusterEvents, HostId, MailboxId, Peer, messenger}
import com.treode.cluster.events.Events
import com.treode.cluster.io.Socket
import com.treode.cluster.misc.{BackoffTimer, RichInt}
import com.treode.concurrent.{Callback, Fiber}
import com.treode.pickle.{Buffer, Pickler, pickle, unpickle}

private class RemoteConnection (
  val id: HostId,
  localId: HostId,
  fiber: Fiber,
  group: AsynchronousChannelGroup,
  mailboxes: MailboxRegistry) (
    implicit events: Events
) extends Peer {

  require (id != localId)

  type Queue = util.ArrayList [PickledMessage]

  abstract class State {

    def disconnect (socket: Socket) = ()

    def unblock() = ()

    def sent() = ()

    def connect (socket: Socket, input: Buffer, clientId: HostId) {
      Loop (socket, input)
      state = Connected (socket, clientId, Buffer (12))
    }

    def close() {
      state = Closed
    }

    def send (message: PickledMessage) = ()
  }

  abstract class HaveSocket extends State {

    def socket: Socket
    def clientId: HostId
    def buffer: Buffer
    def backoff: Iterator [Int]

    object Flushed extends Callback [Unit] {

      def pass (v: Unit) {
        //buffer.release()
        RemoteConnection.this.sent ()
      }

      def fail (t: Throwable) {
        //buffer.release()
        events.exceptionWritingMessage (t)
        RemoteConnection.this.disconnect (socket)
      }}

    def flush(): Unit = fiber.spawn {
      socket.flush (buffer, Flushed)
    }

    def enque (message: PickledMessage) {
      buffer.writeLong (message.mbx.id)
      buffer.writeInt (0)
      val start = buffer.writePos
      message.write (buffer)
      val end = buffer.writePos
      buffer.writePos = start - 4
      buffer.writeInt (end - start)
      buffer.writePos = end
    }

    override def disconnect (socket: Socket) {
      if (socket == this.socket) {
        socket.close()
        state = Disconnected (backoff)
      }}

    override def sent() {
      if (buffer.readableBytes == 0) {
        state = Connected (socket, clientId, buffer)
      } else {
        flush()
        state = Sending (socket, clientId)
      }}

    override def connect (socket: Socket, input: Buffer, clientId: HostId) {
      if (clientId < this.clientId) {
        socket.close()
      } else {
        if (socket != this.socket)
          this.socket.close()
        Loop (socket, input)
        if (buffer.readableBytes == 0) {
          state = Connected (socket, clientId, buffer)
        } else {
          flush()
          state = Sending (socket, clientId)
        }}}

    override def close() {
      socket.close()
      state = Closed
    }

    override def send (message: PickledMessage): Unit =
      enque (message)
  }

  case class Disconnected (backoff: Iterator [Int]) extends State {

    val time = System.currentTimeMillis

    override def send (message: PickledMessage) {
      val socket = Socket.open (group)
      greet (socket)
      state = new Connecting (socket, localId, time, backoff)
      state.send (message)
    }}

  case class Connecting (socket: Socket,  clientId: HostId, time: Long, backoff: Iterator [Int])
  extends HaveSocket {

    val buffer = Buffer (12)

    override def disconnect (socket: Socket) {
      if (socket == this.socket) {
        socket.close()
        state = Block (time, backoff)
      }}}

  case class Connected (socket: Socket, clientId: HostId, buffer: Buffer) extends HaveSocket {

    def backoff = BlockedTimer.iterator

    override def send (message: PickledMessage) {
      enque (message)
      flush()
      state = Sending (socket, clientId)
    }}

  case class Sending (socket: Socket, clientId: HostId) extends HaveSocket {

    val buffer = Buffer (12)

    def backoff = BlockedTimer.iterator
  }

  case class Block (time: Long, backoff: Iterator [Int]) extends State {

    fiber.at (time + backoff.next) (RemoteConnection.this.unblock())

    override def unblock() {
      state = Disconnected (backoff)
    }}

  case object Closed extends State

  private val BlockedTimer = BackoffTimer (500, 500, 1 minutes) (Random)

  // Visible for testing.
  private [messenger] var state: State = new Disconnected (BlockedTimer.iterator)

  case class Loop (socket: Socket, input: Buffer) {

    case class MessageRead (mbx: MailboxId, length: Int) extends Callback [Unit] {

      def pass (v: Unit) {
        mailboxes.deliver (mbx, RemoteConnection.this, input, length)
        readHeader()
      }

      def fail (t: Throwable) {
        events.exceptionReadingMessage (t)
        disconnect (socket)
      }}

    def readMessage (mbx: MailboxId, length: Int): Unit =
      socket.fill (input, length, MessageRead (mbx, length))

    object HeaderRead extends Callback [Unit] {

      def pass (v: Unit) {
        val mbx = input.readLong()
        val length = input.readInt()
        readMessage (mbx, length)
      }

      def fail (t: Throwable) {
        events.exceptionReadingMessage (t)
        disconnect (socket)
      }}

    def readHeader(): Unit =
      socket.fill (input, 12, HeaderRead)

    fiber.spawn (readHeader())
  }

  private def hearHello (socket: Socket) {
    val input = Buffer (12)
    socket.fill (input, 9, new Callback [Unit] {
      def pass (v: Unit) {
        val Hello (clientId) = unpickle (Hello.pickle, input)
        if (clientId == id) {
          connect (socket, input, localId)
        } else {
          events.errorWhileGreeting (id, clientId)
          disconnect (socket)
        }}
      def fail (t: Throwable) {
        events.exceptionWhileGreeting (t)
        disconnect (socket)
      }})
  }

  private def sayHello (socket: Socket) {
    val buffer = Buffer (12)
    pickle (Hello.pickle, Hello (localId), buffer)
    socket.flush (buffer, new Callback [Unit] {
      def pass (v: Unit) {
        hearHello (socket)
      }
      def fail (t: Throwable) {
        events.exceptionWhileGreeting (t)
        disconnect (socket)
      }})
  }

  private def greet (socket: Socket) {
    socket.connect (address, new Callback [Unit] {
      def pass (v: Unit) {
        sayHello (socket)
      }
      def fail (t: Throwable) {
        events.exceptionWhileGreeting (t)
        disconnect (socket)
      }})
  }

  private def disconnect (socket: Socket): Unit = fiber.execute {
    state.disconnect (socket)
  }

  private def unblock(): Unit = fiber.execute {
    state.unblock()
  }

  private def sent(): Unit = fiber.execute {
    state.sent()
  }

  def connect (socket: Socket, input: Buffer, clientId: HostId): Unit = fiber.execute {
    state.connect (socket, input, clientId)
  }

  def close(): Unit = fiber.execute {
    state.close()
  }

  def send [A] (p: Pickler [A], mbx: MailboxId, msg: A): Unit = fiber.execute {
    state.send (PickledMessage (p, mbx, msg))
  }

  override def hashCode() = id.hashCode()

  override def equals (other: Any): Boolean =
    other match {
      case that: Peer => id == that.id
      case _ => false
    }

  override def toString = "Peer(%08X)" format id.id
}