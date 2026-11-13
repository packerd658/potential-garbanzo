package io.github.homepanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Outcome of picking an address to connect to. */
sealed interface Resolution {
    data class Found(val route: Route, val baseUrl: String) : Resolution
    data class Unreachable(val details: String) : Resolution
    data object NotConfigured : Resolution
}

class Connectivity(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    // ---------------------------------------------------------------- network state

    /** True when the active network is Wi‑Fi or Ethernet (i.e. probably at home). */
    fun isOnLan(): Boolean {
        // When a VPN such as Tailscale is up, the active network is the VPN, so look at all networks.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    /**
     * True when the Tailscale VPN is up on this device. Tailscale gives every device an address in
     * 100.64.0.0/10 (IPv4) and fd7a:115c:a1e0::/48 (IPv6), so a VPN network or tun interface holding
     * one of those is treated as Tailscale.
     */
    fun isTailscaleActive(): Boolean {
        @Suppress("DEPRECATION")
        val viaConnectivity = cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                cm.getLinkProperties(n)?.linkAddresses?.any { isTailscaleAddress(it.address) } == true
        }
        if (viaConnectivity) return true
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif ->
                nif.isUp && nif.inetAddresses.toList().any(::isTailscaleAddress)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isTailscaleInstalled(): Boolean =
        app.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) != null

    /** Opens the Tailscale app, or its store listing if it isn't installed. */
    fun tailscaleIntent(): Intent =
        app.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
                .takeIf { it.resolveActivity(app.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://tailscale.com/download/android"))

    // ---------------------------------------------------------------- route selection

    /**
     * Picks the address to use. In [Mode.AUTO] every configured address is probed at once and the
     * best reachable one wins, in this order of preference:
     *  - Local, when on Wi‑Fi/Ethernet (fastest, works without internet)
     *  - Tailscale, when the Tailscale VPN is up
     *  - Remote
     *  - then anything else configured (e.g. local through a Tailscale subnet router).
     */
    suspend fun resolve(settings: AppSettings, exclude: Set<Route> = emptySet()): Resolution {
        if (!settings.hasAnyUrl) return Resolution.NotConfigured

        if (settings.mode != Mode.AUTO) {
            val route = when (settings.mode) {
                Mode.LOCAL -> Route.LOCAL
                Mode.TAILSCALE -> Route.TAILSCALE
                else -> Route.REMOTE
            }
            val url = settings.urlFor(route) ?: return Resolution.NotConfigured
            // Forced mode: always use it and let the WebView report errors.
            return Resolution.Found(route, url)
        }

        val order = priorityOrder(settings).filter { it !in exclude }
        if (order.isEmpty()) return Resolution.Unreachable("")
        val results = coroutineScope {
            order.associateWith { route ->
                async { probe(settings.urlFor(route)!!, settings.mayTrustSelfSigned(Uri.parse(settings.urlFor(route)).host)) }
            }.mapValues { it.value.await() }
        }
        order.firstOrNull { results[it] == null }?.let { return Resolution.Found(it, settings.urlFor(it)!!) }
        val details = order.joinToString("\n") { "${app.getString(it.labelRes)}: ${results[it]}" }
        return Resolution.Unreachable(details)
    }

    fun priorityOrder(settings: AppSettings): List<Route> {
        val preferred = buildList {
            if (isOnLan()) add(Route.LOCAL)
            if (isTailscaleActive()) add(Route.TAILSCALE)
            add(Route.REMOTE)
            add(Route.TAILSCALE)
            add(Route.LOCAL)
        }.distinct()
        return preferred.filter { settings.urlFor(it) != null }
    }

    /** Returns null when [baseUrl] answers like a Home Assistant server, or a short error otherwise. */
    suspend fun probe(baseUrl: String, trustSelfSigned: Boolean): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$baseUrl/manifest.json").openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            if (trustSelfSigned && conn is HttpsURLConnection) {
                conn.sslSocketFactory = trustAllContext.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            val code = conn.responseCode
            // Any HTTP answer means the server is there; 401/403 still means "reachable".
            if (code in 200..499) null else "HTTP $code"
        } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        private const val PROBE_TIMEOUT_MS = 3500

        fun isTailscaleAddress(a: InetAddress): Boolean = when (a) {
            is Inet4Address -> {
                val b = a.address
                (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            }
            is Inet6Address -> {
                val b = a.address
                b[0] == 0xfd.toByte() && b[1] == 0x7a.toByte() && b[2] == 0x11.toByte() &&
                    b[3] == 0x5c.toByte() && b[4] == 0xa1.toByte() && b[5] == 0xe0.toByte()
            }
            else -> false
        }

        // Used only for probing hosts the user explicitly allowed self-signed certificates for.
        private val trustAllContext: SSLContext by lazy {
            @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        }
    }
}
