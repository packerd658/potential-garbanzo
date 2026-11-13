package io.github.homepanel

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager

/** How the app should pick an address for Home Assistant. */
enum class Mode(val key: String) {
    AUTO("auto"), LOCAL("local"), TAILSCALE("tailscale"), REMOTE("remote");

    companion object {
        fun fromKey(key: String?): Mode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** A concrete way of reaching Home Assistant. */
enum class Route(val labelRes: Int) {
    LOCAL(R.string.route_local),
    TAILSCALE(R.string.route_tailscale),
    REMOTE(R.string.route_remote),
}

data class AppSettings(
    val localUrl: String?,
    val tailscaleUrl: String?,
    val remoteUrl: String?,
    val mode: Mode,
    val allowSelfSigned: Boolean,
) {
    fun urlFor(route: Route): String? = when (route) {
        Route.LOCAL -> localUrl
        Route.TAILSCALE -> tailscaleUrl
        Route.REMOTE -> remoteUrl
    }

    val configuredRoutes: List<Route> get() = Route.entries.filter { urlFor(it) != null }

    val hasAnyUrl: Boolean get() = configuredRoutes.isNotEmpty()

    /** The route whose base URL has the same host and port as [url], if any. */
    fun routeForUrl(url: String?): Route? {
        val uri = url?.let(Uri::parse) ?: return null
        return configuredRoutes.firstOrNull { sameOrigin(Uri.parse(urlFor(it)), uri) }
    }

    /** True when self-signed certificates may be accepted for this host. */
    fun mayTrustSelfSigned(host: String?): Boolean {
        if (!allowSelfSigned || host == null) return false
        return listOf(localUrl, tailscaleUrl).any { Uri.parse(it ?: return@any false).host.equals(host, true) }
    }

    companion object {
        const val KEY_LOCAL = "local_url"
        const val KEY_TAILSCALE = "tailscale_url"
        const val KEY_REMOTE = "remote_url"
        const val KEY_MODE = "mode"
        const val KEY_SELF_SIGNED = "allow_self_signed"
        const val KEY_LAST_PATH = "last_path"

        fun load(context: Context): AppSettings {
            val p = PreferenceManager.getDefaultSharedPreferences(context)
            return AppSettings(
                localUrl = normalizeUrl(p.getString(KEY_LOCAL, null)),
                tailscaleUrl = normalizeUrl(p.getString(KEY_TAILSCALE, null)),
                remoteUrl = normalizeUrl(p.getString(KEY_REMOTE, null)),
                mode = Mode.fromKey(p.getString(KEY_MODE, null)),
                allowSelfSigned = p.getBoolean(KEY_SELF_SIGNED, false),
            )
        }

        fun setMode(context: Context, mode: Mode) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY_MODE, mode.key).apply()
        }

        /** Trims, adds http:// when no scheme was typed, and drops trailing slashes. */
        fun normalizeUrl(raw: String?): String? {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            s = s.trimEnd('/')
            val uri = Uri.parse(s)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
            return s
        }

        fun sameOrigin(a: Uri, b: Uri): Boolean =
            a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

        private fun effectivePort(u: Uri): Int =
            if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
    }
}
