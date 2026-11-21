package com.powerhub.deya

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LoggerDiscovery {
    fun findSolarmanLogger(timeoutMs: Long = 8000): String? {
        val subnets = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
                .mapNotNull { address ->
                    val parts = address.hostAddress?.split(".") ?: return@mapNotNull null
                    if (parts.size != 4) null else parts.take(3).joinToString(".") to parts.last().toIntOrNull()
                }
        }.getOrDefault(emptyList())

        val targets = subnets.flatMap { (prefix, own) ->
            (1..254).filter { it != own }.map { "$prefix.$it" }
        }.distinct()
        if (targets.isEmpty()) return null

        val pool = Executors.newFixedThreadPool(32)
        return try {
            val tasks = targets.map { ip ->
                Callable {
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress(ip, 8899), 400) }
                        ip
                    }.getOrNull()
                }
            }
            pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
                .asSequence()
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .firstOrNull()
        } finally {
            pool.shutdownNow()
        }
    }
}
