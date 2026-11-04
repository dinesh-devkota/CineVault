package com.dineshdevkota.cinevault.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    fun discover(): Flow<NasDiscoveryState> = callbackFlow {
        val nsd = manager
        if (nsd == null) {
            trySend(NasDiscoveryState(error = "Network discovery is unavailable on this device."))
            close()
            return@callbackFlow
        }
        val search = Search(nsd, { trySend(it) }, { close() })
        search.start()
        awaitClose { search.cancel() }
    }.conflate()
}

@Suppress("DEPRECATION") // Sequential one-shot resolution also supports Android 8–13.
private class Search(
    private val manager: NsdManager,
    private val emit: (NasDiscoveryState) -> Unit,
    private val close: () -> Unit,
) {
    private class Advertisement(val key: String, val info: NsdServiceInfo)
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    // All fields below are confined to the main handler, including NSD callbacks.
    private val present = linkedMapOf<String, Advertisement>()
    private val pending = ArrayDeque<Advertisement>()
    private val devices = linkedMapOf<String, DiscoveredNas>()
    private var resolving: NsdManager.ResolveListener? = null
    private var registered = false
    private var finished = false
    private var stopAttempts = 0
    private val deadline = Runnable { finish() }

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
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = dispatch {
            if (info.serviceType.trimEnd('.') != "_smb._tcp") return@dispatch
            val id = key(info)
            if (present.containsKey(id)) return@dispatch
            val advertisement = Advertisement(id, info)
            present[id] = advertisement
            pending.addLast(advertisement)
            resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) = dispatch {
            val id = key(info)
            present.remove(id)
            devices.remove(id)
            publish()
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
        publish()
        handler.postDelayed(deadline, 10_000L)
        try {
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

    private fun resolveNext() {
        if (resolving != null || finished || cancelled.get()) return
        var next = pending.removeFirstOrNull()
        while (next != null && present[next.key] !== next) next = pending.removeFirstOrNull()
        val advertisement = next ?: return
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = dispatch {
                if (resolving !== this) return@dispatch
                resolving = null
                resolveNext()
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
                }
                resolveNext()
            }
        }
        resolving = callback
        try {
            manager.resolveService(advertisement.info, callback)
        } catch (_: RuntimeException) {
            resolving = null
            handler.post { if (!finished && !cancelled.get()) resolveNext() }
        }
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
        // Earlier Android versions cannot cancel resolution; its callbacks are ignored.
        resolving = null
        pending.clear()
        present.clear()
        if (!cancelled.get()) publish(searching = false, error = error)
        close()
    }

    fun cancel() {
        cancelled.set(true)
        handler.post { finish() }
    }
}
