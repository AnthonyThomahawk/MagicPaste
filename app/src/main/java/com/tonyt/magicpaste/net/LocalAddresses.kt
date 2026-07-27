package com.tonyt.magicpaste.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Where other devices on the network can reach this one. */
object LocalAddresses {

    /**
     * IPv4 addresses of every up, non-loopback interface, Wi-Fi first.
     *
     * There is usually exactly one that matters (`wlan0`), but tethering, VPNs
     * and USB reverse-tethering all add their own, and any of them can be the
     * one the other device is actually on — so show them all rather than guess.
     */
    fun candidates(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { interfacePriority(it.name) }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLinkLocalAddress }
                    .map { it.hostAddress ?: "" }
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** `http://…:port` for each address in [candidates]. */
    fun urls(port: Int): List<String> = candidates().map { "http://$it:$port" }

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("ap") -> 2 // hotspot
        else -> 3
    }
}
