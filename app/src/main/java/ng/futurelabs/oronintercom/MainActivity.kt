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
        super.onStop()
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
