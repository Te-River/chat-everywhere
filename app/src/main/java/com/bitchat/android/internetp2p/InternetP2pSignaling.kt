package com.bitchat.android.internetp2p

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.ContactIdentityResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Coordinator between the Nostr DM signaling channel and the internet P2P
 * transport. The Nostr DM handler feeds incoming control messages here; this
 * object answers OFFERs and drives [InternetMeshTransport.connectToPeer].
 *
 * Decentralization: signaling reuses the user's own Nostr relays (their
 * choice of servers), never a purpose-built rendezvous host.
 */
object InternetP2pSignaling {

    private const val TAG = "InternetP2pSignaling"

    @Volatile private var transport: InternetMeshTransport? = null
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Lazily ensures the transport exists: if the mesh service has not wired
     * it up yet (e.g. the foreground service did not start), create it via the
     * holder, which also attaches this signaling object. Safe to call from the
     * UI entry points; no-op when already attached.
     */
    private fun ensureTransport(): InternetMeshTransport? {
        transport?.let { return it }
        val context = appContext ?: run {
            Log.w(TAG, "P2P transport not attached and no app context to create it")
            P2pEventLog.log("P2P 传输不可用：无应用上下文，无法创建")
            return null
        }
        return try {
            val created = com.bitchat.android.service.MeshServiceHolder
                .getInternetTransportOrCreate(context)
            created.start()
            transport = created
            P2pEventLog.log("P2P 传输已就绪")
            created
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lazily create P2P transport: ${e.message}")
            P2pEventLog.log("P2P 传输创建失败：${e.message}")
            null
        }
    }

    /** Attaches the transport instance (and app context for Nostr replies). */
    fun attach(t: InternetMeshTransport, context: Context) {
        transport = t
        appContext = context.applicationContext
    }

    /** Detaches the transport (e.g., when the mesh service shuts down). */
    fun detach() {
        transport = null
        appContext = null
    }

    /**
     * Handles an incoming `[P2P_...]` control message from [senderPubkey]
     * (hex Nostr pubkey). Called from the Nostr DM handler before the content
     * is treated as a chat message.
     */
    fun handleIncoming(senderPubkey: String, content: String) {
        val msg = P2pControlMessage.parse(content) ?: return
        val t = transport ?: run {
            Log.w(TAG, "Dropping P2P signaling (transport not attached)")
            return
        }
        val peerID = resolvePeerID(senderPubkey) ?: run {
            Log.w(TAG, "No peerID known for sender; ignoring P2P signaling")
            return
        }
        // Remember the peer's latest candidate so a later session can punch
        // without a fresh OFFER round-trip.
        try {
            com.bitchat.android.services.ContactDirectory.cacheP2pCandidate(peerID, msg.candidate)
        } catch (_: Exception) { }
        P2pEventLog.log(
            "收到 ${msg.kind}：对方候选 局域网=${msg.candidate.lanHost ?: "无"} " +
                "IPv6=${msg.candidate.ipv6Host ?: "无"} 公网=${msg.candidate.mappedHost ?: "无"} " +
                "端口=${msg.candidate.tcpPort} nonce=${msg.candidate.nonce.take(8)}…"
        )
        when (msg.kind) {
            P2pControlMessage.Kind.OFFER -> {
                // Answer with our own candidate, then both sides punch.
                scope.launch {
                    val local = t.gatherLocalCandidate()
                    if (local == null) {
                        Log.w(TAG, "Cannot gather local candidate; skipping answer")
                        return@launch
                    }
                    val answer = P2pControlMessage.encode(P2pControlMessage.Kind.ANSWER, local)
                    val sourceGeohash = if (peerID.startsWith("nostr_")) {
                        // Stranger: answer over the same geohash identity the
                        // OFFER arrived on (regular DMs need a favorite mapping).
                        com.bitchat.android.nostr.GeohashConversationRegistry.get(peerID)
                    } else null
                    if (sourceGeohash != null) {
                        sendControlGeohash(senderPubkey, answer, sourceGeohash)
                    } else if (peerID.startsWith("nostr_")) {
                        // Stranger without a geohash context (QR / share-link
                        // flow): the sender pubkey hex is enough to reply over
                        // the encrypted Nostr DM channel directly.
                        sendControlDirect(senderPubkey, answer)
                    } else {
                        sendControl(senderPubkey, answer)
                    }
                    t.connectToPeer(peerID, msg.candidate)
                }
            }
            P2pControlMessage.Kind.ANSWER,
            P2pControlMessage.Kind.CANDIDATE -> {
                t.connectToPeer(peerID, msg.candidate)
            }
        }
    }

    /**
     * Probes every participant of the given geohash channel with an OFFER so
     * strangers in the same channel can upgrade to direct links without a
     * favorite relationship. Participants are supplied by the caller (from
     * [com.bitchat.android.ui.ChatState.getGeohashPeopleValue]); self, blocked
     * users and already-connected peers are skipped. OFFERs travel over the
     * per-geohash Nostr identity ([NostrTransport.sendPrivateMessageGeohash]).
     *
     * @return Number of OFFERs actually dispatched (0 when skipped / no
     *   transport / no sendable participants).
     */
    suspend fun searchGeohashChannel(geohash: String, participantPubkeys: List<String>): Int {
        val t = transport ?: return 0
        val context = appContext ?: return 0
        if (geohash.isBlank() || participantPubkeys.isEmpty()) {
            Log.i(TAG, "Geohash channel search skipped: no channel/participants")
            return 0
        }
        val myPubkey = try {
            NostrIdentityBridge.deriveIdentity(geohash, context).publicKeyHex
        } catch (e: Exception) {
            Log.w(TAG, "Cannot derive geohash identity for $geohash")
            return 0
        }
        return coroutineScope {
            val local = t.gatherLocalCandidate()
            if (local == null) {
                Log.w(TAG, "Cannot gather local candidate; geohash channel search skipped")
                return@coroutineScope 0
            }
            val offer = P2pControlMessage.encode(P2pControlMessage.Kind.OFFER, local)
            var offered = 0
            for (rawPubkey in participantPubkeys) {
                val pubkeyHex = rawPubkey.lowercase()
                if (pubkeyHex == myPubkey.lowercase()) continue
                val peerID = resolvePeerID(pubkeyHex) ?: continue
                if (t.isPeerConnected(peerID)) continue
                if (sendControlGeohash(pubkeyHex, offer, geohash)) {
                    offered++
                } else {
                    Log.w(TAG, "Geohash channel OFFER failed for ${pubkeyHex.take(12)}…")
                }
            }
            Log.i(TAG, "Geohash channel search complete: $offered OFFER(s) sent in $geohash")
            offered
        }
    }

    /**
     * Builds a `bitchat-p2p://` link for the current device: gathers the
     * local candidate and embeds it (with our Nostr npub) so another peer can
     * establish a direct link without a favorite relationship. Used by both
     * the QR-code flow and the share-link flow.
     *
     * @return The link URI, or null when the P2P transport is not attached or
     *   no Nostr identity exists.
     */
    suspend fun exportLinkUri(): String? {
        val t = ensureTransport() ?: run {
            Log.w(TAG, "Cannot export link: P2P transport unavailable")
            return null
        }
        val context = appContext ?: return null
        val npub = try {
            NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
        } catch (e: Exception) {
            null
        } ?: return null
        val local = t.gatherLocalCandidate() ?: return null
        // The QR/link flow is asymmetric: we generate the URI but never learn
        // the importer's candidate, so we must start listening for the
        // importer's inbound punch right away. Without this the importer's
        // handshake reaches a closed NAT mapping and the link never forms.
        t.listenForInboundLinks()
        return P2pUriCodec.encode(npub, local)
    }

    /**
     * Imports a `bitchat-p2p://` link (from a QR scan or a pasted/shared
     * link) and attempts a direct connection. The embedded sender npub lets us
     * derive the stranger identity key without a favorite relationship.
     *
     * @return The resolved P2P identity key (noiseKeyHex for favorites, or the
     *   `nostr_<pub16>` stranger alias) when the link was parsed and a
     *   connection attempt started, or null when it could not be imported.
     */
    fun importLinkUri(uri: String): String? {
        Log.i(TAG, "importLinkUri called: ${uri.take(48)}… len=${uri.length}")
        P2pEventLog.log("收到链接（${uri.take(24)}…，长度 ${uri.length}）")
        val t = ensureTransport() ?: run {
            Log.w(TAG, "Cannot import link: P2P transport unavailable")
            P2pEventLog.log("导入失败：P2P 传输不可用")
            return null
        }
        val payload = P2pUriCodec.decode(uri) ?: run {
            Log.w(TAG, "Ignoring unrecognized peer link")
            P2pEventLog.log("导入失败：无法解析链接（格式错误）")
            return null
        }
        // Port convention: the generator (scanned side) declares its listener
        // port at the top level of the link; dial that exact port even if the
        // embedded candidate disagrees (follow-the-scanned-side rule).
        val candidate = if (payload.punchPort > 0 && payload.candidate.tcpPort != payload.punchPort) {
            P2pEventLog.log(
                "端口约定：按被扫码方端口 ${payload.punchPort}（候选内 ${payload.candidate.tcpPort}，已对齐）"
            )
            payload.candidate.copy(tcpPort = payload.punchPort)
        } else {
            payload.candidate
        }
        val peerID = resolvePeerID(payload.npub) ?: run {
            Log.w(TAG, "Cannot derive identity for link sender; ignoring")
            P2pEventLog.log("导入失败：无法识别对方身份")
            return null
        }
        P2pEventLog.log(
            "端口约定：对方监听端口=${payload.punchPort.takeIf { it > 0 } ?: candidate.tcpPort}，" +
                "局域网=${candidate.lanHost ?: "无"} IPv6=${candidate.ipv6Host ?: "无"}"
        )
        t.connectToPeer(peerID, candidate)
        P2pEventLog.log("已解析链接，开始连接对方…")
        Log.i(TAG, "Imported peer link, connecting to ${peerID.take(12)}…")

        // The QR/link flow is asymmetric: the generator (A) listens inbound but
        // never learns our candidate. If A sits behind a restrictive NAT, its
        // first inbound packet from us will be dropped, so hole punching needs
        // BOTH sides to send. Send our candidate back to A over the encrypted
        // Nostr DM channel so A can answer and both sides punch symmetrically.
        val recipientHex = try {
            ContactIdentityResolver.nostrPubkeyHex(payload.npub)
        } catch (e: Exception) {
            null
        }
        if (recipientHex != null) {
            scope.launch {
                val local = t.gatherLocalCandidate()
                if (local != null) {
                    P2pEventLog.log(
                        "回传候选：局域网=${local.lanHost ?: "无"} IPv6=${local.ipv6Host ?: "无"} " +
                            "公网=${local.mappedHost ?: "无"} 端口=${local.tcpPort}"
                    )
                    val offer = P2pControlMessage.encode(P2pControlMessage.Kind.OFFER, local)
                    if (sendControlDirect(recipientHex, offer)) {
                        Log.i(TAG, "Sent OFFER back to link generator ${recipientHex.take(12)}…")
                    }
                }
            }
        }
        return peerID
    }

    /**
     * Initiates a direct link by sending an OFFER to [peerID] over Nostr.
     * Called by the router when a mesh path is unavailable but the peer is a
     * favorite with a known Nostr mapping.
     *
     * When a candidate for this peer is already cached (from a previous
     * session or a received ANSWER), we also try punching right away so the
     * link can come up without waiting for the relay round-trip.
     */
    fun sendOffer(peerID: String) {
        val t = transport ?: return
        val npub = resolveNostrPubkey(peerID) ?: return
        // Reuse a cached candidate: the peer's NAT mapping may still be valid.
        val cached = try {
            com.bitchat.android.services.ContactDirectory.getCachedP2pCandidate(peerID)
        } catch (_: Exception) {
            null
        }
        if (cached != null) {
            t.connectToPeer(peerID, cached)
        }
        scope.launch {
            val local = t.gatherLocalCandidate()
            if (local == null) {
                Log.w(TAG, "Cannot gather local candidate; no OFFER sent")
                return@launch
            }
            sendControl(npub, P2pControlMessage.encode(P2pControlMessage.Kind.OFFER, local))
        }
    }

    /**
     * User-facing search: probes every mutual favorite that has a Nostr
     * mapping with an OFFER so peers currently online can be discovered and
     * upgraded to direct links. Peers with no Nostr pubkey, or peers we are
     * already directly connected to, are skipped. No-op when the P2P
     * transport is not attached (feature disabled).
     *
     * @return Number of OFFERs sent (0 when skipped).
     */
    fun searchAll(): Int {
        val t = transport ?: run {
            Log.w(TAG, "P2P search skipped: internet P2P transport not attached")
            return 0
        }
        val favorites = try {
            FavoritesPersistenceService.shared.getAllRelationships()
        } catch (e: Exception) {
            Log.w(TAG, "P2P search skipped: cannot read favorites")
            return 0
        }
        var offered = 0
        for (relationship in favorites) {
            if (!relationship.isMutual) continue
            val npub = relationship.peerNostrPublicKey ?: continue
            if (npub.isBlank()) continue
            val noiseKey = try {
                ContactIdentityResolver.noiseKeyHex(relationship.peerNoisePublicKey)
            } catch (e: Exception) {
                null
            } ?: continue
            if (t.isPeerConnected(noiseKey)) continue
            try {
                sendOffer(noiseKey)
                offered++
            } catch (e: Exception) {
                Log.w(TAG, "P2P search OFFER failed for ${noiseKey.take(12)}…: ${e.message}")
            }
        }
        Log.i(TAG, "P2P search complete: OFFERs sent to $offered mutual favorite(s)")
        return offered
    }

    // ------------------------------------------------------------------

    private fun sendControl(recipientNostr: String, content: String) {
        val context = appContext ?: return
        try {
            val nostr = NostrTransport.getInstance(context)
            nostr.sendPrivateMessage(
                content = content,
                to = recipientNostr,
                recipientNickname = "",
                messageID = UUID.randomUUID().toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send P2P signaling: ${e.message}")
        }
    }

    /**
     * Sends P2P signaling to a stranger over the per-geohash Nostr identity.
     * Regular [sendControl] requires a favorite mapping to resolve the target;
     * geohash DMs address a raw pubkey directly, which is how OFFER/ANSWER
     * flows between peers without a favorite relationship.
     *
     * @return True when the send was dispatched to the Nostr transport.
     */
    private fun sendControlGeohash(recipientHex: String, content: String, geohash: String): Boolean {
        val context = appContext ?: return false
        return try {
            val nostr = NostrTransport.getInstance(context)
            nostr.sendPrivateMessageGeohash(
                content = content,
                toRecipientHex = recipientHex,
                messageID = UUID.randomUUID().toString(),
                sourceGeohash = geohash
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send geohash P2P signaling: ${e.message}")
            false
        }
    }

    /**
     * Sends P2P signaling directly to a stranger's Nostr pubkey (hex) over
     * the encrypted DM channel, without requiring a favorite relationship.
     * Used by the QR / share-link flow where the recipient is only known by
     * pubkey and there is no geohash context.
     *
     * @return True when the send was dispatched to the Nostr transport.
     */
    private fun sendControlDirect(recipientHex: String, content: String): Boolean {
        val context = appContext ?: return false
        return try {
            val nostr = NostrTransport.getInstance(context)
            nostr.sendPrivateMessageToPubkeyHex(
                content = content,
                recipientHex = recipientHex,
                messageID = UUID.randomUUID().toString()
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send direct P2P signaling: ${e.message}")
            false
        }
    }

    /**
     * Maps a hex Nostr pubkey to the P2P identity key.
     *
     * Mutual favorites resolve to their stable noiseKeyHex (authenticated
     * identity). Strangers (no favorite relationship) fall back to the
     * `nostr_<pub16>` derived alias, which is registered in the persisted
     * GeohashAliasRegistry so the reverse lookup ([resolveNostrPubkey]) works
     * for answering OFFERs without a favorite relationship.
     */
    private fun resolvePeerID(senderPubkey: String): String? {
        return try {
            val targetHex = ContactIdentityResolver.nostrPubkeyHex(senderPubkey) ?: return null
            FavoritesPersistenceService.shared.getAllRelationships()
                .firstOrNull { relationship ->
                    relationship.peerNostrPublicKey
                        ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
                        ?.equals(targetHex, ignoreCase = true) == true
                }
                ?.peerNoisePublicKey
                ?.let { ContactIdentityResolver.noiseKeyHex(it) }
                ?: run {
                    // Stranger: nostr_ derived alias, persisted for reverse lookup.
                    val alias = ContactIdentityResolver.nostrAliasForPubkey(targetHex) ?: return null
                    com.bitchat.android.nostr.GeohashAliasRegistry.put(alias, targetHex)
                    alias
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Maps a P2P identity key back to the Nostr pubkey (npub or hex) for
     * replies. `nostr_` aliases (strangers) resolve through the persisted
     * GeohashAliasRegistry; noiseKeyHex / mesh peer IDs resolve through
     * favorites.
     */
    private fun resolveNostrPubkey(peerID: String): String? {
        return try {
            if (peerID.startsWith("nostr_")) {
                com.bitchat.android.nostr.GeohashAliasRegistry.get(peerID)?.let { return it }
            }
            FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(peerID)
        } catch (e: Exception) {
            null
        }
    }
}
