package io.github.homepanel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Connection to Home Assistant's WebSocket API, shared by all screens. */
object HaClient {

    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object NotConfigured : Status
        data object NeedsLogin : Status
        data class Connected(val route: Route, val baseUrl: String) : Status
        data class Error(val message: String) : Status
    }

    data class ServerInfo(val name: String, val version: String, val tempUnit: String)

    data class Forecast(val datetime: String, val condition: String, val high: Double?, val low: Double?)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    val entities: StateFlow<Map<String, Entity>> = _entities

    private val _server = MutableStateFlow<ServerInfo?>(null)
    val server: StateFlow<ServerInfo?> = _server

    private val _forecasts = MutableStateFlow<Map<String, List<Forecast>>>(emptyMap())
    val forecasts: StateFlow<Map<String, List<Forecast>>> = _forecasts

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var socket: WebSocket? = null
    private var generation = 0 // ignores callbacks from sockets we've already replaced
    private var nextId = 1
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var stateSubscriptionId = -1
    private var connectJob: Job? = null
    private var retryJob: Job? = null
    private var networkJob: Job? = null
    private var forecastJob: Job? = null
    private var retryDelayMs = 2_000L
    private var running = false
    private var triedRefresh = false

    val currentBase: String? get() = (_status.value as? Status.Connected)?.baseUrl

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in RECONNECT_KEYS && running) reconnect()
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Favourites.load(app)
        PreferenceManager.getDefaultSharedPreferences(app).registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** Called when the app comes to the foreground. */
    fun start() {
        if (running) return
        running = true
        reconnect()
    }

    /** Called when the app goes to the background: closes the socket to save battery. */
    fun stop() {
        running = false
        connectJob?.cancel()
        retryJob?.cancel()
        forecastJob?.cancel()
        closeSocket()
        if (_status.value is Status.Connected || _status.value is Status.Connecting) _status.value = Status.Idle
    }

    fun reconnect() {
        retryJob?.cancel()
        connectJob?.cancel()
        closeSocket()
        triedRefresh = false
        connectJob = scope.launch { connect(forceRefresh = false) }
    }

    /** Network changed: in automatic mode, move to a better address if there is one. */
    fun onNetworkChanged() {
        if (!running) return
        networkJob?.cancel()
        networkJob = scope.launch {
            delay(1_500)
            val settings = AppSettings.load(app)
            val st = _status.value
            if (st is Status.Connected) {
                if (settings.mode != Mode.AUTO) return@launch
                val r = Connectivity(app).resolve(settings)
                if (r is Resolution.Found && r.baseUrl != st.baseUrl) reconnect()
            } else if (st is Status.Error || st is Status.Idle) {
                reconnect()
            }
        }
    }

    private suspend fun connect(forceRefresh: Boolean) {
        _status.value = Status.Connecting
        val settings = AppSettings.load(app)
        val base = when (val r = Connectivity(app).resolve(settings)) {
            Resolution.NotConfigured -> {
                _status.value = Status.NotConfigured
                return
            }
            is Resolution.Unreachable -> {
                fail(app.getString(R.string.no_route, r.details))
                return
            }
            is Resolution.Found -> r.baseUrl
        }
        pendingRoute = settings.routeForUrl(base) ?: Route.REMOTE
        val token = try {
            Auth.accessToken(app, settings, base, forceRefresh)
        } catch (_: Auth.LoginRequired) {
            pendingBase = base
            _status.value = Status.NeedsLogin
            return
        } catch (e: IOException) {
            fail(e.message ?: e.javaClass.simpleName)
            return
        }
        pendingBase = base
        openSocket(settings, base, token)
    }

    /** Base URL picked by the last connection attempt, even if it hasn't finished (used by login). */
    var pendingBase: String? = null
        private set
    private var pendingRoute: Route = Route.REMOTE

    private fun openSocket(settings: AppSettings, base: String, token: String) {
        val gen = ++generation
        val wsUrl = base.replaceFirst("http", "ws") + "/api/websocket"
        val req = Request.Builder().url(wsUrl).build()
        socket = Http.clientFor(settings, base).newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (gen == generation) handle(webSocket, text, token, base) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (gen == generation) fail(t.message ?: t.javaClass.simpleName) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (gen == generation) fail(app.getString(R.string.connection_closed)) }
            }
        })
    }

    private suspend fun handle(ws: WebSocket, text: String, token: String, base: String) {
        val msg = JSONObject(text)
        when (msg.optString("type")) {
            "auth_required" -> ws.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_invalid" -> {
                closeSocket()
                if (!triedRefresh && !Auth.usesLongLivedToken(app)) {
                    triedRefresh = true
                    connect(forceRefresh = true)
                } else {
                    _status.value = Status.NeedsLogin
                }
            }
            "auth_ok" -> {
                retryDelayMs = 2_000
                _status.value = Status.Connected(pendingRoute, base)
                scope.launch { loadInitial() }
            }
            "result" -> pending.remove(msg.optInt("id"))?.complete(msg)
            "event" -> if (msg.optInt("id") == stateSubscriptionId) {
                val data = msg.optJSONObject("event")?.optJSONObject("data") ?: return
                val id = data.optString("entity_id")
                val newState = data.optJSONObject("new_state")
                _entities.value = if (newState == null) _entities.value - id else _entities.value + (id to Entity.fromJson(newState))
                if (id.startsWith("weather.")) scheduleForecast()
            }
        }
    }

    private suspend fun loadInitial() {
        try {
            val cfg = request(JSONObject().put("type", "get_config")).optJSONObject("result")
            if (cfg != null) {
                _server.value = ServerInfo(
                    name = cfg.optString("location_name", "Home"),
                    version = cfg.optString("version"),
                    tempUnit = cfg.optJSONObject("unit_system")?.optString("temperature") ?: "°C",
                )
            }
            val sub = JSONObject().put("type", "subscribe_events").put("event_type", "state_changed")
            stateSubscriptionId = nextId // request() gives the message this id; events reuse it
            request(sub)
            val states = request(JSONObject().put("type", "get_states")).optJSONArray("result") ?: JSONArray()
            _entities.value = buildMap { for (i in 0 until states.length()) Entity.fromJson(states.getJSONObject(i)).let { put(it.id, it) } }
            loadForecasts()
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun scheduleForecast() {
        if (forecastJob?.isActive == true) return
        forecastJob = scope.launch {
            delay(5_000)
            loadForecasts()
        }
    }

    private suspend fun loadForecasts() {
        val weather = _entities.value.values.filter { it.domain == "weather" }
        val out = mutableMapOf<String, List<Forecast>>()
        for (w in weather) {
            // Older Home Assistant versions put the forecast in the entity's attributes.
            w.attrs.optJSONArray("forecast")?.let { out[w.id] = parseForecast(it) }
            if (out.containsKey(w.id)) continue
            runCatching {
                val res = callService("weather", "get_forecasts", w.id, JSONObject().put("type", "daily"), returnResponse = true)
                res?.optJSONObject("response")?.optJSONObject(w.id)?.optJSONArray("forecast")?.let { out[w.id] = parseForecast(it) }
            }
        }
        _forecasts.value = out
    }

    private fun parseForecast(a: JSONArray) = List(a.length()) { i ->
        val f = a.getJSONObject(i)
        Forecast(
            datetime = f.optString("datetime"),
            condition = f.optString("condition"),
            high = f.optDouble("temperature").takeIf { !it.isNaN() },
            low = f.optDouble("templow").takeIf { !it.isNaN() },
        )
    }

    /** Sends a command and waits for its result. Throws if Home Assistant reports an error. */
    suspend fun request(payload: JSONObject): JSONObject {
        val ws = socket ?: throw IOException(app.getString(R.string.not_connected))
        val id = nextId++
        payload.put("id", id)
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw IOException(app.getString(R.string.not_connected))
        }
        val res = try {
            withTimeout(15_000) { d.await() }
        } finally {
            pending.remove(id)
        }
        if (!res.optBoolean("success", false)) {
            throw IOException(res.optJSONObject("error")?.optString("message") ?: "Request failed")
        }
        return res
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        data: JSONObject? = null,
        returnResponse: Boolean = false,
    ): JSONObject? {
        val p = JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
        data?.let { p.put("service_data", it) }
        entityId?.let { p.put("target", JSONObject().put("entity_id", it)) }
        if (returnResponse) p.put("return_response", true)
        return request(p).optJSONObject("result")
    }

    /** Latest still image from a camera, or null. */
    suspend fun cameraSnapshot(entity: Entity, maxWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val st = _status.value as? Status.Connected ?: return@withContext null
        val settings = AppSettings.load(app)
        val token = runCatching { Auth.accessToken(app, settings, st.baseUrl) }.getOrNull() ?: return@withContext null
        val path = entity.attrs.optString("entity_picture").ifBlank { "/api/camera_proxy/${entity.id}" }
        val req = Request.Builder().url(st.baseUrl + path).header("Authorization", "Bearer $token").build()
        runCatching {
            Http.clientFor(settings, st.baseUrl).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body.bytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }

    /** Shows fixed data without a server. Used by the screenshot tests. */
    @androidx.annotation.VisibleForTesting
    fun showPreview(status: Status, entities: List<Entity>, server: ServerInfo?, forecasts: Map<String, List<Forecast>>) {
        running = false
        connectJob?.cancel()
        retryJob?.cancel()
        closeSocket()
        _server.value = server
        _forecasts.value = forecasts
        _entities.value = entities.associateBy { it.id }
        _status.value = status
    }

    private fun fail(message: String) {
        closeSocket()
        _status.value = Status.Error(message)
        if (!running) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000)
            connect(forceRefresh = false)
        }
    }

    private fun closeSocket() {
        generation++
        socket?.close(1000, null)
        socket = null
        pending.values.forEach { it.completeExceptionally(IOException("closed")) }
        pending.clear()
    }

    private val RECONNECT_KEYS = setOf(
        AppSettings.KEY_LOCAL, AppSettings.KEY_TAILSCALE, AppSettings.KEY_REMOTE,
        AppSettings.KEY_MODE, AppSettings.KEY_SELF_SIGNED, Auth.KEY_LONG_LIVED,
    )
}
