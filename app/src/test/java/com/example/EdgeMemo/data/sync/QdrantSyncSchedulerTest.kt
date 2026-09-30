package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.13 — scheduling regression contract for the Qdrant-native
 * pipeline (12A §25): unique work, dedicated name, connectivity constraint,
 * exponential backoff, and no duplicate schedules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun qdrantWorkUsesItsOwnDedicatedUniqueName() {
        assertEquals("edgemind-qdrant-sync", QdrantSyncWorker.WORK_NAME)
        assertEquals("edgemind-qdrant-sync", SyncScheduler.QDRANT_SYNC_WORK_NAME)
        assertNotEquals(SyncScheduler.SYNC_WORK_NAME, SyncScheduler.QDRANT_SYNC_WORK_NAME)
    }

    @Test
    fun duplicateSchedulingCollapsesToExactlyOneExecution() {
        val scheduler = SyncScheduler(context)
        scheduler.requestQdrantSync()
        scheduler.requestQdrantSync()
        scheduler.requestQdrantSync()

        val infos = workManager
            .getWorkInfosForUniqueWork(SyncScheduler.QDRANT_SYNC_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, infos[0].state)
    }

    @Test
    fun legacyAndQdrantPipelinesCoexistWithoutInterleaving() {
        val scheduler = SyncScheduler(context)
        scheduler.requestSync()
        scheduler.requestQdrantSync()

        val legacy = workManager.getWorkInfosForUniqueWork(SyncScheduler.SYNC_WORK_NAME).get()
        val qdrant = workManager.getWorkInfosForUniqueWork(SyncScheduler.QDRANT_SYNC_WORK_NAME).get()
        assertEquals(1, legacy.size)
        assertEquals(1, qdrant.size)
        assertNotEquals(legacy[0].id, qdrant[0].id)
    }

    @Test
    fun qdrantRequestRequiresConnectivityAndUsesBoundedExponentialBackoff() {
        val spec = workSpecOf(SyncScheduler.buildQdrantRequest())
        val constraints = read(spec, "constraints") as androidx.work.Constraints
        val networkField = constraints.javaClass.declaredFields.first {
            it.type.simpleName.contains("NetworkType")
        }.apply { isAccessible = true }
        assertEquals(
            "cloud execution must require connectivity",
            "CONNECTED",
            networkField.get(constraints)?.toString(),
        )
        assertEquals(BackoffPolicy.EXPONENTIAL, read(spec, "backoffPolicy"))
        val delay = readAny(
            spec,
            listOf("backoffDelayMillis", "backoffDelay", "backoffDelayDuration"),
        )
        val delayMs = when (delay) {
            is kotlin.time.Duration -> delay.inWholeMilliseconds
            is Number -> delay.toLong()
            else -> throw IllegalStateException("unexpected backoff representation: $delay")
        }
        assertEquals(SyncScheduler.BACKOFF_DELAY_SECONDS * 1000L, delayMs)
        assertTrue(
            "worker class must be the Qdrant-native pipeline worker",
            readAny(spec, listOf("workClassName", "workerClassName")).toString()
                .contains("QdrantSyncWorker"),
        )
    }

    /** WorkSpec is module-internal; reflection pins the scheduling contract
     *  without changing production visibility. Handles both Java-field and
     *  Kotlin-property shapes of androidx.work. */
    private fun workSpecOf(request: androidx.work.OneTimeWorkRequest): Any =
        request.javaClass.methods.first { it.name.startsWith("getWorkSpec") }.invoke(request)

    private fun readAny(target: Any, candidates: List<String>): Any? {
        var last: NoSuchElementException? = null
        for (name in candidates) {
            try {
                return read(target, name)
            } catch (e: NoSuchElementException) {
                last = e
            }
        }
        throw last ?: NoSuchElementException("none of $candidates present on ${target.javaClass}")
    }

    private fun read(target: Any, property: String): Any? {
        val getterName = "get" + property.replaceFirstChar { c -> c.uppercase() }
        for (method in target.javaClass.methods) {
            if (method.name == getterName && method.parameterCount == 0) {
                return method.invoke(target)
            }
        }
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val f = type.getDeclaredField(property)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchElementException(
            "no accessor or field for '$property' on ${target.javaClass}: " +
                target.javaClass.methods.map { it.name }.sorted().joinToString(",") +
                " | " + target.javaClass.declaredFields.map { it.name }.sorted().joinToString(","),
        )
    }

    @Test
    fun startupResumePolicyKeepsAlreadyScheduledWorkUntouched() {
        val scheduler = SyncScheduler(context)
        scheduler.requestQdrantSync(ExistingWorkPolicy.KEEP)
        val first = workManager.getWorkInfosForUniqueWork(SyncScheduler.QDRANT_SYNC_WORK_NAME).get()[0]
        scheduler.requestQdrantSync(ExistingWorkPolicy.KEEP)
        val second = workManager.getWorkInfosForUniqueWork(SyncScheduler.QDRANT_SYNC_WORK_NAME).get()[0]
        assertEquals("KEEP must never supersede a scheduled run", first.id, second.id)
    }
}
