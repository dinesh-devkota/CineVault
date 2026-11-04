-- Frozen Room v1 createAllTables DDL, including its identity hash.
-- Preserve this historical schema when current entities change.
CREATE TABLE IF NOT EXISTS `sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER, `share` TEXT NOT NULL, `rootPath` TEXT NOT NULL, `username` TEXT NOT NULL, `encryptedPassword` TEXT NOT NULL, `lastScanAt` INTEGER, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `movies` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `parsedTitle` TEXT NOT NULL, `parsedYear` INTEGER, `title` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, `year` INTEGER, `overview` TEXT, `tagline` TEXT, `tmdbId` INTEGER, `posterPath` TEXT, `backdropPath` TEXT, `genres` TEXT NOT NULL, `runtimeMinutes` INTEGER, `rating` REAL, `metadataFetched` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `isWatched` INTEGER NOT NULL, `playbackPositionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `lastPlayedAt` INTEGER, `isHidden` INTEGER NOT NULL, `isMissing` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE UNIQUE INDEX IF NOT EXISTS `index_movies_sourceId_filePath` ON `movies` (`sourceId`, `filePath`);
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '3486e8641b4da236da2bc2eaa6549712');
PRAGMA user_version = 1;
