package com.example.EdgeMemo.native.qdrant

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import com.example.EdgeMemo.testing.TestNativeLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host JVM tests proving the qdrant-edge persistence chain:
 *
 *   create -> upsert -> search -> close -> reopen -> search
 *
 * and that a vector written by one process is retrievable from another
 * process (true on-disk persistence, not just in-memory).
 *
 * Runs against the host-built .so (not on Android). The library is loaded
 * through the shared [TestNativeLoader] so that all native tests in the unit
 * test JVM share a single classloader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantEdgePersistenceTest {

    companion object {
        private const val LIB_PROPERTY = "edgememo.qdrant.lib"
        private const val PHASE_PROPERTY = "edge.ph"
        private const val PERSIST_DIR_PROPERTY = "edge.persist.dir"
        private const val DIMENSION = 4

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val phase = args.firstOrNull() ?: System.getProperty(PHASE_PROPERTY)
                ?: throw IllegalArgumentException("phase (write|verify) required")
            val persistDir = File(
                System.getProperty(PERSIST_DIR_PROPERTY)
                    ?: throw IllegalArgumentException("$PERSIST_DIR_PROPERTY required"),
            )
            System.load(TestNativeLoader.nativeLibraryPath())
            when (phase) {
                "write" -> {
                    persistDir.mkdirs()
                    val shard = NativeBridge.nativeCreate(persistDir.absolutePath, DIMENSION)
                    NativeBridge.nativeUpsert(
                        shard,
                        "1001",
                        floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
                    )
                    NativeBridge.nativeUpsert(
                        shard,
                        "1002",
                        floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f),
                    )
                    NativeBridge.nativeUpsert(
                        shard,
                        "1003",
                        floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f),
                    )
                    NativeBridge.nativeClose(shard)
                }

                // Simulates an Android process death: upserts happen (with the
                // store's flush-after-upsert) and the JVM exits WITHOUT any
                // close/Drop, exactly like a killed Android process.
                "crashwrite" -> {
                    persistDir.mkdirs()
                    val shard = NativeBridge.nativeCreate(persistDir.absolutePath, DIMENSION)
                    NativeBridge.nativeUpsert(
                        shard,
                        "51",
                        floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f),
                    )
                    NativeBridge.nativeUpsert(
                        shard,
                        "52",
                        floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f),
                    )
                    NativeBridge.nativeFlush(shard)
                    // No nativeClose — the JVM exit releases the directory
                    // lock without ever dropping the shard.
                }

                "verify" -> {
                    val shard = NativeBridge.nativeOpen(persistDir.absolutePath)
                    val count = NativeBridge.nativeCount(shard)
                    val results = NativeBridge.nativeSearch(
                        shard,
                        floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f),
                        1,
                    )
                    NativeBridge.nativeClose(shard)
                    val topId = results.firstOrNull()?.id ?: "NONE"
                    println("$POSITIVE_VERIF top=$topId count=$count")
                }

                else -> throw IllegalArgumentException("unknown phase: $phase")
            }
        }

        private const val POSITIVE_VERIF = "VERIFIED"
    }

    @Test
    fun vectorSurvivesReopenWithinSameProcess() = runBlocking {
        val dir = newTempDir("edgememo-same-process")
        val store = QdrantEdgeVectorStore(dir)
        try {
            store.initialize(DIMENSION)
            store.upsert(
                listOf(
                    VectorPoint("1", floatArrayOf(1f, 0f, 0f, 0f)),
                    VectorPoint("2", floatArrayOf(0f, 1f, 0f, 0f)),
                    VectorPoint("3", floatArrayOf(0f, 0f, 1f, 0f)),
                ),
            )
            assertEquals(3L, store.count())

            val top = store.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0f), 3)
            assertEquals("1", top.first().id)
            assertTrue(top.first().score > 0.9)

            store.close()

            val reopened = QdrantEdgeVectorStore(dir)
            reopened.open()
            assertEquals("data must survive reopen", 3L, reopened.count())
            val again = reopened.search(floatArrayOf(0.1f, 0.9f, 0.1f, 0f), 3)
            assertEquals("2", again.first().id)
            reopened.optimize()
            reopened.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun vectorSurvivesSeparateProcessRestart() {
        val dir = newTempDir("edgememo-separate-process")
        try {
            val lib = TestNativeLoader.nativeLibraryPath()
            val write = runChild(lib, "write", dir)
            assertTrue("write process must succeed", write.isSuccess)
            val verify = runChild(lib, "verify", dir)
            assertTrue("verify process must succeed", verify.isSuccess)
            assertTrue(
                "verify process must find the written vector, got:\n${verify.output}",
                verify.output.contains("VERIFIED top=1001 count=3"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun searchOnEmptyShardReturnsNothing() = runBlocking {
        val dir = newTempDir("edgememo-empty")
        val store = QdrantEdgeVectorStore(dir)
        try {
            store.initialize(DIMENSION)
            assertEquals(0L, store.count())
            assertTrue(store.search(floatArrayOf(1f, 0f, 0f, 0f), 5).isEmpty())
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dimensionMismatchFails() = runBlocking {
        val dir = newTempDir("edgememo-dim")
        val store = QdrantEdgeVectorStore(dir)
        try {
            store.initialize(DIMENSION)
            try {
                store.upsert(listOf(VectorPoint("1", floatArrayOf(1f, 0f))))
                throw AssertionError("expected dimension mismatch failure")
            } catch (expected: QdrantNativeException) {
                assertTrue(expected.message.orEmpty().contains("dimension"))
            }
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesPoint() = runBlocking {
        val dir = newTempDir("edgememo-delete")
        val store = QdrantEdgeVectorStore(dir)
        try {
            store.initialize(DIMENSION)
            store.upsert(listOf(VectorPoint("7", floatArrayOf(1f, 0f, 0f, 0f))))
            assertEquals(1L, store.count())
            store.delete("7")
            assertEquals(0L, store.count())
            assertTrue(store.search(floatArrayOf(1f, 0f, 0f, 0f), 5).isEmpty())
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun vectorSurvivesWithoutGracefulClose() = runBlocking {
        // Simulates an Android process death: the writing process exits
        // without any close/Drop (no graceful flush), so the persisted points
        // must come from the store's flush-after-upsert alone.
        val dir = newTempDir("edgememo-no-close")
        try {
            val lib = TestNativeLoader.nativeLibraryPath()
            val crashWrite = runChild(lib, "crashwrite", dir)
            assertTrue("crash-write process must succeed", crashWrite.isSuccess)

            val second = QdrantEdgeVectorStore(dir)
            second.open()
            assertEquals("points must survive a process death without close", 2L, second.count())
            val top = second.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0f), 2)
            assertEquals("51", top.first().id)
            second.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runChild(lib: String, phase: String, dir: File): ChildResult {
        val java = "${System.getProperty("java.home")}/bin/java"
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            java,
            "-D$LIB_PROPERTY=$lib",
            "-D$PHASE_PROPERTY=$phase",
            "-D$PERSIST_DIR_PROPERTY=${dir.absolutePath}",
            "-cp",
            classpath,
            QdrantEdgePersistenceTest::class.java.name,
            phase,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        process.destroyForcibly()
        return if (finished && process.exitValue() == 0) {
            ChildResult(true, output)
        } else {
            ChildResult(false, output)
        }
    }

    private fun newTempDir(prefix: String): File {
        val dir = File.createTempFile(prefix, "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private data class ChildResult(val isSuccess: Boolean, val output: String)
}