package com.example.EdgeMemo.presentation

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.data.seed.CoolingWaterPumpDataset
import com.example.EdgeMemo.data.seed.CubicalDataset
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.presentation.components.AuroraBackground
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import com.example.EdgeMemo.testing.TestNativeLoader
import com.example.EdgeMemo.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design-review harness: renders the seeded shell to PNGs in build/ui-shots.
 * Skipped unless EDGEMIND_SHOTS=1. On Windows also point the JVM temp dir at a
 * short path, or Qdrant segment paths overflow MAX_PATH:
 *   EDGEMIND_SHOTS=1 JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:/rt ./gradlew --no-daemon  *     :app:testDebugUnitTest --tests "*UiScreenshotHarness"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1100dp-xhdpi")
class UiScreenshotHarness {

    companion object {
        @JvmStatic
        @BeforeClass
        fun load() = TestNativeLoader.ensureLoaded()

        val outDir = File(System.getProperty("user.dir"), "build/ui-shots").apply { mkdirs() }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(dark: Boolean): AppContainer {
        org.junit.Assume.assumeTrue(System.getenv("EDGEMIND_SHOTS") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        runBlocking {
            CubicalDataset.seedIfNeeded(app, container.memoryRepository)
            CoolingWaterPumpDataset.seedIfNeeded(app, container.memoryRepository)
        }
        compose.setContent {
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                MyApplicationTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize()) {
                        AuroraBackground(darkTheme = dark)
                        EdgeMindShell(container, dark, {}, "Subarno", {})
                    }
                }
            }
        }
        return container
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        runCatching {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("edge-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
        }
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tab(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun tour(prefix: String, dark: Boolean) {
        show(dark)
        shot("$prefix-1-dashboard")
        tab("edge-tab-machines"); shot("$prefix-2-machines")
        runCatching {
            compose.onAllNodesWithTag("edge-machine-card-p-101", useUnmergedTree = true).onFirst().performClick()
            shot("$prefix-3-machine-detail")
        }
        tab("edge-tab-ask"); shot("$prefix-4-ask")
        tab("edge-tab-sync"); shot("$prefix-5-sync")
        tab("edge-tab-settings"); shot("$prefix-6-settings")
    }

    @Test fun dark() = tour("dark", true)

    @Test fun light() = tour("light", false)
}
