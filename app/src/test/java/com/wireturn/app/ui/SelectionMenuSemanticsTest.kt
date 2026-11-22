package com.wireturn.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionMenuSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun selectionDialogOptionsAreRadioButtonsWithTheirState() {
        var picked by mutableStateOf("TCP")
        var dismissed = false
        compose.setContent {
            SelectionDialog(
                title = "Транспорт",
                items = listOf("TCP", "UDP"),
                onSelect = { picked = it },
                onDismiss = { dismissed = true },
                isSelected = { it == picked }
            ) { item, _ ->
                Text(item, modifier = Modifier.padding(16.dp))
            }
        }
        compose.onNode(hasText("TCP")).assert(isRadio).assertIsSelected()
        compose.onNode(hasText("UDP")).assert(isRadio).assertIsNotSelected()

        compose.onNodeWithText("UDP").performClick()
        assertEquals("UDP", picked)
        assertEquals(true, dismissed)
    }

    @Test
    fun appDropdownMenuShowsItsTitleAndItems() {
        var expanded by mutableStateOf(true)
        var clicked = false
        compose.setContent {
            AppDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                title = "Действия"
            ) {
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    onClick = { clicked = true; expanded = false }
                )
            }
        }
        compose.onNodeWithText("Действия").assertIsDisplayed()
        compose.onNodeWithText("Копировать").assertIsDisplayed().performClick()
        assertEquals(true, clicked)
        compose.waitForIdle()
        assertFalse(expanded)
    }
}
