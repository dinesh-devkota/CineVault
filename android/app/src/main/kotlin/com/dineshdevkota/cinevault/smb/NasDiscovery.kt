package com.dineshdevkota.cinevault.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ext.SdkExtensions
import com.dineshdevkota.cinevault.core.DiscoveryTiming
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

data class DiscoveredNas(val name: String, val host: String, val port: Int)

data class NasDiscoveryState(
    val devices: List<DiscoveredNas> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

/** Searches only SMB services advertised on the local network. */
class NasDiscovery(context: Context) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    fun discover(): Flow<NasDiscoveryState> = callbackFlow {
        val nsd = manager
        if (nsd == null) {
            trySend(NasDiscoveryState(error = "Network discovery is unavailable on this device."))
            close()
            return@callbackFlow
        }
        val search = Search(nsd, wifiManager, { trySend(it) }, { close() })
        search.start()
        awaitClose { search.cancel() }
    }.conflate()
}

@Suppress("DEPRECATION") // Sequential one-shot resolution also supports Android 8–13.
private class Search(
    private val manager: NsdManager,
    private val wifiManager: WifiManager?,
    private val emit: (NasDiscoveryState) -> Unit,
    private val close: () -> Unit,
) {
    private class Advertisement(val key: String, val info: NsdServiceInfo, var attempts: Int = 0)
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    // All fields below are confined to the main handler, including NSD callbacks.
    private val present = linkedMapOf<String, Advertisement>()
    private val pending = ArrayDeque<Advertisement>()
    private val retrying = mutableSetOf<Advertisement>()
    private val devices = linkedMapOf<String, DiscoveredNas>()
    private var resolving: NsdManager.ResolveListener? = null
    private var registered = false
    private var finished = false
    private var stopAttempts = 0
    private var multicastLock: WifiManager.MulticastLock? = null
    private var timing = DiscoveryTiming(SystemClock.elapsedRealtime())
    private val deadline = Runnable { checkDeadline() }

    private fun dispatch(block: () -> Unit) {
        handler.post { if (!finished && !cancelled.get()) block() }
    }

    private fun key(info: NsdServiceInfo) =
        "${info.serviceName}\u0000${info.serviceType.trimEnd('.')}".lowercase(Locale.ROOT)

    private fun publish(searching: Boolean = true, error: String? = null) {
        emit(NasDiscoveryState(
            devices.values.distinctBy { it.host to it.port }
                .sortedBy { it.name.lowercase(Locale.ROOT) },
            searching,
            error,
        ))
    }

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = dispatch {
            timing.started(SystemClock.elapsedRealtime())
            checkDeadline()
        }
        override fun onServiceFound(info: NsdServiceInfo) = dispatch {
            if (!info.serviceType.trimEnd('.').equals("_smb._tcp", ignoreCase = true)) return@dispatch
            val id = key(info)
            if (present.containsKey(id)) return@dispatch
            val advertisement = Advertisement(id, info)
            present[id] = advertisement
            pending.addLast(advertisement)
            resolveNext()
            checkDeadline()
        }
        override fun onServiceLost(info: NsdServiceInfo) = dispatch {
            val id = key(info)
            present.remove(id)
            devices.remove(id)
            publish()
            checkDeadline()
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = dispatch {
            registered = false
            finish("Network discovery could not start. Try again or enter the NAS address.")
        }
        override fun onDiscoveryStopped(serviceType: String) {
            handler.post {
                registered = false
                if (!finished && !cancelled.get()) finish()
            }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            // A bounded retry is still needed after cancellation has closed the flow.
            handler.post { stopDiscovery() }
        }
    }

    fun start() = dispatch {
        timing = DiscoveryTiming(SystemClock.elapsedRealtime())
        publish()
        checkDeadline()
        try {
            acquireMulticastLockIfNeeded()
            registered = true
            manager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: SecurityException) {
            registered = false
            finish("Allow local network access to discover your NAS, or enter its address.")
        } catch (_: RuntimeException) {
            registered = false
            finish("Network discovery is unavailable. You can enter the NAS address.")
        }
    }

    private fun acquireMulticastLockIfNeeded() {
        // Foreground NSD manages Wi-Fi multicast itself from Android 13 extension 7
        // (and on Android 14+). Older Wi-Fi devices otherwise filter mDNS replies.
        val systemManagesMulticast = Build.VERSION.SDK_INT >= 34 ||
            (Build.VERSION.SDK_INT >= 33 &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7)
        if (systemManagesMulticast) return
        val lock = wifiManager?.createMulticastLock("CineMedia:NasDiscovery") ?: return
        lock.setReferenceCounted(false)
        multicastLock = lock
        lock.acquire()
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock
        multicastLock = null
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: RuntimeException) {
            // Cleanup is idempotent even if Wi-Fi has already released the lock.
        }
    }

    private fun resolveNext() {
        if (resolving != null || finished || cancelled.get()) return
        var next = pending.removeFirstOrNull()
        while (next != null && present[next.key] !== next) next = pending.removeFirstOrNull()
        val advertisement = next ?: return
        advertisement.attempts++
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = dispatch {
                if (resolving !== this) return@dispatch
                resolving = null
                retry(advertisement)
                resolveNext()
                checkDeadline()
            }
            override fun onServiceResolved(info: NsdServiceInfo) = dispatch {
                if (resolving !== this) return@dispatch
                resolving = null
                val host = info.host?.hostAddress
                // A lost service can reappear with the same name during resolution.
                if (present[advertisement.key] === advertisement &&
                    !host.isNullOrBlank() && info.port in 1..65535) {
                    devices[advertisement.key] = DiscoveredNas(info.serviceName, host, info.port)
                    publish()
                } else retry(advertisement)
                resolveNext()
                checkDeadline()
            }
        }
        resolving = callback
        try {
            manager.resolveService(advertisement.info, callback)
        } catch (_: RuntimeException) {
            resolving = null
            retry(advertisement)
            handler.post {
                if (!finished && !cancelled.get()) { resolveNext(); checkDeadline() }
            }
        }
    }

    private fun retry(advertisement: Advertisement) {
        if (advertisement.attempts >= 2 || present[advertisement.key] !== advertisement) return
        retrying.add(advertisement)
        handler.postDelayed({
            retrying.remove(advertisement)
            if (!finished && !cancelled.get()) {
                if (present[advertisement.key] === advertisement) pending.addLast(advertisement)
                resolveNext()
                checkDeadline()
            }
        }, 500L)
    }

    private fun checkDeadline() {
        if (finished || cancelled.get()) return
        handler.removeCallbacks(deadline)
        val hasPending = resolving != null || retrying.isNotEmpty() || pending.any { present[it.key] === it }
        val remaining = timing.remaining(SystemClock.elapsedRealtime(), hasPending)
        if (remaining > 0) handler.postDelayed(deadline, remaining)
        else finish(when {
            !timing.hasStarted -> "Network discovery did not start. Check your connection and try again."
            devices.isEmpty() && present.isNotEmpty() ->
                "A NAS was found, but its address could not be resolved. Try again or enter its address."
            else -> null
        })
    }

    private fun stopDiscovery() {
        if (!registered || stopAttempts >= 2) return
        stopAttempts++
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: RuntimeException) {
            registered = false
        }
    }

    private fun finish(error: String? = null) {
        if (finished) return
        finished = true
        handler.removeCallbacks(deadline)
        stopDiscovery()
        if (Build.VERSION.SDK_INT >= 34) resolving?.let {
            try { manager.stopServiceResolution(it) } catch (_: RuntimeException) { /* Already ended. */ }
        }
        releaseMulticastLock()
        // Earlier Android versions cannot cancel resolution; its callbacks are ignored.
        resolving = null
        pending.clear()
        retrying.clear()
        present.clear()
        if (!cancelled.get()) publish(searching = false, error = error)
        close()
    }

    fun cancel() {
        cancelled.set(true)
        handler.post { finish() }
    }
}
