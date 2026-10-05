package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.XrayLinkConfig
import com.wireturn.app.ui.ValidatorUtils
import com.wireturn.app.ui.activities.kernel.XrayLinkConfigActivity

// A profile with no tunnel kernel: Xray alone, straight to the server in the profile's own link.
// CoreService runs nothing for it (runsBinary) - XrayService takes the link from the kernel config
// instead of the profile's Xray overlay, and the core status just follows Xray's.
object XrayKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.XRAY
    override val runsBinary: Boolean = false
    override val displayNameRes: Int = R.string.kernel_xray
    override val configActivityClass = XrayLinkConfigActivity::class.java

    override fun description(context: Context, cfg: KernelConfig): String {
        val link = (cfg as KernelConfig.Xray).config.link
        return context.getString(ValidatorUtils.uriProtocolStringRes(link))
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.ic_xray_24px

    override val defaultProfileName: String = "Xray Server"

    override fun decodeUri(uri: String): KernelConfig? =
        XrayLinkConfig.parse(uri)?.let { KernelConfig.Xray(it) }

    // The usual share-link convention: the name rides in the fragment.
    override fun displayNameFromUri(uri: String): String? = try {
        android.net.Uri.parse(uri.trim()).fragment?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> = emptyList()

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean = false
}
