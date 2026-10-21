package com.arulebin.warden

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.arulebin.egressledger.capture.FlowKey
import com.arulebin.egressledger.ledger.AppIdentity
import com.arulebin.egressledger.ledger.Attributor
import java.net.InetSocketAddress

/**
 * Per-app attribution: asks the OS which UID owns a connection, then which packages hold that UID.
 *
 * getConnectionOwnerUid only answers the app that currently owns the active VPN — which the
 * Warden VpnService is — and throws SecurityException otherwise. It needs API 29, so the
 * ledger feature is gated on 29+ while the rest of Warden keeps minSdk 26 (see [Egress]).
 *
 * Package names on Android 11+ are filtered by package visibility; Warden declares
 * QUERY_ALL_PACKAGES (it is not distributed through Play).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class UidAttributor(context: Context) : Attributor {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm: PackageManager = context.packageManager

    /** The UID lookup is a binder call per connection; cache the UID → packages step, stable for the life of an install. */
    private val packagesByUid = HashMap<Int, List<String>>()

    override fun attribute(flow: FlowKey): AppIdentity? {
        val uid = try {
            cm.getConnectionOwnerUid(
                flow.protocol,
                InetSocketAddress(flow.src, flow.srcPort),
                InetSocketAddress(flow.dst, flow.dstPort),
            )
        } catch (e: SecurityException) {
            return null // our VPN is not the active one
        }
        if (uid == Process.INVALID_UID) return null
        val packages = synchronized(packagesByUid) {
            packagesByUid.getOrPut(uid) { pm.getPackagesForUid(uid)?.sorted().orEmpty() }
        }
        return AppIdentity(uid, packages)
    }
}
