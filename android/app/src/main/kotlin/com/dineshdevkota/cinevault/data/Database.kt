package com.dineshdevkota.cinevault.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sources SET lastScanAt = :time WHERE id = :id")
    suspend fun setLastScan(id: String, time: Long)
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies")
    fun observeAll(): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE id = :id")
    fun observe(id: String): Flow<MovieEntity?>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun get(id: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: String): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE metadataFetched = 0 AND isMissing = 0")
    suspend fun needingMetadata(): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(movie: MovieEntity)

    @Update
    suspend fun update(movie: MovieEntity)

    @Query("UPDATE movies SET isMissing = 0, fileSize = :size WHERE id = :id")
    suspend fun markPresent(id: String, size: Long)

    @Query("UPDATE movies SET isMissing = 1 WHERE id = :id")
    suspend fun markMissing(id: String)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    /** Saves a resume point; replaying a watched movie makes it in-progress again (tvOS parity). */
    @Query(
        "UPDATE movies SET playbackPositionMs = :positionMs, isWatched = 0, lastPlayedAt = :playedAt, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :id",
    )
    suspend fun saveProgress(id: String, positionMs: Long, durationMs: Long, playedAt: Long)

    @Query(
        "UPDATE movies SET isWatched = 1, playbackPositionMs = 0, lastPlayedAt = :playedAt, " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :id",
    )
    suspend fun markFinished(id: String, durationMs: Long, playedAt: Long)

    @Query("UPDATE movies SET isWatched = :watched, playbackPositionMs = 0 WHERE id = :id")
    suspend fun setWatched(id: String, watched: Boolean)

    @Query("UPDATE movies SET playbackPositionMs = 0 WHERE id = :id")
    suspend fun resetProgress(id: String)

    @Query("UPDATE movies SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    /** Stores TMDB details without touching playback state or the user's title. */
    @Query(
        "UPDATE movies SET tmdbId = :tmdbId, overview = :overview, tagline = :tagline, runtimeMinutes = :runtimeMinutes, " +
            "rating = :rating, posterPath = :posterPath, backdropPath = :backdropPath, genres = :genresJson, " +
            "metadataFetched = 1 WHERE id = :id",
    )
    suspend fun applyMetadata(
        id: String,
        tmdbId: Int,
        overview: String?,
        tagline: String?,
        runtimeMinutes: Int?,
        rating: Double?,
        posterPath: String?,
        backdropPath: String?,
        genresJson: String,
    )

    /** Takes TMDB's title and year unless the user locked the title; a manual re-match ([force]) always does. */
    @Query(
        "UPDATE movies SET title = :title, sortTitle = :sortTitle, year = COALESCE(:year, year) " +
            "WHERE id = :id AND (isLocked = 0 OR :force)",
    )
    suspend fun applyMatchedTitle(id: String, title: String, sortTitle: String, year: Int?, force: Boolean)

    @Query("UPDATE movies SET metadataFetched = 1 WHERE id = :id")
    suspend fun markMetadataFetched(id: String)

    @Query("UPDATE movies SET metadataFetched = 0")
    suspend fun clearMetadataFetched()
}

@Database(entities = [SourceEntity::class, MovieEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CineVaultDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun movies(): MovieDao

    companion object {
        fun create(context: Context): CineVaultDatabase =
            Room.databaseBuilder(context, CineVaultDatabase::class.java, "cinevault.db").build()
    }
}
