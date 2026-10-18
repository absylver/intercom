package ng.futurelabs.oronintercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

/**
 * Runs the intercom as a foreground service (like a phone call), so it keeps
 * working with the screen locked and the phone in a pocket.
 */
class IntercomService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TALK_TOGGLE = "talk_toggle"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "intercom"
        private const val NOTIF_ID = 7

        @Volatile var instance: IntercomService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val rtc = Executors.newSingleThreadExecutor { Thread(it, "oron-rtc") }

    private var socket: Socket? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var micTrack: AudioTrack? = null
    private var egl: EglBase? = null
    private var camera: CameraStreamer? = null
    private val peers = HashMap<String, Peer>()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var tones: ToneGenerator? = null
    private lateinit var audio: AudioManager

    private var name = "Camera"
    private var talking = false
    private var muted = false
    private var latched = false

    /** Runs on the WebRTC thread; silently ignored once the intercom has stopped. */
    private fun exec(block: () -> Unit) {
        if (!rtc.isShutdown) runCatching { rtc.execute(block) }
    }

    private class Peer(val pc: PeerConnection) {
        var remoteSet = false
        val pendingIce = ArrayList<IceCandidate>()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 3000)
                name = intent.getStringExtra(EXTRA_NAME) ?: "Camera"
                if (instance == null) begin(host, port)
            }
            ACTION_TALK_TOGGLE -> toggleLatch()
            ACTION_STOP -> {
                finish()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    /* ---------------- Start / stop ---------------- */

    private fun begin(host: String, port: Int) {
        instance = this
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        IntercomState.set { UiState(running = true, host = host) }

        goForeground("Connecting to the control room…")
        holdLocks()
        setupAudioRouting()
        setupMediaButtons()
        tones = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()

        exec { setupWebRtc() }
        connectSocket(host, port)
    }

    private fun finish() {
        setTalking(false)
        socket?.off()
        socket?.disconnect()
        socket = null
        camera?.dispose()
        exec {
            peers.values.forEach { runCatching { it.pc.dispose() } }
            peers.clear()
            camera = null
            runCatching { micTrack?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            micTrack = null; factory = null; adm = null
            runCatching { egl?.release() }
            egl = null
        }
        rtc.shutdown()
        mediaSession?.release()
        tones?.release()
        releaseAudioRouting()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        instance = null
        IntercomState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance != null) finish()
        super.onDestroy()
    }

    /* ---------------- Foreground notification ---------------- */

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Intercom", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while you are connected to the intercom"
                    setShowBadge(false)
                }
            )
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(
            this, 1, Intent(this, IntercomService::class.java).setAction(ACTION_TALK_TOGGLE), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, IntercomService::class.java).setAction(ACTION_STOP), flags
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Oron Intercom · $name")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (latched) "STOP TALKING" else "TALK", talk).build())
            .addAction(Notification.Action.Builder(null, "LEAVE", stop).build())
            .build()
    }

    private fun updateNotification() {
        if (instance == null) return
        val s = IntercomState.ui.value
        val text = when {
            !s.connected -> "Reconnecting to the control room…"
            s.muted -> "Muted by the director"
            s.talking -> "You are talking"
            s.directorTalking -> "Director speaking"
            s.voiceLive -> "Connected · listening"
            s.directorOnline -> "Connecting voice…"
            else -> "Waiting for the director"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    /* ---------------- Keep Wi-Fi and CPU awake ---------------- */

    private fun holdLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "oron:wifi").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oron:cpu").apply {
            setReferenceCounted(false); acquire()
        }
    }

    /* ---------------- Audio: earphones first, never the loudspeaker ---------------- */

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = routeAudio()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = routeAudio()
    }

    private fun setupAudioRouting() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        audio.registerAudioDeviceCallback(deviceCallback, main)
        routeAudio()
    }

    private fun routeAudio() {
        if (Build.VERSION.SDK_INT >= 31) {
            val order = listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            )
            val devices = audio.availableCommunicationDevices
            val pick = order.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }
            if (pick != null) runCatching { audio.setCommunicationDevice(pick) }
        } else {
            val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val wired = outs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            val bt = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            @Suppress("DEPRECATION")
            if (!wired && bt) {
                runCatching { audio.startBluetoothSco(); audio.isBluetoothScoOn = true }
            } else {
                runCatching { audio.stopBluetoothSco(); audio.isBluetoothScoOn = false }
            }
        }
    }

    private fun releaseAudioRouting() {
        if (!::audio.isInitialized) return
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= 31) runCatching { audio.clearCommunicationDevice() }
        @Suppress("DEPRECATION")
        runCatching { audio.stopBluetoothSco() }
        audio.mode = AudioManager.MODE_NORMAL
    }

    /* ---------------- Earphone button = push-to-talk ---------------- */
    // Hold the button: talk while held. Quick press: talk stays on until the next press.

    private var downAt = 0L
    private var startedByPress = false

    private fun setupMediaButtons() {
        mediaSession = MediaSession(this, "OronIntercom").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    } ?: return false
                    if (ev.keyCode !in setOf(
                            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                        )
                    ) return false
                    when (ev.action) {
                        KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) {
                            downAt = SystemClock.elapsedRealtime()
                            startedByPress = !talking
                            if (!talking) setTalking(true)
                        }
                        KeyEvent.ACTION_UP -> {
                            val held = SystemClock.elapsedRealtime() - downAt
                            if (startedByPress && held < 400) {
                                latched = true // short press: stay on
                                IntercomState.set { it.copy(latched = true) }
                                updateNotification()
                            } else {
                                latched = false
                                setTalking(false)
                            }
                        }
                    }
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    private fun toggleLatch() {
        latched = !talking
        setTalking(!talking)
    }

    /** Called from the screen's TALK button too. */
    fun setTalking(on: Boolean) {
        val next = on && !muted
        if (!next) latched = false
        if (next == talking) {
            IntercomState.set { it.copy(latched = latched) }
            return
        }
        talking = next
        exec { micTrack?.setEnabled(next) }
        if (next) tones?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        socket?.emit("operator-talking", JSONObject().put("talking", next))
        IntercomState.set { it.copy(talking = next, latched = latched) }
        updateNotification()
    }

    /* ---------------- Camera (called from the screen) ---------------- */

    val eglContext: EglBase.Context? get() = egl?.eglBaseContext

    fun startCamera(settings: CameraSettings) {
        // Tell Android this service now also uses the camera, so it keeps running with the screen off.
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startForeground(
                    NOTIF_ID, buildNotification("Camera on · slot ${settings.slot}"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            }
        }
        camera?.start(settings)
    }

    fun updateCamera(settings: CameraSettings) { camera?.update(settings) }

    fun stopCamera() {
        camera?.stop()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { startForeground(NOTIF_ID, buildNotification("Connected"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        }
    }

    fun addPreview(sink: VideoSink) { camera?.addPreview(sink) }
    /** Zoom, exposure, white balance, focus, light, stabilization from the controls panel. */
    fun setCameraControls(c: CameraControls) { camera?.setControls(c) }

    /** Tap-to-focus on the preview. */
    fun focusAtView(x: Float, y: Float, viewW: Int, viewH: Int, fill: Boolean, mirrored: Boolean) {
        camera?.focusAtView(x, y, viewW, viewH, fill, mirrored)
    }

    /** Full-screen view reports the screen rotation so the picture stays upright either way round. */
    fun setStreamRotation(degrees: Int?) { camera?.setScreenRotation(degrees) }
    fun removePreview(sink: VideoSink) { camera?.removePreview(sink) }

    /* ---------------- WebRTC ---------------- */

    private fun setupWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        adm = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val eglBase = EglBase.create()
        egl = eglBase
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            // Hardware H.264 (and VP8) for the phone camera feed.
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        camera = CameraStreamer(
            context = applicationContext,
            factory = factory!!,
            egl = eglBase,
            exec = { block -> exec(block) },
            emit = { event, data -> socket?.emit(event, data) },
            operatorName = { name },
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory!!.createAudioSource(constraints)
        micTrack = factory!!.createAudioTrack("oron-mic", source).apply { setEnabled(false) }
    }

    private fun peerFor(directorId: String): Peer? {
        peers[directorId]?.let { return it }
        val f = factory ?: return null
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                val cand = JSONObject()
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex)
                socket?.emit("signal", JSONObject().put("to", directorId).put("data", JSONObject().put("candidate", cand)))
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                val live = state == PeerConnection.PeerConnectionState.CONNECTED
                IntercomState.set { it.copy(voiceLive = live) }
                main.post { updateNotification() }
                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                    exec { dropPeer(directorId) }
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Remote audio plays automatically through the audio device module.
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        micTrack?.let { pc.addTrack(it, listOf("oron")) }
        return Peer(pc).also { peers[directorId] = it }
    }

    private fun dropPeer(id: String) {
        peers.remove(id)?.let { runCatching { it.pc.dispose() } }
        if (peers.isEmpty()) IntercomState.set { it.copy(voiceLive = false) }
    }

    private fun onSignal(from: String, data: JSONObject) = exec {
        val peer = peerFor(from) ?: return@exec
        val sdp = data.optJSONObject("sdp")
        val cand = data.optJSONObject("candidate")
        if (sdp != null) {
            val type = SessionDescription.Type.fromCanonicalForm(sdp.optString("type"))
            val desc = SessionDescription(type, sdp.optString("sdp"))
            peer.pc.setRemoteDescription(object : SimpleSdp() {
                override fun onSetSuccess() = exec {
                    peer.remoteSet = true
                    peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
                    peer.pendingIce.clear()
                    if (type == SessionDescription.Type.OFFER) answer(from, peer)
                }
            }, desc)
        } else if (cand != null) {
            val ice = IceCandidate(
                cand.optString("sdpMid", "0"),
                cand.optInt("sdpMLineIndex", 0),
                cand.optString("candidate"),
            )
            if (peer.remoteSet) peer.pc.addIceCandidate(ice) else peer.pendingIce.add(ice)
        }
    }

    private fun answer(to: String, peer: Peer) {
        peer.pc.createAnswer(object : SimpleSdp() {
            override fun onCreateSuccess(desc: SessionDescription) = exec {
                peer.pc.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        val sdp = JSONObject().put("type", "answer").put("sdp", desc.description)
                        socket?.emit("signal", JSONObject().put("to", to).put("data", JSONObject().put("sdp", sdp)))
                    }
                }, desc)
            }
        }, MediaConstraints())
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    /* ---------------- Signaling (same server as the web page) ---------------- */

    private fun connectSocket(host: String, port: Int) {
        val opts = IO.Options().apply {
            transports = arrayOf(WebSocket.NAME)
            reconnection = true
            reconnectionDelay = 500
            reconnectionDelayMax = 2000
            reconnectionAttempts = Int.MAX_VALUE
            timeout = 5000
        }
        val s = IO.socket("http://$host:$port", opts)
        socket = s

        s.on(Socket.EVENT_CONNECT) {
            IntercomState.set { it.copy(connected = true, error = null) }
            s.emit("register", JSONObject().put("role", "operator").put("name", name))
            camera?.announce() // the laptop forgets camera slots when the WiFi drops
            main.post { updateNotification() }
        }
        s.on(Socket.EVENT_DISCONNECT) {
            IntercomState.set { it.copy(connected = false, voiceLive = false, directorTalking = false) }
            exec { peers.keys.toList().forEach { dropPeer(it) } }
            main.post { setTalking(false); updateNotification() }
        }
        s.on(Socket.EVENT_CONNECT_ERROR) {
            IntercomState.set { it.copy(connected = false, error = "Can't reach the laptop at $host") }
        }
        s.on("director-online") { args ->
            val online = args.firstOrNull() as? Boolean ?: false
            IntercomState.set { it.copy(directorOnline = online) }
            main.post { updateNotification() }
        }
        s.on("signal") { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            val from = o.optString("from")
            val data = o.optJSONObject("data") ?: return@on
            if (from.isNotEmpty()) onSignal(from, data)
        }
        s.on("director-talking") { args ->
            val on = args.firstOrNull() as? Boolean ?: false
            if (on && !IntercomState.ui.value.directorTalking) tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            IntercomState.set { it.copy(directorTalking = on) }
            main.post { updateNotification() }
        }
        s.on("muted") { args ->
            muted = args.firstOrNull() as? Boolean ?: false
            IntercomState.set { it.copy(muted = muted) }
            main.post { if (muted) setTalking(false); updateNotification() }
        }
        s.on("peer-left") { args ->
            val id = args.firstOrNull() as? String ?: return@on
            exec { dropPeer(id) }
        }
        // Camera feed signaling (separate from the intercom audio).
        s.on("cam-pair") { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            camera?.onPair(o.optString("peer"), o.optInt("gen", 0))
        }
        s.on("cam-signal") { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            val data = o.optJSONObject("data") ?: return@on
            camera?.onSignal(o.optString("from"), data)
        }
        s.on("cam-unpair") { camera?.onUnpair() }
        // Lens / exposure / focus changes made on the laptop's console.
        s.on("cam-control") { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            val id = o.optString("id")
            if (id.isEmpty()) return@on
            camera?.applyRemote(id, if (o.isNull("value")) null else o.opt("value")) { front ->
                val cam = camera ?: return@applyRemote
                getSharedPreferences("oron", Context.MODE_PRIVATE).edit().putBoolean("camFront", front).apply()
                cam.update(cam.settings.copy(front = front))
            }
        }
        s.on("camera-stopped") { args ->
            val o = args.firstOrNull() as? JSONObject
            camera?.onStoppedByServer(o?.optString("reason") ?: "")
        }
        s.on("camera-error") { args ->
            val msg = args.firstOrNull() as? String ?: return@on
            camera?.note("laptop says: $msg")
            IntercomState.set { it.copy(cameraError = msg) }
        }
        s.connect()
    }
}
