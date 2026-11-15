package com.example.lumipos.sync

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds the tablet's LAN address so the master can show clients where to connect. */
object NetworkUtils {

    /** First usable site-local IPv4, preferring 192.168.x.x over other private ranges. */
    fun localIpv4(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: return@forEach
                        val priority = when {
                            host.startsWith("192.168.") -> 0
                            host.startsWith("10.") -> 1
                            host.startsWith("172.") -> 2
                            else -> 3
                        }
                        candidates.add(priority to host)
                    }
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
