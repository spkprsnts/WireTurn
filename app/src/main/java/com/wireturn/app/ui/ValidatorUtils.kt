package com.wireturn.app.ui

object ValidatorUtils {
    /**
     * Проверяет, является ли строка пустой или валидным адресом формата host:port или ip:port.
     */
    fun isValidHostPort(input: String): Boolean {
        if (input.isBlank()) return true

        // Регулярное выражение поддерживает:
        // 1. localhost
        // 2. Доменные имена (example.com)
        // 3. IPv4 (127.0.0.1)
        // 4. Обязательный порт через двоеточие
        val pattern = Regex("""^(localhost|(?:(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\-]*[a-zA-Z0-9])\.)*(?:[A-Za-z0-9]|[A-Za-z0-9][a-zA-Z0-9\-]*[A-Za-z0-9])|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d{1,5})$""")

        val match = pattern.matchEntire(input) ?: return false

        // Извлекаем порт (последняя группа в регулярке) и проверяем диапазон
        val portString = match.groupValues.lastOrNull() ?: return false
        val port = portString.toIntOrNull() ?: return false

        return port in 1..65535
    }

    /**
     * Проверяет, является ли строка валидным URL.
     */
    fun isValidUrl(input: String): Boolean {
        if (input.isBlank()) return true
        return android.util.Patterns.WEB_URL.matcher(input).matches()
    }

    /**
     * Проверяет, является ли строка валидной VLESS-ссылкой.
     */
    fun isValidVlessLink(input: String): Boolean {
        if (input.isBlank()) return true
        return input.startsWith("vless://", ignoreCase = true) && input.contains("@")
    }

    /**
     * Проверяет, является ли строка валидной Turnable-ссылкой.
     */
    fun isValidTurnableUrl(input: String): Boolean {
        if (input.isBlank()) return true
        return input.startsWith("turnable://", ignoreCase = true) && input.contains("@")
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
}
