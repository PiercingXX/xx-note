package com.piercingxx.xxnote.ui.sync

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * WS9 honesty surface (design §12 item 4, R10): connection state in plain
 * words, the outbox with per-note reasons, the conflict list with its
 * Resolve sheet, a Test connection that runs the real PROPFIND and prints
 * what came back, the full-length log, and this week's tallies. Top to
 * bottom, in that order — no spinner anywhere, every state explained.
 */
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val vm: SyncViewModel = viewModel()
    // Re-query on (re)entry so state never outlives reality.
    LaunchedEffect(Unit) { vm.refresh() }
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("‹ Back", style = bodyStyle(13.sp, Tokens.White80))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                Text(
                    if (state.syncing) "Syncing…" else "Sync now",
                    style = bodyStyle(13.sp, Tokens.White90),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 1. CONNECTION STATE IN WORDS.
            Text(
                state.headline,
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = if (state.tone == SyncViewModel.Tone.ATTENTION) Tokens.Warn else Tokens.White90,
                ),
            )

            // Failed disk writes speak here, in words, until the next attempt (§15).
            state.notice?.let { notice ->
                Spacer(Modifier.height(6.dp))
                Text(notice, style = bodyStyle(12.sp, Tokens.Warn))
            }

            SectionLabel("OUTBOX")
            if (state.outbox.isEmpty()) {
                EmptyLine("Nothing waiting")
            } else {
                state.outbox.forEach { row ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(row.reason, style = bodyStyle(13.sp, Tokens.White90))
                        Text(
                            "${row.noteId.takeLast(8)} · ${row.op}",
                            style = eyebrowStyle(Tokens.White50),
                        )
                    }
                }
            }

            // WS10 §10: cache usage + orphan count, and the two manual
            // actions — a sweep is NEVER automatic (a reference may live in an
            // unpulled note), eviction sheds local copies only; the remote is
            // truth either way.
            SectionLabel("ATTACHMENTS")
            Text(
                Wording.bytes(state.cacheUsageBytes),
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 16.sp,
                    fontFeatureSettings = "tnum",
                    color = Tokens.White90,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.orphanCount} unreferenced attachments",
                style = bodyStyle(13.sp, Tokens.White80),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.sweepOrphans() },
                    enabled = !state.maintainingAttachments,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tokens.White90,
                        contentColor = Tokens.Ink,
                    ),
                ) {
                    Text(
                        if (state.maintainingAttachments) "Working…" else "Sweep orphans",
                        style = bodyStyle(13.sp, Tokens.Ink),
                    )
                }
                Button(
                    onClick = { vm.evictAttachmentCache() },
                    enabled = !state.maintainingAttachments,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tokens.White90,
                        contentColor = Tokens.Ink,
                    ),
                ) {
                    Text("Evict to budget", style = bodyStyle(13.sp, Tokens.Ink))
                }
            }

            SectionLabel("CONFLICTS")
            if (state.conflicts.isEmpty()) {
                EmptyLine("No unresolved forks")
            } else {
                state.conflicts.forEach { conflict ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp),
                    ) {
                        Box(
                            Modifier
                                .padding(end = 10.dp)
                                .width(3.dp)
                                .height(28.dp)
                                .background(Tokens.Error),
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { vm.openConflict(conflict.originalId) },
                        ) {
                            Text(conflict.title, style = bodyStyle(16.sp, Tokens.White90))
                            Text("Resolve fork", style = eyebrowStyle(Tokens.White50))
                        }
                    }
                }
            }

            SectionLabel("TEST CONNECTION")
            Button(
                onClick = { vm.testConnection() },
                enabled = !state.testing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Tokens.White90,
                    contentColor = Tokens.Ink,
                ),
            ) {
                Text(if (state.testing) "Probing…" else "Test connection", style = bodyStyle(13.sp, Tokens.Ink))
            }
            state.testResult?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(result, style = bodyStyle(11.sp, Tokens.White80))
            }

            SectionLabel("SYNC LOG")
            if (state.logLines.isEmpty()) {
                EmptyLine("No sync has run yet")
            } else {
                state.logLines.forEach { line ->
                    Text(line.text, style = bodyStyle(10.sp, if (line.ok) Tokens.White50 else Tokens.Error))
                }
            }

            SectionLabel("THIS WEEK")
            Text(
                state.tallyLine,
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 16.sp,
                    fontFeatureSettings = "tnum",
                    color = Tokens.White90,
                ),
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    state.sheet?.let { pair ->
        ResolveSheet(
            pair = pair,
            onDismiss = { vm.closeSheet() },
            onAcceptSide = { side -> vm.applyResolution(pair, side, editedBody = null) },
            onApplyEdited = { text -> vm.applyResolution(pair, side = null, editedBody = text) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveSheet(
    pair: SyncViewModel.ConflictPair,
    onDismiss: () -> Unit,
    onAcceptSide: (ResolveMath.Side) -> Unit,
    onApplyEdited: (String) -> Unit,
) {
    var editing by remember(pair.forkId) { mutableStateOf(false) }
    var edited by remember(pair.forkId) { mutableStateOf(pair.forkMarkedBody) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.InkRaised) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Resolve · ${pair.originalTitle}", style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90))
            Text("accept one side, or edit the merged text", style = eyebrowStyle(Tokens.White50))

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("ORIGINAL", style = eyebrowStyle(Tokens.White50))
                    MarkedBody(pair.originalBody, highlightMarkers = false)
                }
                Column(Modifier.weight(1f)) {
                    Text("FORK (marked)", style = eyebrowStyle(Tokens.White50))
                    MarkedBody(pair.forkMarkedBody, highlightMarkers = true)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { onAcceptSide(ResolveMath.Side.MINE) }) {
                    Text("Accept mine", style = bodyStyle(13.sp, Tokens.Ink))
                }
                Button(onClick = { onAcceptSide(ResolveMath.Side.THEIRS) }) {
                    Text("Accept theirs", style = bodyStyle(13.sp, Tokens.Ink))
                }
            }

            TextButton(onClick = { editing = !editing }) {
                Text(
                    if (editing) "Hide editor" else "Edit merged text instead",
                    style = bodyStyle(13.sp, Tokens.White80),
                )
            }
            if (editing) {
                BasicTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    textStyle = bodyStyle(11.sp, Tokens.White90),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .background(Tokens.Graphite)
                        .padding(8.dp),
                )
                Button(
                    onClick = { onApplyEdited(edited) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Tokens.White90,
                        contentColor = Tokens.Ink,
                    ),
                ) {
                    Text("Apply edited text", style = bodyStyle(13.sp, Tokens.Ink))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Monospace body; diff3 marker lines highlighted when [highlightMarkers]. */
@Composable
private fun MarkedBody(text: String, highlightMarkers: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(Tokens.Graphite)
            .verticalScroll(rememberScrollState())
            .padding(6.dp),
    ) {
        text.split('\n').forEachIndexed { index, line ->
            val marker = highlightMarkers && ResolveMath.isMarkerLine(line)
            Text(
                line.ifEmpty { " " },
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = if (marker) Tokens.Error else Tokens.White80,
                    background = if (marker) Tokens.White10 else androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = eyebrowStyle(Tokens.White50),
        modifier = Modifier.padding(top = 26.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = bodyStyle(13.sp, Tokens.White50))
}

private fun eyebrowStyle(color: androidx.compose.ui.graphics.Color) = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 11.sp,
    letterSpacing = 0.08.em,
    fontWeight = FontWeight.Medium,
    color = color,
)

private fun bodyStyle(size: androidx.compose.ui.unit.TextUnit, color: androidx.compose.ui.graphics.Color) = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = size,
    color = color,
)
