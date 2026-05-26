package com.wireturn.app.ui

import androidx.core.net.toUri
import androidx.core.util.PatternsCompat
import com.google.common.net.HostAndPort
import com.google.common.net.InetAddresses

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
     * Проверяет, является ли строка валидной VLESS-ссылкой. Пустая строка считается невалидной.
     */
    fun isValidVlessLink(input: String): Boolean {
        if (input.isBlank()) return false
        return try {
            val uri = input.toUri()
            uri.scheme?.equals("vless", ignoreCase = true) == true &&
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
}
