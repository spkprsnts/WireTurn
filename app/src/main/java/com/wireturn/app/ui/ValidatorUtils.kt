package com.wireturn.app.ui

import androidx.core.net.toUri
import androidx.core.util.PatternsCompat
import com.google.common.net.HostAndPort
import com.google.common.net.InetAddresses
import com.wireturn.app.R
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.XrayConfiguration

enum class UriProtocol { VLESS, TROJAN, HYSTERIA2 }

object ValidatorUtils {
    fun isValidHost(input: String): Boolean {
        if (input.isBlank()) return false
        
        return InetAddresses.isInetAddress(input) || 
                PatternsCompat.DOMAIN_NAME.matcher(input).matches() ||
                input.equals("localhost", ignoreCase = true)
    }

    fun isValidPort(input: String): Boolean {
        val port = input.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /**
     * Проверяет, является ли строка валидным адресом формата host:port или ip:port.
     * Поддерживает IPv6 в формате [::1]:80. Пустая строка считается невалидной.
     */
    fun isValidHostPort(input: String): Boolean {
        if (input.isBlank()) return false
        return try {
            val hp = HostAndPort.fromString(input)
            hp.hasPort() && isValidHost(hp.host) && isValidPort(hp.port.toString())
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Проверяет, что host в строке host:port — loopback (127.0.0.0/8, ::1 или "localhost").
     * Небезопасные значения (например 0.0.0.0 или адрес в локальной сети) возвращают false.
     */
    fun isLoopbackHostPort(input: String): Boolean {
        return try {
            val host = HostAndPort.fromString(input).host
            host.equals("localhost", ignoreCase = true) ||
                    (InetAddresses.isInetAddress(host) && InetAddresses.forString(host).isLoopbackAddress)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Определяет протокол ссылки (vless/trojan/hysteria2) по её схеме.
     * Возвращает null, если схема не распознана.
     */
    fun detectUriProtocol(input: String): UriProtocol? {
        if (input.isBlank()) return null
        val scheme = try { input.toUri().scheme } catch (_: Exception) { null } ?: return null
        return when (scheme.lowercase()) {
            "vless" -> UriProtocol.VLESS
            "trojan" -> UriProtocol.TROJAN
            "hysteria2", "hy2" -> UriProtocol.HYSTERIA2
            else -> null
        }
    }

    /**
     * Строковый ресурс с названием протокола (VLESS/Trojan/Hysteria2), определённого по ссылке.
     * Нераспознанная или пустая ссылка трактуется как VLESS (исторический дефолт).
     */
    fun uriProtocolStringRes(link: String): Int = when (detectUriProtocol(link)) {
        UriProtocol.TROJAN -> R.string.trojan
        UriProtocol.HYSTERIA2 -> R.string.hysteria2
        UriProtocol.VLESS, null -> R.string.vless
    }

    /**
     * Проверяет, является ли строка валидной VLESS-ссылкой. Пустая строка считается невалидной.
     */
    fun isValidVlessLink(input: String): Boolean {
        if (input.isBlank()) return false
        return try {
            val uri = input.toUri()
            detectUriProtocol(input) != null &&
                    !uri.userInfo.isNullOrBlank() &&
                    !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Извлекает host:port из VLESS ссылки.
     */
    fun parseVlessAddress(link: String): String? {
        return try {
            val uri = link.toUri()
            val host = uri.host ?: return null
            val port = uri.port
            if (port != -1) "$host:$port" else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет, является ли строка валидной http(s) ссылкой с непустым хостом.
     * Пустая строка считается невалидной.
     */
    fun isValidUrl(input: String): Boolean {
        if (input.isBlank()) return false
        return try {
            val uri = input.toUri()
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                    !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun isValidProxyUser(input: String): Boolean {
        if (input.isBlank()) return true
        return input.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == '-' || it == '_' || it == '.' } && input.length >= 3
    }

    fun isValidProxyPass(input: String): Boolean {
        if (input.isBlank()) return true
        return input.length >= 3
    }

    fun cleanProxyString(input: String): String {
        return input.trim().filter { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == '-' || it == '_' || it == '.' }
    }

    fun isValidUuid4(input: String): Boolean {
        if (input.isBlank()) return false
        val regex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        return regex.matches(input)
    }

    /**
     * Route's own socket type is authoritative (tcp -> VLESS/Trojan, udp -> WireGuard/Hysteria2) -
     * see external/turnable/docs/REFERENCE.md. Mismatch is possible on Turnable/FreeTurn only,
     * where the kernel itself exposes a fixed tcp/udp entry point. FreeTurn dropped its tcp tunnel
     * mode entirely (v3.0.0+) - it's udp-only now, unconditionally. Returns the kernel's required
     * socket ("tcp"/"udp") if it conflicts with what xrayProtocol/vlessLink actually need, else null.
     */
    fun kernelTransportMismatch(kernelConfig: KernelConfig?, xrayProtocol: XrayConfiguration, vlessLink: String): String? {
        val kernelRequiredSocket = when (kernelConfig) {
            is KernelConfig.Turnable -> kernelConfig.config.routes.find { it.routeId == kernelConfig.config.selectedRouteId }
                ?.socket?.lowercase()?.takeIf { it == "tcp" || it == "udp" }
            is KernelConfig.FreeTurn -> "udp"
            else -> null
        } ?: return null

        val xrayNeedsUdp = when (xrayProtocol) {
            XrayConfiguration.WIREGUARD -> true
            XrayConfiguration.VLESS -> detectUriProtocol(vlessLink) == UriProtocol.HYSTERIA2
        }
        val requiredForXray = if (xrayNeedsUdp) "udp" else "tcp"
        return kernelRequiredSocket.takeIf { it != requiredForXray }
    }
}
