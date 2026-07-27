package com.tonyt.magicpaste.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Where other devices on the network can reach this one. */
object LocalAddresses {

    /**
     * IPv4 addresses of every up, non-loopback interface that has a subnet
     * around it, Wi-Fi first.
     *
     * There is usually exactly one that matters (`wlan0`), but tethering, VPNs
     * and USB reverse-tethering all add their own, and any of them can be the
     * one the other device is actually on — so show them all rather than guess.
     *
     * A `/32` address is the exception: it has no subnet, so no other device can
     * ever share it. Vendor virtual adapters look exactly like that — OnePlus
     * ships a `vgate0` for network acceleration — and listing them offers an
     * address nobody can reach.
     */
    fun candidates(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { interfacePriority(it.name) }
            .flatMap { networkInterface ->
                networkInterface.interfaceAddresses.asSequence()
                    .filter { it.networkPrefixLength.toInt() in 1..<HOST_ONLY_PREFIX }
                    .map { it.address }
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

    /** A prefix this long leaves no room for anyone else on the network. */
    private const val HOST_ONLY_PREFIX = 32

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("ap") -> 2 // hotspot
        else -> 3
    }
}
