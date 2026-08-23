package com.piercingxx.xxnote.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The capture bar's tap semantics (R1, M6 ruling), proven as a pure table:
 * the first tap on an unfocused field only focuses it; a tap commits only
 * when the field is already focused AND text is drafted; blank drafts are
 * never minted by stray taps. (IME Done and the checklist glyph bypass this
 * predicate by contract — they always commit.)
 */
class CaptureCommitTest {

    @Test
    fun firstTapOnUnfocusedField_onlyFocuses_neverCommits() {
        assertEquals(CaptureTap.FOCUS, captureTapOutcome(fieldFocused = false, draftIsBlank = true))
        assertEquals(CaptureTap.FOCUS, captureTapOutcome(fieldFocused = false, draftIsBlank = false))
    }

    @Test
    fun tapOnFocusedFieldWithDraftedText_commits() {
        assertEquals(CaptureTap.COMMIT, captureTapOutcome(fieldFocused = true, draftIsBlank = false))
    }

    @Test
    fun tapOnFocusedFieldWithBlankDraft_keepsNativeCaretBehaviour() {
        assertEquals(CaptureTap.NOTHING, captureTapOutcome(fieldFocused = true, draftIsBlank = true))
    }

    @Test
    fun whitespaceOnlyDraft_isStillBlank_emptyNotesNeverMintedByTaps() {
        // The screen passes draft.isBlank(); a spaces-only draft must land on
        // NOTHING, not COMMIT — same guarantee as an empty one.
        assertEquals(
            CaptureTap.NOTHING,
            captureTapOutcome(fieldFocused = true, draftIsBlank = "   ".isBlank()),
        )
        assertEquals(
            CaptureTap.COMMIT,
            captureTapOutcome(fieldFocused = true, draftIsBlank = "milk".isBlank()),
        )
    }
}
