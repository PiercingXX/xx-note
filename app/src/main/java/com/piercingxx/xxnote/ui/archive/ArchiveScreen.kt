package com.piercingxx.xxnote.ui.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.piercingxx.xxnote.ui.grid.NoteTone
import com.piercingxx.xxnote.ui.grid.hairlineColor
import com.piercingxx.xxnote.ui.grid.isFullOutline
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * WS8 archive surface (design §12 item 3): archived notes are a first-class
 * verb, distinct from delete — they leave the grid but never the user's grasp.
 * Rows show title fallback + preview line + tone hairline (D12); per row:
 * unarchive (frontmatter `archived: false` through the vault) and open in the
 * editor. Empty state speaks plainly. Signature is final (Routes contract).
 */
@Composable
fun ArchiveScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val vm: ArchiveViewModel = viewModel()
    val state by vm.state.collectAsState()

    // D1: file wins — re-list the mirror whenever the screen resumes (return
    // from the editor must reflect what landed meanwhile).
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
            .navigationBarsPadding(),
    ) {
        TopBar(onBack = onBack)

        state.notice?.let { words -> NoticeLine(words) }

        if (!state.loading && state.rows.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.rows, key = { it.id }) { card ->
                    ArchiveRow(
                        card = card,
                        busy = state.busy,
                        onUnarchive = { vm.unarchive(card.id) },
                        onOpen = { onOpenNote(card.id) },
                    )
                    HorizontalDivider(color = Tokens.White10, thickness = 1.dp)
                }
            }
        }
    }
}

// ---- Top bar ----------------------------------------------------------------

@Composable
private fun TopBar(onBack: () -> Unit) {
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
        Text(
            "Archive",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
        )
    }
}

// ---- Row --------------------------------------------------------------------

@Composable
private fun ArchiveRow(
    card: NoteCard,
    busy: Boolean,
    onUnarchive: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .toneTreatment(card.tone),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                card.title,
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActionText(
                glyph = "UNARCHIVE",
                description = "Unarchive '${card.title}'",
                enabled = !busy,
                onClick = onUnarchive,
            )
        }
        if (card.preview.isNotEmpty()) {
            Text(
                card.preview,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Tokens.White80,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Compact text control matching the labels/editor glyph idiom. */
@Composable
private fun ActionText(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 7.dp, vertical = 9.dp),
    ) {
        Text(
            glyph,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                letterSpacing = 0.08.em,
                color = if (enabled) Tokens.White80 else Tokens.White25,
            ),
        )
    }
}

// ---- Tone hairline (D12/O2, mirrors the grid card treatment) -------------------

private fun Modifier.toneTreatment(tone: NoteTone): Modifier {
    val hairline = tone.hairlineColor() ?: return this
    return if (tone.isFullOutline()) {
        this.border(1.dp, hairline, RoundedCornerShape(2.dp))
    } else {
        this.drawBehind {
            drawRect(
                color = hairline,
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
            )
        }
    }
}

// ---- Shared chrome -------------------------------------------------------------

@Composable
private fun NoticeLine(words: String) {
    Text(
        words,
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.InkRaised)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Warn),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Nothing archived.",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 24.sp, color = Tokens.White90),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Archive from a note's editor.",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
        )
    }
}
