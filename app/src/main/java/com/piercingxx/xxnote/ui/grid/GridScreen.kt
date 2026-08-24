package com.piercingxx.xxnote.ui.grid

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.SpaceMono
import com.piercingxx.xxnote.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * WS7/WS8 home surface (design §12 item 1): staggered two-column card grid
 * with PINNED / OTHERS sections, top bar (drawer · wordmark · search · sync),
 * the persistent bottom capture bar, a navigation drawer
 * (Notes/Archive/Trash/Labels/Sync), live FTS search over the local cache
 * (R4), and long-press multi-select with a batch action bar.
 *
 * Capture CREATES the note first, then [onOpenNote] navigates — R1, no
 * spinner anywhere in that path. Signature is final (Routes contract).
 */
@Composable
fun GridScreen(
    onOpenNote: (String) -> Unit,
    onOpenSync: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenLabels: () -> Unit,
) {
    val vm: GridViewModel = viewModel()
    val state by vm.state.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selecting = state.selection.active

    // Colour picker sheet for the selection action bar (six canonical tones).
    var pickerOpen by remember { mutableStateOf(false) }

    // D1: file wins — re-list the mirror every time the screen resumes
    // (return from editor/sync must reflect what landed meanwhile).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = drawerState.isOpen || selecting || state.field.open) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            selecting -> vm.exitSelection()
            else -> vm.collapseSearch()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !selecting && !state.field.open,
        drawerContent = {
            DrawerPanel(
                onNotes = { scope.launch { drawerState.close() } },
                onArchive = {
                    scope.launch { drawerState.close() }
                    onOpenArchive()
                },
                onTrash = {
                    scope.launch { drawerState.close() }
                    onOpenTrash()
                },
                onLabels = {
                    scope.launch { drawerState.close() }
                    onOpenLabels()
                },
                onSync = {
                    scope.launch { drawerState.close() }
                    onOpenSync()
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Tokens.Ink)
                .imePadding(),
        ) {
            if (selecting) {
                SelectionBar(
                    count = state.selection.ids.size,
                    pinTarget = vm.pinTargetForSelection(),
                    applying = state.applyingBatch,
                    onExit = vm::exitSelection,
                    onPin = { vm.applyBatch(BatchAction.Pin(vm.pinTargetForSelection())) },
                    onArchive = { vm.applyBatch(BatchAction.Archive()) },
                    onDelete = { vm.applyBatch(BatchAction.Trash) },
                    onPickColour = { pickerOpen = true },
                )
            } else {
                TopBar(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSync = onOpenSync,
                    field = state.field,
                    onExpandSearch = vm::expandSearch,
                    onCollapseSearch = vm::collapseSearch,
                    onQueryChange = vm::onQueryChange,
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.field.isActive &&
                        !state.searching &&
                        state.results.isEmpty() ->
                        NoResultsState(
                            query = state.field.raw,
                            modifier = Modifier.matchParentSize(),
                        )

                    state.field.isActive || state.searching ->
                        ResultsGrid(
                            cards = state.results,
                            selectedIds = state.selection.ids,
                            selecting = selecting,
                            onOpenNote = onOpenNote,
                            onSelectTap = vm::toggleSelect,
                            onSelectLongPress = vm::onCardLongPress,
                        )

                    !state.loading && state.pinned.isEmpty() && state.others.isEmpty() ->
                        EmptyState(modifier = Modifier.matchParentSize())

                    else ->
                        NoteGrid(
                            pinned = state.pinned,
                            others = state.others,
                            selecting = selecting,
                            selectedIds = state.selection.ids,
                            onOpenNote = onOpenNote,
                            onSelectTap = vm::toggleSelect,
                            onSelectLongPress = vm::onCardLongPress,
                        )
                }
            }

            val batchWords = state.batchNotice
            if (batchWords != null) {
                BatchNotice(batchWords)
            }

            if (!selecting) {
                CaptureBar(
                    onCreate = { draftTitle, type ->
                        vm.capture(draftTitle, type, onCreated = onOpenNote)
                    },
                    capturing = state.capturing,
                    notice = state.notice,
                )
            } else {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }

    if (pickerOpen) {
        ColourSheet(
            onSelect = { tone ->
                pickerOpen = false
                vm.applyBatch(BatchAction.Color(canonicalColorFor(tone)))
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

// ---- Colour sheet -------------------------------------------------------------

/**
 * The six-tone picker sheet (§12 item 1, D12): Keep's twelve colour names as
 * six surface tones. Choosing one writes the canonical Keep name for that
 * tone through the batch fold — the file gets a real `color:` value other
 * tools round-trip, never a tone enum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColourSheet(
    onSelect: (NoteTone) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Tokens.InkRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "NOTE COLOUR",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White50,
                ),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                NoteTone.entries.forEach { tone ->
                    ColourSwatch(tone = tone, onClick = { onSelect(tone) })
                }
            }
        }
    }
}

@Composable
private fun ColourSwatch(tone: NoteTone, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tone.surfaceColor())
                .border(1.dp, Tokens.White25, RoundedCornerShape(8.dp)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            canonicalColorFor(tone),
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.White50),
        )
    }
}

// ---- Top bar ----------------------------------------------------------------

@Composable
private fun TopBar(
    onOpenDrawer: () -> Unit,
    onOpenSync: () -> Unit,
    field: SearchFieldState,
    onExpandSearch: () -> Unit,
    onCollapseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = "Menu",
                tint = Tokens.White90,
            )
        }
        if (field.open) {
            SearchField(
                raw = field.raw,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCollapseSearch) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close search",
                    tint = Tokens.White90,
                )
            }
        } else {
            Text(
                "XX-Note",
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
            )
            IconButton(onClick = onExpandSearch) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = Tokens.White90,
                )
            }
            IconButton(onClick = onOpenSync) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Sync",
                    tint = Tokens.White90,
                )
            }
        }
    }
}

/**
 * The live search input (WS8). Expanding grabs focus immediately — the
 * keyboard is up before the transition finishes, Keep-style. Query text flows
 * verbatim to [com.piercingxx.xxnote.ui.grid.GridViewModel.onQueryChange],
 * which sanitizes + debounces before the local-cache-only FTS query (R4).
 */
@Composable
private fun SearchField(
    raw: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = modifier
            .height(44.dp)
            .background(Tokens.InkRaised, RoundedCornerShape(10.dp))
            .border(1.dp, Tokens.White25, RoundedCornerShape(10.dp)),
    ) {
        if (raw.isEmpty()) {
            Text(
                "Search",
                modifier = Modifier.align(Alignment.CenterStart),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
            )
        }
        BasicTextField(
            value = raw,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, color = Tokens.White90),
            cursorBrush = SolidColor(Tokens.Signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions.Default,
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focusRequester)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

// ---- Multi-select action bar ---------------------------------------------------

/**
 * The transformed top bar during selection (§12 item 1): count + actions.
 * The count renders in pure Signal white — a reserved moment (§12.1), same
 * treatment as the current drawer item. All buttons disable while a batch is
 * being written ([applying]); no spinner, the bar simply goes quiet.
 */
@Composable
private fun SelectionBar(
    count: Int,
    pinTarget: Boolean,
    applying: Boolean,
    onExit: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onPickColour: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit, enabled = !applying) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Exit selection",
                tint = Tokens.White90,
            )
        }
        Text(
            "$count selected",
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.Signal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onPin, enabled = !applying) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = if (pinTarget) "Pin" else "Unpin",
                tint = Tokens.White90,
            )
        }
        IconButton(onClick = onArchive, enabled = !applying) {
            ArchiveGlyph()
        }
        IconButton(onClick = onPickColour, enabled = !applying) {
            ToneSwatchGlyph()
        }
        IconButton(onClick = onDelete, enabled = !applying) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = Tokens.White90,
            )
        }
    }
}

/**
 * Archive glyph drawn from primitives — material-icons-core ships no archive
 * mark and extended icons are a forbidden dependency (same reasoning as
 * [ChecklistGlyph] below).
 */
@Composable
private fun ArchiveGlyph() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5.dp.toPx())
        val color = Tokens.White90
        drawLine(
            color = color,
            start = Offset(w * 0.12f, h * 0.22f),
            end = Offset(w * 0.88f, h * 0.22f),
            strokeWidth = stroke.width,
        )
        drawRect(
            color = color,
            topLeft = Offset(w * 0.2f, h * 0.36f),
            size = Size(w * 0.6f, h * 0.46f),
            style = stroke,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.08f),
            end = Offset(w * 0.5f, h * 0.52f),
            strokeWidth = stroke.width,
        )
    }
}

/** Colour affordance: a 3×2 mosaic of the six surface tones (D12), monochrome-legal. */
@Composable
private fun ToneSwatchGlyph() {
    val tones = NoteTone.entries
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tones.chunked(3).forEach { rowTones ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rowTones.forEach { tone ->
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(tone.surfaceColor())
                            .border(0.5.dp, Tokens.White50),
                    )
                }
            }
        }
    }
}

// ---- Drawer -----------------------------------------------------------------

/**
 * Navigation drawer (design §12 item 3, WS8 slice): Notes · Archive · Trash ·
 * Labels · Sync. Entries ride JetBrains Mono; the CURRENT item (Notes, this
 * screen) gets the reserved-moment treatment — Signal-white text plus a
 * Signal left bar (§12.1: pure white is the accent's alone).
 */
@Composable
private fun DrawerPanel(
    onNotes: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onLabels: () -> Unit,
    onSync: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = Tokens.Ink) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(modifier = Modifier.height(32.dp))
            // Wordmark block: PIERCINGXX eyebrow over XX-NOTE display face.
            Text(
                "PIERCINGXX",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White50,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "XX-NOTE",
                style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, color = Tokens.White90),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Tokens.White10),
            )
            Spacer(modifier = Modifier.height(8.dp))
            DrawerRow("NOTES", current = true, onClick = onNotes)
            DrawerRow("ARCHIVE", current = false, onClick = onArchive)
            DrawerRow("TRASH", current = false, onClick = onTrash)
            DrawerRow("LABELS", current = false, onClick = onLabels)
            DrawerRow("SYNC", current = false, onClick = onSync)
        }
    }
}

@Composable
private fun DrawerRow(label: String, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(if (current) 2.dp else 0.dp)
                .height(20.dp)
                .background(Tokens.Signal),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            label,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 15.sp,
                letterSpacing = 0.04.em,
                color = if (current) Tokens.Signal else Tokens.White80,
            ),
        )
    }
}

// ---- Grid -------------------------------------------------------------------

@Composable
private fun NoteGrid(
    pinned: List<NoteCard>,
    others: List<NoteCard>,
    selecting: Boolean,
    selectedIds: Set<String>,
    onOpenNote: (String) -> Unit,
    onSelectTap: (String) -> Unit,
    onSelectLongPress: (String) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned", span = StaggeredGridItemSpan.FullLine) { SectionHeader("PINNED") }
            items(pinned, key = { "pinned-${it.id}" }) { card ->
                CardCell(
                    card = card,
                    selecting = selecting,
                    selected = card.id in selectedIds,
                    onOpenNote = onOpenNote,
                    onSelectTap = onSelectTap,
                    onSelectLongPress = onSelectLongPress,
                )
            }
        }
        if (others.isNotEmpty()) {
            if (pinned.isNotEmpty()) {
                item(key = "header-others", span = StaggeredGridItemSpan.FullLine) { SectionHeader("OTHERS") }
            }
            items(others, key = { it.id }) { card ->
                CardCell(
                    card = card,
                    selecting = selecting,
                    selected = card.id in selectedIds,
                    onOpenNote = onOpenNote,
                    onSelectTap = onSelectTap,
                    onSelectLongPress = onSelectLongPress,
                )
            }
        }
    }
}

/**
 * Search results (§12 item 1): ONE flat list — section headers deliberately
 * hidden, recency order straight from NoteDao.search. Long-press multi-select
 * works here exactly as on the normal grid.
 */
@Composable
private fun ResultsGrid(
    cards: List<NoteCard>,
    selecting: Boolean,
    selectedIds: Set<String>,
    onOpenNote: (String) -> Unit,
    onSelectTap: (String) -> Unit,
    onSelectLongPress: (String) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        items(cards, key = { "result-${it.id}" }) { card ->
            CardCell(
                card = card,
                selecting = selecting,
                selected = card.id in selectedIds,
                onOpenNote = onOpenNote,
                onSelectTap = onSelectTap,
                onSelectLongPress = onSelectLongPress,
            )
        }
    }
}

@Composable
private fun CardCell(
    card: NoteCard,
    selecting: Boolean,
    selected: Boolean,
    onOpenNote: (String) -> Unit,
    onSelectTap: (String) -> Unit,
    onSelectLongPress: (String) -> Unit,
) {
    NoteCardView(
        card = card,
        selected = selected,
        onClick = {
            if (selecting) onSelectTap(card.id) else onOpenNote(card.id)
        },
        onLongClick = { onSelectLongPress(card.id) },
    )
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Ink)
            .padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
        style = TextStyle(
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            letterSpacing = 0.08.em,
            color = Tokens.White50,
        ),
    )
}

/**
 * One grid card. Tone treatment per D12/O2: four surface elevations, two
 * hairline treatments (2.dp left bar / 1.dp full outline, White10).
 * Selected cards gain a White50 outline (WS8 multi-select treatment) layered
 * over the tone hairline. Tap opens — or toggles while selecting;
 * LONG-PRESS enters selection from anywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCardView(
    card: NoteCard,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .toneTreatment(card.tone)
            .then(
                if (selected) {
                    Modifier.border(1.dp, Tokens.White50, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        if (card.title.isNotEmpty()) {
            Text(
                card.title,
                style = TextStyle(fontFamily = SpaceMono, fontSize = 16.sp, color = Tokens.White90),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.preview.isNotEmpty()) {
            Spacer(modifier = Modifier.height(if (card.title.isEmpty()) 0.dp else 6.dp))
            Text(
                card.preview,
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Tokens.White80,
                ),
                maxLines = CARD_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.totalCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            // Tabular figures rule (§12.1): monospaced face, digits align.
            Text(
                "${card.doneCount} / ${card.totalCount}",
                style = TextStyle(fontFamily = SpaceMono, fontSize = 13.sp, color = Tokens.White90),
            )
        }
        if (card.labels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LabelChipRow(labels = card.labels)
        }
    }
}

/** Hairline treatments in White10 (D12/O2). Left bar = 2.dp; full outline = 1.dp. */
private fun Modifier.toneTreatment(tone: NoteTone): Modifier {
    val hairline = tone.hairlineColor() ?: return this
    return if (tone.isFullOutline()) {
        this.border(1.dp, hairline, RoundedCornerShape(2.dp))
    } else {
        this.drawBehind {
            drawRect(color = hairline, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
        }
    }
}

// ---- Empty states --------------------------------------------------------------

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Nothing yet.",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 24.sp, color = Tokens.White90),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Capture below.",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
        )
    }
}

/** Search miss (WS8): plain words, query quoted verbatim. */
@Composable
private fun NoResultsState(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "no results for '$query'",
            style = TextStyle(fontFamily = SpaceMono, fontSize = 18.sp, color = Tokens.White90),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "try fewer letters",
            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = Tokens.White50),
        )
    }
}

/** One-line batch failure notice (plain words, failed count included). */
@Composable
private fun BatchNotice(words: String) {
    Text(
        words,
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.InkRaised)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Warn),
    )
}

// ---- Capture bar ---------------------------------------------------------------

/**
 * Persistent capture bar (R1). Commit gestures (M6 ruling): the FIRST tap on
 * the unfocused field only FOCUSES it — drafting comes first; a tap on an
 * already-focused field commits only when text is drafted, so stray taps
 * never mint empty notes; IME Done and the checklist glyph are deliberate
 * intents and always commit. A draft typed into the field becomes the new
 * note's title. Creation is synchronous-at-the-vault on IO — the UI shows no
 * spinner state at all; the editor is simply the next screen. A failed write
 * speaks in [notice] words and leaves the draft standing for retry.
 */
@Composable
private fun CaptureBar(
    onCreate: (draftTitle: String, type: NoteType) -> Unit,
    capturing: Boolean,
    notice: String?,
) {
    var draft by remember { mutableStateOf("") }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.InkRaised)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptureField(
                draft = draft,
                enabled = !capturing,
                onDraftChange = { draft = it },
                onCommit = { onCreate(draft, NoteType.NOTE) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onCreate(draft, NoteType.CHECKLIST) },
                enabled = !capturing,
                modifier = Modifier.size(40.dp),
            ) {
                ChecklistGlyph()
            }
        }
        if (notice != null) {
            Text(
                notice,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Tokens.InkRaised)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, color = Tokens.Warn),
            )
        }
    }
}

/**
 * Checklist capture glyph: a bordered empty box with a check inside — drawn
 * from core primitives because material-icons-core ships no checkbox glyph
 * (extended icons would be a new dependency, forbidden).
 */
@Composable
private fun ChecklistGlyph() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(1.5.dp, Tokens.White90, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = "New checklist",
            tint = Tokens.White90,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The capture input. Tap routing follows the PURE [captureTapOutcome]:
 * unfocused or blank-draft states leave the field's native gesture handling
 * completely alone (first tap focuses and raises the IME; caret placement
 * works); once focused WITH drafted text, one quiet overlay turns a second
 * tap into a commit. Done/glyph commits bypass this composable entirely.
 */
@Composable
private fun CaptureField(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .height(44.dp)
            .background(Tokens.Ink, RoundedCornerShape(10.dp))
            .border(1.dp, Tokens.White25, RoundedCornerShape(10.dp)),
    ) {
        if (draft.isEmpty()) {
            Text(
                "Take a note…",
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
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            interactionSource = interactionSource,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        when (captureTapOutcome(fieldFocused = focused, draftIsBlank = draft.isBlank())) {
            CaptureTap.FOCUS, CaptureTap.NOTHING -> Unit // native focus/caret behaviour
            CaptureTap.COMMIT -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClick = onCommit)
                    .semantics { contentDescription = "Save note" },
            )
        }
    }
}
