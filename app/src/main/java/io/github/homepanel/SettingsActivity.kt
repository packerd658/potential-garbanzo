package io.github.homepanel

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var connectivity: Connectivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            connectivity = Connectivity(requireContext())

            for (key in listOf(AppSettings.KEY_LOCAL, AppSettings.KEY_TAILSCALE, AppSettings.KEY_REMOTE)) {
                findPreference<EditTextPreference>(key)?.apply {
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        it.setSelection(it.text.length)
                    }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                        AppSettings.normalizeUrl(p.text) ?: getString(R.string.pref_not_set)
                    }
                    setOnPreferenceChangeListener { _, value ->
                        // Store the cleaned-up form (scheme added, trailing slash removed).
                        val normalized = AppSettings.normalizeUrl(value as String?)
                        text = normalized.orEmpty()
                        false
                    }
                }
            }

            findPreference<Preference>("tailscale_status")?.setOnPreferenceClickListener {
                try {
                    startActivity(connectivity.tailscaleIntent())
                } catch (_: ActivityNotFoundException) {
                }
                true
            }

            findPreference<Preference>("test")?.setOnPreferenceClickListener {
                runTest()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            findPreference<Preference>("tailscale_status")?.setSummary(
                when {
                    connectivity.isTailscaleActive() -> R.string.tailscale_connected
                    connectivity.isTailscaleInstalled() -> R.string.tailscale_disconnected
                    else -> R.string.tailscale_missing
                },
            )
        }

        private fun runTest() {
            val settings = AppSettings.load(requireContext())
            if (!settings.hasAnyUrl) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.test_results)
                    .setMessage(R.string.not_configured)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.test_results)
                .setMessage(R.string.testing)
                .setPositiveButton(R.string.ok, null)
                .show()
            viewLifecycleOwner.lifecycleScope.launch {
                val jobs = settings.configuredRoutes.map { route ->
                    val url = settings.urlFor(route)!!
                    route to async { connectivity.probe(url, settings.mayTrustSelfSigned(android.net.Uri.parse(url).host)) }
                }
                val lines = jobs.map { (route, job) ->
                    val err = job.await()
                    val status = if (err == null) "✓ ${getString(R.string.reachable)}" else "✗ ${getString(R.string.unreachable)} ($err)"
                    "${getString(route.labelRes)} — ${settings.urlFor(route)}\n$status"
                }
                val ts = getString(R.string.pref_tailscale_status) + ": " + getString(
                    if (connectivity.isTailscaleActive()) R.string.tailscale_connected else R.string.tailscale_disconnected,
                ).substringBefore(" —")
                dialog.setMessage((lines + ts).joinToString("\n\n"))
            }
        }
    }
}
