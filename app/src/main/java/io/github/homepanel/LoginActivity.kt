package io.github.homepanel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.homepanel.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import java.util.UUID

/** Shows Home Assistant's own login page and catches the redirect that carries the login code. */
class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding
    private val state = UUID.randomUUID().toString()
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        b.toolbar.setNavigationOnClickListener { finish() }

        val base = intent.getStringExtra(EXTRA_BASE) ?: HaClient.pendingBase
        if (base == null) {
            finish()
            return
        }
        val settings = AppSettings.load(this)
        b.toolbar.subtitle = Uri.parse(base).host

        CookieManager.getInstance().setAcceptCookie(true)
        with(b.web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                catchRedirect(request.url, base, settings)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && catchRedirect(Uri.parse(url), base, settings)) view.stopLoading()
                b.progress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                b.progress.isVisible = false
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                finish()
                return true
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (settings.mayTrustSelfSigned(Uri.parse(error.url).host)) handler.proceed() else handler.cancel()
            }
        }
        b.web.loadUrl(Auth.authorizeUrl(base, state))
    }

    private fun catchRedirect(uri: Uri, base: String, settings: AppSettings): Boolean {
        if (!uri.toString().startsWith(Auth.REDIRECT_URI)) return false
        if (handled) return true
        handled = true
        val code = uri.getQueryParameter("code")
        if (code == null || uri.getQueryParameter("state") != state) {
            Toast.makeText(this, R.string.login_failed, Toast.LENGTH_LONG).show()
            finish()
            return true
        }
        b.web.visibility = View.INVISIBLE
        b.progress.isVisible = true
        lifecycleScope.launch {
            try {
                Auth.exchangeCode(this@LoginActivity, settings, base, code)
                HaClient.reconnect()
                setResult(RESULT_OK)
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, getString(R.string.login_failed) + "\n" + e.message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
        return true
    }

    override fun onDestroy() {
        if (::b.isInitialized) runCatching { b.web.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BASE = "base"
    }
}
