package com.wireturn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import com.wireturn.app.data.Profile
import com.wireturn.app.ui.screens.ProfileListItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileListItemSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)

    @Test
    fun theActiveProfileIsTheSelectedOneOfTheSet() {
        compose.setContent {
            ProfileListItem(profile = Profile(id = "a", name = "Дом"), isSelected = true, onClick = {})
            ProfileListItem(profile = Profile(id = "b", name = "Работа"), isSelected = false, onClick = {})
        }
        compose.onNode(hasText("Дом")).assert(role(Role.RadioButton)).assertIsSelected()
        compose.onNode(hasText("Работа")).assert(role(Role.RadioButton)).assertIsNotSelected()
    }

    @Test
    fun inSelectionModeARowIsACheckbox() {
        var checked by mutableStateOf(false)
        compose.setContent {
            ProfileListItem(
                profile = Profile(id = "a", name = "Дом"),
                isSelected = true,
                checked = checked,
                onClick = { checked = !checked }
            )
        }
        compose.onNode(hasText("Дом")).assert(role(Role.Checkbox)).assert(isOff())

        compose.onNode(hasText("Дом")).performClick()
        assertEquals(true, checked)
        compose.onNode(hasText("Дом")).assert(isOn())
    }
}
