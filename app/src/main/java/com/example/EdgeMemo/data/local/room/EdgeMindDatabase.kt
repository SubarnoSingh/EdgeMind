package com.example.EdgeMemo.data.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoryEntity::class,
        SyncOutboxEntity::class,
        ConflictEntity::class,
        CloudCursorEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(MemoryTypeConverters::class)
abstract class EdgeMindDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun conflictDao(): ConflictDao

    abstract fun cloudCursorDao(): CloudCursorDao

    companion object {
        /**
         * v1 → v2: add the persisted policy state (decision explanation and the
         * redacted sync representation, present only for `SYNC_REDACTED`).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN policyReason TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN redactedTitle TEXT")
                db.execSQL("ALTER TABLE memories ADD COLUMN redactedContent TEXT")
            }
        }

        /**
         * v2 → v3: add the durable sync outbox (Phase 6). No payload or
         * content is created for existing rows; new rows only ever contain the
         * SyncPayloadFactory-sanctioned representation.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `operationId` TEXT NOT NULL,
                        `memoryId` TEXT NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `payloadTitle` TEXT NOT NULL,
                        `payloadContent` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`operationId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_memoryId` ON `sync_outbox` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_state` ON `sync_outbox` (`state`)")
            }
        }

        /**
         * v3 → v4: Phase 7. Add the cloud-authority column to memories plus the
         * persisted conflict table and the incremental cloud-pull checkpoint.
         * Existing rows stay untouched (authority is null until cloud knowledge
         * is applied); device-authored memories are never reclassified here.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN authority TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conflicts` (
                        `conflictId` TEXT NOT NULL,
                        `subjectKey` TEXT NOT NULL,
                        `localMemoryId` TEXT,
                        `incomingMemoryId` TEXT,
                        `localTitle` TEXT NOT NULL,
                        `incomingTitle` TEXT NOT NULL,
                        `localContent` TEXT NOT NULL,
                        `incomingContent` TEXT NOT NULL,
                        `localVersion` INTEGER,
                        `incomingVersion` INTEGER,
                        `localContentHash` TEXT,
                        `incomingContentHash` TEXT NOT NULL,
                        `localOrigin` TEXT NOT NULL,
                        `incomingOrigin` TEXT NOT NULL,
                        `localAuthority` TEXT,
                        `incomingAuthority` TEXT,
                        `detectedAt` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `resolvedAt` INTEGER,
                        `resolution` TEXT,
                        PRIMARY KEY(`conflictId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conflicts_state` ON `conflicts` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conflicts_subjectKey` ON `conflicts` (`subjectKey`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_pull_cursor` (
                        `id` TEXT NOT NULL,
                        `cursor` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}