package com.piercingxx.xxnote

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.sync.SyncScheduler
import com.piercingxx.xxnote.ui.Routes
import com.piercingxx.xxnote.ui.archive.ArchiveScreen
import com.piercingxx.xxnote.ui.editor.EditorScreen
import com.piercingxx.xxnote.ui.grid.GridScreen
import com.piercingxx.xxnote.ui.labels.LabelGridScreen
import com.piercingxx.xxnote.ui.labels.LabelsScreen
import com.piercingxx.xxnote.ui.setup.SetupScreen
import com.piercingxx.xxnote.ui.sync.SyncScreen
import com.piercingxx.xxnote.ui.trash.TrashScreen
import com.piercingxx.xxnote.ui.theme.ThemeSync
import com.piercingxx.xxnote.ui.theme.Tokens
import com.piercingxx.xxnote.ui.theme.XxNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Family theme sync: load the launcher-synced ground (if any) into
        // Tokens before the first frame, so the whole UI — the neutral
        // pre-route frame included — composes on the persisted ground.
        ThemeSync.load(applicationContext)
        // Start-route resolution no longer blocks onCreate (#8): a lifecycleScope
        // coroutine performs the credential lookup (Room suspend DAOs are
        // main-safe, so nothing pins Dispatchers here), and until it lands the
        // UI shows a neutral Ink frame. The same lookup schedules periodic sync
        // (#3b) whenever a credential row exists; Setup completion enqueues its
        // own pass on persist().
        var startOnSetup by mutableStateOf<Boolean?>(null)
        lifecycleScope.launch {
            val configured =
                XxDatabase.getInstance(applicationContext).credentialDao().get() != null
            if (configured) SyncScheduler.ensurePeriodic(applicationContext)
            startOnSetup = !configured
        }
        setContent {
            // Window chrome follows the active ground: reading activeGround
            // here re-runs the effect whenever a theme broadcast lands, so
            // the decor background and system-bar contrast flip with the UI.
            // XML Theme.XxNote pins the AMOLED default for cold start.
            val ground = Tokens.activeGround
            SideEffect {
                val bg = ground.background.toInt()
                window.setBackgroundDrawable(ColorDrawable(bg))
                @Suppress("DEPRECATION")
                window.statusBarColor = bg
                @Suppress("DEPRECATION")
                window.navigationBarColor = bg
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !ground.isDark
                    isAppearanceLightNavigationBars = !ground.isDark
                }
            }
            XxNoteTheme {
                val resolved = startOnSetup
                if (resolved == null) {
                    Box(Modifier.fillMaxSize().background(Tokens.Ink))
                } else {
                    AppNavHost(startOnSetup = resolved)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(startOnSetup: Boolean) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (startOnSetup) Routes.SETUP else Routes.GRID,
    ) {
        composable(Routes.GRID) {
            GridScreen(
                onOpenNote = { id -> nav.navigate(Routes.editor(id)) },
                onOpenSync = { nav.navigate(Routes.SYNC) },
                onOpenArchive = { nav.navigate(Routes.ARCHIVE) },
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                onOpenLabels = { nav.navigate(Routes.LABELS) },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
        ) { entry ->
            EditorScreen(
                noteId = entry.arguments?.getString("noteId").orEmpty(),
                onClose = { nav.popBackStack() },
            )
        }
        composable(Routes.SYNC) {
            SyncScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ARCHIVE) {
            ArchiveScreen(onBack = { nav.popBackStack() }, onOpenNote = { id -> nav.navigate(Routes.editor(id)) })
        }
        composable(Routes.TRASH) {
            TrashScreen(onBack = { nav.popBackStack() }, onOpenNote = { id -> nav.navigate(Routes.editor(id)) })
        }
        composable(Routes.LABELS) {
            // H2: onOpenLabel hands over a Uri.encode-ed name (see LabelsScreen);
            // the nav argument below decodes it automatically — no second decode
            // here, which would corrupt labels containing a literal `%`.
            LabelsScreen(onBack = { nav.popBackStack() }, onOpenLabel = { name -> nav.navigate(Routes.label(name)) })
        }
        composable(
            route = Routes.LABEL,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            LabelGridScreen(
                label = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenNote = { id -> nav.navigate(Routes.editor(id)) },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onConfigured = {
                    nav.navigate(Routes.GRID) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
            )
        }
    }
}
