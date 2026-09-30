package com.example.EdgeMemo

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.example.EdgeMemo.data.seed.CubicalDataset
import com.example.EdgeMemo.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WorkManager uses ON-DEMAND initialization (default initializer removed in
 * the manifest): the first `WorkManager.getInstance` call — from the app or
 * from a WorkManager-restored process — configures itself from this
 * provider. No Activity needs to exist first, and durable scheduled work
 * resumes from WorkManager's own persisted database on process death/reboot.
 */
class EdgeMindApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Phase 12B.12: resume the durable Qdrant-native sync pipeline on every
        // process start (KEEP: an already scheduled/executing run is untouched).
        container.scheduleQdrantSync()
        // Phase 13.4: import any pre-cutover Room rows exactly once, off the
        // main thread. The importer opens Room ONLY when the legacy database
        // file exists and its completion marker is absent; fresh installs
        // never touch Room at all. Failures leave the data in Room untouched
        // and are retried on the next start (the marker is written only on
        // completion); if the import produced sync work it is drained by the
        // authoritative Qdrant sync worker.
        startupScope.launch {
            runCatching { container.migrateLegacyRoomDataIfNeeded() }
                .onFailure { container.recordLegacyImportFailure(it) }
            // Load the P-101 cubical dataset once; a failure retries next start.
            // Skipped under Robolectric so host tests keep their empty stores.
            if (Build.FINGERPRINT != "robolectric") {
                runCatching { CubicalDataset.seedIfNeeded(this@EdgeMindApplication, container.memoryRepository) }
                    .onFailure { Log.e("EdgeMind", "cubical dataset seed failed", it) }
            }
        }
    }
}
