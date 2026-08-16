package com.bitchat.android.internetp2p

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the internet P2P channel.
 *
 * Decentralization note: the STUN server list is user-controlled and stored
 * locally; nothing here depends on a service we operate.
 */
object P2pPreferenceManager {

    private const val PREFS_NAME = "internet_p2p_preferences"
    private const val KEY_ENABLED = "p2p_enabled"
    private const val KEY_STUN_SERVERS = "stun_servers"

    private const val DEFAULT_ENABLED = true

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _stunServers = MutableStateFlow(P2pConfig.DEFAULT_STUN_SERVERS.joinToString(","))
    val stunServers: StateFlow<String> = _stunServers.asStateFlow()

    private lateinit var sharedPrefs: SharedPreferences
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = sharedPrefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        _stunServers.value = sharedPrefs.getString(KEY_STUN_SERVERS, null)
            ?: P2pConfig.DEFAULT_STUN_SERVERS.joinToString(",")
        isInitialized = true
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (isInitialized) {
            sharedPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    fun getStunServers(): List<String> =
        _stunServers.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setStunServers(servers: String) {
        _stunServers.value = servers
        if (isInitialized) {
            sharedPrefs.edit().putString(KEY_STUN_SERVERS, servers).apply()
        }
    }
}
