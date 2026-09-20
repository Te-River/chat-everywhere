package com.bitchat.android.internetp2p

import kotlinx.coroutines.*
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Loopback integration checks for the UDP demultiplexer + shared-socket link.
 * These directly exercise the two regressions behind "打洞失败":
 *   A) closing one UDP link must NOT close the engine's shared socket;
 *   B) multiple links + a handshake waiter sharing one socket must not steal
 *      each other's datagrams (single receive queue).
 */
object UdpDemuxIT {

    private var failures = 0
    private fun check(name: String, cond: Boolean) {
        if (cond) println("PASS  $name") else { failures++; println("FAIL  $name") }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking(Dispatchers.IO) {
            testBidirectionalSharedSocket()
            testSocketSurvivesLinkClose()
            testHandshakeRoutingAndLinkData()
            testTargetedBeatsWildcard()
        }
        if (failures == 0) println("ALL GREEN") else { println("$failures FAILURE(S)"); kotlin.system.exitProcess(1) }
    }

    private fun frame(payload: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

    /** A: two links over two sockets pointed at each other deliver both ways. */
    private suspend fun testBidirectionalSharedSocket() = coroutineScope {
        val sa = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val sb = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val ea = sa.localSocketAddress as InetSocketAddress
        val eb = sb.localSocketAddress as InetSocketAddress
        val demuxA = UdpDemux(sa, this, "A")
        val demuxB = UdpDemux(sb, this, "B")

        val gotAtB = CompletableDeferred<String>()
        val gotAtA = CompletableDeferred<String>()
        // linkOnA lives on demuxA and represents A's view of peer B; its
        // onFrame fires when A RECEIVES (i.e. B->A traffic). Symmetrically for
        // linkOnB on demuxB (fires on A->B traffic).
        val linkOnA = UdpLink(eb, { gotAtA.complete(String(it)) }, this,
            { t, b -> demuxA.send(t, b) }, { demuxA.unregister(eb) })
        val linkOnB = UdpLink(ea, { gotAtB.complete(String(it)) }, this,
            { t, b -> demuxB.send(t, b) }, { demuxB.unregister(ea) })
        demuxA.register(eb, linkOnA)
        demuxB.register(ea, linkOnB)

        linkOnA.send("hello-from-A".toByteArray())
        linkOnB.send("hello-from-B".toByteArray())

        val b = withTimeoutOrNull(3000) { gotAtB.await() }
        val a = withTimeoutOrNull(3000) { gotAtA.await() }
        check("A->B delivered", b == "hello-from-A")
        check("B->A delivered (bidirectional)", a == "hello-from-B")
        linkOnA.close(); linkOnB.close(); demuxA.close(); demuxB.close()
    }

    /** A (regression): closing one link keeps the shared socket usable. */
    private suspend fun testSocketSurvivesLinkClose() = coroutineScope {
        val sa = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val sb = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val ea = sa.localSocketAddress as InetSocketAddress
        val eb = sb.localSocketAddress as InetSocketAddress
        val demuxA = UdpDemux(sa, this, "A")
        val demuxB = UdpDemux(sb, this, "B")

        // First link on A -> closes immediately (the old bug closed `sa`).
        val l1 = UdpLink(eb, {}, this, { t, b -> demuxA.send(t, b) }, { demuxA.unregister(eb) })
        demuxA.register(eb, l1)
        l1.close()

        // Second link on the SAME socket A must still send successfully.
        val got = CompletableDeferred<String>()
        val l2 = UdpLink(eb, {}, this, { t, b -> demuxA.send(t, b) }, { demuxA.unregister(eb) })
        demuxA.register(eb, l2)
        val peer = UdpLink(ea, { got.complete(String(it)) }, this,
            { t, b -> demuxB.send(t, b) }, { demuxB.unregister(ea) })
        demuxB.register(ea, peer)

        check("shared socket NOT closed by a link", !sa.isClosed)
        val sent = l2.send("after-close".toByteArray())
        check("send after sibling link close returns true", sent)
        val r = withTimeoutOrNull(3000) { got.await() }
        check("datagram received after sibling link close", r == "after-close")
        l2.close(); peer.close(); demuxA.close(); demuxB.close()
    }

    /** B: a handshake waiter and a data link coexist; data is not stolen. */
    private suspend fun testHandshakeRoutingAndLinkData() = coroutineScope {
        val sa = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val sb = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val ea = sa.localSocketAddress as InetSocketAddress
        val eb = sb.localSocketAddress as InetSocketAddress
        val demuxA = UdpDemux(sa, this, "A")
        val demuxB = UdpDemux(sb, this, "B")

        // A waits for a handshake carrying nonce "abcd1234".
        val waiter = UdpDemux.HandshakeWaiter(listOf("abcd1234".toByteArray()), echoBytes = "BP2Pecho".toByteArray())
        demuxA.attach(waiter)
        // A also has a data link to B.
        val got = CompletableDeferred<String>()
        val link = UdpLink(eb, { got.complete(String(it)) }, this,
            { t, b -> demuxA.send(t, b) }, { demuxA.unregister(eb) })
        demuxA.register(eb, link)

        // B sends a handshake to A. On loopback A sees the source as B's own
        // endpoint (eb), since that is where the datagram was sent from.
        demuxB.send(ea, "BP2Pabcd1234".toByteArray())
        val match = waiter.await(3000)
        check("handshake matched by nonce", match != null)
        check("handshake source is B's endpoint", match?.source == eb)

        // B then sends a DATA frame from the same endpoint; the link gets it
        // (the consumed handshake waiter must not swallow it).
        demuxB.send(ea, frame("payload".toByteArray()))
        val r = withTimeoutOrNull(3000) { got.await() }
        check("data frame delivered to link after handshake", r == "payload")

        // A should have received B's echo? No—echo went A->B; verify B's demux
        // delivered it to nothing harmful (just ensure no crash / socket alive).
        check("socket still open", !sa.isClosed)
        demuxA.detach(waiter); link.close(); demuxA.close(); demuxB.close()
    }

    /** Targeted waiter (knows nonce) wins over wildcard inbound listener. */
    private suspend fun testTargetedBeatsWildcard() = coroutineScope {
        val sa = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val sb = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val ea = sa.localSocketAddress as InetSocketAddress
        val demuxA = UdpDemux(sa, this, "A")
        val demuxB = UdpDemux(sb, this, "B")

        val wildcard = UdpDemux.HandshakeWaiter(emptyList(), null)
        val targeted = UdpDemux.HandshakeWaiter(listOf("deadbeef".toByteArray()), null)
        demuxA.attach(wildcard)   // attach wildcard FIRST
        demuxA.attach(targeted)

        demuxB.send(ea, "BP2Pdeadbeef".toByteArray())
        val t = targeted.await(2000)
        val w = wildcard.await(200)
        check("targeted waiter matched", t != null)
        check("wildcard did NOT steal targeted handshake", w == null)
        demuxA.detach(wildcard); demuxA.detach(targeted); demuxA.close(); demuxB.close()
    }
}
