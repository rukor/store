package com.treode.store.catalog

import scala.util.Random

import com.treode.async.{AsyncTestTools, StubScheduler}
import com.treode.cluster.{Cluster, HostId, MailboxId, StubActiveHost, StubNetwork}
import com.treode.store.{Bytes, StoreConfig}
import com.treode.pickle.{Pickler, Picklers}
import org.scalacheck.Gen
import org.scalatest.{FreeSpec, PropSpec, ShouldMatchers, Specs}
import org.scalatest.prop.PropertyChecks

import AsyncTestTools._

class BrokerSpec extends Specs (BrokerBehaviors, BrokerProperties)

object BrokerBehaviors extends FreeSpec with ShouldMatchers {

  val ID1 = MailboxId (0xCB)
  val ID2 = MailboxId (0x2D)
  val cat1 = new CatalogDescriptor (ID1, Picklers.fixedLong)
  val cat2 = new CatalogDescriptor (ID2, Picklers.fixedLong)

  val values = Seq (
      0x292C28335A06E344L, 0xB58E76CED969A4C7L, 0xDF20D7F2B8C33B9EL, 0x63D5DAAF0C58D041L,
      0x834727637190788AL, 0x2AE35ADAA804CE32L, 0xE3AA9CFF24BC92DAL, 0xCE33BD811236E7ADL,
      0x7FAF87891BE9818AL, 0x3C15A9283BDFBA51L, 0xE8E45A575513FA90L, 0xE224EF2739907F79L,
      0xFC275E6C532CB3CBL, 0x40C505971288B2DDL, 0xCD1C2FD6707573E1L, 0x2D62491B453DA6A3L,
      0xA079188A7E0C8C39L, 0x46A5B2A69D90533AL, 0xD68C9A2FDAEE951BL, 0x7C781E5CF39A5EB1L)

  val bytes = values map (Bytes (_))

  val patches = {
    var prev = Bytes.empty
    for (v <- bytes) yield {
      val p = CatalogHandler.diff (prev, v)
      prev = v
      p
    }}

  private class RichBroker (implicit random: Random, scheduler: StubScheduler) {

    implicit val config = StoreConfig (8, 1<<20)

    val broker = new Broker

    def status = broker.status

    def ping (values: (MailboxId, Int)*) =
      broker.ping (values)

    def sync (other: RichBroker) {
      val task = for {
        status <- broker.status
        updates <- other.broker.ping (status)
      } yield broker.sync (updates)
      task.pass
    }

    def double (other: RichBroker) {
      val task = for {
        status <- broker.status
        updates <- other.broker.ping (status)
      } yield {
        broker.sync (updates)
        broker.sync (updates)
      }
      task.pass
    }

    def listen (desc: CatalogDescriptor [Long]) (f: Long => Any) =
      broker.listen (desc) (f)

    def issue (desc: CatalogDescriptor [Long]) (ver1: Int, val1: Long) = {
      broker.issue (desc) (ver1, val1)
      scheduler.runTasks()
    }}

  private def newBroker = {
    implicit val random = new Random (0)
    implicit val scheduler = StubScheduler.random (random)
    val broker = new RichBroker
    (scheduler, broker)
  }

  private def newBrokers = {
    implicit val random = new Random (0)
    implicit val scheduler = StubScheduler.random (random)
    implicit val config = StoreConfig (8, 1<<20)
    val broker1 = new RichBroker
    val broker2 = new RichBroker
    (scheduler, broker1, broker2)
  }

  "When the broker is empty, it should" - {

    "yield an empty status" in {
      implicit val (scheduler, broker) = newBroker
      broker.status expectSeq ()
    }

    "yield empty updates on empty ping" in {
      implicit val (scheduler, broker) = newBroker
      broker.ping () expectSeq ()
    }

    "yield empty updates on non-empty ping" in {
      implicit val (scheduler, broker) = newBroker
      broker.ping (ID1 -> 12) expectSeq ()
    }}

  "When the broker has one local update, it should" - {

    "yield a non-empty status" in {
      implicit val (scheduler, broker) = newBroker
      broker.issue (cat1) (1, values (0))
      broker.status expectSeq (ID1 -> 1)
    }

    def right (version: Int, patches: Seq [Bytes]): Update =
      Right ((version, patches))

    "yield non-empty deltas on empty ping" in {
      implicit val (scheduler, broker) = newBroker
      broker.issue (cat1) (1, values (0))
      broker.ping () expectSeq (ID1 -> right (0, patches take 1))
    }

    "yield non-empty deltas on ping missing the value" in {
      implicit val (scheduler, broker) = newBroker
      broker.issue (cat1) (1, values (0))
      broker.ping (ID2 -> 1) expectSeq (ID1 -> right (0, patches take 1))
    }

    "yield non-empty deltas on ping out-of-date with this host" in {
      implicit val (scheduler, broker) = newBroker
      broker.issue (cat1) (1, values (0))
      broker.issue (cat1) (2, values (1))
      broker.ping (ID1 -> 1) expectSeq (ID1 -> right (1, patches drop 1 take 1))
    }

    "yield empty deltas on ping up-to-date with this host" in {
      implicit val (scheduler, broker) = newBroker
      broker.issue (cat1) (1, values (0))
      broker.ping (ID1 -> 1) expectSeq ()
    }}

  "When the broker receives a sync with one value, it should" - {

    "yield a status that contains the value" in {
      implicit val (scheduler, b1, b2) = newBrokers
      b1.issue (cat1) (1, values (0))
      b2.sync (b1)
      b2.status expectSeq (ID1 -> 1)
    }

    "invoke the listener on a first update" in {
      implicit val (scheduler, b1, b2) = newBrokers
      var v = 0L
      b2.listen (cat1) (v = _)
      b1.issue (cat1) (1, values (0))
      b2.sync (b1)
      expectResult (values (0)) (v)
    }

    "invoke the listener on second update" in {
      implicit val (scheduler, b1, b2) = newBrokers
      var v = 0L
      b2.listen (cat1) (v = _)
      b1.issue (cat1) (1, values (0))
      b2.sync (b1)
      b1.issue (cat1) (2, values (1))
      b2.sync (b1)
      expectResult (values (1)) (v)
    }

    "ignore a repeated update" in {
      implicit val (scheduler, b1, b2) = newBrokers
      var count = 0
      b2.listen (cat1) (_ => count += 1)
      b1.issue (cat1) (1, values (0))
      b2.double (b1)
      expectResult (1) (count)
    }
  }

  "When the broker receives a sync with two values it should" - {

    "invoke the listener once foreach value" in {
      implicit val (scheduler, b1, b2) = newBrokers
      var v1 = 0L
      var v2 = 0L
      b2.listen (cat1) (v1 = _)
      b2.listen (cat2) (v2 = _)
      b1.issue (cat1) (1, values (0))
      b1.issue (cat2) (1, values (1))
      b2.sync (b1)
      expectResult (values (0)) (v1)
      expectResult (values (1)) (v2)
    }}}

object BrokerProperties extends PropSpec with PropertyChecks {

  val seeds = Gen.choose (0L, Long.MaxValue)

  val values = BrokerBehaviors.values

  val c1 = {
    import Picklers._
    CatalogDescriptor (0x07, fixedLong)
  }

  val c2 = {
    import Picklers._
    CatalogDescriptor (0x7A, seq (fixedLong))
  }

  class StubHost (id: HostId, network: StubNetwork) extends StubActiveHost (id, network) {
    import network.{random, scheduler}

    implicit val cluster: Cluster = this

    private val broker = new Broker

    var v1 = 0L
    var v2 = Seq.empty [Long]

    broker.listen (c1) (v1 = _)
    broker.listen (c2) (v2 = _)

    broker.attach (this)

    def issue [C] (desc: CatalogDescriptor [C]) (version: Int, cat: C): Unit =
      broker.issue (desc) (version, cat)
  }

  def checkUnity (seed: Long, mf: Double) {
    val kit = StubNetwork (seed)
    kit.messageFlakiness = mf
    val hs = kit.install (3, new StubHost (_, kit))
    for (h1 <- hs; h2 <- hs)
      h1.hail (h2.localId, null)
    kit.runTasks()

    val vs1 = values
    val vs2 = vs1.updated (5, 0x4B00FB5F38430882L)
    val vs3 = vs2.updated (17, 0x8C999CB6054CCB61L)
    val vs4 = vs3.updated (11, 0x3F081D8657CD9220L)

    val Seq (h1, h2, h3) = hs
    h1.issue (c1) (1, 0xED0F7511F6E3EC20L)
    h1.issue (c2) (1, vs1)
    h1.issue (c1) (2, 0x30F517CC57223260L)
    h1.issue (c2) (2, vs2)
    h1.issue (c1) (3, 0xC97846EBE5AC571BL)
    h1.issue (c2) (3, vs3)
    h1.issue (c1) (4, 0x4A048A835ED3A0A6L)
    h1.issue (c2) (4, vs4)
    kit.runTasks (timers = true, count = 150)

    // Hosts do not receive their own issues.
    h1.v1 = 0x4A048A835ED3A0A6L
    h1.v2 = vs4

    for (h <- hs) {
      expectResult (0x4A048A835ED3A0A6L) (h.v1)
      expectResult (vs4) (h.v2)
    }}

  property ("The broker should distribute catalogs") {
    forAll (seeds) { seed =>
      checkUnity (seed, 0.0)
    }}

  property ("The broker should distribute catalogs with a flakey network") {
    forAll (seeds) { seed =>
      checkUnity (seed, 0.1)
    }}
}