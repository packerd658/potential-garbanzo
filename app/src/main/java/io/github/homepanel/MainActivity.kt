package io.github.homepanel

import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import io.github.homepanel.databinding.ActivityMainBinding

/** The app shell: five tabs along the bottom, as in the design. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var askedLocalPermission = false

    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.local_permission_denied, Toast.LENGTH_LONG).show()
        HaClient.reconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        HaClient.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.content.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            b.nav.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        b.nav.setOnItemSelectedListener { showTab(it.itemId); true }
        b.nav.setOnItemReselectedListener { }
        // Home starts selected, so selecting it again would only fire the "reselected" listener.
        showTab(b.nav.selectedItemId)
    }

    fun selectTab(id: Int) {
        b.nav.selectedItemId = id
    }

    private fun showTab(id: Int) {
        val fm = supportFragmentManager
        val tag = "tab_$id"
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.fragments.filter { it.tag?.startsWith("tab_") == true && it.tag != tag }.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.content, newTab(id), tag)
        tx.commit()
    }

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.tab_devices -> DevicesFragment()
        R.id.tab_cameras -> CamerasFragment()
        R.id.tab_status -> StatusFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    override fun onStart() {
        super.onStart()
        if (!maybeRequestLocalNetworkPermission()) HaClient.start()
        registerNetworkCallbacks()
    }

    override fun onStop() {
        unregisterNetworkCallbacks()
        HaClient.stop()
        super.onStop()
    }

    /** Android 17 asks before an app may talk to devices on the home network. */
    private fun maybeRequestLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37 || askedLocalPermission || AppSettings.load(this).localUrl == null) return false
        if (ContextCompat.checkSelfPermission(this, PERMISSION_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return false
        askedLocalPermission = true
        HaClient.start()
        localNetworkPermission.launch(PERMISSION_LOCAL_NETWORK)
        return true
    }

    private fun registerNetworkCallbacks() {
        if (callbacks.isNotEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java)
        fun cb() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = HaClient.onNetworkChanged()
            override fun onLost(network: Network) = HaClient.onNetworkChanged()
        }
        runCatching {
            cb().also { cm.registerDefaultNetworkCallback(it); callbacks += it }
            // Tailscale coming and going.
            val vpn = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cb().also { cm.registerNetworkCallback(vpn, it); callbacks += it }
        }
    }

    private fun unregisterNetworkCallbacks() {
        val cm = getSystemService(ConnectivityManager::class.java)
        callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        callbacks.clear()
    }

    companion object {
        private const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
