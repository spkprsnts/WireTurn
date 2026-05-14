package com.wireturn.app.ui

object ValidatorUtils {
    fun isValidHost(input: String): Boolean {
        if (input.isBlank()) return false
        val pattern = Regex("""^(localhost|(?:(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\-]*[a-zA-Z0-9])\.)*(?:[A-Za-z0-9]|[A-Za-z0-9][a-zA-Z0-9\-]*[A-Za-z0-9])|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$""")
        return pattern.matches(input)
    }

    fun isValidPort(input: String): Boolean {
        val port = input.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /**
     * Проверяет, является ли строка пустой или валидным адресом формата host:port или ip:port.
     */
    fun isValidHostPort(input: String): Boolean {
        if (input.isBlank()) return true

        val parts = input.split(":")
        if (parts.size != 2) return false
        
        return isValidHost(parts[0]) && isValidPort(parts[1])
    }

    /**
     * Проверяет, является ли строка валидной VLESS-ссылкой.
     */
    fun isValidVlessLink(input: String): Boolean {
        if (input.isBlank()) return true
        return input.startsWith("vless://", ignoreCase = true) && input.contains("@")
    }

    /**
     * Извлекает host:port из VLESS ссылки.
     */
    fun parseVlessAddress(link: String): String? {
        if (!isValidVlessLink(link)) return null
        return try {
            val afterVless = link.substring(8)
            val hostPart = afterVless.substringAfter('@').substringBefore('?').substringBefore('/')
            if (hostPart.contains(':')) hostPart else null
        } catch (_: Exception) {
            null
        }
    }

    fun isValidProxyUser(input: String): Boolean = input.trim().length >= 3

    fun isValidProxyPass(input: String): Boolean = input.trim().length >= 3

    fun cleanProxyString(input: String): String {
        return input.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
