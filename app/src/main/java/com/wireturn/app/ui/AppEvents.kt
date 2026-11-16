package com.wireturn.app.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A simple event bus for transient UI signals across activities.
 */
object UIEventBus {
    private val _scrollRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scrollRequest = _scrollRequest.asSharedFlow()

    fun requestScroll(id: String) {
        _scrollRequest.tryEmit(id)
    }
}

/**
 * A `wireturn://`/`wt://` link plus the referring site, if the system handed one over (e.g. a
 * browser passing [android.app.Activity.getReferrer] for a link the user tapped on a web page).
 * Only used to label *profile* bundles in the confirmation dialog - subscription links carry
 * their own host as the label, since the URL itself is the "site".
 */
data class DeepLinkRequest(val link: String, val referrerHost: String? = null)

/**
 * Carries `wireturn://`/`wt://` deep links from [com.wireturn.app.ui.activities.MainActivity]'s
 * intent handling to the composable that shows the import confirmation dialog. A bus (rather than
 * passing the link through composable params) because the link can arrive at any time, including
 * while MainActivity is already showing other content.
 */
object DeepLinkBus {
    private val _pendingLink = Channel<DeepLinkRequest>(capacity = Channel.UNLIMITED)
    val pendingLink = _pendingLink.receiveAsFlow()

    fun submit(request: DeepLinkRequest) {
        _pendingLink.trySend(request)
    }
}

/**
 * [com.wireturn.app.CoreTileService] can't show the system VPN consent dialog itself, so when
 * consent is missing it opens MainActivity instead of sending its usual broadcast; this bus
 * tells [com.wireturn.app.ui.CoreTriggerController] to replay the start action from there.
 */
object TileVpnConsentBus {
    private val _pending = Channel<Unit>(capacity = Channel.CONFLATED)
    val pending = _pending.receiveAsFlow()

    fun submit() {
        _pending.trySend(Unit)
    }
}
