package com.arulebin.warden

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-global, Compose-observable protection state.
 *
 * [WardenVpnService] writes [running] when the tunnel goes up or down; the UI
 * reads it and recomposes automatically. A single mutable global is fine for a
 * one-screen app — a larger app would route this through a ViewModel instead.
 */
object WardenState {
    var running by mutableStateOf(false)
}
