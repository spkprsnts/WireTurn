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
import androidx.compose.runtime.snapshotFlow

/**
 * A [TextFieldState] for a field whose text is hoisted as a plain `value` / `onValueChange` pair
 * (a config copy, a screen-level var) - the state-based TextField keeps its own text, so the two
 * are kept in sync here instead of at every call site.
 *
 * Edits go out through [onValueChange]; a [value] the caller changes on its own (a reset, a QR
 * scan, privacy mode's [redact]) replaces the field's text. The caller's echo of our own edit is
 * skipped: by the time it recomposes, more input may already be in the state, and writing the
 * older text back would drop those characters.
 *
 * Input the caller would reject or rewrite (digits only, max length, trim) belongs in the
 * field's InputTransformation: an onValueChange that ignores an edit leaves [value] unchanged, so
 * nothing would bring the field's text back.
 */
@Composable
fun rememberSyncedTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    // Plain holder, not snapshot state: only the effects below read it.
    val lastSent = remember { arrayOfNulls<String>(1) }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != currentValue) {
                lastSent[0] = text
                currentOnValueChange(text)
            }
        }
    }
    LaunchedEffect(value) {
        if (value != lastSent[0] && state.text.toString() != value) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
        lastSent[0] = null
    }
    return state
}

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
