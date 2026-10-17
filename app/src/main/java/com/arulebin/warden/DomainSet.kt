package com.arulebin.warden

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A large, read-only set of domains stored as sorted 64-bit hashes.
 *
 * Adult blocklists run past a million entries. As a HashSet<String> that is well
 * over 100 MB of heap; as a sorted LongArray it is 8 bytes per domain and a
 * binary search per lookup. The trade-off is a theoretical false positive when
 * two domains share a 64-bit hash, which at this size is around 1 in 10^13 per
 * lookup — far below anything a user could notice.
 *
 * Matching is subdomain-aware: "example.com" in the set also matches
 * "cdn.example.com", by hashing each dot-suffix of the queried host without
 * allocating substrings.
 */
class DomainSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    fun containsHost(host: String): Boolean {
        if (hashes.isEmpty()) return false
        var start = 0
        while (true) {
            if (hashes.binarySearch(hash(host, start)) >= 0) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
    }

    fun writeTo(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(hashes.size)
            for (h in hashes) out.writeLong(h)
        }
        // Rename is atomic, so a crash mid-write never leaves a half-written list in place.
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Accumulates hashes while parsing a download, growing as needed. */
    class Builder(initialCapacity: Int = 1 shl 16) {
        private var buf = LongArray(initialCapacity)
        private var n = 0

        fun add(domain: String) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = hash(domain, 0)
        }

        fun build(): DomainSet {
            val arr = buf.copyOf(n)
            buf = LongArray(0) // release the working buffer before sorting
            arr.sort()
            // Dedupe in place: sources overlap heavily.
            var w = 0
            for (i in arr.indices) {
                if (w == 0 || arr[i] != arr[w - 1]) arr[w++] = arr[i]
            }
            return DomainSet(if (w == arr.size) arr else arr.copyOf(w))
        }
    }

    companion object {
        private const val MAGIC = 0x57444E31 // "WDN1"

        val EMPTY = DomainSet(LongArray(0))

        fun readFrom(file: File): DomainSet? = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                if (input.readInt() != MAGIC) return null
                val count = input.readInt()
                val arr = LongArray(count)
                for (i in 0 until count) arr[i] = input.readLong()
                DomainSet(arr)
            }
        }.getOrNull()

        /** FNV-1a over host[start..], finished with a 64-bit avalanche mix. */
        fun hash(s: String, start: Int): Long {
            var h = -0x340d631b7bdddcdbL // FNV offset basis
            for (i in start until s.length) {
                h = h xor s[i].code.toLong()
                h *= 0x100000001b3L // FNV prime
            }
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }
    }
}
