package com.dineshdevkota.cinevault.smb

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Uses a synthetic local advertisement only; never reads or clears the user's library or secrets. */
@RunWith(AndroidJUnit4::class)
class NasDiscoveryIntegrationTest {
    @Test fun firstScanFindsAnAdvertisementAfterTheOldTenSecondCutoff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = requireNotNull(context.getSystemService(NsdManager::class.java))
        val name = "CineMedia Late ${UUID.randomUUID().toString().take(8)}"
        val stopped = AtomicBoolean(false)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (stopped.get()) runCatching { manager.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        val result = async {
            withTimeout(40_000) {
                NasDiscovery(context).discover().first { state -> state.devices.any { it.name == name } }
            }
        }
        val publish = launch {
            delay(12_000)
            manager.registerService(NsdServiceInfo().apply {
                serviceName = name
                serviceType = "_smb._tcp."
                port = 1445
            }, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        try {
            val found = result.await().devices.single { it.name == name }
            assertTrue(found.host.isNotBlank())
            assertEquals(1445, found.port)
        } finally {
            stopped.set(true)
            publish.cancelAndJoin()
            result.cancelAndJoin()
            runCatching { manager.unregisterService(listener) }
        }
    }
}
