package io.github.homepanel

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.homepanel.databinding.FragmentHomeBinding
import io.github.homepanel.databinding.ItemCameraBinding
import io.github.homepanel.databinding.ItemTileBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private lateinit var tiles: TileAdapter
    private var forecastOpen = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentHomeBinding.bind(view)
        tiles = TileAdapter(this)
        b.favourites.layoutManager = GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (tiles.getItemViewType(position) == TileAdapter.CAMERA) 2 else 1
            }
        }
        b.favourites.adapter = tiles
        b.favourites.itemAnimator = null

        b.edit.setOnClickListener { Ui.chooseFavourites(this) }
        b.chooseFavourites.setOnClickListener { Ui.chooseFavourites(this) }
        b.allDevices.setOnClickListener { (activity as? MainActivity)?.selectTab(R.id.tab_devices) }
        b.weatherCard.setOnClickListener {
            forecastOpen = !forecastOpen
            render()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(HaClient.entities, HaClient.status, HaClient.forecasts, Favourites.ids, HaClient.server) { _, _, _, _, _ -> }
                        .collect { render() }
                }
                launch { CameraImages.images.collect { tiles.notifyItemRangeChanged(0, tiles.itemCount, TileAdapter.IMAGE) } }
                launch {
                    // Refresh pinned camera snapshots every 10 seconds while Home is on screen.
                    while (true) {
                        if (!isHidden) {
                            val cams = Favourites.ids.value.mapNotNull { HaClient.entities.value[it] }.filter { it.domain == "camera" }
                            CameraImages.refresh(cams, resources.displayMetrics.widthPixels)
                        }
                        delay(10_000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
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
        val entities = HaClient.entities.value
        val status = HaClient.status.value

        // Header
        b.greeting.text = Ui.greeting(ctx)
        val on = entities.values.count(Domains::countsAsOn)
        b.subtitle.text = if (entities.isEmpty()) HaClient.server.value?.name.orEmpty()
        else resources.getQuantityString(R.plurals.devices_on, on, on)
        b.subtitle.isVisible = b.subtitle.text.isNotEmpty()

        renderStatus(status)
        renderClimate(entities)
        renderWeather(entities)

        // Favourites
        val favs = Favourites.ids.value.mapNotNull { entities[it] }
        val connected = status is HaClient.Status.Connected
        b.pinCard.isVisible = favs.isEmpty() && connected
        b.favTitle.isVisible = favs.isNotEmpty()
        b.favourites.isVisible = favs.isNotEmpty()
        tiles.submitList(favs.map { TileItem(it, Domains.stateLabel(ctx, it), Favourites.contains(it.id)) })
    }

    private fun renderStatus(status: HaClient.Status) {
        val b = _b ?: return
        b.statusCard.isVisible = status !is HaClient.Status.Connected && status !is HaClient.Status.Idle
        b.statusProgress.isVisible = status is HaClient.Status.Connecting
        b.statusButton.isVisible = status !is HaClient.Status.Connecting
        when (status) {
            HaClient.Status.Connecting -> b.statusText.setText(R.string.status_connecting)
            HaClient.Status.NotConfigured -> {
                b.statusText.setText(R.string.not_configured)
                b.statusButton.setText(R.string.open_settings)
                b.statusButton.setOnClickListener { (activity as? MainActivity)?.selectTab(R.id.tab_settings) }
            }
            HaClient.Status.NeedsLogin -> {
                b.statusText.setText(R.string.status_login)
                b.statusButton.setText(R.string.log_in)
                b.statusButton.setOnClickListener { startActivity(Intent(requireContext(), LoginActivity::class.java)) }
            }
            is HaClient.Status.Error -> {
                b.statusText.text = status.message
                b.statusButton.setText(R.string.retry)
                b.statusButton.setOnClickListener { HaClient.reconnect() }
            }
            else -> Unit
        }
    }

    private fun renderClimate(entities: Map<String, Entity>) {
        val b = _b ?: return
        val climate = Favourites.ids.value.firstNotNullOfOrNull { id -> entities[id]?.takeIf { it.domain == "climate" } }
            ?: entities.values.filter { it.domain == "climate" && it.available }.minByOrNull { it.name }
        b.climateCard.isVisible = climate != null
        if (climate == null) return

        b.climateMode.text = Domains.hvacLabel(requireContext(), climate.state)
        b.climateTemp.text = climate.num("current_temperature")?.let(Domains::formatTemp) ?: "—"
        b.climateCard.setOnClickListener { Ui.chooseHvacMode(this, climate) }

        val low = climate.num("target_temp_low")
        val high = climate.num("target_temp_high")
        val single = climate.num("temperature")
        fun setpoint(v: TextView, value: Double?, key: String, colorRes: Int) {
            v.isVisible = value != null
            if (value == null) return
            v.text = Domains.formatTemp(value).removeSuffix("°")
            v.backgroundTintList = ContextCompat.getColorStateList(requireContext(), colorRes)
            v.setOnClickListener { Ui.adjustSetpoint(this, climate, key) }
        }
        if (low != null && high != null) {
            setpoint(b.setLow, low, "target_temp_low", R.color.accent_orange)
            setpoint(b.setHigh, high, "target_temp_high", R.color.accent_blue)
        } else {
            setpoint(b.setLow, single, "temperature", if (climate.state == "cool") R.color.accent_blue else R.color.accent_orange)
            b.setHigh.isVisible = false
        }

        val outside = outsideTemperature(entities)
        b.outsideChip.isVisible = outside != null
        if (outside != null) {
            b.outsideChip.text = SpannableStringBuilder()
                .append(getString(R.string.outside_temperature))
                .append("  ")
                .inSpans(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.text)), StyleSpan(android.graphics.Typeface.BOLD)) {
                    append(Domains.formatTemp(outside))
                }
        }
    }

    /** A sensor named like "outside"/"outdoor" temperature, else the weather entity's temperature. */
    private fun outsideTemperature(entities: Map<String, Entity>): Double? {
        val sensor = entities.values.firstOrNull {
            it.domain == "sensor" && it.available && it.attrs.optString("device_class") == "temperature" &&
                OUTSIDE.containsMatchIn(it.id + " " + it.name)
        }
        sensor?.state?.toDoubleOrNull()?.let { return it }
        return entities.values.firstOrNull { it.domain == "weather" }?.num("temperature")
    }

    private fun renderWeather(entities: Map<String, Entity>) {
        val b = _b ?: return
        val weather = Favourites.ids.value.firstNotNullOfOrNull { id -> entities[id]?.takeIf { it.domain == "weather" } }
            ?: entities.values.filter { it.domain == "weather" && it.available }.minByOrNull { it.name }
        b.weatherCard.isVisible = weather != null
        if (weather == null) return

        b.weatherIcon.setImageResource(Ui.weatherIcon(weather.state))
        b.weatherTemp.text = weather.num("temperature")?.let(Domains::formatTemp) ?: "—"
        b.weatherCondition.text = Ui.weatherLabel(weather.state)
        val forecast = HaClient.forecasts.value[weather.id].orEmpty()
        val today = forecast.firstOrNull()
        b.weatherHiLo.isVisible = today?.high != null
        if (today?.high != null) {
            b.weatherHiLo.text = getString(R.string.high_low, Domains.formatTemp(today.high), today.low?.let(Domains::formatTemp) ?: "—")
        }
        b.weatherChevron.rotation = if (forecastOpen) 90f else 0f
        b.weatherChevron.isVisible = forecast.size > 1
        b.forecastRow.isVisible = forecastOpen && forecast.size > 1
        if (b.forecastRow.isVisible) fillForecast(b.forecastRow, forecast.take(5))
    }

    private fun fillForecast(row: LinearLayout, days: List<HaClient.Forecast>) {
        row.removeAllViews()
        val ctx = requireContext()
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        for (d in days) {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val label = runCatching { dayName.format(parse.parse(d.datetime.take(10))!!) }.getOrDefault("")
            col.addView(TextView(ctx).apply { text = label; setTextColor(ContextCompat.getColor(ctx, R.color.muted)); textSize = 13f })
            col.addView(ImageView(ctx).apply {
                setImageResource(Ui.weatherIcon(d.condition))
                val s = (26 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = s / 4; bottomMargin = s / 4 }
            })
            col.addView(TextView(ctx).apply { text = d.high?.let(Domains::formatTemp) ?: "—"; setTextColor(ContextCompat.getColor(ctx, R.color.text)); textSize = 14f })
            col.addView(TextView(ctx).apply { text = d.low?.let(Domains::formatTemp) ?: ""; setTextColor(ContextCompat.getColor(ctx, R.color.muted)); textSize = 13f })
            row.addView(col)
        }
    }

    companion object {
        private val OUTSIDE = Regex("outside|outdoor|exterior|external", RegexOption.IGNORE_CASE)
    }
}

data class TileItem(val entity: Entity, val label: String, val favourite: Boolean) {
    // Entity has no equals(); compare what's shown.
    val key get() = entity.id
    val content get() = "${entity.state}|$label|${Domains.isActive(entity)}|${entity.name}|$favourite"
}

/** Favourite tiles on Home: square tiles for devices, wide tiles for cameras. */
class TileAdapter(private val f: Fragment) : ListAdapter<TileItem, RecyclerView.ViewHolder>(DIFF) {
    class TileVH(val b: ItemTileBinding) : RecyclerView.ViewHolder(b.root)
    class CamVH(val b: ItemCameraBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (getItem(position).entity.domain == "camera") CAMERA else TILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == CAMERA) CamVH(ItemCameraBinding.inflate(inf, parent, false))
        else TileVH(ItemTileBinding.inflate(inf, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(IMAGE)) {
            if (holder is CamVH) bindImage(holder, getItem(position).entity)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val e = item.entity
        when (holder) {
            is TileVH -> with(holder.b) {
                name.text = e.name
                state.text = item.label
                Ui.styleIcon(iconBg, icon, e)
                root.setOnClickListener { Ui.onEntityTapped(f, it, e) }
                root.setOnLongClickListener { Ui.confirmRemoveFavourite(f, e); true }
            }
            is CamVH -> with(holder.b) {
                name.text = e.name
                Ui.styleStar(star, e.id)
                star.setOnClickListener { Ui.confirmRemoveFavourite(f, e) }
                bindImage(holder, e)
            }
        }
    }

    private fun bindImage(h: CamVH, e: Entity) {
        val bmp = CameraImages.images.value[e.id]
        h.b.image.setImageBitmap(bmp)
        h.b.placeholder.isVisible = bmp == null
    }

    companion object {
        const val TILE = 0
        const val CAMERA = 1
        const val IMAGE = "image"
        val DIFF = object : DiffUtil.ItemCallback<TileItem>() {
            override fun areItemsTheSame(a: TileItem, b: TileItem) = a.key == b.key
            override fun areContentsTheSame(a: TileItem, b: TileItem) = a.content == b.content
        }
    }
}
