package com.wireturn.app.domain

/**
 * The User-Agent a subscription is fetched with (Subscription.userAgent). Panels pick what to serve
 * by it - Frostbite hands turnable:// links only to Turnable's apps, others serve an HTML page to
 * a client they don't know - so a subscription can be pinned to the client a panel expects.
 * Null is [AUTO]; anything else is sent as it is, except [WIRETURN], which stands for the app's own
 * versioned one.
 */
object SubscriptionUserAgents {
    /** WireTurn's own, then - for an HTML answer only - the kernels' clients' in turn. */
    val AUTO: String? = null

    /** WireTurn's own ("WireTurn/<version>"), with nothing tried after it. */
    const val WIRETURN = "wireturn"

    // The official clients of the kernels WireTurn runs. qWDTT and CSQTT identify their Android
    // apps; FreeTurn fetches from Go without setting one, so net/http sends its default.
    const val QWDTT = "qWDTT-Subscription/1.0"
    const val CSQTT = "csqtt-client"

    // Turnable's panels (Frostbite) serve turnable:// links only to Turnable's own apps.
    const val TURNABLE = "Turnable-Android/1.0"
    const val FREETURN = "Go-http-client/1.1"

    /** What [AUTO] tries after WireTurn's own when the panel answers with a web page. */
    val autoFallbacks = listOf(QWDTT, CSQTT, TURNABLE, FREETURN)

    /** The presets offered for a subscription, [AUTO] first; any other value is a custom one. */
    val presets: List<String?> = listOf(AUTO, WIRETURN, QWDTT, CSQTT, TURNABLE, FREETURN)

    /** [value] as it goes out, with WireTurn's own UA for [AUTO] and [WIRETURN]. */
    fun resolve(value: String?, wireTurnUserAgent: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() && it != WIRETURN } ?: wireTurnUserAgent

    /** A stored value cleaned up: blank is [AUTO]. */
    fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
