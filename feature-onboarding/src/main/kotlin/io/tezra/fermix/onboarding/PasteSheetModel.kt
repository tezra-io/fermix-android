package io.tezra.fermix.onboarding

import io.tezra.fermix.protocol.PAIRING_LINK_PREFIX
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The paste sheet (design section 13.3, step 2) as the ViewModel keeps it: [field] is the open sheet's field,
 * or null while the sheet is closed. Kept in the ViewModel, so a rotation or a fold keeps a half-typed link
 * (section 13.11, rule 3), and never in the saved state, which outlives the process, as the link carries the
 * pairing's secret (section 12.4). A link it takes goes to [ceremony], as a scanned one does. Its calls come
 * on the main thread, as the ViewModel's do.
 */
class PasteSheetModel internal constructor(
    private val stack: StateFlow<List<OnboardingKey>>,
    private val ceremony: CeremonyDriver,
) {
    private val fieldState = MutableStateFlow<PasteField?>(null)

    val field: StateFlow<PasteField?> = fieldState.asStateFlow()

    /** "Paste a pairing link" on Pair, Scan or a failure screen: the sheet, empty. */
    fun open() {
        val top = stack.value.lastOrNull()
        require(top == OnboardingKey.Pair || top == OnboardingKey.Scan || top is OnboardingKey.Failure) {
            "the paste sheet opens on Pair, Scan or a failure screen, not on $top"
        }
        fieldState.value = PasteField(text = "", refused = false)
    }

    /** The owner's edit of the field, which a refusal under it no longer describes. */
    fun edit(text: String) {
        val open = checkNotNull(fieldState.value) { "the field is the open sheet's" }
        fieldState.value = open.copy(text = text, refused = false)
    }

    /**
     * Paste: the primary clip's link in the field ([pastedLink]), read as "Continue" reads it; an empty
     * clipboard brings nothing. Once read, a clip with a pairing link in it is cleared, whatever the phone
     * makes of the link, as it carries a secret (section 12.4); a clip with none is the owner's own and stays.
     */
    fun pasteFrom(clip: PrimaryClip) {
        val open = checkNotNull(fieldState.value) { "Paste is the open sheet's" }
        val text = clip.text() ?: return
        val link = pastedLink(text)
        val outcome = readLink(link)
        if (PAIRING_LINK_PREFIX in text) clip.clear()
        settle(open.copy(text = link), outcome)
    }

    /**
     * "Continue": the field's link ([pastedLink]), as [readLink] reads it. When it is a pairing link, taken or
     * refused, a primary clip with a pairing link in it is cleared, as the keyboard's paste leaves the clip
     * holding what it put in the field, the owner's edits aside (section 12.4); a clip with none stays. The
     * keyboard's own clipboard history is out of reach (section 6.5, onboarding gotcha 12).
     */
    fun submit(clip: PrimaryClip) {
        val open = checkNotNull(fieldState.value) { "Continue is the open sheet's" }
        val outcome = readLink(pastedLink(open.text))
        if (outcome !is LinkOutcome.NotAFermixCode) clearIfLink(clip)
        settle(open, outcome)
    }

    /** The sheet dismissed, or the screen that opened it gone: its field goes with it. */
    fun close() {
        fieldState.value = null
    }

    /**
     * A text the scan refuses stays in the field, [open], with the refusal under it; any other closes the
     * sheet, and the ceremony driver takes it.
     */
    private fun settle(
        open: PasteField,
        outcome: LinkOutcome,
    ) {
        if (outcome.refused) {
            fieldState.value = open.copy(refused = true)
            return
        }
        fieldState.value = null
        ceremony.onLink(outcome)
    }
}

/**
 * The link in a pasted [text]: its first word that starts `fermix://pair?`, as `fermix pair` prints the link
 * after "Manual pairing URI:" on a line a terminal copies whole, its end with it; else the text without the
 * blank around it.
 */
private fun pastedLink(text: String): String {
    val words = text.trim().split(Regex("\\s+"))
    return words.firstOrNull { it.startsWith(PAIRING_LINK_PREFIX) } ?: text.trim()
}

/** Clears [clip] when it has a pairing link in it. */
private fun clearIfLink(clip: PrimaryClip) {
    if (clip.text()?.contains(PAIRING_LINK_PREFIX) == true) clip.clear()
}
