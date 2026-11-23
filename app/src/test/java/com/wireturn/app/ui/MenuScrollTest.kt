package com.wireturn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.ui.theme.WireturnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A menu taller than the screen can be scrolled to its last item. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h400dp")
class MenuScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longMenu_scrollsToItsLastItem() {
        compose.setContent {
            WireturnTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Box {
                    AppDropdownMenu(expanded = true, onDismissRequest = {}, title = "Title") {
                        repeat(20) { i ->
                            DropdownMenuItem(text = { Text("Item $i") }, onClick = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Item 19").performScrollTo().assertIsDisplayed()
    }
}
