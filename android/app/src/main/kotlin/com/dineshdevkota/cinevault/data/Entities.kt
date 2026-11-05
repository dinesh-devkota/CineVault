package com.dineshdevkota.cinevault.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.dineshdevkota.cinevault.core.LibraryEntry
import com.dineshdevkota.cinevault.core.EpisodeInfo
import com.dineshdevkota.cinevault.core.EpisodeParser
import com.dineshdevkota.cinevault.core.MediaFile
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.core.TitleMatcher
import com.dineshdevkota.cinevault.core.WatchRules
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** A NAS share (SMB). The password is stored AES/GCM-encrypted with an Android Keystore key. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int? = null,
    val share: String,
    /** Folder inside the share to scan, e.g. "Movies". Empty scans the whole share. */
    val rootPath: String = "",
    val username: String = "",
    val encryptedPassword: String = "",
    val lastScanAt: Long? = null,
    /** Literal folder paths relative to rootPath; excluded rows retain their history. */
    @ColumnInfo(defaultValue = "'[]'") val ignoredFolders: List<String> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val ignoredFiles: List<String> = emptyList(),
) {
    fun config(secrets: SecretBox): SourceConfig = SourceConfig(
        id = id,
        host = host,
        port = port,
        share = share,
        rootPath = rootPath,
        username = username,
        password = if (username.isEmpty()) "" else secrets.decrypt(encryptedPassword),
        ignoredFolders = ignoredFolders,
        ignoredFiles = ignoredFiles,
    )
}

@Entity(
    tableName = "movies",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId", "filePath"], unique = true)],
)
data class MovieEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    override val sourceId: String,
    val filePath: String,
    override val fileName: String,
    val fileSize: Long,
    val parsedTitle: String,
    val parsedYear: Int?,
    override val title: String,
    override val sortTitle: String = WatchRules.sortTitle(title),
    override val year: Int?,
    val overview: String? = null,
    val tagline: String? = null,
    val tmdbId: Int? = null,
    val seriesTitle: String? = null,
    val seriesTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seasonPosterPath: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    override val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    override val rating: Double? = null,
    val metadataFetched: Boolean = false,
    /** User renamed / re-matched manually; rescans and auto-match won't overwrite. */
    val isLocked: Boolean = false,
    override val addedAt: Long = System.currentTimeMillis(),
    override val isWatched: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    override val isWatchlisted: Boolean = false,
    override val playbackPositionMs: Long = 0,
    val durationMs: Long = 0,
    override val lastPlayedAt: Long? = null,
    override val isHidden: Boolean = false,
    override val isMissing: Boolean = false,
) : LibraryEntry {
    val episodeInfo: EpisodeInfo?
        get() {
            if (MediaFile.isAudio(filePath)) return null
            val local = EpisodeParser.parse(filePath)
            val name = (seriesTitle ?: local?.seriesTitle)?.takeIf { it.isNotBlank() } ?: return null
            val season = (seasonNumber ?: local?.season)?.takeIf { it in 0..999 } ?: return null
            val episode = (episodeNumber ?: local?.episode)?.takeIf { it in 0..999 } ?: return null
            return EpisodeInfo(name, season, episode, if (seriesTmdbId != null) year else local?.year)
        }

    val mediaKind: MediaKind
        get() = MediaFile.kind(filePath, genres, matched = metadataFetched && tmdbId != null, isEpisode = episodeInfo != null)

    val seriesKey: String?
        get() {
            val info = episodeInfo ?: return null
            return seriesTmdbId?.let { "tmdb:$it" }
                ?: "local:${TitleMatcher.normalize(info.seriesTitle)}|${info.year ?: ""}"
        }

    val progress: Float
        get() = if (durationMs > 0) (playbackPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val isInProgress: Boolean get() = WatchRules.isInProgress(isWatched, playbackPositionMs)

    fun withTitle(newTitle: String): MovieEntity = copy(title = newTitle, sortTitle = WatchRules.sortTitle(newTitle))
}

class Converters {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(value: List<String>): String = Json.encodeToString(serializer, value)

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else runCatching { Json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}
