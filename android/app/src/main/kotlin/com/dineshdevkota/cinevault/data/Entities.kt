package com.dineshdevkota.cinevault.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.dineshdevkota.cinevault.core.LibraryEntry
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
) {
    fun config(secrets: SecretBox): SourceConfig = SourceConfig(
        id = id,
        host = host,
        port = port,
        share = share,
        rootPath = rootPath,
        username = username,
        password = if (username.isEmpty()) "" else secrets.decrypt(encryptedPassword),
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
    val sourceId: String,
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
    override val playbackPositionMs: Long = 0,
    val durationMs: Long = 0,
    override val lastPlayedAt: Long? = null,
    override val isHidden: Boolean = false,
    override val isMissing: Boolean = false,
) : LibraryEntry {
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
