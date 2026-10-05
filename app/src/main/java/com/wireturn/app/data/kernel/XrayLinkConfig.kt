package com.wireturn.app.data.kernel

import com.google.gson.annotations.SerializedName
import com.wireturn.app.ui.ValidatorUtils

// A profile without a tunnel kernel: Xray (vless-client) connects straight to the server in the
// link - vless://, trojan://, hysteria2:// or hy2://. vless-client parses the link itself, so the
// app only keeps it as is.
data class XrayLinkConfig(
    @SerializedName("link") val link: String = "",
    // Mux streams, "0" - off. Same as the overlay's VlessConfig.mux.
    @SerializedName("mux") val mux: String = "0"
) {
    fun isValid(): Boolean = ValidatorUtils.isValidVlessLink(link)

    fun sanitize(): XrayLinkConfig = copy(
        link = (link as Any?)?.toString()?.trim()?.take(4096) ?: "",
        mux = (mux as Any?)?.toString()?.trim()?.takeIf { it.toIntOrNull() != null }?.take(20) ?: "0"
    )

    companion object {
        /** [uri] as a link of its own, or null if it isn't a (valid) vless/trojan/hysteria2 link. */
        fun parse(uri: String): XrayLinkConfig? {
            val trimmed = uri.trim()
            return if (ValidatorUtils.isValidVlessLink(trimmed)) XrayLinkConfig(trimmed) else null
        }
    }
}
