package com.tonyt.magicpaste.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Answers mDNS queries for a single `.local` hostname, so other devices can open
 * `http://magicpaste.local:8123` instead of hunting down an IP address.
 *
 * Android's [android.net.nsd.NsdManager] can only advertise *service instances*
 * — it offers no way to make a hostname of our choosing resolvable — so this
 * speaks the protocol directly: join `224.0.0.251:5353`, wait for A questions
 * that name us, and answer with this device's addresses. That subset is enough
 * for the resolvers that matter here (macOS/iOS, Windows, Linux with Avahi,
 * Android itself).
 */
class MdnsResponder(context: Context, hostname: String) {

    /** The advertised name, normalised to lowercase without a trailing dot. */
    val hostname: String = hostname.trim().trimEnd('.').lowercase()

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /**
     * Joins the multicast group and starts answering. Throws when the socket
     * cannot be opened — the caller decides whether that sinks the server or
     * just loses the friendly name.
     */
    fun start() {
        check(socket == null) { "already started" }

        // Wi-Fi radios drop multicast unless an app is holding a lock; without
        // this the queries never reach us on most devices.
        multicastLock = wifi?.createMulticastLock("magicpaste-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val opened = MulticastSocket(MDNS_PORT).apply {
            reuseAddress = true
            joinGroup(InetSocketAddress(group, MDNS_PORT), null)
        }
        socket = opened
        running = true

        thread = Thread({ receiveLoop(opened) }, "magicpaste-mdns").apply {
            isDaemon = true
            start()
        }

        // Unsolicited announcements populate caches before anyone asks; sent
        // twice because the first one races the group join on some stacks.
        announce(opened, ttl = RECORD_TTL_SECONDS)
        announce(opened, ttl = RECORD_TTL_SECONDS)
    }

    /** Says goodbye (TTL 0 flushes remote caches) and releases everything. */
    fun stop() {
        running = false
        socket?.let { open ->
            runCatching { announce(open, ttl = 0) }
            runCatching { open.close() }
        }
        socket = null
        thread = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(MAX_PACKET)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                handle(socket, packet)
            } catch (failure: Exception) {
                if (running) Log.w(TAG, "mDNS receive failed", failure)
                if (socket.isClosed) return
            }
        }
    }

    private fun handle(socket: MulticastSocket, packet: DatagramPacket) {
        val message = packet.data.copyOf(packet.length)
        if (message.size < HEADER_SIZE) return

        val flags = message.readShort(2)
        if (flags and QUERY_RESPONSE_BIT != 0) return // a response, not a question

        val id = message.readShort(0)
        val questionCount = message.readShort(4)
        var offset = HEADER_SIZE
        var asked = false

        repeat(questionCount) {
            val (name, next) = readName(message, offset) ?: return
            if (next + 4 > message.size) return
            val type = message.readShort(next)
            offset = next + 4
            if (name.equals(hostname, ignoreCase = true) &&
                (type == TYPE_A || type == TYPE_ANY)
            ) {
                asked = true
            }
        }
        if (!asked) return

        // A query from a port other than 5353 is a legacy one-shot resolver: it
        // needs a unicast reply that echoes its ID. Everyone else listens on the
        // group, where the ID must be zero.
        val legacy = packet.port != MDNS_PORT
        val answer = response(
            id = if (legacy) id else 0,
            addresses = addressesFor(packet.address),
            ttl = RECORD_TTL_SECONDS,
        ) ?: return
        val destination = if (legacy) {
            InetSocketAddress(packet.address, packet.port)
        } else {
            InetSocketAddress(group, MDNS_PORT)
        }
        socket.send(DatagramPacket(answer, answer.size, destination))
    }

    private fun announce(socket: MulticastSocket, ttl: Int) {
        val message = response(id = 0, addresses = addressesFor(source = null), ttl = ttl) ?: return
        socket.send(DatagramPacket(message, message.size, InetSocketAddress(group, MDNS_PORT)))
    }

    /**
     * The addresses to answer with: the one sharing a subnet with [source] when
     * we can tell — a device with Wi-Fi plus a hotspot has several, and only one
     * is reachable from any given querier — otherwise all of them.
     */
    private fun addressesFor(source: InetAddress?): List<Inet4Address> {
        val local = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.asSequence() }
                .filter { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                .filter { it.networkPrefixLength in 1..31 }
                .toList()
        }.getOrDefault(emptyList())

        if (source is Inet4Address) {
            val sameSubnet = local.filter {
                sharesPrefix(it.address as Inet4Address, source, it.networkPrefixLength.toInt())
            }
            if (sameSubnet.isNotEmpty()) return sameSubnet.map { it.address as Inet4Address }
        }
        return local.map { it.address as Inet4Address }
    }

    private fun sharesPrefix(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        val left = a.address
        val right = b.address
        var remaining = prefixLength
        for (index in left.indices) {
            if (remaining <= 0) return true
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((left[index].toInt() and mask) != (right[index].toInt() and mask)) return false
            remaining -= 8
        }
        return true
    }

    // --- DNS wire format ---------------------------------------------------

    /** An authoritative response carrying one A record per address. */
    private fun response(id: Int, addresses: List<Inet4Address>, ttl: Int): ByteArray? {
        if (addresses.isEmpty()) return null
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(id)
            writeShort(RESPONSE_FLAGS)
            writeShort(0) // questions
            writeShort(addresses.size) // answers
            writeShort(0) // authority
            writeShort(0) // additional
            for (address in addresses) {
                writeName(this, hostname)
                writeShort(TYPE_A)
                // IN, with the cache-flush bit: these records replace anything
                // a resolver previously cached under this name.
                writeShort(CLASS_IN or CACHE_FLUSH_BIT)
                writeInt(ttl)
                writeShort(4)
                write(address.address)
            }
        }
        return bytes.toByteArray()
    }

    private fun writeName(out: DataOutputStream, name: String) {
        for (label in name.split('.')) {
            val encoded = label.toByteArray(Charsets.UTF_8)
            out.writeByte(encoded.size)
            out.write(encoded)
        }
        out.writeByte(0)
    }

    /**
     * Decodes a possibly-compressed name at [offset], returning it with the
     * offset just past it in the original stream, or null on a malformed packet.
     */
    private fun readName(message: ByteArray, offset: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var position = offset
        var next = -1 // set once the first compression pointer is followed
        var jumps = 0

        while (true) {
            if (position >= message.size || jumps > MAX_POINTER_JUMPS) return null
            val length = message[position].toInt() and 0xFF
            when {
                length == 0 -> {
                    val after = if (next >= 0) next else position + 1
                    return labels.joinToString(".") to after
                }

                length and POINTER_MASK == POINTER_MASK -> {
                    if (position + 1 >= message.size) return null
                    if (next < 0) next = position + 2
                    position = ((length and 0x3F) shl 8) or (message[position + 1].toInt() and 0xFF)
                    jumps++
                }

                else -> {
                    if (position + 1 + length > message.size) return null
                    labels += String(message, position + 1, length, Charsets.UTF_8)
                    position += 1 + length
                }
            }
        }
    }

    private fun ByteArray.readShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private companion object {
        const val TAG = "MdnsResponder"

        const val MDNS_PORT = 5353
        val group: InetAddress = InetAddress.getByName("224.0.0.251")

        const val MAX_PACKET = 9000
        const val HEADER_SIZE = 12
        const val MAX_POINTER_JUMPS = 8
        const val POINTER_MASK = 0xC0

        const val QUERY_RESPONSE_BIT = 0x8000
        const val RESPONSE_FLAGS = 0x8400 // QR + AA
        const val TYPE_A = 1
        const val TYPE_ANY = 255
        const val CLASS_IN = 1
        const val CACHE_FLUSH_BIT = 0x8000

        const val RECORD_TTL_SECONDS = 120
    }
}
