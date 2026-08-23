package com.piercingxx.xxnote.ui.labels

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * WS8 labels surface (design §12 item 3): every label in use with its note
 * count, inline create, inline rename and delete — rename/delete propagate
 * across ALL live notes through [LabelsViewModel] — and each row opening its
 * filtered grid via [onOpenLabel]. Signature is final (Routes contract).
 *
 * Create assigns nothing on purpose: labels exist only in use, so a valid
 * create just validates and clears the field rather than minting an empty
 * shell row (ruling documented on [LabelsViewModel.create]).
 */
@Composable
fun LabelsScreen(onBack: () -> Unit, onOpenLabel: (String) -> Unit) {
    val vm: LabelsViewModel = viewModel()
    val state by vm.state.collectAsState()

    // D1: file wins — re-list the mirror whenever the screen resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<LabelUsage?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(onBack = onBack)

        CreateRow(
            draft = draft,
            enabled = !state.busy,
            onDraftChange = { draft = it },
            onCreate = { if (vm.create(draft)) draft = "" },
        )

        state.notice?.let { words -> NoticeLine(words) }

        if (!state.loading && state.labels.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.labels, key = { it.name.lowercase() }) { usage ->
                    LabelRow(
                        usage = usage,
                        busy = state.busy,
                        isRenaming = renaming == usage.name,
                        renameDraft = renameDraft,
                        onRenameDraftChange = { renameDraft = it },
                        onStartRename = {
                            renaming = usage.name
                            renameDraft = usage.name
                        },
                        onCancelRename = { renaming = null },
                        onCommitRename = {
                            vm.rename(usage.name, renameDraft)
                            renaming = null
                        },
                        onAskDelete = { confirmDelete = usage },
                        onOpen = {
                            renaming = null
                            // H2: the name interpolates into "label/{name}" raw — a
                            // `/`, `?`, `#`, `%`, or space would corrupt (or crash)
                            // navigation. Encode HERE at the call site; MainActivity's
                            // nav-arg decode reverses it exactly once.
                            onOpenLabel(android.net.Uri.encode(usage.name))
                        },
                    )
                    HorizontalDivider(color = Tokens.White10, thickness = 1.dp)
                }
            }
        }
    }

    confirmDelete?.let { usage ->
        DeleteConfirmDialog(
            usage = usage,
            onConfirm = {
                vm.delete(usage.name)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
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
            "Labels",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
        )
    }
}

// ---- Create -----------------------------------------------------------------

/**
 * Create field + ADD. The ADD control is the §12.1 strong-emphasis moment:
 * white block, ink text. Done on the IME commits too.
 */
@Composable
private fun CreateRow(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .background(Tokens.Ink, RoundedCornerShape(10.dp))
                .border(1.dp, Tokens.White25, RoundedCornerShape(10.dp)),
        ) {
            if (draft.isEmpty()) {
                Text(
                    "New label…",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 14.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, color = Tokens.White50),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, color = Tokens.White90),
                cursorBrush = SolidColor(Tokens.Signal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate() }),
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled && draft.isNotBlank(), onClick = onCreate)
                .background(Tokens.Signal)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(
                "ADD",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.Ink,
                ),
            )
        }
    }
}

// ---- Rows -------------------------------------------------------------------

@Composable
private fun LabelRow(
    usage: LabelUsage,
    busy: Boolean,
    isRenaming: Boolean,
    renameDraft: String,
    onRenameDraftChange: (String) -> Unit,
    onStartRename: () -> Unit,
    onCancelRename: () -> Unit,
    onCommitRename: () -> Unit,
    onAskDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    if (isRenaming) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(Tokens.Ink, RoundedCornerShape(10.dp))
                    .border(1.dp, Tokens.White25, RoundedCornerShape(10.dp)),
            ) {
                BasicTextField(
                    value = renameDraft,
                    onValueChange = onRenameDraftChange,
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, color = Tokens.White90),
                    cursorBrush = SolidColor(Tokens.Signal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitRename() }),
                    modifier = Modifier
                        .matchParentSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCancelRename)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            ) {
                Text(
                    "CANCEL",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.White50),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = renameDraft.isNotBlank(), onClick = onCommitRename)
                    .background(Tokens.Signal)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    "SAVE",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Ink),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                usage.name,
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White90),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Tabular figures rule (§12.1): digits in monospaced face.
            Text(
                usage.count.toString(),
                modifier = Modifier.padding(start = 10.dp),
                style = TextStyle(fontFamily = SpaceMono, fontSize = 13.sp, color = Tokens.White50),
            )
            ActionText(
                glyph = "REN",
                description = "Rename '${usage.name}'",
                enabled = !busy,
                onClick = onStartRename,
            )
            ActionText(
                glyph = "DEL",
                description = "Delete '${usage.name}'",
                enabled = !busy,
                onClick = onAskDelete,
            )
        }
    }
}

/** Compact text control matching the editor bottom-bar glyph idiom. */
@Composable
private fun ActionText(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
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

// ---- Delete confirmation ------------------------------------------------------

/** §12 item 3: delete confirms with the untag count before propagating. */
@Composable
private fun DeleteConfirmDialog(
    usage: LabelUsage,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noun = if (usage.count == 1) "note" else "notes"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Graphite,
        title = {
            Text("Remove label?", style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90))
        },
        text = {
            Text(
                "Remove '${usage.name}' from ${usage.count} $noun? The notes themselves stay.",
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White80),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("REMOVE", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White90))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White50))
            }
        },
    )
}

// ---- Shared chrome ------------------------------------------------------------

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
        Text("No labels yet.", style = TextStyle(fontFamily = SpaceMono, fontSize = 24.sp, color = Tokens.White90))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Tag a note from its editor.",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
        )
    }
}
