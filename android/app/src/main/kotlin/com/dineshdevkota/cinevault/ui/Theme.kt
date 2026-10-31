package com.dineshdevkota.cinevault.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as MobileMaterialTheme
import androidx.compose.material3.darkColorScheme as mobileDarkColorScheme

val Background = Color(0xFF0E0F13)
val SurfaceColor = Color(0xFF1A1C22)
val Accent = Color(0xFFE5A00D)
val SecondaryText = Color(0xFFA7A9B2)
val ErrorText = Color(0xFFFF8A80)

/** TV overscan-safe horizontal margin. */
val ScreenPadding = 48.dp

@Composable
fun CineVaultTheme(content: @Composable () -> Unit) {
    // Text fields come from mobile Material 3, everything else from TV Material 3.
    MobileMaterialTheme(
        colorScheme = mobileDarkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Background,
            surface = SurfaceColor,
            onSurface = Color.White,
            onSurfaceVariant = SecondaryText,
        ),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = Color.Black,
                background = Background,
                onBackground = Color.White,
                surface = SurfaceColor,
                onSurface = Color.White,
                onSurfaceVariant = SecondaryText,
                border = Accent,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                colors = SurfaceDefaults.colors(containerColor = Background, contentColor = Color.White),
            ) {
                content()
            }
        }
    }
}

fun ComponentActivity.setCineVaultContent(content: @Composable () -> Unit) = setContent { CineVaultTheme(content) }
