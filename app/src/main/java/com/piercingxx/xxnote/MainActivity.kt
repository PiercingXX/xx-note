package com.piercingxx.xxnote

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
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
import com.piercingxx.xxnote.ui.setup.SetupLogic
import com.piercingxx.xxnote.ui.setup.SetupScreen
import com.piercingxx.xxnote.ui.share.ShareBlockedScreen
import com.piercingxx.xxnote.ui.share.ShareIngest
import com.piercingxx.xxnote.ui.share.ShareIntents
import com.piercingxx.xxnote.ui.sync.SyncScreen
import com.piercingxx.xxnote.ui.trash.TrashScreen
import com.piercingxx.xxnote.ui.theme.ThemeSync
import com.piercingxx.xxnote.ui.theme.Tokens
import com.piercingxx.xxnote.ui.theme.XxNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var onIncomingShare: ((Intent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Family theme sync: load the launcher-synced ground (if any) into
        // Tokens before the first frame, so the whole UI — the neutral
        // pre-route frame included — composes on the persisted ground.
        ThemeSync.load(applicationContext)
        // Start-route resolution no longer blocks onCreate (#8): a lifecycleScope
        // coroutine performs the credential / local-only lookup (Room suspend
        // DAOs are main-safe, so nothing pins Dispatchers here), and until it
        // lands the UI shows a neutral Ink frame. Periodic sync (#3b) is
        // scheduled only when a credential row exists; a local-only sentinel
        // opens the grid without one. Setup completion enqueues its own pass
        // on persist().
        var startOnSetup by mutableStateOf<Boolean?>(null)
        var shareOutcome by mutableStateOf<ShareIngest.Outcome?>(null)
        var pendingShare by mutableStateOf<ShareIntents.Request?>(null)
        fun applyShare(request: ShareIntents.Request?) {
            pendingShare = request
            if (request == null) return
            lifecycleScope.launch {
                shareOutcome = withContext(Dispatchers.IO) {
                    ShareIntents.ingest(applicationContext, request)
                }
            }
        }
        onIncomingShare = { incoming -> applyShare(ShareIntents.parse(incoming)) }
        lifecycleScope.launch {
            val db = XxDatabase.getInstance(applicationContext)
            val credential = db.credentialDao().get()
            val localOnly = SetupLogic.isLocalOnlySentinel(
                db.settingDao().get(SetupLogic.KEY_LOCAL_ONLY),
            )
            if (credential != null) SyncScheduler.ensurePeriodic(applicationContext)
            val parsed = ShareIntents.parse(intent)
            pendingShare = parsed
            val outcome = if (parsed != null) {
                withContext(Dispatchers.IO) { ShareIntents.ingest(applicationContext, parsed) }
            } else {
                null
            }
            shareOutcome = outcome
            startOnSetup = SetupLogic.startOnSetup(
                hasCredential = credential != null,
                localOnly = localOnly,
            )
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
                val share = shareOutcome
                when {
                    resolved == null -> Box(Modifier.fillMaxSize().background(Tokens.Ink))
                    share is ShareIngest.Outcome.NeedSetup -> ShareBlockedScreen(
                        message = ShareIngest.NEED_SETUP_WORDS,
                        actionLabel = "open Setup",
                        onAction = {
                            shareOutcome = null
                            startOnSetup = true
                        },
                        onClose = { finish() },
                    )
                    share is ShareIngest.Outcome.Refused -> ShareBlockedScreen(
                        message = share.reason,
                        onClose = { finish() },
                    )
                    else -> AppNavHost(
                        startOnSetup = resolved,
                        openNoteId = (share as? ShareIngest.Outcome.Created)?.noteId,
                        onSetupConfigured = {
                            startOnSetup = false
                            val pending = pendingShare
                            if (pending != null) applyShare(pending)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onIncomingShare?.invoke(intent)
    }
}

@Composable
private fun AppNavHost(
    startOnSetup: Boolean,
    openNoteId: String? = null,
    onSetupConfigured: () -> Unit = {},
) {
    val nav = rememberNavController()
    LaunchedEffect(openNoteId) {
        val id = openNoteId ?: return@LaunchedEffect
        nav.navigate(Routes.editor(id))
    }
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
            SyncScreen(
                onBack = { nav.popBackStack() },
                onOpenSetup = { nav.navigate(Routes.SETUP) },
            )
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
                    onSetupConfigured()
                },
            )
        }
    }
}
