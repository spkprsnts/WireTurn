package com.wireturn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What TalkBack gets from a switch row: one node with its label, the switch role and state. */
@RunWith(RobolectricTestRunner::class)
class SwitchRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private var checked by mutableStateOf(false)

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    private val isDisabled = SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)

    private fun theToggle() = compose.onAllNodes(isToggleable()).also { it.assertCountEquals(1) }[0]

    @Test
    fun aRowItsItemTogglesIsTheItemsOneSwitchNode() {
        compose.setContent {
            SectionItem(onClick = { checked = !checked }) {
                SwitchRow(label = "Fake DNS", checked = checked, onCheckedChange = { checked = it })
            }
        }
        theToggle().assert(isSwitch).assert(hasText("Fake DNS")).assert(isOff())

        theToggle().performClick()
        assertTrue(checked)
        theToggle().assert(isOn())
    }

    @Test
    fun aStandaloneRowTogglesFromItsLabel() {
        compose.setContent {
            SwitchRow(label = "IPv6", checked = checked, onCheckedChange = { checked = it })
        }
        theToggle().assert(isSwitch).assert(hasText("IPv6")).assert(isOff())

        theToggle().performClick()
        assertTrue(checked)
    }

    @Test
    fun aSwitchNothingElseTogglesIsLabelledItself() {
        compose.setContent {
            // An item without onClick: only the switch takes taps.
            SectionItem {
                SwitchRow(label = "Mux", checked = checked, onCheckedChange = { checked = it })
            }
        }
        theToggle().assert(isSwitch).assert(hasContentDescription("Mux"))
        // The plain item isn't reported as a disabled control any more.
        compose.onAllNodes(isDisabled).assertCountEquals(0)

        theToggle().performClick()
        assertTrue(checked)
    }

    @Test
    fun aSplitRowsSwitchIsLabelledAndTheRowOpensSomethingElse() {
        var opened = false
        compose.setContent {
            SwitchRow(
                label = "Xray",
                checked = checked,
                onCheckedChange = { checked = it },
                onRowClick = { opened = true }
            )
        }
        theToggle().assert(isSwitch).assert(hasContentDescription("Xray"))

        compose.onNode(hasText("Xray")).performClick()
        assertTrue(opened)
        assertFalse(checked)
    }

    @Test
    fun aDisabledItemStaysDisabled() {
        compose.setContent {
            SectionItem(onClick = { checked = !checked }, enabled = false) {
                SwitchRow(label = "Locked", checked = checked, onCheckedChange = { checked = it }, enabled = false)
            }
        }
        theToggle().assert(isDisabled)
    }

    @Test
    fun theMainSwitchIsOneSwitchNode() {
        compose.setContent {
            MainSwitchItem(label = "VPN", checked = checked, onCheckedChange = { checked = it })
        }
        theToggle().assert(isSwitch).assert(hasText("VPN"))

        theToggle().performClick()
        assertEquals(true, checked)
    }
}
