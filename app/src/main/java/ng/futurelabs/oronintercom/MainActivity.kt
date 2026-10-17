package ng.futurelabs.oronintercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("oron", Context.MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var mark: TextView
    private lateinit var titleView: TextView
    private lateinit var joinPanel: View
    private lateinit var livePanel: View
    private lateinit var camName: EditText
    private lateinit var camPos: EditText
    private lateinit var found: TextView
    private lateinit var manualHost: EditText
    private lateinit var incoming: TextView
    private lateinit var ptt: Button
    private lateinit var dirState: TextView
    private lateinit var voiceState: TextView
    private lateinit var battery: TextView

    // Camera card
    private lateinit var cameraPanel: View
    private lateinit var camBadge: TextView
    private lateinit var camStatus: TextView
    private lateinit var camToggle: Button
    private lateinit var camFlip: Button
    private lateinit var previewBox: View
    private lateinit var preview: SurfaceViewRenderer
    private var previewReady = false
    private var previewAttached = false
    private var camSettings = CameraSettings()
    private val slotChips = ArrayList<TextView>()
    private val qualityChips = ArrayList<TextView>()
    private val fpsChips = ArrayList<TextView>()

    private var discoveredHost: String? = null
    private var discoveredPort = 3000
    private var discovery: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.parseColor("#2D2B50")

        status = findViewById(R.id.status)
        mark = findViewById(R.id.mark)
        titleView = findViewById(R.id.title)
        joinPanel = findViewById(R.id.join_panel)
        livePanel = findViewById(R.id.live_panel)
        camName = findViewById(R.id.cam_name)
        camPos = findViewById(R.id.cam_pos)
        found = findViewById(R.id.found)
        manualHost = findViewById(R.id.manual_host)
        incoming = findViewById(R.id.incoming)
        ptt = findViewById(R.id.ptt)
        dirState = findViewById(R.id.dir_state)
        voiceState = findViewById(R.id.voice_state)
        battery = findViewById(R.id.battery)

        camName.setText(prefs.getString("name", ""))
        camPos.setText(prefs.getString("pos", ""))
        manualHost.setText(prefs.getString("manualHost", ""))

        findViewById<Button>(R.id.join).setOnClickListener { join() }
        findViewById<Button>(R.id.leave).setOnClickListener {
            startService(Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_STOP))
        }
        battery.setOnClickListener { askBatteryExemption() }
        setupPtt()
        setupCamera()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                IntercomState.ui.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!IntercomState.ui.value.running) startDiscovery()
        battery.visibility = if (isBatteryOptimized()) View.VISIBLE else View.GONE
    }

    override fun onStop() {
        discovery?.cancel()
        detachPreview()
        super.onStop()
    }

    override fun onDestroy() {
        detachPreview()
        if (previewReady) runCatching { preview.release() }
        super.onDestroy()
    }

    /* ---------------- Find the laptop automatically (UDP beacon from OronIntercom.exe) ---------------- */

    private fun startDiscovery() {
        if (discovery?.isActive == true) return
        discovery = lifecycleScope.launch {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("oron:discovery").apply { setReferenceCounted(false); acquire() }
            try {
                withContext(Dispatchers.IO) {
                    val sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 1500
                        bind(InetSocketAddress(41234))
                    }
                    sock.use {
                        val buf = ByteArray(1024)
                        while (isActive) {
                            try {
                                val packet = DatagramPacket(buf, buf.size)
                                it.receive(packet)
                                val msg = JSONObject(String(packet.data, 0, packet.length))
                                if (msg.optString("app") != "oron-intercom") continue
                                val host = packet.address.hostAddress ?: continue
                                val port = msg.optInt("port", 3000)
                                val label = msg.optString("name", "")
                                withContext(Dispatchers.Main) {
                                    if (discoveredHost != host) {
                                        discoveredHost = host
                                        discoveredPort = port
                                        found.text = "✓ Found the control laptop${if (label.isNotEmpty()) " ($label)" else ""} at $host"
                                        found.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))
                                    }
                                }
                            } catch (_: SocketTimeoutException) {
                                // keep listening
                            } catch (_: Exception) {
                                // ignore stray packets
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                found.text = "Couldn't search the WiFi automatically. Type the laptop address below."
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /* ---------------- Join ---------------- */

    private fun join() {
        val name = camName.text.toString().trim().ifEmpty { "Unnamed camera" }
        val pos = camPos.text.toString().trim()
        val label = if (pos.isNotEmpty()) "$name · $pos" else name
        val typed = manualHost.text.toString().trim()
            .removePrefix("http://").removePrefix("https://").substringBefore("/")
        val (host, port) = when {
            typed.isNotEmpty() -> typed.substringBefore(":") to (typed.substringAfter(":", "").toIntOrNull() ?: 3000)
            discoveredHost != null -> discoveredHost!! to discoveredPort
            else -> {
                Toast.makeText(this, "Still looking for the laptop. Check the WiFi, or type its address.", Toast.LENGTH_LONG).show()
                return
            }
        }
        prefs.edit().putString("name", name).putString("pos", pos).putString("manualHost", typed).apply()

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingJoin = Triple(host, port, label)
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
            return
        }
        start(host, port, label)
    }

    private var pendingJoin: Triple<String, Int, String>? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else Toast.makeText(this, "Camera permission is needed to send video.", Toast.LENGTH_LONG).show()
            return
        }
        val p = pendingJoin ?: return
        pendingJoin = null
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            start(p.first, p.second, p.third)
        } else {
            Toast.makeText(this, "The microphone is needed so you can talk to the director.", Toast.LENGTH_LONG).show()
        }
    }

    private fun start(host: String, port: Int, label: String) {
        discovery?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(camName.windowToken, 0)
        val intent = Intent(this, IntercomService::class.java)
            .setAction(IntercomService.ACTION_START)
            .putExtra(IntercomService.EXTRA_HOST, host)
            .putExtra(IntercomService.EXTRA_PORT, port)
            .putExtra(IntercomService.EXTRA_NAME, label)
        ContextCompat.startForegroundService(this, intent)
        if (isBatteryOptimized() && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            askBatteryExemption()
        }
    }

    /* ---------------- Push-to-talk on screen ---------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPtt() {
        ptt.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    IntercomService.instance?.setTalking(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    IntercomService.instance?.setTalking(false)
                }
            }
            true
        }
    }

    /* ---------------- Screen ---------------- */

    private fun render(s: UiState) {
        joinPanel.visibility = if (s.running) View.GONE else View.VISIBLE
        livePanel.visibility = if (s.running) View.VISIBLE else View.GONE
        cameraPanel.visibility = if (s.running) View.VISIBLE else View.GONE
        renderCamera(s)
        if (!s.running) {
            status.text = "Not connected"
            titleView.text = getString(R.string.app_name)
            mark.text = "LO"
            startDiscoveryIfVisible()
            return
        }
        val name = prefs.getString("name", "Camera") ?: "Camera"
        titleView.text = name
        mark.text = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
        status.text = when {
            s.error != null && !s.connected -> s.error
            !s.connected -> "Reconnecting to ${s.host}…"
            else -> "Connected to ${s.host}"
        }
        dirState.text = stat("DIRECTOR", if (s.directorOnline) "Online" else "Offline")
        voiceState.text = stat("VOICE", if (s.voiceLive) "Live" else if (s.directorOnline) "Linking…" else "—")

        incoming.text = when {
            s.muted -> "MUTED BY DIRECTOR"
            s.directorTalking -> "DIRECTOR SPEAKING"
            else -> "STANDBY"
        }
        incoming.setBackgroundResource(if (s.directorTalking) R.drawable.pill_live else R.drawable.pill)
        incoming.setTextColor(
            ContextCompat.getColor(
                this,
                if (s.directorTalking) R.color.onGrad else if (s.muted) R.color.warn else R.color.muted,
            )
        )
        ptt.isActivated = s.talking
        ptt.text = when {
            s.muted -> "MUTED"
            s.talking && s.latched -> "TALKING\n(tap earphone\nbutton to stop)"
            s.talking -> "TALKING"
            else -> "HOLD TO\nTALK"
        }
    }

    private fun startDiscoveryIfVisible() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startDiscovery()
    }

    /* ---------------- Camera card ---------------- */

    private fun setupCamera() {
        cameraPanel = findViewById(R.id.camera_panel)
        camBadge = findViewById(R.id.cam_badge)
        camStatus = findViewById(R.id.cam_status)
        camToggle = findViewById(R.id.cam_toggle)
        camFlip = findViewById(R.id.cam_flip)
        previewBox = findViewById(R.id.preview_box)
        preview = findViewById(R.id.preview)

        camSettings = CameraSettings(
            slot = prefs.getInt("camSlot", 1).coerceIn(1, 4),
            quality = Quality.from(prefs.getString("camQuality", Quality.HD720.label)),
            fps = if (prefs.getInt("camFps", 30) == 60) 60 else 30,
            front = prefs.getBoolean("camFront", false),
        )

        buildChips(findViewById(R.id.slot_row), listOf("1", "2", "3", "4"), slotChips) { i ->
            applySettings(camSettings.copy(slot = i + 1))
        }
        buildChips(findViewById(R.id.quality_row), Quality.entries.map { it.label }, qualityChips) { i ->
            applySettings(camSettings.copy(quality = Quality.entries[i]))
        }
        buildChips(findViewById(R.id.fps_row), listOf("30 fps", "60 fps"), fpsChips) { i ->
            applySettings(camSettings.copy(fps = if (i == 1) 60 else 30))
        }
        camFlip.setOnClickListener { applySettings(camSettings.copy(front = !camSettings.front)) }
        camToggle.setOnClickListener {
            if (IntercomState.ui.value.cameraOn) {
                IntercomService.instance?.stopCamera()
            } else if (hasPermission(Manifest.permission.CAMERA)) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            }
        }
        // 16:9 preview box sized from its width.
        previewBox.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val want = (r - l) * 9 / 16
            if (want > 0 && v.layoutParams.height != want) v.post { v.layoutParams = v.layoutParams.apply { height = want } }
        }
        syncChips()
    }

    private fun buildChips(row: LinearLayout, labels: List<String>, out: MutableList<TextView>, onPick: (Int) -> Unit) {
        labels.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.chip)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text))
                textSize = 13f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.montserrat)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { onPick(i) }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) lp.marginStart = (8 * resources.displayMetrics.density).toInt()
            row.addView(chip, lp)
            out.add(chip)
        }
    }

    private fun syncChips() {
        slotChips.forEachIndexed { i, c -> c.isSelected = camSettings.slot == i + 1 }
        qualityChips.forEachIndexed { i, c -> c.isSelected = camSettings.quality == Quality.entries[i] }
        fpsChips.forEachIndexed { i, c -> c.isSelected = (camSettings.fps == 60) == (i == 1) }
        camFlip.text = if (camSettings.front) "FRONT LENS" else "BACK LENS"
    }

    private fun applySettings(next: CameraSettings) {
        camSettings = next
        prefs.edit()
            .putInt("camSlot", next.slot)
            .putString("camQuality", next.quality.label)
            .putInt("camFps", next.fps)
            .putBoolean("camFront", next.front)
            .apply()
        syncChips()
        if (IntercomState.ui.value.cameraOn) IntercomService.instance?.updateCamera(next)
        if (previewReady) preview.setMirror(next.front)
    }

    private fun startCamera() {
        IntercomService.instance?.startCamera(camSettings)
    }

    private fun attachPreview() {
        val svc = IntercomService.instance ?: return
        if (previewAttached) return
        if (!previewReady) {
            val ctx = svc.eglContext ?: return
            preview.init(ctx, null)
            preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            preview.setEnableHardwareScaler(true)
            previewReady = true
        }
        preview.setMirror(camSettings.front)
        svc.addPreview(preview)
        previewAttached = true
    }

    private fun detachPreview() {
        if (!previewAttached) return
        IntercomService.instance?.removePreview(preview)
        previewAttached = false
    }

    private fun renderCamera(s: UiState) {
        val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        previewBox.visibility = if (s.cameraOn) View.VISIBLE else View.GONE
        if (s.cameraOn && visible) attachPreview() else if (!s.cameraOn) detachPreview()

        camToggle.text = if (s.cameraOn) "STOP CAMERA" else "START CAMERA"
        camToggle.setBackgroundResource(if (s.cameraOn) R.drawable.btn_ghost else R.drawable.btn)
        camToggle.setTextColor(ContextCompat.getColor(this, if (s.cameraOn) R.color.muted else R.color.ink))
        camBadge.text = when {
            s.cameraLive -> "LIVE · CAM ${s.cameraSlot}"
            s.cameraOn -> "CONNECTING"
            else -> "OFF"
        }
        camBadge.setBackgroundResource(if (s.cameraLive) R.drawable.pill_live else R.drawable.pill)
        camBadge.setTextColor(ContextCompat.getColor(this, if (s.cameraLive) R.color.onGrad else R.color.muted))
        camStatus.setTextColor(ContextCompat.getColor(this, if (s.cameraError != null) R.color.warn else R.color.muted))
        camStatus.text = when {
            s.cameraError != null -> s.cameraError
            s.cameraLive -> "Sending ${camSettings.quality.label} at ${camSettings.fps} fps to “Oron Camera ${s.cameraSlot}”. You can lock the phone."
            s.cameraOn -> "Waiting for the laptop to accept camera slot ${s.cameraSlot}…"
            else -> "Mount the phone, pick its slot, then start. In OBS or vMix choose the matching “Oron Camera”."
        }
    }

    /* ---------------- Helpers ---------------- */

    /** Small muted label over a big value, like the sample's stat tiles. */
    private fun stat(label: String, value: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        sb.append(label)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.45f), 0, label.length, 0)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.muted)),
            0, label.length, 0,
        )
        sb.append("\n").append(value)
        return sb
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
