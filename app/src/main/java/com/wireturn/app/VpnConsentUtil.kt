package com.wireturn.app

import android.content.Context
import android.net.VpnService

/**
 * Non-null (and an Intent to launch the system consent dialog with) exactly when VPN mode is
 * enabled but the app doesn't yet have VpnService consent - the one condition both the in-app
 * start flow and the quick-settings tile need to agree on to decide whether starting the core
 * can proceed directly or has to go through consent first.
 */
fun vpnConsentIntent(context: Context, vpnModeEnabled: Boolean): android.content.Intent? =
    if (vpnModeEnabled) VpnService.prepare(context) else null
