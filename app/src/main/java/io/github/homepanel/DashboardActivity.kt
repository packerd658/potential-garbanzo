package io.github.homepanel

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: MaterialButton
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView

    private lateinit var connectivity: Connectivity
    private lateinit var settings: AppSettings

    private var currentRoute: Route? = null
    private var currentBase: String? = null
    private var resolveJob: Job? = null
    private var networkJob: Job? = null
    private var networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var askedLocalPermission = false
    private val failedRoutes = mutableSetOf<Route>()
    private var webDestroyed = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.local_permission_denied, Toast.LENGTH_LONG).show()
        connect(keepPath = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        connectivity = Connectivity(this)
        settings = AppSettings.load(this)

        web = findViewById(R.id.web)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.banner_text)
        bannerButton = findViewById(R.id.banner_button)
        errorView = findViewById(R.id.error)
        errorText = findViewById(R.id.error_text)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        toolbar.inflateMenu(R.menu.main)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reconnect -> connect(keepPath = true)
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.mode_auto -> changeMode(Mode.AUTO)
                R.id.mode_local -> changeMode(Mode.LOCAL)
                R.id.mode_tailscale -> changeMode(Mode.TAILSCALE)
                R.id.mode_remote -> changeMode(Mode.REMOTE)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        bannerButton.setOnClickListener { openTailscale() }
        findViewById<View>(R.id.error_retry).setOnClickListener { connect(keepPath = true) }
        findViewById<View>(R.id.error_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            currentRoute = savedInstanceState.getString(STATE_ROUTE)?.let { runCatching { Route.valueOf(it) }.getOrNull() }
            currentBase = currentRoute?.let { settings.urlFor(it) }
            updateToolbar()
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallbacks()
    }

    override fun onResume() {
        super.onResume()
        val fresh = AppSettings.load(this)
        val changed = fresh != settings
        settings = fresh
        updateModeMenu()
        updateTailscaleBanner()
        when {
            maybeRequestLocalNetworkPermission() -> Unit // connect() runs after the user answers
            changed || currentBase == null -> connect(keepPath = true)
        }
    }

    override fun onStop() {
        unregisterNetworkCallbacks()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webDestroyed) web.saveState(outState)
        currentRoute?.let { outState.putString(STATE_ROUTE, it.name) }
    }

    override fun onDestroy() {
        if (!webDestroyed) web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- connecting

    /**
     * Picks an address and loads it. With [keepPath], the page currently open (e.g. /lovelace/kitchen)
     * is reopened on the new address.
     */
    private fun connect(keepPath: Boolean, exclude: Set<Route> = emptySet()) {
        if (exclude.isEmpty()) failedRoutes.clear()
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            showLoading(true)
            hideError()
            updateTailscaleBanner()
            when (val r = connectivity.resolve(settings, exclude)) {
                Resolution.NotConfigured -> {
                    showLoading(false)
                    showError(getString(R.string.not_configured))
                }
                is Resolution.Unreachable -> {
                    showLoading(false)
                    showError(getString(R.string.no_route, r.details))
                }
                is Resolution.Found -> load(r.route, r.baseUrl, keepPath)
            }
        }
    }

    private fun load(route: Route, base: String, keepPath: Boolean) {
        val path = if (keepPath) currentPath() else null
        val previous = currentRoute
        currentRoute = route
        currentBase = base
        updateToolbar()
        updateTailscaleBanner()
        if (previous != null && previous != route) {
            Toast.makeText(this, getString(R.string.switched_to, getString(route.labelRes)), Toast.LENGTH_SHORT).show()
        }
        val target = base + (path ?: "")
        if (web.url != target) web.loadUrl(target)
    }

    /** Path + query of the page currently shown, if it belongs to one of the configured servers. */
    private fun currentPath(): String? {
        val url = web.url ?: return null
        if (settings.routeForUrl(url) == null) return null
        val uri = Uri.parse(url)
        val path = (uri.encodedPath ?: "") + (uri.encodedQuery?.let { "?$it" } ?: "")
        // Don't carry auth callback URLs (they hold one-time codes) to a different server.
        if (path.isEmpty() || path == "/" || path.startsWith("/auth/")) return null
        return path
    }

    private fun changeMode(mode: Mode) {
        AppSettings.setMode(this, mode)
        settings = settings.copy(mode = mode)
        updateModeMenu()
        connect(keepPath = true)
    }

    // ---------------------------------------------------------------- network changes

    private fun registerNetworkCallbacks() {
        if (networkCallbacks.isNotEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
        }
        val vpnCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallbacks += cb
            // Watch VPNs (Tailscale) coming and going, too.
            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(vpnRequest, vpnCb)
            networkCallbacks += vpnCb
        } catch (_: Exception) {
            // Not fatal: the user can still reconnect manually.
        }
    }

    private fun unregisterNetworkCallbacks() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallbacks.clear()
    }

    /** Called on a binder thread; debounced, then re-picks the best address in automatic mode. */
    private fun onNetworkChanged() {
        lifecycleScope.launch {
            networkJob?.cancel()
            networkJob = launch {
                delay(NETWORK_SETTLE_MS)
                updateTailscaleBanner()
                if (settings.mode != Mode.AUTO || currentBase == null) return@launch
                val r = connectivity.resolve(settings)
                if (r is Resolution.Found && r.baseUrl != currentBase) {
                    load(r.route, r.baseUrl, keepPath = true)
                } else if (errorView.isVisible) {
                    connect(keepPath = true)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Android 17 local network

    /**
     * Android 17 (API 37) asks the user before an app may talk to devices on the local network.
     * Returns true if the permission prompt was shown.
     */
    private fun maybeRequestLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37 || askedLocalPermission || settings.localUrl == null) return false
        if (ContextCompat.checkSelfPermission(this, PERMISSION_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        askedLocalPermission = true
        localNetworkPermission.launch(PERMISSION_LOCAL_NETWORK)
        return true
    }

    // ---------------------------------------------------------------- Tailscale

    private fun updateTailscaleBanner() {
        val wantsTailscale = settings.tailscaleUrl != null &&
            (settings.mode == Mode.TAILSCALE || (settings.mode == Mode.AUTO && errorView.isVisible))
        val show = wantsTailscale && !connectivity.isTailscaleActive()
        banner.isVisible = show
        if (show) {
            bannerText.setText(R.string.tailscale_not_connected)
            bannerButton.setText(if (connectivity.isTailscaleInstalled()) R.string.open_tailscale else R.string.get_tailscale)
        }
    }

    private fun openTailscale() {
        try {
            startActivity(connectivity.tailscaleIntent())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.get_tailscale, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private fun updateToolbar() {
        val route = currentRoute
        toolbar.subtitle = when {
            route == null -> null
            settings.mode == Mode.AUTO -> "${getString(route.labelRes)} · ${getString(R.string.mode_auto)}"
            else -> getString(route.labelRes)
        }
    }

    private fun updateModeMenu() {
        val id = when (settings.mode) {
            Mode.AUTO -> R.id.mode_auto
            Mode.LOCAL -> R.id.mode_local
            Mode.TAILSCALE -> R.id.mode_tailscale
            Mode.REMOTE -> R.id.mode_remote
        }
        toolbar.menu.findItem(id)?.isChecked = true
        toolbar.menu.findItem(R.id.mode_local)?.isEnabled = settings.localUrl != null
        toolbar.menu.findItem(R.id.mode_tailscale)?.isEnabled = settings.tailscaleUrl != null
        toolbar.menu.findItem(R.id.mode_remote)?.isEnabled = settings.remoteUrl != null
        updateToolbar()
    }

    private fun showLoading(show: Boolean) {
        progress.isVisible = show
    }

    private fun showError(message: String) {
        errorText.text = message
        errorView.isVisible = true
        updateTailscaleBanner()
    }

    private fun hideError() {
        errorView.isVisible = false
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString HomePanel/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    // Our own servers stay in the app; everything else opens in the browser.
                    if (settings.routeForUrl(uri.toString()) != null) return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                showLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                showLoading(false)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                showLoading(false)
                val failed = currentRoute
                // In automatic mode, fall back to the next address before showing an error.
                if (failed != null) failedRoutes += failed
                if (settings.mode == Mode.AUTO && settings.configuredRoutes.any { it !in failedRoutes }) {
                    connect(keepPath = true, exclude = failedRoutes.toSet())
                } else {
                    showError(getString(R.string.load_error, request.url.toString(), error.description))
                }
            }

            // The WebView renderer can be killed under memory pressure. Rebuild the screen instead of crashing.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                webDestroyed = true
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                recreate()
                return true
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = Uri.parse(error.url).host
                if (settings.mayTrustSelfSigned(host)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    showLoading(false)
                    showError(getString(R.string.ssl_error, host ?: error.url))
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(fileChooserParams.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    companion object {
        private const val STATE_ROUTE = "route"
        private const val NETWORK_SETTLE_MS = 1500L
        private const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
