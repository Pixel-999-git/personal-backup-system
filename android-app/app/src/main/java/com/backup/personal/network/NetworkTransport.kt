package com.backup.personal.network

interface NetworkTransport {
    val transportType: String
    fun buildUrl(host: String, port: Int, path: String): String
    fun isAvailable(): Boolean
}

class DirectLanTransport : NetworkTransport {
    override val transportType: String = "LAN / Direct Wi-Fi"

    override fun buildUrl(host: String, port: Int, path: String): String {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$cleanHost:$port$cleanPath"
    }

    override fun isAvailable(): Boolean = true
}

class TailscaleOverlayTransport : NetworkTransport {
    override val transportType: String = "Tailscale / WireGuard Overlay"

    override fun buildUrl(host: String, port: Int, path: String): String {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$cleanHost:$port$cleanPath"
    }

    override fun isAvailable(): Boolean = true
}

class NetworkTransportProvider {
    companion object {
        fun getTransport(host: String): NetworkTransport {
            return if (host.startsWith("100.") || host.contains(".ts.net")) {
                TailscaleOverlayTransport()
            } else {
                DirectLanTransport()
            }
        }
    }
}
