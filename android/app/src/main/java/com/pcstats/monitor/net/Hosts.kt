package com.pcstats.monitor.net

import com.pcstats.monitor.protocol.USB_HOST
import com.pcstats.monitor.protocol.WS_PORT

enum class Transport { USB, WIFI }

object Hosts {
    /** Normalizes user input like " ws://192.168.1.5:28555/ws " to "192.168.1.5" (port kept only if non-default). */
    fun normalize(input: String): String {
        var h = input.trim()
        h = h.removePrefix("ws://").removePrefix("http://")
        h = h.substringBefore('/')
        if (h.endsWith(":$WS_PORT") && h.count { it == ':' } == 1) h = h.substringBeforeLast(':')
        return h
    }

    fun withPort(ip: String, port: Int?): String =
        if (port == null || port == WS_PORT) ip
        else if (ip.contains(':')) "[$ip]:$port"
        else "$ip:$port"

    fun wsUrl(host: String): String {
        val h = normalize(host)
        val hasPort = h.startsWith("[") && h.contains("]:") || (h.count { it == ':' } == 1)
        val authority = when {
            hasPort -> h
            h.contains(':') -> "[$h]:$WS_PORT" // bare IPv6
            else -> "$h:$WS_PORT"
        }
        return "ws://$authority/ws"
    }

    fun transportOf(host: String): Transport {
        val h = normalize(host)
        return if (h == USB_HOST || h.startsWith("$USB_HOST:") || h == "localhost") Transport.USB else Transport.WIFI
    }
}
