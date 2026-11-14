package io.github.homepanel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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
                    text = AppSettings.normalizeUrl(value as String?).orEmpty()
                    false
                }
            }
        }

        findPreference<EditTextPreference>(Auth.KEY_LONG_LIVED)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                if (p.text.isNullOrBlank()) getString(R.string.pref_token_summary) else getString(R.string.pref_token_set)
            }
            setOnPreferenceChangeListener { _, _ -> updateAccount(); true }
        }

        findPreference<Preference>("account")?.setOnPreferenceClickListener {
            if (Auth.isLoggedIn(requireContext())) confirmLogout()
            else startActivity(Intent(requireContext(), LoginActivity::class.java))
            true
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

        findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME
    }

    /** In the main app, add the big "Settings" title the other tabs have. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        if (activity !is MainActivity) return list
        val d = resources.displayMetrics.density
        val title = TextView(requireContext()).apply {
            setText(R.string.tab_settings)
            setTextAppearance(R.style.TextAppearance_HomePanel_Title)
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), 0)
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) refresh()
    }

    private fun refresh() {
        updateAccount()
        findPreference<Preference>("tailscale_status")?.setSummary(
            when {
                connectivity.isTailscaleActive() -> R.string.tailscale_connected
                connectivity.isTailscaleInstalled() -> R.string.tailscale_disconnected
                else -> R.string.tailscale_missing
            },
        )
    }

    private fun updateAccount() {
        val ctx = context ?: return
        findPreference<Preference>("account")?.setSummary(
            when {
                Auth.usesLongLivedToken(ctx) -> R.string.account_token
                Auth.isLoggedIn(ctx) -> R.string.account_logged_in
                else -> R.string.account_logged_out
            },
        )
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.confirm_logout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.log_out) { _, _ ->
                val ctx = requireContext().applicationContext
                lifecycleScope.launch {
                    Auth.logout(ctx, AppSettings.load(ctx), HaClient.currentBase ?: HaClient.pendingBase)
                    findPreference<EditTextPreference>(Auth.KEY_LONG_LIVED)?.text = null
                    updateAccount()
                    HaClient.reconnect()
                }
            }
            .show()
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
        lifecycleScope.launch {
            val jobs = settings.configuredRoutes.map { route ->
                val url = settings.urlFor(route)!!
                route to async { connectivity.probe(url, settings.mayTrustSelfSigned(Uri.parse(url).host)) }
            }
            val lines = jobs.map { (route, job) ->
                val err = job.await()
                val status = if (err == null) "✓ ${getString(R.string.reachable)}" else "✗ ${getString(R.string.unreachable)} ($err)"
                "${getString(route.labelRes)} — ${settings.urlFor(route)}\n$status"
            }
            val ts = getString(R.string.pref_tailscale_status) + ": " + getString(
                if (connectivity.isTailscaleActive()) R.string.tailscale_on else R.string.tailscale_off,
            )
            dialog.setMessage((lines + ts).joinToString("\n\n"))
        }
    }
}
