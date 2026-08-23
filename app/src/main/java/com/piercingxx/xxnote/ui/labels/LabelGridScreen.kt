package com.piercingxx.xxnote.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piercingxx.xxnote.ui.grid.NoteCard
import com.piercingxx.xxnote.ui.grid.LabelChipRow
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * WS8 filtered grid for one label (design §12 item 3): the live notes carrying
 * [label] (case-insensitive, §8) as simple rows — title with first-body-line
 * fallback plus one preview line, the card's own label chips, and an unassign
 * "×" that removes THIS grid's label from that note through
 * [LabelGridViewModel.unassign]. Signature is final (Routes contract).
 *
 * The staggered-grid card composable is private to GridScreen, so this surface
 * uses rows (per contract); chips render through the shared [LabelChipRow]
 * primitive from NoteCard.kt.
 */
@Composable
fun LabelGridScreen(label: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val vm: LabelGridViewModel = viewModel()
    val state by vm.state.collectAsState()

    // Bind on entry / nav-arg change; D1 re-list on resume.
    LaunchedEffect(label) { vm.open(label) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(label = label, onBack = onBack)

        state.notice?.let { words ->
            Text(
                words,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Tokens.InkRaised)
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Warn),
            )
        }

        if (!state.loading && state.cards.isEmpty()) {
            EmptyState(label = label, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.cards, key = { it.id }) { card ->
                    NoteRow(
                        card = card,
                        label = label,
                        onOpen = { onOpenNote(card.id) },
                        onUnassign = { vm.unassign(card.id, label) },
                    )
                    HorizontalDivider(color = Tokens.White10, thickness = 1.dp)
                }
            }
        }
    }
}

// ---- Top bar ----------------------------------------------------------------

@Composable
private fun TopBar(label: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Tokens.White90,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                "LABEL",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White50,
                ),
            )
            Text(
                label,
                style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Rows -------------------------------------------------------------------

/**
 * Simple note row: title (mapper already applies the §8 first-body-line
 * fallback), one preview line, the note's label chips, and the unassign ×.
 */
@Composable
private fun NoteRow(
    card: NoteCard,
    label: String,
    onOpen: () -> Unit,
    onUnassign: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (card.title.isNotEmpty()) {
                Text(
                    card.title,
                    style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val firstPreviewLine = card.preview.lineSequence().firstOrNull().orEmpty()
            if (firstPreviewLine.isNotEmpty()) {
                Spacer(modifier = Modifier.height(if (card.title.isEmpty()) 0.dp else 5.dp))
                Text(
                    firstPreviewLine,
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White80),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.labels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LabelChipRow(labels = card.labels)
            }
        }
        IconButton(onClick = onUnassign, modifier = Modifier.size(40.dp)) {
            Text(
                "×",
                modifier = Modifier.semantics { contentDescription = "Remove '$label' from this note" },
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 18.sp, color = Tokens.White50),
            )
        }
    }
}

// ---- Empty state ------------------------------------------------------------

@Composable
private fun EmptyState(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nothing tagged.", style = TextStyle(fontFamily = SpaceMono, fontSize = 24.sp, color = Tokens.White90))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "'$label' tags no live notes.",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
        )
    }
}
