package com.piercingxx.xxnote.ui.grid

/**
 * Pure capture-bar tap semantics (R1, M6 ruling): a first tap on the
 * unfocused field FOCUSES it so drafting is possible; a tap on an already
 * focused field commits only when there is drafted text — empty notes are
 * never minted by stray taps. The IME Done action and the checklist glyph
 * are deliberate intents and always commit, blank or not (their callers do
 * not consult this predicate). Unit-tested in CaptureCommitTest.
 */
enum class CaptureTap {
    /** Focus the field and raise the keyboard; nothing is created. */
    FOCUS,

    /** Commit the draft as a plain note. */
    COMMIT,

    /** Already focused with a blank draft: keep native caret behaviour. */
    NOTHING,
}

fun captureTapOutcome(fieldFocused: Boolean, draftIsBlank: Boolean): CaptureTap = when {
    !fieldFocused -> CaptureTap.FOCUS
    draftIsBlank -> CaptureTap.NOTHING
    else -> CaptureTap.COMMIT
}
