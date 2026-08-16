package com.bitchat.android.internetp2p

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.NostrTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                    sendControl(senderPubkey, P2pControlMessage.encode(P2pControlMessage.Kind.ANSWER, local))
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

    /** Maps a hex Nostr pubkey to the mesh peerID used as the link key. */
    private fun resolvePeerID(senderPubkey: String): String? {
        return try {
            FavoritesPersistenceService.shared.findPeerIDForNostrPubkey(senderPubkey)
        } catch (e: Exception) {
            null
        }
    }

    /** Maps a mesh peerID to the Nostr pubkey (npub or hex) for replies. */
    private fun resolveNostrPubkey(peerID: String): String? {
        return try {
            FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(peerID)
        } catch (e: Exception) {
            null
        }
    }
}
