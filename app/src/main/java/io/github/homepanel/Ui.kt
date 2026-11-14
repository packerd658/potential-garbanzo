package io.github.homepanel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.homepanel.databinding.DialogSetpointBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/** Things several tabs do: run an entity's action, pick favourites, adjust a thermostat. */
object Ui {

    fun greeting(c: Context): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> c.getString(R.string.greeting_morning)
        in 12..16 -> c.getString(R.string.greeting_afternoon)
        in 17..21 -> c.getString(R.string.greeting_evening)
        else -> c.getString(R.string.greeting_night)
    }

    /** Colours an entity's round icon: amber when on, grey when off. */
    fun styleIcon(bg: View, icon: ImageView, e: Entity) {
        val c = bg.context
        val active = Domains.isActive(e)
        icon.setImageResource(Domains.icon(e))
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(c, if (active) R.color.active else R.color.text))
        bg.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(c, if (active) R.color.active_bg else R.color.chip))
        bg.alpha = if (e.available) 1f else 0.5f
    }

    fun styleStar(star: ImageView, id: String) {
        val fav = Favourites.contains(id)
        star.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        star.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(star.context, if (fav) R.color.active else R.color.muted))
    }

    /** What a tap on a tile or row does. */
    fun onEntityTapped(f: Fragment, view: View, e: Entity) {
        if (e.domain == "climate") {
            chooseHvacMode(f, e)
            return
        }
        val action = Domains.primaryAction(e) ?: return
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (action.confirm) {
            MaterialAlertDialogBuilder(f.requireContext())
                .setMessage(f.getString(R.string.confirm_unlock, e.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.unlock) { _, _ -> call(f, action.domain, action.service, e.id) }
                .show()
        } else {
            call(f, action.domain, action.service, e.id)
        }
    }

    fun call(f: Fragment, domain: String, service: String, entityId: String?, data: JSONObject? = null) {
        val ctx = f.requireContext().applicationContext
        f.viewLifecycleOwner.lifecycleScope.launch {
            try {
                HaClient.callService(domain, service, entityId, data)
            } catch (e: Exception) {
                Toast.makeText(ctx, ctx.getString(R.string.action_failed, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Entities that can be pinned to Home. */
    fun pinnable(all: Collection<Entity>): List<Entity> =
        all.filter { it.domain in Domains.DEVICE_DOMAINS || it.domain == "camera" || it.domain == "sensor" }
            .sortedWith(compareBy({ it.domain == "sensor" }, { it.name.lowercase() }))

    fun chooseFavourites(f: Fragment) {
        val c = f.requireContext()
        val items = pinnable(HaClient.entities.value.values)
        if (items.isEmpty()) {
            Toast.makeText(c, R.string.no_devices, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { "${it.name}  ·  ${c.getString(if (it.domain == "sensor") R.string.domain_sensor else Domains.title(it.domain))}" }
            .toTypedArray()
        val checked = items.map { Favourites.contains(it.id) }.toBooleanArray()
        MaterialAlertDialogBuilder(c)
            .setTitle(R.string.choose_favourites)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val keep = Favourites.ids.value.filter { id -> items.indexOfFirst { it.id == id }.let { it < 0 || checked[it] } }
                val added = items.filterIndexed { i, e -> checked[i] && e.id !in keep }.map { it.id }
                Favourites.set(c, keep + added)
            }
            .show()
    }

    fun confirmRemoveFavourite(f: Fragment, e: Entity) {
        MaterialAlertDialogBuilder(f.requireContext())
            .setMessage(f.getString(R.string.remove_favourite, e.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> Favourites.toggle(f.requireContext(), e.id) }
            .show()
    }

    fun chooseHvacMode(f: Fragment, e: Entity) {
        val modes = e.attrs.optJSONArray("hvac_modes")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        if (modes.isEmpty()) return
        val c = f.requireContext()
        MaterialAlertDialogBuilder(c)
            .setTitle(e.name)
            .setSingleChoiceItems(modes.map { Domains.hvacLabel(c, it) }.toTypedArray(), modes.indexOf(e.state)) { d, i ->
                call(f, "climate", "set_hvac_mode", e.id, JSONObject().put("hvac_mode", modes[i]))
                d.dismiss()
            }
            .show()
    }

    /** +/- dialog for a thermostat setpoint. [which] is "temperature", "target_temp_low" or "target_temp_high". */
    fun adjustSetpoint(f: Fragment, e: Entity, which: String) {
        val c = f.requireContext()
        val start = e.num(which) ?: return
        val step = e.num("target_temp_step") ?: if (HaClient.server.value?.tempUnit?.contains("F") == true) 1.0 else 0.5
        val min = e.num("min_temp") ?: 5.0
        val max = e.num("max_temp") ?: 35.0
        var value = start
        val b = DialogSetpointBinding.inflate(LayoutInflater.from(c))
        fun show() { b.value.text = Domains.formatTemp(value) }
        show()
        b.minus.setOnClickListener { value = (value - step).coerceAtLeast(min); show() }
        b.plus.setOnClickListener { value = (value + step).coerceAtMost(max); show() }
        val title = when (which) {
            "target_temp_low" -> R.string.heat_to
            "target_temp_high" -> R.string.cool_to
            else -> R.string.target
        }
        MaterialAlertDialogBuilder(c)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val data = JSONObject()
                if (which == "temperature") {
                    data.put("temperature", value)
                } else {
                    // Both ends of the range have to be sent together.
                    data.put("target_temp_low", if (which == "target_temp_low") value else e.num("target_temp_low"))
                    data.put("target_temp_high", if (which == "target_temp_high") value else e.num("target_temp_high"))
                }
                call(f, "climate", "set_temperature", e.id, data)
            }
            .show()
    }

    fun weatherIcon(condition: String): Int = when (condition) {
        "sunny" -> R.drawable.wx_sunny
        "clear-night" -> R.drawable.wx_night
        "partlycloudy" -> R.drawable.wx_partly
        "cloudy", "windy", "windy-variant", "exceptional" -> R.drawable.wx_cloudy
        "rainy", "pouring" -> R.drawable.wx_rain
        "lightning", "lightning-rainy" -> R.drawable.wx_storm
        "snowy", "snowy-rainy", "hail" -> R.drawable.wx_snow
        "fog" -> R.drawable.wx_fog
        else -> R.drawable.wx_cloudy
    }

    fun weatherLabel(condition: String): String = when (condition) {
        "clear-night" -> "Clear"
        "partlycloudy" -> "Partly cloudy"
        "rainy" -> "Rain"
        "pouring" -> "Heavy rain"
        "lightning" -> "Thunderstorm"
        "lightning-rainy" -> "Thunderstorm"
        "snowy" -> "Snow"
        "snowy-rainy" -> "Sleet"
        "windy", "windy-variant" -> "Windy"
        else -> Domains.pretty(condition.replace('-', ' '))
    }
}

/** Latest snapshot of each camera, shared by the Home and Cameras tabs. */
object CameraImages {
    private val _images = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val images: StateFlow<Map<String, Bitmap>> = _images

    suspend fun refresh(cameras: List<Entity>, maxWidth: Int) {
        for (cam in cameras) {
            val bmp = HaClient.cameraSnapshot(cam, maxWidth) ?: continue
            _images.value = _images.value + (cam.id to bmp)
        }
    }
}
