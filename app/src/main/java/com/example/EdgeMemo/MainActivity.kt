package com.example.EdgeMemo

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.EdgeMemo.presentation.components.AuroraBackground
import com.example.EdgeMemo.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Paint the window the same color as the saved theme BEFORE Compose
        // draws, so cold start in dark mode never flashes white (and vice
        // versa). The theme resource fallback only covers the system night
        // mode; this covers the in-app toggle too.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialDark = prefs.getBoolean(KEY_DARK_THEME, true)
        window.setBackgroundDrawable(
            ColorDrawable(if (initialDark) 0xFF15171D.toInt() else 0xFFF3F4F7.toInt()),
        )
        val container = (application as EdgeMindApplication).container
        container.onAppForeground()
        setContent {
            EdgeMindRoot(container = container)
        }
    }
}

private const val PREFS_NAME = "edgemind_ui"
private const val KEY_DARK_THEME = "dark_theme"
private const val KEY_PROFILE_NAME = "profile_name"

/**
 * App-level appearance state, persisted across restarts.
 */
class ThemeState(
    initialDark: Boolean,
    private val onToggle: (ThemeState) -> Unit,
) {
    var darkTheme by mutableStateOf(initialDark)
        private set

    fun toggle() {
        darkTheme = !darkTheme
        onToggle(this)
    }
}

/**
 * Local-only user profile. No login, no network — the name personalizes the
 * greeting and lives only in app preferences.
 */
class ProfileState(
    initialName: String,
    private val onNameChange: (String) -> Unit,
) {
    var name by mutableStateOf(initialName)
        private set

    fun updateName(value: String) {
        name = value
        onNameChange(value)
    }
}

@Composable
private fun EdgeMindRoot(container: com.example.EdgeMemo.di.AppContainer) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val themeState = remember {
        ThemeState(
            initialDark = prefs.getBoolean(KEY_DARK_THEME, true),
            onToggle = { prefs.edit().putBoolean(KEY_DARK_THEME, it.darkTheme).apply() },
        )
    }
    val profileState = remember {
        ProfileState(
            initialName = prefs.getString(KEY_PROFILE_NAME, "") ?: "",
            onNameChange = { prefs.edit().putString(KEY_PROFILE_NAME, it).apply() },
        )
    }

    // Keep system-bar icon contrast in sync with the IN-APP theme toggle
    // (default edge-to-edge styles follow the system dark mode, which can
    // disagree with the user's chosen EdgeMind appearance).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !themeState.darkTheme
                isAppearanceLightNavigationBars = !themeState.darkTheme
            }
        }
    }

    MyApplicationTheme(darkTheme = themeState.darkTheme) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(darkTheme = themeState.darkTheme)
            EdgeMindShell(
                container = container,
                darkTheme = themeState.darkTheme,
                onToggleTheme = themeState::toggle,
                profileName = profileState.name,
                onProfileNameChange = profileState::updateName,
            )
        }
    }
}
