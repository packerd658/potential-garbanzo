package io.github.homepanel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.homepanel.databinding.FragmentStatusBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Connection details, Tailscale, server info and anything unavailable. */
class StatusFragment : Fragment(R.layout.fragment_status) {
    private var _b: FragmentStatusBinding? = null
    private lateinit var connectivity: Connectivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentStatusBinding.bind(view).also { _b = it }
        connectivity = Connectivity(requireContext())

        b.reconnect.setOnClickListener { HaClient.reconnect() }
        b.connectVia.setOnClickListener { chooseMode() }
        b.openTailscale.setOnClickListener {
            try {
                startActivity(connectivity.tailscaleIntent())
            } catch (_: ActivityNotFoundException) {
            }
        }
        b.openDashboard.setOnClickListener { startActivity(Intent(requireContext(), DashboardActivity::class.java)) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(HaClient.status, HaClient.server, HaClient.entities) { _, _, _ -> }.collect { render() }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        if (!hidden) render()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun render() {
        val b = _b ?: return
        val ctx = requireContext()
        val settings = AppSettings.load(ctx)

        when (val st = HaClient.status.value) {
            is HaClient.Status.Connected -> {
                b.connTitle.text = getString(R.string.connected_via, getString(st.route.labelRes))
                b.connDetail.text = st.baseUrl + "\n" + getString(R.string.pref_mode) + ": " +
                    resources.getStringArray(R.array.mode_entries)[settings.mode.ordinal]
            }
            HaClient.Status.Connecting, HaClient.Status.Idle -> {
                b.connTitle.setText(R.string.status_connecting)
                b.connDetail.text = ""
            }
            HaClient.Status.NeedsLogin -> {
                b.connTitle.setText(R.string.log_in)
                b.connDetail.setText(R.string.status_login)
            }
            HaClient.Status.NotConfigured -> {
                b.connTitle.setText(R.string.not_connected)
                b.connDetail.setText(R.string.not_configured)
            }
            is HaClient.Status.Error -> {
                b.connTitle.setText(R.string.not_connected)
                b.connDetail.text = st.message
            }
        }

        b.tailscaleState.setText(
            when {
                connectivity.isTailscaleActive() -> R.string.tailscale_on
                connectivity.isTailscaleInstalled() -> R.string.tailscale_off
                else -> R.string.tailscale_not_installed
            },
        )
        b.openTailscale.setText(if (connectivity.isTailscaleInstalled()) R.string.open_tailscale else R.string.get_tailscale)

        val server = HaClient.server.value
        val entities = HaClient.entities.value.values
        b.serverName.text = server?.name ?: "—"
        b.serverDetail.text = listOfNotNull(
            server?.version?.takeIf { it.isNotBlank() }?.let { "Home Assistant $it" },
            entities.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.entity_count, it, it) },
        ).joinToString(" · ")

        val unavailable = entities.filter { it.state == "unavailable" && it.domain != "update" }.sortedBy { it.name.lowercase() }
        b.attentionTitle.text = if (unavailable.isEmpty()) getString(R.string.all_good)
        else resources.getQuantityString(R.plurals.unavailable_count, unavailable.size, unavailable.size)
        b.attentionList.text = unavailable.take(12).joinToString("\n") { it.name } +
            if (unavailable.size > 12) "\n…" else ""
    }

    private fun chooseMode() {
        val ctx = requireContext()
        val current = AppSettings.load(ctx).mode
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.connect_via)
            .setSingleChoiceItems(R.array.mode_entries, current.ordinal) { d, i ->
                AppSettings.setMode(ctx, Mode.entries[i]) // HaClient reconnects when the setting changes
                d.dismiss()
            }
            .show()
    }
}
