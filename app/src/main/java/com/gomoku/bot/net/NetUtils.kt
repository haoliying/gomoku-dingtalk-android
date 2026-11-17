package com.gomoku.bot.net

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** 获取本机局域网 IPv4 地址，优先返回常见的私网地址。 */
    fun localIpAddress(): String? {
        return try {
            var fallback: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val ip = address.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivate172(ip)) {
                        return ip
                    }
                    if (fallback == null && !ip.startsWith("169.254.")) fallback = ip
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivate172(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
