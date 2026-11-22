package com.vidgrab.app.transfer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Détection Wi-Fi / Hotspot sans permission de localisation + raccourcis système natifs. */
object LocalNetHelper {

    data class NetInfo(
        val active: Boolean,
        val primaryIp: String,
        val broadcasts: List<InetAddress>,
        val gatewayIp: String?
    )

    fun inspect(ctx: Context): NetInfo {
        var primary: String? = null
        val broadcasts = LinkedHashSet<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val n = nif.name.lowercase()
                val isLocalWifiOrAp = n.startsWith("wlan") || n.startsWith("ap") ||
                    n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wifi") || n.startsWith("eth")
                if (!isLocalWifiOrAp) continue

                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (primary == null || n.startsWith("ap") || n.startsWith("swlan")) {
                            primary = ip
                        }
                        ia.broadcast?.let { broadcasts.add(it) }
                    }
                }
            }
            runCatching { broadcasts.add(InetAddress.getByName("255.255.255.255")) }
        }
        return NetInfo(
            active = primary != null,
            primaryIp = primary.orEmpty(),
            broadcasts = broadcasts.toList(),
            gatewayIp = findGatewayIp(ctx)
        )
    }

    /** Récupère la passerelle DHCP (utile quand l'émetteur est le Hotspot et bloque le broadcast UDP). */
    private fun findGatewayIp(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (route in lp.routes) {
            val gw = route.gateway
            if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                return gw.hostAddress
            }
        }
        null
    }.getOrNull()

    /** Ouvre le panneau flottant Wi-Fi Android 10+ directement par-dessus VidGrab. */
    fun openWifiPanel(ctx: Context) {
        val intent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Ouvre les réglages Point d'accès / Partage de connexion en 1 clic. */
    fun openHotspotSettings(ctx: Context) {
        val tether = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(tether) }.onFailure {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
