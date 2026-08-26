package com.piercingxx.xxnote.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.ui.grid.NoteTone
import com.piercingxx.xxnote.ui.grid.canonicalColorFor
import com.piercingxx.xxnote.ui.grid.hairlineColor
import com.piercingxx.xxnote.ui.grid.isFullOutline
import com.piercingxx.xxnote.ui.grid.surfaceColor
import com.piercingxx.xxnote.ui.grid.toneForColor
import com.piercingxx.xxnote.ui.labels.LabelOps
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens
import java.io.File
import kotlin.math.roundToInt

/**
 * WS7 full-screen editor (design §12 item 2): Space Mono 28sp title bound to
 * frontmatter `title`, JetBrains Mono 16sp body filling the rest, tappable
 * GFM checkboxes, debounced 800 ms save, colour-tone picker in the bottom
 * bar. [noteId] always names an existing note — capture created it before
 * navigating; an unknown id closes immediately (defensive). Signature is
 * final.
 *
 * **Checkbox tap implementation (chosen approach): clickable overlay boxes**
 * aligned through the body field's [TextLayoutResult] glyph metrics, drawn as
 * siblings of the field inside one shared padded Box so they scroll together
 * and share a coordinate space. Each overlay covers the `- [ ] ` prefix of
 * exactly one task line; a tap calls [ChecklistToggle.at] (pure), which swaps
 * ONE character (` ` ↔ `x`) in the underlying text — same-length, so the
 * selection and every other offset survive untouched by construction.
 *
 * Tradeoff vs. intercepting taps on the field itself: overlays leave the
 * field's native gesture handling (caret, selection, IME) completely alone —
 * a pointerInput tap-interceptor would race BasicTextField's internal
 * detectors for consumed events. Cost: hit rects recompute on text/layout
 * change, and the checkbox glyphs themselves are caret-inaccessible
 * (acceptable — they are controls, not prose).
 *
 * Retitle updates frontmatter `title:` ONLY. Filename/slug regeneration and
 * the remote MOVE rename are DEFERRED (L3 follow-up); §9 makes filenames
 * cosmetic — identity is the `id`, so correctness never rides the name.
 *
 * Deferred beyond this workstream, documented: general inline-markdown
 * styling (headings/emphasis/links/code per §4.3). When it lands it must be
 * length-preserving so the toggle-offset math above stays valid.
 */
@Composable
fun EditorScreen(noteId: String, onClose: () -> Unit) {
    val vm: EditorViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(noteId) { vm.load(noteId) }
    LaunchedEffect(state.missing) { if (state.missing) onClose() }

    // Hardening #2 + §15 resync: the editor is the one screen where losing
    // state actually costs writing, so unlike the list screens (whose
    // observers only refresh), its lifecycle observer acts: ON_STOP —
    // backgrounding, Recents swipe-away, incoming call, the preamble to an
    // OOM kill — cancels the pending 800 ms debounce and persists immediately
    // (a no-op when nothing is dirty), and ON_RESUME re-reads the mirror so a
    // background sync pull that landed while the screen was away is adopted
    // or merged instead of being overwritten by the next keystroke.
    // onCleared stays as the ViewModel-level second line of defence.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.flushPendingSave()
                Lifecycle.Event.ON_RESUME -> vm.resyncFromDisk()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showColorSheet by remember { mutableStateOf(false) }
    var showLabelSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // WS10 gallery insert + P2.12 camera capture. The system photo picker
    // needs NO permission; CAMERA is requested ONLY when the user taps CAM —
    // §13's first-capture timing — and a refusal costs nothing: one line of
    // plain words, gallery still working, no repeated nagging. Either way the
    // bytes ride the editor's single save pipeline (see
    // [EditorViewModel.insertImage] / [EditorViewModel.insertCapturedPhoto]).
    // Title/body field state lives HERE (not inside the ready branch) so the
    // picker callback can read the cursor at launch time.
    val bodyFocus = remember { FocusRequester() }
    var titleValue by remember { mutableStateOf(TextFieldValue("")) }
    var bodyValue by remember { mutableStateOf(TextFieldValue("")) }

    val context = LocalContext.current

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            vm.insertImage(uri, bodyValue.selection.min)
        }
    }

    // P2.12: TakePicture writes JPEG bytes into a FileProvider-owned cache
    // file we hand the camera app by content URI (grantUriPermissions-scoped,
    // app-private otherwise). On success those bytes enter
    // [EditorViewModel.insertCapturedPhoto] — the same pipeline as the picker.
    // rememberSaveable, not remember: the TakePicture result is redelivered
    // after process death / recreation, and a plain remember would have
    // dropped the captured photo on the floor with no words (Uri is
    // Parcelable — the default saver carries it).
    var pendingCaptureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val target = pendingCaptureUri
        pendingCaptureUri = null
        if (captured && target != null) {
            vm.insertCapturedPhoto(target, bodyValue.selection.min)
        } else if (target != null) {
            vm.discardCaptureUri(target)
        }
    }
    val launchCapture = {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture-${Ulid.generate()}.jpg")
        val target = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCaptureUri = target
        takePicture.launch(target)
        Unit
    }
    val requestCameraThenCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCapture() else vm.onCameraPermissionDenied()
    }
    state.insertion?.let { insertion ->
        LaunchedEffect(insertion) {
            val at = insertion.offset.coerceIn(0, bodyValue.text.length)
            bodyValue = bodyValue.copy(
                text = bodyValue.text.substring(0, at) +
                    insertion.snippet +
                    bodyValue.text.substring(at),
                selection = TextRange(at + insertion.snippet.length),
            )
            vm.consumeInsertion()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ink)
            .statusBarsPadding()
            .imePadding(),
    ) {
        if (!state.ready) {
            // Bare ink while loading — never a spinner (R1's spirit applies here too).
        } else {
            // Field seeding keys on [UiState.generation], never `Unit`: the
            // generation bumps only when the view model publishes new
            // authoritative text (initial load, §15 adopt/merge), so ordinary
            // recompositions never re-copy over typed bytes. A recreation
            // (rotation / theme change — composition reborn, view model
            // surviving) re-runs this fresh and seeds from the CURRENT buffer,
            // not first-load text; BasicTextField's programmatic set fires no
            // onValueChange, which is exactly why the key must be right.
            LaunchedEffect(state.generation) {
                titleValue = TextFieldValue(state.initialTitle, TextRange(state.initialTitle.length))
                bodyValue = TextFieldValue(state.initialBody, TextRange(state.initialBody.length))
                runCatching { bodyFocus.requestFocus() } // keyboard up before transition ends
            }

            BasicTextField(
                value = titleValue,
                onValueChange = {
                    titleValue = it
                    vm.onTitleChange(it.text)
                },
                textStyle = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                    color = Tokens.White90,
                ),
                cursorBrush = SolidColor(Tokens.Signal),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )

            HorizontalDivider(color = Tokens.White10, thickness = 1.dp)

            // L10/§15: a failed vault write speaks here, in one line, until
            // the next successful save clears it. Nothing is marked saved
            // that was not.
            state.saveError?.let { words ->
                Text(
                    words,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.InkRaised)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.Warn),
                )
            }

            // P2.12/§13: a refused camera prompt speaks once, in one line,
            // and changes nothing else — the editor stays fully usable.
            state.cameraWords?.let { words ->
                Text(
                    words,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.InkRaised)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.Warn),
                )
            }

            BodyField(
                bodyValue = bodyValue,
                onBodyChange = {
                    bodyValue = it
                    vm.onBodyChange(it.text)
                },
                focusRequester = bodyFocus,
                onToggleAt = { offset ->
                    vm.toggleCheckboxAt(offset)?.let { toggled ->
                        bodyValue = bodyValue.copy(text = toggled)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            EditorBottomBar(
                onLabelClick = { showLabelSheet = true },
                onColorClick = { showColorSheet = true },
                archived = state.archived,
                onArchiveClick = { vm.setArchived(!state.archived) }, // M6: real verb via the save pipeline
                onAttachClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCaptureClick = {
                    // §13: the CAMERA prompt fires here and only here — at
                    // the user's first capture attempt, never at launch. A
                    // fresh attempt clears last time's refusal words.
                    vm.clearCameraWords()
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) launchCapture() else requestCameraThenCapture.launch(Manifest.permission.CAMERA)
                },
                onDeleteRequest = { confirmDelete = true },
            )
        }
    }

    if (showColorSheet && state.ready) {
        ColorToneSheet(
            selected = toneForColor(state.colorName),
            onPick = { tone ->
                showColorSheet = false
                vm.setColor(canonicalColorFor(tone))
            },
            onDismiss = { showColorSheet = false },
        )
    }

    if (showLabelSheet && state.ready) {
        LabelsSheet(
            selected = state.labels,
            known = state.knownLabels
                .plus(state.labels)
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() },
            onToggle = vm::toggleLabel,
            onCreate = vm::addLabel,
            onDismiss = { showLabelSheet = false },
        )
    }

    if (confirmDelete) {
        // M6: overflow Delete confirms before the D9 trash move — reversible
        // for the retention window, so plain words suffice.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Tokens.Graphite,
            title = {
                Text(
                    "Move to trash?",
                    style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
                )
            },
            text = {
                Text(
                    "The note moves to Trash and rests there for 7 days — restore it any time.",
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White80),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteNote(onDeleted = onClose)
                }) {
                    Text("DELETE", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.Warn))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("CANCEL", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.White50))
                }
            },
        )
    }
}

// ---- Body -------------------------------------------------------------------

/**
 * Scrollable body editor plus checkbox overlays. The inner Box carries the
 * padding, so the field's laid-out-text origin and the overlay coordinate
 * space ([TextLayoutResult] values) coincide exactly.
 */
@Composable
private fun BodyField(
    bodyValue: TextFieldValue,
    onBodyChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onToggleAt: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current

    Box(modifier = modifier.verticalScroll(rememberScrollState())) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            BasicTextField(
                value = bodyValue,
                onValueChange = onBodyChange,
                textStyle = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = Tokens.White90,
                ),
                cursorBrush = SolidColor(Tokens.Signal),
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            layout?.let { tl ->
                checkboxHits(bodyValue.text, tl).forEach { hit ->
                    val widthDp = with(density) { hit.width.toDp() }
                    val heightDp = with(density) { hit.height.toDp() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(hit.left.roundToInt(), hit.top.roundToInt()) }
                            .size(width = widthDp, height = heightDp)
                            .pointerInput(hit.toggleOffset) {
                                detectTapGestures { _ -> onToggleAt(hit.toggleOffset) }
                            },
                    )
                }
            }
        }
    }
}

private data class CheckboxHit(
    val toggleOffset: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Hit rects for every GFM task-item line: from two glyph-widths before the
 * `[` (covering the bullet marker) through three after it (covering the box
 * and its trailing space), full visual-line height. Monospace faces make the
 * column math exact; soft-wrapped long lines resolve through
 * [TextLayoutResult.getLineForOffset], landing the box on the line's FIRST
 * visual segment — where its glyphs are.
 */
private fun checkboxHits(text: String, layout: TextLayoutResult): List<CheckboxHit> {
    if (text.isEmpty()) return emptyList()
    val hits = ArrayList<CheckboxHit>()
    var lineStart = 0
    for (line in text.split('\n')) {
        val match = ChecklistToggle.TASK_LINE.matchEntire(line)
        if (match != null) {
            val boxOffset = lineStart + match.groupValues[1].length // the ' ' or 'x'
            val bracketOffset = boxOffset - 1                        // the '['
            if (bracketOffset >= 0 && bracketOffset < text.length) {
                val bracket = layout.getBoundingBox(bracketOffset)
                if (bracket.width > 0f && bracket.height > 0f) {
                    val visualLine = layout.getLineForOffset(boxOffset)
                    val charWidth = bracket.width
                    val left = maxOf(bracket.left - 2f * charWidth, layout.getLineLeft(visualLine))
                    val right = bracket.right + 3f * charWidth
                    hits.add(
                        CheckboxHit(
                            toggleOffset = boxOffset,
                            left = left,
                            top = layout.getLineTop(visualLine),
                            width = right - left,
                            height = layout.getLineBottom(visualLine) - layout.getLineTop(visualLine),
                        ),
                    )
                }
            }
        }
        lineStart += line.length + 1
    }
    return hits
}

// ---- Bottom bar ----------------------------------------------------------------

/**
 * Label / colour / archive / attach / capture / overflow. COLOUR, LABEL,
 * ARCHIVE and — since WS10 — ATT all work through the editor's single save
 * pipeline; DELETE lives in the overflow menu behind a confirm dialog. ATT
 * opens the system photo picker (gallery insert; no permission needed). CAM
 * (P2.12) captures through TakePicture into the same pipeline: §13 times its
 * CAMERA prompt to this tap, and a refusal leaves one line of words with the
 * rest of the editor untouched.
 */
@Composable
private fun EditorBottomBar(
    onLabelClick: () -> Unit,
    onColorClick: () -> Unit,
    archived: Boolean,
    onArchiveClick: () -> Unit,
    onAttachClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.InkRaised)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLabelClick) {
            Text(
                "LBL",
                modifier = Modifier.semantics { contentDescription = "Labels" },
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White90,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        ToolSwatch(onClick = onColorClick)
        IconButton(onClick = onArchiveClick) {
            Text(
                "ARC",
                modifier = Modifier.semantics {
                    contentDescription = if (archived) "Unarchive" else "Archive"
                },
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = if (archived) Tokens.Signal else Tokens.White90,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        IconButton(onClick = onAttachClick) {
            Text(
                "ATT",
                modifier = Modifier.semantics { contentDescription = "Insert image" },
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White90,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        IconButton(onClick = onCaptureClick) {
            Text(
                "CAM",
                modifier = Modifier.semantics { contentDescription = "Take photo" },
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White90,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { overflowOpen = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "More",
                tint = Tokens.White90,
            )
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Delete",
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, color = Tokens.White90),
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onDeleteRequest()
                    },
                )
            }
        }
    }
}

/** The working colour control: six tone dots; selected dot enlarges. */
@Composable
private fun ToolSwatch(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteTone.entries.forEach { tone ->
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tone.surfaceColor())
                        .then(
                            when {
                                tone.isFullOutline() -> Modifier.border(1.dp, Tokens.White25, RoundedCornerShape(2.dp))
                                else -> Modifier
                            },
                        ),
                )
            }
        }
    }
}

// ---- Colour sheet ---------------------------------------------------------------

/**
 * D12/O2 picker: the six tones only, labelled by their canonical Keep names
 * (what lands in `color:`). Selecting writes immediately via
 * [EditorViewModel.setColor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorToneSheet(
    selected: NoteTone,
    onPick: (NoteTone) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.InkRaised) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                "COLOUR",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White50,
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))
            NoteTone.entries.forEach { tone ->
                val isSelected = tone == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tone) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TonePreviewCard(tone, isSelected = isSelected)
                    Text(
                        canonicalColorFor(tone),
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White90),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isSelected) {
                        Text("selected", style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Signal))
                    }
                }
            }
        }
    }
}

/** Mini card previewing exactly how the tone renders in the grid. */
@Composable
private fun TonePreviewCard(tone: NoteTone, isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(tone.surfaceColor())
            .border(1.dp, if (isSelected) Tokens.Signal else Tokens.White25, RoundedCornerShape(3.dp)),
    ) {
        when {
            tone == NoteTone.HAIRLINE_LEFT -> Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(Tokens.White10),
            )
            tone == NoteTone.HAIRLINE_FULL -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(3.dp)
                    .border(1.dp, Tokens.White10, RoundedCornerShape(2.dp)),
            )
            else -> Unit
        }
    }
}

// ---- Label sheet (WS8) -----------------------------------------------------------

/**
 * WS8 label sheet: every label in use across the vault as checkboxes that
 * reflect THIS note's labels, plus a create-new field. Toggles and adds fold
 * into the editor's single debounced save via [EditorViewModel.toggleLabel] /
 * [EditorViewModel.addLabel]; the checkboxes mirror saved state as it lands.
 * Invalid create names surface in plain words inline (§15 tone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsSheet(
    selected: List<String>,
    known: List<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.InkRaised) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LABELS",
                    style = TextStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        letterSpacing = 0.08.em,
                        color = Tokens.White50,
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "DONE",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.Signal),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                known.forEach { name ->
                    val checked = selected.any { it.equals(name, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(name) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(name) },
                        )
                        Text(
                            name,
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White90),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (known.isEmpty()) {
                    Text(
                        "No labels yet — make the first one below.",
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Tokens.White10, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Tokens.Ink, RoundedCornerShape(10.dp))
                        .border(1.dp, Tokens.White25, RoundedCornerShape(10.dp)),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "New label…",
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 14.dp),
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            invalid = null
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White90),
                        cursorBrush = SolidColor(Tokens.Signal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            commitLabel(draft, onInvalid = { invalid = it }, onCommit = { name ->
                                onCreate(name)
                                draft = ""
                            })
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                }
                Text(
                    "ADD",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = draft.isNotBlank()) {
                            commitLabel(draft, onInvalid = { invalid = it }, onCommit = { name ->
                                onCreate(name)
                                draft = ""
                            })
                        }
                        .background(Tokens.Signal)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = Tokens.Ink),
                )
            }
            invalid?.let { words ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(words, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Warn))
            }
        }
    }
}

private fun commitLabel(raw: String, onInvalid: (String) -> Unit, onCommit: (String) -> Unit) {
    val name = try {
        LabelOps.normalize(raw)
    } catch (e: IllegalArgumentException) {
        onInvalid(e.message ?: "label can't be used")
        return
    }
    onCommit(name)
}
