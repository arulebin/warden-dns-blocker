package com.arulebin.warden

/**
 * JNI binding for libhev-socks5-tunnel: a userspace TCP/IP stack (lwIP) that reads
 * raw IP packets from a file descriptor and turns each TCP connection / UDP flow
 * into a SOCKS5 session against [Socks5Server].
 *
 * The natives are registered in the library's JNI_OnLoad against this class (see
 * the -DPKGNAME/-DCLSNAME flags in app/build.gradle.kts), so the method names and
 * signatures here must match src/hev-jni.c exactly.
 */
@Suppress("FunctionName")
object TProxy {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts the stack on its own native thread. [fd] is read and written as one IP packet per call. */
    external fun TProxyStartService(configPath: String, fd: Int)

    /** Stops the stack and joins its thread. Safe to call when not running. */
    external fun TProxyStopService()

    /** tx packets, tx bytes, rx packets, rx bytes — as seen by the stack. */
    external fun TProxyGetStats(): LongArray
}
