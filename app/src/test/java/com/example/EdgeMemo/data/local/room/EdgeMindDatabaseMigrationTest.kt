package com.example.EdgeMemo.data.local.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves schema downgrades from a real v1/v2 database with existing rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeMindDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "migration-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun v1RowSurvivesMigrationThroughV4WithNullPolicyAndAuthorityState() {
        val name = "migration-${UUID.randomUUID()}"

        // hand-built v1 schema at the location Room will open: exactly the
        // columns and indices Room v1 expected, stamped as schema version 1.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
            raw.execSQL("PRAGMA user_version = 1")
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memories` (
                    `memoryId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `chunkId` TEXT,
                    `source` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `origin` TEXT NOT NULL,
                    `syncDecision` TEXT NOT NULL,
                    `syncState` TEXT NOT NULL,
                    `sensitivity` TEXT NOT NULL,
                    `importance` INTEGER NOT NULL,
                    `version` INTEGER NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `subjectKey` TEXT,
                    `supersedes` TEXT,
                    `tombstone` INTEGER NOT NULL,
                    `metadata` TEXT NOT NULL,
                    PRIMARY KEY(`memoryId`)
                )
                """.trimIndent(),
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_type` ON `memories` (`type`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_tombstone` ON `memories` (`tombstone`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_subjectKey` ON `memories` (`subjectKey`)")
            raw.execSQL(
                "INSERT INTO `memories` VALUES ('mem-1','Gate code','Gate code for Site 7 is 4412',NULL," +
                    "'USER_ENTRY','NOTE','[\"access\"]',1,1,'LOCAL','LOCAL_ONLY','LOCAL','STANDARD',0,1,'hash',NULL,NULL,0,'{}')",
            )
        }

        val db = Room.databaseBuilder(context, EdgeMindDatabase::class.java, name)
            .addMigrations(
                EdgeMindDatabase.MIGRATION_1_2,
                EdgeMindDatabase.MIGRATION_2_3,
                EdgeMindDatabase.MIGRATION_3_4,
            )
            .build()

        try {
            val dao = db.memoryDao()
            runBlocking {
                val row = dao.getById("mem-1")
                assertEquals("Gate code", row?.title)
                assertEquals("LOCAL_ONLY", row?.syncDecision)
                // v2 adds policy state without inventing values for old rows
                assertNull(row?.policyReason)
                assertNull(row?.redactedTitle)
                assertNull(row?.redactedContent)
                assertEquals(listOf("[\"access\"]"), row?.tags)
                // v3 adds an empty sync outbox that leaves old memories untouched
                assertEquals(0, db.syncOutboxDao().countAll())
                // v4 adds an empty conflicts table and cloud checkpoint
                assertEquals(0, db.conflictDao().countUnresolved())
                assertNull(row?.authority)
                assertNull(db.cloudCursorDao().get())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun v2DatabaseWithMemoryAndOperationMigratesScarlessly() {
        val name = "migration-${UUID.randomUUID()}"

        // hand-built v2 schema (policy columns added), stamped version 2.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
            raw.execSQL("PRAGMA user_version = 2")
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memories` (
                    `memoryId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `chunkId` TEXT,
                    `source` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `origin` TEXT NOT NULL,
                    `syncDecision` TEXT NOT NULL,
                    `syncState` TEXT NOT NULL,
                    `sensitivity` TEXT NOT NULL,
                    `importance` INTEGER NOT NULL,
                    `version` INTEGER NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `subjectKey` TEXT,
                    `supersedes` TEXT,
                    `tombstone` INTEGER NOT NULL,
                    `metadata` TEXT NOT NULL,
                    `policyReason` TEXT,
                    `redactedTitle` TEXT,
                    `redactedContent` TEXT,
                    PRIMARY KEY(`memoryId`)
                )
                """.trimIndent(),
            )
            raw.execSQL(
                "INSERT INTO `memories` VALUES ('mem-2','P-101 repair','Repair of P-101 bearing',NULL," +
                    "'USER_ENTRY','REPAIR','[\"repair\"]',2,2,'LOCAL','SYNC_REDACTED','SYNCED','SENSITIVE',1,1,'h2','P-101',NULL,0,'{}'," +
                    "'site code',NULL,'[P-101]')",
            )
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_type` ON `memories` (`type`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_tombstone` ON `memories` (`tombstone`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_subjectKey` ON `memories` (`subjectKey`)")
        }

        val db = Room.databaseBuilder(context, EdgeMindDatabase::class.java, name)
            .addMigrations(
                EdgeMindDatabase.MIGRATION_2_3,
                EdgeMindDatabase.MIGRATION_3_4,
            )
            .build()

        try {
            val dao = db.memoryDao()
            runBlocking {
                val row = dao.getById("mem-2")
                assertEquals("P-101 repair", row?.title)
                assertEquals("SYNC_REDACTED", row?.syncDecision)
                assertEquals("[P-101]", row?.redactedContent)
                assertEquals(0, db.syncOutboxDao().countAll())
                assertNull(row?.authority)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun v3DatabaseWithMemoryAndOutboxMigratesToV4KeepingBoth() {
        val name = "migration-${UUID.randomUUID()}"

        // hand-built v3 schema (policy columns + sync outbox), stamped version 3.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
            raw.execSQL("PRAGMA user_version = 3")
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memories` (
                    `memoryId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `chunkId` TEXT,
                    `source` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `origin` TEXT NOT NULL,
                    `syncDecision` TEXT NOT NULL,
                    `syncState` TEXT NOT NULL,
                    `sensitivity` TEXT NOT NULL,
                    `importance` INTEGER NOT NULL,
                    `version` INTEGER NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `subjectKey` TEXT,
                    `supersedes` TEXT,
                    `tombstone` INTEGER NOT NULL,
                    `metadata` TEXT NOT NULL,
                    `policyReason` TEXT,
                    `redactedTitle` TEXT,
                    `redactedContent` TEXT,
                    PRIMARY KEY(`memoryId`)
                )
                """.trimIndent(),
            )
            raw.execSQL(
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
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_createdAt` ON `memories` (`createdAt`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_type` ON `memories` (`type`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_tombstone` ON `memories` (`tombstone`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_subjectKey` ON `memories` (`subjectKey`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_memoryId` ON `sync_outbox` (`memoryId`)")
            raw.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_state` ON `sync_outbox` (`state`)")
            raw.execSQL(
                "INSERT INTO `memories` VALUES ('mem-3','Bearing','SKF-6205 bearing replaced',NULL," +
                    "'USER_ENTRY','REPAIR','[]',3,3,'LOCAL','SYNC','PENDING','STANDARD',0,1,'h3','SKF-6205',NULL,0,'{}'," +
                    "'IDENTIFIER',NULL,NULL)",
            )
            raw.execSQL(
                "INSERT INTO `sync_outbox` VALUES ('UPSERT-mem-3','mem-3','UPSERT','Bearing','SKF-6205 bearing replaced',3,1,'PENDING',NULL)",
            )
        }

        val db = Room.databaseBuilder(context, EdgeMindDatabase::class.java, name)
            .addMigrations(EdgeMindDatabase.MIGRATION_3_4)
            .build()

        try {
            runBlocking {
                val row = db.memoryDao().getById("mem-3")
                assertEquals("Bearing", row?.title)
                assertEquals("PENDING", row?.syncState)
                assertNull(row?.authority)
                assertEquals(1L, db.syncOutboxDao().countAll())
                assertEquals("UPSERT-mem-3", db.syncOutboxDao().getById("UPSERT-mem-3")?.operationId)
                // fresh v4 tables are empty and usable
                assertEquals(0L, db.conflictDao().countUnresolved())
                assertNull(db.cloudCursorDao().get())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun freshDatabaseOpensAtCurrentVersion() {
        val db = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "fresh-${UUID.randomUUID()}")
            .addMigrations(EdgeMindDatabase.MIGRATION_1_2)
            .build()
        try {
            runBlocking {
                assertEquals(0L, db.memoryDao().count())
            }
        } finally {
            db.close()
        }
    }
}