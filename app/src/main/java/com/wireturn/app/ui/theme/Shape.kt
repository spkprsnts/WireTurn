package com.wireturn.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Corner radii the shape scale is built from - shared with GroupCardCorners below, which needs
// them as Dp (SectionItem animates its corners between the two).
private val CornerExtraSmall = 4.dp
private val CornerLargeIncreased = 20.dp

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
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(CornerLargeIncreased),
    extraLarge = RoundedCornerShape(24.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp)
)

/**
 * Corner radii for grouped [com.wireturn.app.ui.SectionItem] cards: the outer radius on the
 * first/last item of a group ([Shapes.largeIncreased]), and the tight inner radius on the joints
 * between them ([Shapes.extraSmall]).
 */
object GroupCardCorners {
    val outer = CornerLargeIncreased
    val inner = CornerExtraSmall
}
