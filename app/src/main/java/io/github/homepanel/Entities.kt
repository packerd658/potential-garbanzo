package io.github.homepanel

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One Home Assistant entity, as reported by the WebSocket API. */
class Entity(val id: String, val state: String, val attrs: JSONObject) {
    val domain: String get() = id.substringBefore('.')
    val name: String
        get() = attrs.optString("friendly_name").ifBlank {
            id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    val available: Boolean get() = state != "unavailable" && state != "unknown"

    fun num(key: String): Double? = if (attrs.has(key) && !attrs.isNull(key)) attrs.optDouble(key).takeIf { !it.isNaN() } else null

    companion object {
        fun fromJson(o: JSONObject) = Entity(o.getString("entity_id"), o.optString("state"), o.optJSONObject("attributes") ?: JSONObject())
    }
}

/** What a tap on an entity does. */
data class Action(val domain: String, val service: String, val confirm: Boolean = false)

object Domains {
    /** Domains listed on the Devices tab, in display order. */
    val DEVICE_DOMAINS = listOf(
        "light", "switch", "fan", "climate", "cover", "lock", "media_player",
        "vacuum", "input_boolean", "scene", "script", "button",
    )

    fun title(domain: String): Int = when (domain) {
        "light" -> R.string.domain_light
        "switch" -> R.string.domain_switch
        "fan" -> R.string.domain_fan
        "climate" -> R.string.domain_climate
        "cover" -> R.string.domain_cover
        "lock" -> R.string.domain_lock
        "media_player" -> R.string.domain_media
        "vacuum" -> R.string.domain_vacuum
        "input_boolean" -> R.string.domain_input_boolean
        "scene" -> R.string.domain_scene
        "script" -> R.string.domain_script
        "button" -> R.string.domain_button
        "camera" -> R.string.domain_camera
        else -> R.string.domain_other
    }

    fun icon(e: Entity): Int = when (e.domain) {
        "light" -> R.drawable.ic_bulb
        "climate" -> R.drawable.ic_thermostat
        "cover" -> R.drawable.ic_blinds
        "lock" -> if (e.state == "unlocked") R.drawable.ic_lock_open else R.drawable.ic_lock
        "media_player" -> R.drawable.ic_music
        "scene", "script", "button" -> R.drawable.ic_play
        "camera" -> R.drawable.ic_videocam
        else -> R.drawable.ic_power
    }

    /** True for "on"-like states, used to highlight the tile. */
    fun isActive(e: Entity): Boolean = when (e.domain) {
        "light", "switch", "fan", "input_boolean" -> e.state == "on"
        "cover" -> e.state == "open" || e.state == "opening"
        "lock" -> e.state == "unlocked" || e.state == "open"
        "media_player" -> e.state == "playing" || e.state == "on" || e.state == "paused"
        "climate" -> e.available && e.state != "off"
        "vacuum" -> e.state == "cleaning"
        else -> false
    }

    /** Counted in "N devices on" on the Home tab. */
    fun countsAsOn(e: Entity): Boolean = when (e.domain) {
        "light", "switch", "fan" -> e.state == "on"
        "media_player" -> e.state == "playing"
        else -> false
    }

    fun primaryAction(e: Entity): Action? {
        if (!e.available && e.domain !in setOf("scene", "script", "button")) return null
        return when (e.domain) {
            "light", "switch", "fan", "input_boolean" -> Action(e.domain, "toggle")
            "cover" -> Action("cover", "toggle")
            "lock" -> if (e.state == "locked") Action("lock", "unlock", confirm = true) else Action("lock", "lock")
            "media_player" -> Action("media_player", "media_play_pause")
            "scene" -> Action("scene", "turn_on")
            "script" -> Action("script", "turn_on")
            "button" -> Action("button", "press")
            "vacuum" -> if (e.state == "cleaning") Action("vacuum", "return_to_base") else Action("vacuum", "start")
            else -> null
        }
    }

    /** Domains that get an on/off switch rather than a tap action. */
    fun hasSwitch(e: Entity) = e.domain in setOf("light", "switch", "fan", "input_boolean")

    fun stateLabel(c: Context, e: Entity): String {
        if (e.state == "unavailable") return c.getString(R.string.state_unavailable)
        return when (e.domain) {
            "light" -> if (e.state == "on") {
                e.num("brightness")?.let { c.getString(R.string.state_on_pct, Math.round(it / 255.0 * 100).toInt()) }
                    ?: c.getString(R.string.state_on)
            } else c.getString(R.string.state_off)
            "switch", "fan", "input_boolean" -> c.getString(if (e.state == "on") R.string.state_on else R.string.state_off)
            "climate" -> {
                val cur = e.num("current_temperature")?.let { formatTemp(it) }
                listOfNotNull(hvacLabel(c, e.state), cur).joinToString(" · ")
            }
            "scene", "script", "button" -> c.getString(R.string.state_tap_to_run)
            "sensor" -> e.state + (e.attrs.optString("unit_of_measurement").takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
            else -> pretty(e.state)
        }
    }

    fun hvacLabel(c: Context, mode: String): String = when (mode) {
        "heat_cool" -> c.getString(R.string.hvac_heat_cool)
        "heat" -> c.getString(R.string.hvac_heat)
        "cool" -> c.getString(R.string.hvac_cool)
        "auto" -> c.getString(R.string.hvac_auto)
        "dry" -> c.getString(R.string.hvac_dry)
        "fan_only" -> c.getString(R.string.hvac_fan)
        "off" -> c.getString(R.string.hvac_off)
        else -> pretty(mode)
    }

    fun pretty(s: String) = s.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** 23.0 -> "23°", 15.5 -> "15.5°". */
    fun formatTemp(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return (if (r == Math.floor(r)) r.toLong().toString() else r.toString()) + "°"
    }
}

/** Pinned entities, in the order the user picked them. */
object Favourites {
    private const val KEY = "favourites"
    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids: StateFlow<List<String>> = _ids
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null) ?: return
        _ids.value = runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun set(context: Context, ids: List<String>) {
        _ids.value = ids.distinct()
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(KEY, JSONArray(_ids.value).toString()) }
    }

    fun toggle(context: Context, id: String) {
        set(context, if (id in _ids.value) _ids.value - id else _ids.value + id)
    }

    fun contains(id: String) = id in _ids.value
}
