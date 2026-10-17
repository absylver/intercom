package ng.futurelabs.oronintercom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Video quality presets the operator can pick. */
enum class Quality(val label: String, val width: Int, val height: Int, val kbps: Int) {
    HD1080("1080p", 1920, 1080, 8000),
    HD720("720p", 1280, 720, 4500),
    SD480("480p", 854, 480, 2000);

    companion object {
        fun from(label: String?): Quality = entries.firstOrNull { it.label == label } ?: HD720
    }
}

data class CameraSettings(
    val slot: Int = 1,
    val quality: Quality = Quality.HD720,
    val fps: Int = 30,
    val front: Boolean = false,
)

/**
 * Sends this phone's camera to one "Oron Camera" slot on the laptop.
 *
 *  - The back camera always sends a landscape picture and the front camera a portrait one,
 *    whatever the screen is doing (locked, in a pocket, app in the background).
 *  - Every pairing from the laptop is numbered ("gen"); messages from older attempts are ignored.
 *  - All PeerConnection work runs on the service's WebRTC thread via [exec].
 */
class CameraStreamer(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val exec: (() -> Unit) -> Unit,
    private val emit: (String, JSONObject) -> Unit,
    private val operatorName: () -> String,
) {
    var settings: CameraSettings = CameraSettings()
        private set
    var running: Boolean = false
        private set

    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null

    // Orientation of the picture we send.
    @Volatile private var sensorOrientation: Int = 90
    @Volatile private var frontFacing: Boolean = false
    /** Screen rotation reported by the full-screen view (90/270 in landscape), or null for the lens default. */
    @Volatile private var screenRotation: Int? = null

    private var pc: PeerConnection? = null
    private var sender: RtpSender? = null
    private var receiverId: String? = null
    private var gen: Int = 0
    private var remoteSet: Boolean = false
    private val pendingIce = ArrayList<IceCandidate>()
    private val previewSinks = HashSet<VideoSink>()

    private val main = Handler(Looper.getMainLooper())
    private val pairWatchdog = Runnable {
        exec {
            if (running && receiverId == null) {
                IntercomState.set {
                    it.copy(
                        cameraError = "The laptop didn't answer. Make sure it is running the Oron Intercom " +
                            "desktop app (version 1.6 or newer) and is on the same WiFi.",
                    )
                }
            }
        }
    }

    /* ---------------- Capture ---------------- */

    fun start(s: CameraSettings) {
        exec {
            if (running) stopInternal(sendStop = false)
            settings = s
            val helper = SurfaceTextureHelper.create("oron-camera", egl.eglBaseContext)
            val src = factory.createVideoSource(false)
            textureHelper = helper
            source = src
            if (!openCapturer(s.front)) {
                runCatching { src.dispose() }
                runCatching { helper.dispose() }
                textureHelper = null
                source = null
                IntercomState.set { it.copy(cameraOn = false, cameraError = "No camera found on this phone") }
                return@exec
            }
            val t = factory.createVideoTrack("oron-video", src)
            previewSinks.forEach { t.addSink(it) }
            track = t
            running = true
            IntercomState.set {
                it.copy(cameraOn = true, cameraLive = false, cameraSlot = s.slot, cameraError = null, cameraFront = s.front)
            }
            announce()
        }
    }

    /** Opens the front or back camera and feeds it through our rotation fixer into the video source. */
    private fun openCapturer(front: Boolean): Boolean {
        val src = source ?: return false
        val helper = textureHelper ?: return false
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: names.firstOrNull()
            ?: return false
        frontFacing = enumerator.isFrontFacing(name)
        sensorOrientation = runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.getCameraCharacteristics(name).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }.getOrDefault(if (frontFacing) 270 else 90)
        val cap = enumerator.createCapturer(name, null)
        cap.initialize(helper, context, RotationFixer(src.capturerObserver))
        cap.startCapture(settings.quality.width, settings.quality.height, settings.fps)
        capturer = cap
        return true
    }

    private fun closeCapturer() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
    }

    /**
     * Rotation for each frame: back camera -> landscape, front camera -> portrait,
     * unless the full-screen view tells us the real screen rotation.
     * Same formula WebRTC uses internally, with a fixed "device orientation".
     */
    private fun frameRotation(): Int {
        val device = screenRotation ?: if (frontFacing) 0 else 90
        return if (frontFacing) (sensorOrientation + device) % 360 else (sensorOrientation + 360 - device) % 360
    }

    private inner class RotationFixer(private val inner: CapturerObserver) : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) {
            inner.onCapturerStarted(success)
        }

        override fun onCapturerStopped() {
            inner.onCapturerStopped()
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            val rotation = frameRotation()
            if (rotation == frame.rotation) {
                inner.onFrameCaptured(frame)
            } else {
                inner.onFrameCaptured(VideoFrame(frame.buffer, rotation, frame.timestampNs))
            }
        }
    }

    /** Called by the full-screen view with the screen's rotation (0/90/180/270), or null when it closes. */
    fun setScreenRotation(degrees: Int?) {
        screenRotation = degrees
    }

    /** Tells the laptop which slot this phone feeds. Also used after a WiFi reconnect. */
    fun announce() {
        exec {
            if (!running) return@exec
            receiverId = null
            emit(
                "camera-start",
                JSONObject()
                    .put("slot", settings.slot)
                    .put("name", operatorName())
                    .put("width", settings.quality.width)
                    .put("height", settings.quality.height)
                    .put("fps", settings.fps),
            )
            main.removeCallbacks(pairWatchdog)
            main.postDelayed(pairWatchdog, 8000)
        }
    }

    /** Applies new settings while running. Only a slot change reconnects; the rest happens live. */
    fun update(s: CameraSettings) {
        exec {
            if (!running) {
                settings = s
                return@exec
            }
            val old = settings
            settings = s
            if (s.front != old.front) {
                closeCapturer()
                openCapturer(s.front)
                IntercomState.set { it.copy(cameraFront = s.front) }
            } else if (s.quality != old.quality || s.fps != old.fps) {
                capturer?.changeCaptureFormat(s.quality.width, s.quality.height, s.fps)
            }
            applyEncoding()
            if (s.slot != old.slot) {
                closePeer()
                IntercomState.set { it.copy(cameraSlot = s.slot, cameraLive = false) }
                announce()
            } else if (s.quality != old.quality || s.fps != old.fps) {
                emit(
                    "camera-info",
                    JSONObject().put("width", s.quality.width).put("height", s.quality.height).put("fps", s.fps),
                )
            }
        }
    }

    fun stop() {
        exec { stopInternal(sendStop = true) }
    }

    private fun stopInternal(sendStop: Boolean) {
        main.removeCallbacks(pairWatchdog)
        if (sendStop && running) emit("camera-stop", JSONObject())
        closePeer()
        closeCapturer()
        track?.let { t -> previewSinks.forEach { runCatching { t.removeSink(it) } } }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { textureHelper?.dispose() }
        source = null
        track = null
        textureHelper = null
        running = false
        IntercomState.set { it.copy(cameraOn = false, cameraLive = false) }
    }

    /* ---------------- Preview on the phone ---------------- */

    fun addPreview(sink: VideoSink) {
        exec {
            previewSinks.add(sink)
            track?.addSink(sink)
        }
    }

    fun removePreview(sink: VideoSink) {
        exec {
            previewSinks.remove(sink)
            runCatching { track?.removeSink(sink) }
        }
    }

    /* ---------------- Connection to the laptop's receiver ---------------- */

    fun onPair(peer: String, pairGen: Int) {
        exec { connect(peer, pairGen) }
    }

    private fun connect(peer: String, pairGen: Int) {
        if (!running) return
        main.removeCallbacks(pairWatchdog)
        closePeer()
        receiverId = peer
        gen = pairGen
        IntercomState.set { it.copy(cameraError = null) }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val myGen = pairGen
        val conn = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                val cand = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                emit("cam-signal", JSONObject().put("to", peer).put("data", JSONObject().put("candidate", cand).put("gen", myGen)))
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                exec {
                    if (gen != myGen) return@exec
                    val live = state == PeerConnection.PeerConnectionState.CONNECTED
                    IntercomState.set {
                        it.copy(
                            cameraLive = live,
                            cameraError = if (state == PeerConnection.PeerConnectionState.FAILED) {
                                "Video couldn't connect to the laptop. Retrying…"
                            } else {
                                it.cameraError
                            },
                        )
                    }
                    if (state == PeerConnection.PeerConnectionState.FAILED && receiverId == peer) {
                        main.postDelayed({ exec { if (gen == myGen && running) connect(peer, myGen) } }, 1500)
                    }
                }
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
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return
        pc = conn
        sender = track?.let { conn.addTrack(it, listOf("oron-camera")) }
        applyEncoding()
        conn.createOffer(object : Sdp() {
            override fun onCreateSuccess(desc: SessionDescription) {
                exec {
                    if (pc !== conn) return@exec
                    val tuned = SessionDescription(desc.type, preferH264(desc.description))
                    conn.setLocalDescription(object : Sdp() {
                        override fun onSetSuccess() {
                            val sdp = JSONObject().put("type", "offer").put("sdp", tuned.description)
                            emit("cam-signal", JSONObject().put("to", peer).put("data", JSONObject().put("sdp", sdp).put("gen", myGen)))
                        }
                    }, tuned)
                }
            }
        }, MediaConstraints())
    }

    fun onSignal(from: String, data: JSONObject) {
        exec {
            val conn = pc ?: return@exec
            if (from != receiverId) return@exec
            if (data.has("gen") && data.optInt("gen") != gen) return@exec // from an earlier attempt
            val sdp = data.optJSONObject("sdp")
            val cand = data.optJSONObject("candidate")
            if (sdp != null && sdp.optString("type") == "answer") {
                conn.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        exec {
                            if (pc !== conn) return@exec
                            remoteSet = true
                            pendingIce.forEach { conn.addIceCandidate(it) }
                            pendingIce.clear()
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")))
            } else if (cand != null) {
                val ice = IceCandidate(cand.optString("sdpMid", "0"), cand.optInt("sdpMLineIndex", 0), cand.optString("candidate"))
                if (remoteSet) conn.addIceCandidate(ice) else pendingIce.add(ice)
            }
        }
    }

    fun onUnpair() {
        exec {
            closePeer()
            receiverId = null
            IntercomState.set { it.copy(cameraLive = false) }
            if (running) {
                main.removeCallbacks(pairWatchdog)
                main.postDelayed(pairWatchdog, 8000)
            }
        }
    }

    /** Another phone took this slot, or the laptop rejected it. */
    fun onStoppedByServer(reason: String) {
        exec {
            stopInternal(sendStop = false)
            IntercomState.set {
                it.copy(cameraError = if (reason == "replaced") "Another phone took camera slot ${settings.slot}" else null)
            }
        }
    }

    fun dispose() {
        exec { stopInternal(sendStop = false) }
    }

    private fun closePeer() {
        runCatching { pc?.dispose() }
        pc = null
        sender = null
        remoteSet = false
        pendingIce.clear()
    }

    /** Keep resolution (drop frame rate first if WiFi gets busy) and set the bitrate for the chosen quality. */
    private fun applyEncoding() {
        val snd = sender ?: return
        runCatching {
            val p = snd.parameters
            val kbps = if (settings.fps >= 60) settings.quality.kbps * 3 / 2 else settings.quality.kbps
            p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            p.encodings.forEach { e ->
                e.maxBitrateBps = kbps * 1000
                e.minBitrateBps = kbps * 300
                e.maxFramerate = settings.fps
            }
            snd.setParameters(p)
        }
    }

    /** Puts H.264 first in the offer so the phone's hardware encoder is used. */
    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIndex < 0) return sdp
        val h264 = lines.mapNotNull { Regex("^a=rtpmap:(\\d+) H264/90000").find(it)?.groupValues?.get(1) }
        if (h264.isEmpty()) return sdp
        val parts = lines[mIndex].split(" ")
        if (parts.size <= 3) return sdp
        val header = parts.take(3)
        val pts = parts.drop(3)
        val ordered = pts.filter { it in h264 } + pts.filter { it !in h264 }
        lines[mIndex] = (header + ordered).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
