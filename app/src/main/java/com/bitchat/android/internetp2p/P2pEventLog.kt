package com.bitchat.android.internetp2p

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app event log for the internet P2P channel.
 *
 * Every P2P-related event (transport bring-up, link export/import, hole-punch
 * tier attempts, link established/failed) is appended here so the user can
 * see exactly what happened INSIDE the app - no adb / logcat required. The
 * P2P direct-link sheet renders this list directly.
 */
object P2pEventLog {

    private const val MAX_EVENTS = 60

    private val _events = MutableStateFlow<List<String>>(emptyList())

    /** Most-recent-first event list for UI rendering. */
    val events: StateFlow<List<String>> = _events.asStateFlow()

    /** Appends a timestamped event, keeping only the newest [MAX_EVENTS]. */
    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _events.value = (listOf("$stamp $message") + _events.value).take(MAX_EVENTS)
    }

    fun clear() {
        _events.value = emptyList()
    }
}
