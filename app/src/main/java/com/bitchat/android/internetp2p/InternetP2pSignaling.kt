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
        val t = transport ?: return null
        val context = appContext ?: return null
        val npub = try {
            NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub
        } catch (e: Exception) {
            null
        } ?: return null
        val local = t.gatherLocalCandidate() ?: return null
        return P2pUriCodec.encode(npub, local)
    }

    /**
     * Imports a `bitchat-p2p://` link (from a QR scan or a pasted/shared
     * link) and attempts a direct connection. The embedded sender npub lets us
     * derive the stranger identity key without a favorite relationship.
     *
     * @return True when the link was parsed and a connection attempt started.
     */
    fun importLinkUri(uri: String): Boolean {
        val t = transport ?: return false
        val payload = P2pUriCodec.decode(uri) ?: run {
            Log.w(TAG, "Ignoring unrecognized peer link")
            return false
        }
        val peerID = resolvePeerID(payload.npub) ?: run {
            Log.w(TAG, "Cannot derive identity for link sender; ignoring")
            return false
        }
        t.connectToPeer(peerID, payload.candidate)
        Log.i(TAG, "Imported peer link, connecting to ${peerID.take(12)}…")
        return true
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
