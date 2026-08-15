package com.wireturn.app.ui

import android.content.Context
import android.widget.Toast

private var activeToast: Toast? = null

/**
 * Shows a Toast, cancelling any currently-visible one first so rapid-fire messages (e.g. several
 * import results in a row) don't queue up and play out one after another. Mirrors the old
 * SnackbarHostState.showExclusiveSnackbar this replaced.
 */
fun Context.showExclusiveToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    activeToast?.cancel()
    activeToast = Toast.makeText(applicationContext, message, duration).also { it.show() }
}
