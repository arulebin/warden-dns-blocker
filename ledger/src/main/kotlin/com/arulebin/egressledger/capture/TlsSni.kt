package com.arulebin.egressledger.capture

/**
 * Reads the Server Name Indication out of a TLS ClientHello.
 *
 * The SNI is sent in clear text before any encryption is negotiated, so the
 * endpoint an app is about to talk to can be read without breaking TLS, without a
 * certificate, and without root. Nothing past the ClientHello is ever inspected.
 *
 * Returns null for anything that is not a well-formed ClientHello carrying a
 * host_name — including ECH, where the real name is encrypted (a stated limitation).
 */
object TlsSni {
    private const val CONTENT_HANDSHAKE = 22
    private const val HANDSHAKE_CLIENT_HELLO = 1
    private const val EXT_SERVER_NAME = 0
    private const val NAME_TYPE_HOST = 0

    fun extract(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String? =
        try {
            parse(Reader(data, offset, offset + length))
        } catch (e: Truncated) {
            null // hello split across TCP segments, or not TLS at all
        }

    private fun parse(r: Reader): String? {
        if (r.u8() != CONTENT_HANDSHAKE) return null
        r.skip(2)                                   // legacy record version
        r.narrow(r.u16())                           // this record only (or as much of it as arrived)
        if (r.u8() != HANDSHAKE_CLIENT_HELLO) return null
        r.skip(3)                                   // handshake length
        r.skip(2 + 32)                              // client_version + random
        r.skip(r.u8())                              // session id
        r.skip(r.u16())                             // cipher suites
        r.skip(r.u8())                              // compression methods
        if (!r.has(2)) return null                  // no extensions at all
        val extEnd = r.pos + r.u16()
        while (r.pos + 4 <= extEnd) {
            val type = r.u16()
            val len = r.u16()
            if (type != EXT_SERVER_NAME) {
                r.skip(len); continue
            }
            r.skip(2)                               // server_name_list length
            val nameType = r.u8()
            val nameLen = r.u16()
            if (nameType != NAME_TYPE_HOST) return null
            return String(r.bytes(nameLen), Charsets.US_ASCII).lowercase().trimEnd('.')
        }
        return null
    }

    private class Truncated : Exception()

    /** Bounds-checked cursor: every read past the end throws, so malformed input can never index out of range. */
    private class Reader(private val b: ByteArray, var pos: Int, private var end: Int) {
        init { if (end > b.size) throw Truncated() }
        fun has(n: Int) = pos + n <= end
        private fun need(n: Int) { if (n < 0 || !has(n)) throw Truncated() }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun skip(n: Int) { need(n); pos += n }
        fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
        // A post-quantum key share pushes a ClientHello past one TCP segment (~1.4 KB), so the
        // record may be cut short. Parse what arrived; the SNI usually sits in the first segment.
        fun narrow(n: Int) { end = minOf(end, pos + n) }
    }
}
