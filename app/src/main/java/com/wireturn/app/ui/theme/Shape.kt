package com.wireturn.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Corner radii the shape scale is built from - shared with GroupCardCorners below, which needs
// them as Dp (SectionItem animates its corners between the two).
private val CornerExtraSmall = 4.dp
private val CornerLarge = 16.dp

/**
 * M3E Shape Scale - the 5 baseline sizes plus the 3 Expressive added (largeIncreased,
 * extraLargeIncreased, extraExtraLarge). Declared explicitly (rather than left to
 * MaterialExpressiveTheme's implicit default) so it's the single source of truth other shape
 * values in the app are defined against. extraLarge stays 24dp, below the 28dp token.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(CornerExtraSmall),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(CornerLarge),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp)
)

/**
 * Corner radii for grouped [com.wireturn.app.ui.SectionItem] rows - a segmented list's: the outer
 * radius on the ends of a group ([Shapes.large], ListItemDefaults.segmentedShapes' container
 * shape), and the item's own between them ([Shapes.extraSmall]).
 */
object GroupCardCorners {
    val outer = CornerLarge
    val inner = CornerExtraSmall
}
