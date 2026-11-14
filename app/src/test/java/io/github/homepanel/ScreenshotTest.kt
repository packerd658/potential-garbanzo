package io.github.homepanel

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

/**
 * Renders each tab with sample data and saves PNGs to app/build/screenshots/.
 * Run with: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {

    private fun e(id: String, state: String, attrs: JSONObject = JSONObject()) = Entity(id, state, attrs)

    private fun sample(): List<Entity> = buildList {
        add(
            e(
                "climate.living_room", "heat_cool",
                JSONObject().put("friendly_name", "Living room").put("current_temperature", 23)
                    .put("target_temp_low", 21).put("target_temp_high", 24)
                    .put("hvac_modes", JSONArray(listOf("off", "heat", "cool", "heat_cool"))),
            ),
        )
        add(e("weather.home", "rainy", JSONObject().put("friendly_name", "Home").put("temperature", -24)))
        add(e("sensor.outside_temperature", "15.5", JSONObject().put("device_class", "temperature").put("unit_of_measurement", "°C")))
        val rooms = listOf("Kitchen", "Living room", "Hallway", "Bedroom", "Office", "Porch", "Garage", "Bathroom", "Stairs", "Dining room")
        rooms.forEachIndexed { i, r ->
            add(e("light.${r.lowercase().replace(' ', '_')}", if (i < 9) "on" else "off", JSONObject().put("friendly_name", "$r lights").put("brightness", 200 - i * 15)))
        }
        add(e("switch.coffee_machine", "on", JSONObject().put("friendly_name", "Coffee machine")))
        add(e("switch.heater", "on", JSONObject().put("friendly_name", "Garage heater")))
        add(e("media_player.tv", "playing", JSONObject().put("friendly_name", "Living room TV")))
        add(e("lock.front_door", "locked", JSONObject().put("friendly_name", "Front door")))
        add(e("cover.garage_door", "closed", JSONObject().put("friendly_name", "Garage door")))
        add(e("scene.movie_night", "2026-11-13T20:00:00", JSONObject().put("friendly_name", "Movie night")))
        add(e("camera.driveway", "idle", JSONObject().put("friendly_name", "Driveway")))
        add(e("sensor.broken_plug", "unavailable", JSONObject().put("friendly_name", "Smart plug power")))
    }

    private val forecast = mapOf(
        "weather.home" to listOf(
            HaClient.Forecast("2026-11-14T00:00:00", "rainy", -23.0, -26.0),
            HaClient.Forecast("2026-11-15T00:00:00", "snowy", -20.0, -27.0),
            HaClient.Forecast("2026-11-16T00:00:00", "cloudy", -18.0, -22.0),
            HaClient.Forecast("2026-11-17T00:00:00", "partlycloudy", -15.0, -21.0),
            HaClient.Forecast("2026-11-18T00:00:00", "sunny", -12.0, -19.0),
        ),
    )

    @Test
    fun renderTabs() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HaClient.init(ctx)
        Favourites.set(ctx, emptyList())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            HaClient.showPreview(
                HaClient.Status.Connected(Route.TAILSCALE, "http://homeassistant.tail1234.ts.net:8123"),
                sample(),
                HaClient.ServerInfo("Home", "2026.11.1", "°C"),
                forecast,
            )
            shot(sc, "1-home-empty")

            Favourites.set(ctx, listOf("light.kitchen", "lock.front_door", "switch.coffee_machine", "scene.movie_night", "light.dining_room", "camera.driveway"))
            shot(sc, "2-home-favourites")

            sc.onActivity { it.selectTab(R.id.tab_devices) }
            shot(sc, "3-devices")
            sc.onActivity { it.selectTab(R.id.tab_cameras) }
            shot(sc, "4-cameras")
            sc.onActivity { it.selectTab(R.id.tab_status) }
            shot(sc, "5-status")
            sc.onActivity { it.selectTab(R.id.tab_settings) }
            shot(sc, "6-settings")

            sc.onActivity { it.selectTab(R.id.tab_home) }
            HaClient.showPreview(HaClient.Status.NeedsLogin, emptyList(), null, emptyMap())
            shot(sc, "7-login-needed")
        }
    }

    private fun shot(sc: ActivityScenario<MainActivity>, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        sc.onActivity { a ->
            val root = a.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
