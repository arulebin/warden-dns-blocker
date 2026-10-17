package com.arulebin.warden

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.atomic.AtomicLong

/** A small, Compose-observable ring buffer of recent DNS decisions. */
object QueryLog {

    /** [id] is a stable key so the UI can animate rows in and out; [time] is wall-clock millis. */
    class Entry(
        val domain: String,
        val blocked: Boolean,
        val time: Long = System.currentTimeMillis(),
        val id: Long = ids.getAndIncrement(),
    )

    private val ids = AtomicLong()

    private const val MAX = 100
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Newest first. Read by the UI. */
    val entries = mutableStateListOf<Entry>()

    /** Called from the VPN's background threads; hops to main to mutate state. */
    fun add(domain: String, blocked: Boolean) {
        mainHandler.post {
            entries.add(0, Entry(domain, blocked))
            while (entries.size > MAX) entries.removeAt(entries.size - 1)
        }
    }

    fun clear() {
        mainHandler.post { entries.clear() }
    }
}
