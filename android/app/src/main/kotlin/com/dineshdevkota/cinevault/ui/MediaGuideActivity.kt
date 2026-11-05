@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class MediaGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCineVaultContent { MediaGuideScreen() }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MediaGuideActivity::class.java))
        }
    }
}

@Composable
private fun MediaGuideScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader("Media Guide", kicker = "CINE-MEDIA") }
        item {
            GuideSection(
                "One source for your whole library",
                "Select your Media share and leave the folder empty. If Media is inside another share, " +
                    "choose its Media folder. Scans include every subfolder. A source ending in Media/Movies " +
                    "cannot find sibling Music or TV Shows folders; select their common parent instead.",
            )
        }
        item {
            GuideSection(
                "Choose folders and files to ignore",
                "Open Sources → Edit. Choose the scan folder from the dropdown, then open the ignore picker. " +
                    "Ignore a whole folder and its subfolders, or one file. Save & Scan applies the exclusions " +
                    "without deleting NAS files or watch history. Include again and rescan to restore them. " +
                    "TypeScript code and node_modules are skipped; genuine .ts video files are checked before import.",
            )
        }
        item {
            GuideSection(
                "Names that help matching",
                "Media/Movies/Arrival (2016)/Arrival (2016).mkv\n" +
                    "Media/Documentaries/Title (2020)/Title (2020).mp4\n" +
                    "Media/TV Shows/Show Name (2020)/Season 01/ShowName.S01E01.mkv\n" +
                    "Media/Music/Artist/Album/01-Song.mp3",
                monospaced = true,
            )
        }
        item {
            GuideSection(
                "Posters, details, and categories",
                "Enter your free personal-use TMDB API key or read access token in Settings, then save and " +
                    "fetch missing metadata. Without a key, new items use filename/folder information and " +
                    "placeholder artwork. Matched metadata takes priority over folder guesses. Correct " +
                    "ambiguous matches using the title and year. Keep your key in the app, not in chat.",
            )
        }
        item {
            GuideSection(
                "TV shows and music",
                "Use S01E01-style episode names. Open a show's poster, choose a season, then an episode. " +
                    "Keep audio music in artist/album folders. TMDB's Music category is for screen content; " +
                    "it does not provide an MP3 album catalog.",
            )
        }
        item {
            GuideSection(
                "Supported playback formats",
                "Video: MP4, M4V, MOV, MKV, WebM, AVI, standard MPEG-TS, MPEG-PS, and FLV. " +
                    "Audio: MP3, M4A, AAC, FLAC, WAV, Ogg, and Opus. The TV must also support the codecs " +
                    "inside the file. WMV, ISO, M2TS, WMA, and AIFF can be cataloged but do not have Play " +
                    "controls in this Android app. TypeScript .ts and .d.ts files are not media.",
            )
        }
        item {
            GuideSection(
                "Scan and play",
                "Enter NAS credentials only in the app; a read-only account is recommended. Partial results " +
                    "can appear before scanning finishes, so check the status and last successful scan time. " +
                    "Playback depends on the device's codecs; Cine-Media does not transcode. Use the remote's " +
                    "Back button or the Back control above to return to Settings.",
            )
        }
    }
}

@Composable
private fun GuideSection(title: String, body: String, monospaced: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {}
            .focusable()
            .background(SurfaceColor, shape)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
