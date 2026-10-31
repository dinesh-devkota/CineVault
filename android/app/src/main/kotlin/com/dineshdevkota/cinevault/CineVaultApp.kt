package com.dineshdevkota.cinevault

import android.app.Application
import android.content.Context
import android.util.Log
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.SecretBox
import com.dineshdevkota.cinevault.data.Settings
import com.dineshdevkota.cinevault.library.LibraryScanner
import com.dineshdevkota.cinevault.smb.SmbService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CineVaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** App-wide singletons. Work launched in [scope] outlives individual screens (scans, metadata). */
class AppContainer(context: Context) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.e("CineVault", "Background task failed", error) },
    )
    val secrets = SecretBox()
    val settings = Settings(context, secrets)
    val database = CineVaultDatabase.create(context)
    val smb = SmbService()
    val scanner = LibraryScanner(database, secrets, smb::scanVideos)
}

val Context.container: AppContainer get() = (applicationContext as CineVaultApp).container
