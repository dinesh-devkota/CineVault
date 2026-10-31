package com.dineshdevkota.cinevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.dineshdevkota.cinevault.ui.RootScreen
import com.dineshdevkota.cinevault.ui.setCineVaultContent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) container.scope.launch { container.scanner.scanAll() }
        setCineVaultContent { RootScreen() }
    }
}