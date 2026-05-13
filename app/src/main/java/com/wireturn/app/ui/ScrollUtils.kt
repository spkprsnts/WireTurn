package com.wireturn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

@Composable
fun Modifier.trackScrollDelta(
    onScrollDelta: (delta: Float) -> Unit,
    onSettle: (velocity: Float) -> Unit
): Modifier {
    val connection = remember(onScrollDelta, onSettle) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.y != 0f) {
                    onScrollDelta(consumed.y)
                }

                if (available.y != 0f) {
                    onSettle(10000f) 
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y != 0f) {
                    onSettle(10000f)
                } else {
                    onSettle(consumed.y)
                }
                return super.onPostFling(consumed, available)
            }
        }
    }
    return this.nestedScroll(connection)
}
