package com.dineshdevkota.cinevault.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class NasShare(val name: String, val comment: String = "")

/** Short-lived setup connections are independent of the playback connection cache. */
class NasBrowser {
    suspend fun listShares(config: SourceConfig): List<NasShare> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        withSession(config) { session ->
            ServerService(SMBTransportFactories.SRVSVC.getTransport(session)).shares1
                .filter { visibleDiskShare(it.netName, it.type) }
                .map { NasShare(it.netName, it.remark.orEmpty()) }
                .distinctBy { it.name }
                .sortedBy { it.name.lowercase() }
        }.also { coroutineContext.ensureActive() }
    }

    suspend fun listFolders(config: SourceConfig): List<String> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        withSession(config) { session ->
            val connected = session.connectShare(config.share.trim('/', '\\'))
            connected.use {
                val share = it as? DiskShare ?: throw SmbException("This is not a file share.")
                share.list(SmbService.rootOf(config))
                    .filter { entry -> entry.fileName != "." && entry.fileName != ".." &&
                        EnumUtils.isSet(entry.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY) }
                    .map { entry -> entry.fileName }
                    .sortedBy { name -> name.lowercase() }
            }
        }.also { coroutineContext.ensureActive() }
    }

    private fun <T> withSession(config: SourceConfig, block: (Session) -> T): T {
        val host = SmbService.normalizedHost(config.host)
        require(host.isNotBlank()) { "Enter a NAS address." }
        val contexts = if (config.username.isBlank()) {
            listOf(AuthenticationContext.anonymous(), AuthenticationContext.guest())
        } else {
            val (domain, user) = SmbService.splitDomain(config.username)
            listOf(AuthenticationContext(user, config.password.toCharArray(), domain))
        }
        SMBClient(SmbConfig.builder().withSecurityProvider(BCSecurityProvider())
            .withTimeout(8, TimeUnit.SECONDS).withSoTimeout(10, TimeUnit.SECONDS).build()).use { client ->
            client.connect(host, config.port ?: SMBClient.DEFAULT_PORT).use { connection ->
                for ((index, context) in contexts.withIndex()) {
                    try {
                        return connection.authenticate(context).use(block)
                    } catch (error: SMBApiException) {
                        if (index == contexts.lastIndex || error.statusCode !in authenticationStatuses) throw error
                    }
                }
            }
        }
        throw SmbException("Could not sign in to the NAS.")
    }

    companion object {
        private val authenticationStatuses = setOf(0xC000006DL, 0xC000006AL, 0xC0000022L)

        /** Hidden shares remain available through manual setup, even if a server omits their flags. */
        internal fun visibleDiskShare(name: String, type: Int): Boolean =
            name.isNotBlank() && !name.endsWith('$') && (type and 0xFF) == 0 && (type and Int.MIN_VALUE) == 0
    }
}
