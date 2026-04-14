package com.wireturn.app.data

data class WgConfig(
    val privateKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: String = "",
    val publicKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = "",
    val socks5BindAddress: String = DEFAULT_SOCKS5_BIND_ADDRESS,
    val httpBindAddress: String = ""
) {
    fun isValid(): Boolean {
        return privateKey.isNotBlank() && address.isNotBlank() && publicKey.isNotBlank()
    }

    fun fillDefaults(): WgConfig {
        return copy(
            dns = dns.ifBlank { DEFAULT_DNS },
            mtu = mtu.ifBlank { DEFAULT_MTU },
            endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT },
            allowedIps = allowedIps.ifBlank { DEFAULT_ALLOWED_IPS },
            persistentKeepalive = persistentKeepalive.ifBlank { DEFAULT_PERSISTENT_KEEPALIVE },
            socks5BindAddress = socks5BindAddress.ifBlank { DEFAULT_SOCKS5_BIND_ADDRESS }
        )
    }

    fun toWgString(): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = $privateKey\n")
        sb.append("Address = $address\n")
        if (dns.isNotBlank()) sb.append("DNS = $dns\n")
        if (mtu.isNotBlank()) sb.append("MTU = $mtu\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = $publicKey\n")
        sb.append("Endpoint = $endpoint\n")
        sb.append("AllowedIPs = $allowedIps\n")
        if (persistentKeepalive.isNotBlank()) sb.append("PersistentKeepalive = $persistentKeepalive\n")
        if (httpBindAddress.isNotBlank()) {
            sb.append("\n[http]\n")
            sb.append("BindAddress = $httpBindAddress\n")
        }
        if (socks5BindAddress.isNotBlank()) {
            sb.append("\n[Socks5]\n")
            sb.append("BindAddress = $socks5BindAddress\n")
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_DNS = "1.1.1.1"
        const val DEFAULT_MTU = "1280"
        const val DEFAULT_ENDPOINT = "127.0.0.1:9000"
        const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0"
        const val DEFAULT_PERSISTENT_KEEPALIVE = "25"
        const val DEFAULT_SOCKS5_BIND_ADDRESS = "127.0.0.1:2080"

        fun parse(text: String): WgConfig {
            var privateKey = ""; var address = ""; var dns = ""; var mtu = ""
            var publicKey = ""; var endpoint = ""; var allowedIps = ""
            var persistentKeepalive = ""
            var httpBindAddress = ""; var socks5BindAddress = DEFAULT_SOCKS5_BIND_ADDRESS

            var currentSection = ""
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length - 1).lowercase()
                } else if (trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (currentSection) {
                        "interface" -> when (key) {
                            "privatekey" -> privateKey = value
                            "address" -> address = value
                            "dns" -> dns = value
                            "mtu" -> mtu = value
                        }
                        "peer" -> when (key) {
                            "publickey" -> publicKey = value
                            "endpoint" -> endpoint = value
                            "allowedips" -> allowedIps = value
                            "persistentkeepalive" -> persistentKeepalive = value
                        }
                        "http" -> if (key == "bindaddress") httpBindAddress = value
                        "socks5" -> if (key == "bindaddress") socks5BindAddress = value
                    }
                }
            }
            return WgConfig(privateKey, address, dns, mtu, publicKey, endpoint, allowedIps, persistentKeepalive, socks5BindAddress, httpBindAddress)
        }
    }
}

