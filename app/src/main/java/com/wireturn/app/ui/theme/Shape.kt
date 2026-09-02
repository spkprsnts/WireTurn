package com.wireturn.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 Shape Scale — 5 sizes from extra-small to extra-large. Declared explicitly (rather than
 * left to MaterialExpressiveTheme's implicit default) so it's the single source of truth other
 * shape values in the app can be defined against.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Corner radii for grouped [com.wireturn.app.ui.SectionItem] cards: the outer radius on the
 * first/last item of a group, and the tight inner radius on the joints between them. Sits
 * between [AppShapes.large] (16dp) and [AppShapes.extraLarge] (24dp) - kept as its own named
 * token, since [Shapes] only holds one shape per size and both large/extraLarge are already
 * used elsewhere for their own baseline meaning.
 */
object GroupCardCorners {
    val outer = 20.dp
    val inner = 4.dp // matches AppShapes.extraSmall
}
