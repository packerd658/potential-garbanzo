package io.github.homepanel

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Shared OkHttp clients: a normal one, and one that accepts self-signed certificates. */
object Http {
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private val trustAll: OkHttpClient by lazy {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        default.newBuilder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** The client to use for [baseUrl]: self-signed certificates only where the user allowed them. */
    fun clientFor(settings: AppSettings, baseUrl: String): OkHttpClient =
        if (settings.mayTrustSelfSigned(Uri.parse(baseUrl).host)) trustAll else default
}

/**
 * Sign-in with Home Assistant's own login page (OAuth2 / IndieAuth).
 *
 * Home Assistant accepts any URL as the client ID, and allows a redirect URI on the same host
 * without fetching anything, so the app uses its project site for both. The login WebView
 * catches the redirect, so the browser never actually opens it.
 */
object Auth {
    const val CLIENT_ID = "https://packerd658.github.io/potential-garbanzo/"
    const val REDIRECT_URI = "https://packerd658.github.io/potential-garbanzo/auth-callback"

    private const val KEY_ACCESS = "auth_access_token"
    private const val KEY_REFRESH = "auth_refresh_token"
    private const val KEY_EXPIRES = "auth_expires_at"
    const val KEY_LONG_LIVED = "long_lived_token"

    class LoginRequired : Exception("Login required")

    fun authorizeUrl(base: String, state: String): String =
        Uri.parse("$base/auth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .build().toString()

    fun isLoggedIn(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(KEY_LONG_LIVED, null).isNullOrBlank() || p.getString(KEY_REFRESH, null) != null
    }

    fun usesLongLivedToken(context: Context): Boolean = !prefs(context).getString(KEY_LONG_LIVED, null).isNullOrBlank()

    /**
     * Returns an access token that is valid right now, refreshing it if needed.
     * Throws [LoginRequired] when the user has to sign in, or [IOException] when the server can't be reached.
     */
    suspend fun accessToken(context: Context, settings: AppSettings, base: String, forceRefresh: Boolean = false): String {
        val p = prefs(context)
        p.getString(KEY_LONG_LIVED, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val refresh = p.getString(KEY_REFRESH, null) ?: throw LoginRequired()
        val access = p.getString(KEY_ACCESS, null)
        if (!forceRefresh && access != null && System.currentTimeMillis() < p.getLong(KEY_EXPIRES, 0) - 60_000) return access

        val json = postToken(
            settings, base,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refresh)
                .build(),
        ) ?: run {
            // The server rejected the refresh token (revoked or expired): sign in again.
            clear(context)
            throw LoginRequired()
        }
        save(context, json, keepRefresh = refresh)
        return json.getString("access_token")
    }

    /** Swaps the one-time code from the login page for tokens. */
    suspend fun exchangeCode(context: Context, settings: AppSettings, base: String, code: String) {
        val json = postToken(
            settings, base,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .build(),
        ) ?: throw IOException("Home Assistant rejected the login code")
        save(context, json, keepRefresh = null)
    }

    /** Forgets the tokens and asks the server to revoke them. */
    suspend fun logout(context: Context, settings: AppSettings, base: String?) {
        val refresh = prefs(context).getString(KEY_REFRESH, null)
        clear(context)
        prefs(context).edit { remove(KEY_LONG_LIVED) }
        if (refresh != null && base != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    Http.clientFor(settings, base).newCall(
                        Request.Builder().url("$base/auth/revoke")
                            .post(FormBody.Builder().add("token", refresh).build()).build(),
                    ).execute().close()
                }
            }
        }
    }

    private suspend fun postToken(settings: AppSettings, base: String, body: FormBody): JSONObject? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/auth/token").post(body).build()
            Http.clientFor(settings, base).newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> JSONObject(resp.body.string())
                    resp.code in 400..403 -> null
                    else -> throw IOException("HTTP ${resp.code} from /auth/token")
                }
            }
        }

    private fun save(context: Context, json: JSONObject, keepRefresh: String?) {
        prefs(context).edit {
            putString(KEY_ACCESS, json.getString("access_token"))
            putString(KEY_REFRESH, json.optString("refresh_token").ifEmpty { keepRefresh })
            putLong(KEY_EXPIRES, System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000)
        }
    }

    private fun clear(context: Context) {
        prefs(context).edit {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_EXPIRES)
        }
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
}
