package com.wireturn.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Indicator for text fields, positioned with an offset to float nicely in trailingIcon.
 */
@Composable
fun ConfigFieldIndicator(isModified: Boolean) {
    if (isModified) {
        // Используем Box с нулевым размером в layout, чтобы не сдвигать соседние элементы (например, в trailingIcon)
        Box(
            modifier = Modifier
                .offset(y = (-15).dp, x = 12.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

/**
 * Inline indicator for rows, usually placed after a text label.
 */
@Composable
fun InlineConfigIndicator(isModified: Boolean, modifier: Modifier = Modifier) {
    if (isModified) {
        Box(
            modifier = modifier
                .padding(start = 6.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

