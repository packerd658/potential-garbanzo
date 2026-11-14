package io.github.homepanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.homepanel.databinding.FragmentDevicesBinding
import io.github.homepanel.databinding.ItemDeviceRowBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DevicesFragment : Fragment(R.layout.fragment_devices) {
    private var _b: FragmentDevicesBinding? = null
    private lateinit var adapter: DeviceAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDevicesBinding.bind(view).also { _b = it }
        adapter = DeviceAdapter(this)
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.search.doAfterTextChanged { render() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(HaClient.entities, Favourites.ids) { _, _ -> }.collect { render() }
            }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun render() {
        val b = _b ?: return
        val ctx = requireContext()
        val query = b.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val byDomain = HaClient.entities.value.values
            .filter { it.domain in Domains.DEVICE_DOMAINS }
            .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.id.contains(query) }
            .groupBy { it.domain }
        val rows = buildList {
            for (domain in Domains.DEVICE_DOMAINS) {
                val list = byDomain[domain] ?: continue
                add(Row.Header(ctx.getString(Domains.title(domain))))
                list.sortedBy { it.name.lowercase() }.forEach {
                    add(Row.Device(it, Domains.stateLabel(ctx, it), Favourites.contains(it.id)))
                }
            }
        }
        adapter.submitList(rows)
        b.empty.isVisible = rows.isEmpty()
    }

    sealed interface Row {
        val key: String
        data class Header(val title: String) : Row {
            override val key get() = "h:$title"
        }
        data class Device(val e: Entity, val label: String, val favourite: Boolean) : Row {
            override val key get() = e.id
            val content get() = "${e.state}|$label|$favourite|${e.name}|${e.available}"
        }
    }

    class DeviceAdapter(private val f: Fragment) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {
        class HeaderVH(val v: TextView) : RecyclerView.ViewHolder(v)
        class DeviceVH(val b: ItemDeviceRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_header, parent, false) as TextView)
            else DeviceVH(ItemDeviceRowBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Header -> (holder as HeaderVH).v.text = row.title
                is Row.Device -> with((holder as DeviceVH).b) {
                    val e = row.e
                    name.text = e.name
                    state.text = row.label
                    Ui.styleIcon(iconBg, icon, e)
                    Ui.styleStar(star, e.id)
                    star.setOnClickListener { Favourites.toggle(f.requireContext(), e.id) }

                    val hasSwitch = Domains.hasSwitch(e)
                    toggle.isVisible = hasSwitch
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = e.state == "on"
                    toggle.isEnabled = e.available
                    toggle.setOnCheckedChangeListener { _, checked ->
                        Ui.call(f, e.domain, if (checked) "turn_on" else "turn_off", e.id)
                    }
                    root.setOnClickListener { Ui.onEntityTapped(f, it, e) }
                }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
                override fun areContentsTheSame(a: Row, b: Row) = when {
                    a is Row.Device && b is Row.Device -> a.content == b.content
                    else -> a == b
                }
            }
        }
    }
}
