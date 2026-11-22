package com.wireturn.app.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncedTextFieldStateTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the field sent out, in order. */
    private val sent = mutableListOf<String>()

    /** The caller's hoisted value - the test decides when (and whether) it follows an edit. */
    private var value by mutableStateOf("")

    private lateinit var state: TextFieldState

    private fun setUp(initial: String, echo: Boolean) {
        value = initial
        compose.setContent {
            state = rememberSyncedTextFieldState(value) {
                sent += it
                if (echo) value = it
            }
        }
        compose.waitForIdle()
    }

    private fun type(text: String) = compose.runOnIdle { state.setTextAndPlaceCursorAtEnd(text) }

    private fun fieldText() = compose.runOnIdle { state.text.toString() }

    @Test
    fun startsWithTheCallersValueAndSendsNothing() {
        setUp("initial", echo = true)
        assertEquals("initial", fieldText())
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun everyEditIsSentEvenWithinOneFrame() {
        setUp("", echo = true)
        compose.runOnIdle {
            // No recomposition in between: a snapshotFlow collector saw only the last of these.
            state.setTextAndPlaceCursorAtEnd("a")
            state.setTextAndPlaceCursorAtEnd("ab")
            state.setTextAndPlaceCursorAtEnd("abc")
        }
        assertEquals(listOf("a", "ab", "abc"), sent)
        assertEquals("abc", fieldText())
        assertEquals("abc", value)
    }

    @Test
    fun theCallersOwnChangeReplacesTheTextWithoutComingBack() {
        setUp("old", echo = true)
        type("edited")
        sent.clear()

        value = "reset"
        assertEquals("reset", fieldText())
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun aLateEchoOfAnOlderEditDoesNotDropNewerInput() {
        setUp("", echo = false)
        type("a")
        type("ab")
        type("abc")

        // The caller only now catches up, and with the first edit - "bc" must stay.
        value = "a"
        assertEquals("abc", fieldText())
        value = "abc"
        assertEquals("abc", fieldText())
        assertEquals(listOf("a", "ab", "abc"), sent)
    }

    @Test
    fun aChangeAfterTheEchoesIsTheCallersOwn() {
        setUp("", echo = false)
        type("a")
        value = "a"
        compose.waitForIdle()

        // A value that isn't one of the field's pending edits is the caller's own - it wins over
        // the input it hasn't caught up with.
        type("ab")
        value = "x"
        assertEquals("x", fieldText())
        assertEquals(listOf("a", "ab"), sent)
    }
}
