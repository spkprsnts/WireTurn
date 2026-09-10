package com.wireturn.app.data

import android.net.Uri
import com.google.gson.annotations.SerializedName

data class TurnableRoute(
    @SerializedName("route_id") val routeId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("socket") val socket: String = "",
    @SerializedName("transport") val transport: String? = null
) {
    fun sanitize(): TurnableRoute = copy(
        routeId = (routeId as Any?)?.toString()?.take(100) ?: "",
        name = (name as Any?)?.toString()?.take(100) ?: "",
        socket = (socket as Any?)?.toString()?.take(100) ?: "udp",
        transport = (transport as Any?)?.toString()?.take(100)
    )
}

data class TurnableConfig(
    @SerializedName("user_uuid") val userUuid: String? = null,
    @SerializedName("platform_id") val platformId: String = "vk.com",
    @SerializedName("call_id") val callId: String = "",
    @SerializedName("type") val type: String = "relay",
    @SerializedName("encryption") val encryption: String? = "handshake",
    @SerializedName("pub_key") val pubKey: String? = null,
    @SerializedName("peers") val peers: Int = 1,
    @SerializedName("gateway") val gateway: String = "",
    @SerializedName("proto") val proto: String? = "srtp",
    @SerializedName("cloak") val cloak: String? = "none",
    @SerializedName("routes") val routes: List<TurnableRoute> = emptyList(),
    @SerializedName("selected_route_id") val selectedRouteId: String = ""
) {
    fun sanitize(): TurnableConfig = copy(
        userUuid = (userUuid as Any?)?.toString()?.trim()?.take(200),
        platformId = (platformId as Any?)?.toString()?.take(200) ?: "vk.com",
        callId = (callId as Any?)?.toString()?.take(200) ?: "",
        type = (type as Any?)?.toString()?.take(100) ?: "relay",
        encryption = (encryption as Any?)?.toString()?.take(100) ?: "handshake",
        pubKey = (pubKey as Any?)?.toString()?.take(4096),
        gateway = (gateway as Any?)?.toString()?.take(500) ?: "",
        proto = (proto as Any?)?.toString()?.take(100) ?: "srtp",
        cloak = (cloak as Any?)?.toString()?.take(100) ?: "none",
        selectedRouteId = (selectedRouteId as Any?)?.toString()?.take(100) ?: "",
        routes = (routes as List<TurnableRoute>?)?.map { it.sanitize() } ?: emptyList()
    )

    fun isValid(): Boolean = platformId.isNotBlank() &&
            callId.isNotBlank() &&
            gateway.isNotBlank() &&
            routes.isNotEmpty() &&
            !userUuid.isNullOrBlank()


    val platformDisplayName: String
        get() = getPlatformDisplayName(platformId)

    fun toUri(onlySelected: Boolean = false): String {
        val targetRoutes = if (onlySelected && selectedRouteId.isNotBlank()) {
            routes.filter { it.routeId == selectedRouteId }
        } else {
            routes
        }
        val builder = Uri.Builder().scheme("turnable")
        val userInfo = "${Uri.encode(userUuid ?: "")}:${Uri.encode(callId)}"
        builder.encodedAuthority("$userInfo@$platformId")
        // Each route is a single dash-joined path segment: route_id-socket-transport
        targetRoutes.forEach { builder.appendPath("${it.routeId}-${it.socket}-${it.transport ?: ""}") }
        builder.appendQueryParameter("type", type)
        builder.appendQueryParameter("gateway", gateway)
        proto?.let { builder.appendQueryParameter("proto", it) }
        cloak?.let { builder.appendQueryParameter("cloak", it) }
        builder.appendQueryParameter("peers", peers.toString())
        encryption?.let { builder.appendQueryParameter("encryption", it) }
        pubKey?.let { builder.appendQueryParameter("pub_key", it) }
        if (selectedRouteId.isNotBlank()) builder.appendQueryParameter("selected_route_id", selectedRouteId)
        if (targetRoutes.isNotEmpty()) {
            builder.fragment(targetRoutes.joinToString(", ") { it.name.ifBlank { it.routeId } })
        }
        return builder.build().toString()
    }

    companion object {
        fun getPlatformDisplayName(platformId: String): String = when (platformId) {
            "vk.com" -> "VK"
            else -> platformId
        }
        fun parse(url: String, current: TurnableConfig = TurnableConfig()): TurnableConfig? {
            if (!url.startsWith("turnable://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(url)
                val userParts = (uri.encodedUserInfo ?: "").split(":").map { Uri.decode(it) }
                val userUuid = if (userParts.size > 1) userParts[0].takeIf { it.isNotBlank() } else null
                val callId = if (userParts.size > 1) userParts[1] else userParts.getOrNull(0) ?: ""
                val pathParts = (uri.encodedPath ?: "").split("/").filter { it.isNotBlank() }.map { Uri.decode(it) }
                // Each path segment is route_id-socket-transport; only route_id may itself contain dashes
                val routeNames = (uri.fragment ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                val routes = pathParts.mapIndexed { index, segment ->
                    val parts = segment.split("-")
                    val routeId: String
                    val socket: String
                    val transport: String?
                    if (parts.size >= 3) {
                        transport = parts.last().ifBlank { null }
                        socket = parts[parts.size - 2]
                        routeId = parts.subList(0, parts.size - 2).joinToString("-")
                    } else {
                        routeId = segment
                        socket = "udp"
                        transport = null
                    }
                    TurnableRoute(
                        routeId = routeId,
                        name = routeNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: routeId,
                        socket = socket,
                        transport = transport
                    )
                }
                current.copy(
                    userUuid = userUuid ?: current.userUuid,
                    callId = callId.ifBlank { current.callId },
                    platformId = uri.host ?: current.platformId,
                    type = uri.getQueryParameter("type") ?: current.type,
                    encryption = uri.getQueryParameter("encryption") ?: current.encryption,
                    pubKey = uri.getQueryParameter("pub_key") ?: current.pubKey,
                    peers = uri.getQueryParameter("peers")?.toIntOrNull() ?: current.peers,
                    gateway = uri.getQueryParameter("gateway") ?: current.gateway,
                    proto = uri.getQueryParameter("proto") ?: current.proto,
                    cloak = uri.getQueryParameter("cloak") ?: current.cloak,
                    routes = routes.ifEmpty { current.routes },
                    selectedRouteId = uri.getQueryParameter("selected_route_id")
                        ?: (if (routes.isNotEmpty()) routes.firstOrNull()?.routeId ?: "" else current.selectedRouteId)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
