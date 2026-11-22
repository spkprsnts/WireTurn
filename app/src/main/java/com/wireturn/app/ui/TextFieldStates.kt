package com.wireturn.app.ui

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * A [TextFieldState] for a field whose text is hoisted as a plain `value` / `onValueChange` pair
 * (a config copy, a screen-level var) - the state-based TextField keeps its own text, so the two
 * are kept in sync here instead of at every call site.
 *
 * Every edit goes out through [onValueChange] right as it's committed (the state's onTextChanged);
 * a [value] the caller changes on its own (a reset, a QR scan, privacy mode's [redact]) replaces
 * the field's text. The caller's echoes of our own edits are skipped: by the time it recomposes,
 * more input may already be in the state, and writing the older text back would drop those
 * characters.
 *
 * Input the caller would reject or rewrite (digits only, max length, trim) belongs in the
 * field's InputTransformation: an onValueChange that ignores an edit leaves [value] unchanged, so
 * nothing would bring the field's text back.
 */
@Composable
fun rememberSyncedTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    // Plain holders, not snapshot state: only the callback and the effect below touch them.
    // Texts sent out that the caller hasn't echoed back yet, oldest first.
    val pending = remember { ArrayDeque<String>() }
    // Set while the caller's own value is written in, so it isn't sent back out.
    val applying = remember { booleanArrayOf(false) }
    val onTextChanged = remember {
        { text: CharSequence ->
            if (!applying[0]) {
                val sent = text.toString()
                pending.addLast(sent)
                // A caller that never echoes (it rewrites every value) mustn't grow this forever.
                if (pending.size > MAX_PENDING_ECHOES) pending.removeFirst()
                currentOnValueChange(sent)
            }
        }
    }
    val state = rememberTextFieldState(value, onTextChanged = onTextChanged)

    LaunchedEffect(value) {
        val echo = pending.indexOf(value)
        if (echo >= 0) {
            // The caller caught up to one of our edits - it and the ones before it are done.
            repeat(echo + 1) { pending.removeFirst() }
            return@LaunchedEffect
        }
        pending.clear()
        if (state.text.toString() != value) {
            applying[0] = true
            try {
                state.setTextAndPlaceCursorAtEnd(value)
            } finally {
                applying[0] = false
            }
        }
    }
    return state
}

private const val MAX_PENDING_ECHOES = 64

/** Digits only - for number fields (ports, MTU, intervals). */
val DigitsInput: InputTransformation = InputTransformation.byValue { _, proposed ->
    proposed.filter(Char::isDigit)
}

/** Digits only, at most 4 (MTU). */
val Digits4Input: InputTransformation = InputTransformation.byValue { _, proposed ->
    proposed.filter(Char::isDigit).take(4)
}

/** Digits only, at most 5 (ports). */
val PortInput: InputTransformation = InputTransformation.byValue { _, proposed ->
    proposed.filter(Char::isDigit).take(5)
}

/** No leading or trailing whitespace (links, IDs pasted with a stray space or newline). */
val TrimmedInput: InputTransformation = InputTransformation.byValue { _, proposed ->
    proposed.trim()
}

/** Shows every character as a bullet - privacy mode's masking, behind the spoiler. */
val MaskedOutput = OutputTransformation {
    if (length > 0) replace(0, length, "•".repeat(length))
}

/** Shows the text as blanks, like [redact] - for fields that sit outside the spoiler. */
val RedactedOutput = OutputTransformation {
    replace(0, length, " ".repeat(length.coerceAtLeast(12)))
}
