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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND filePath IN (:paths)")
    suspend fun forPaths(sourceId: String, paths: List<String>): List<MovieEntity>

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

    @Query("SELECT * FROM movies WHERE isMissing = 0")
    suspend fun available(): List<MovieEntity>

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

    @Query("UPDATE movies SET isWatchlisted = :watchlisted WHERE id = :id")
    suspend fun setWatchlisted(id: String, watchlisted: Boolean)

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

    /** TV IDs have a separate namespace; only local episode rows receive these details. */
    @Query(
        "UPDATE movies SET tmdbId = NULL, seriesTitle = :seriesTitle, seriesTmdbId = :seriesTmdbId, " +
            "seasonNumber = :seasonNumber, episodeNumber = :episodeNumber, seasonPosterPath = :seasonPosterPath, " +
            "overview = :overview, tagline = NULL, runtimeMinutes = :runtimeMinutes, rating = :rating, " +
            "posterPath = :posterPath, backdropPath = :backdropPath, genres = :genresJson, metadataFetched = 1 WHERE id = :id",
    )
    suspend fun applyTVMetadata(
        id: String, seriesTitle: String, seriesTmdbId: Int, seasonNumber: Int, episodeNumber: Int,
        seasonPosterPath: String?, overview: String?, runtimeMinutes: Int?, rating: Double?,
        posterPath: String?, backdropPath: String?, genresJson: String,
    )

    @Query("UPDATE movies SET seriesTitle = :seriesTitle, seasonNumber = :season, episodeNumber = :episode, " +
        "tmdbId = NULL, overview = NULL, tagline = NULL, posterPath = NULL, backdropPath = NULL, " +
        "runtimeMinutes = NULL, rating = NULL, genres = '[]', metadataFetched = 1 WHERE id = :id")
    suspend fun rememberEpisode(id: String, seriesTitle: String, season: Int, episode: Int)

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

    /** A manual rename; locked so rescans and automatic matching keep the user's title. */
    @Query("UPDATE movies SET title = :title, sortTitle = :sortTitle, year = :year, isLocked = 1 WHERE id = :id")
    suspend fun rename(id: String, title: String, sortTitle: String, year: Int?)

    @Query("UPDATE movies SET isLocked = 1 WHERE id = :id")
    suspend fun lock(id: String)

    /** Forgets edits and the TMDB match, back to what a fresh scan of the file shows. */
    @Query(
        "UPDATE movies SET title = :title, sortTitle = :sortTitle, year = :year, tmdbId = :tmdbId, isLocked = 0, " +
            "metadataFetched = 0, overview = NULL, tagline = NULL, runtimeMinutes = NULL, rating = NULL, " +
            "posterPath = NULL, backdropPath = NULL, genres = :noGenresJson, seriesTitle = NULL, seriesTmdbId = NULL, " +
            "seasonNumber = NULL, episodeNumber = NULL, seasonPosterPath = NULL WHERE id = :id",
    )
    suspend fun resetToFile(id: String, title: String, sortTitle: String, year: Int?, tmdbId: Int?, noGenresJson: String)
}

@Database(entities = [SourceEntity::class, MovieEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class CineVaultDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun movies(): MovieDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN isWatchlisted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN seriesTitle TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN seriesTmdbId INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN seasonNumber INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN episodeNumber INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN seasonPosterPath TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sources ADD COLUMN ignoredFolders TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE sources ADD COLUMN ignoredFiles TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun create(context: Context, name: String = "cinevault.db"): CineVaultDatabase =
            Room.databaseBuilder(context, CineVaultDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
