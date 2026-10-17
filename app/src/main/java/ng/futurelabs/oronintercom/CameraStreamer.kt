package ng.futurelabs.oronintercom

import android.content.Context
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Video quality presets the operator can pick. */
enum class Quality(val label: String, val width: Int, val height: Int, val kbps: Int) {
    HD1080("1080p", 1920, 1080, 8000),
    HD720("720p", 1280, 720, 4500),
    SD480("480p", 854, 480, 2000);

    companion object {
        fun from(label: String?) = entries.firstOrNull { it.label == label } ?: HD720
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
 * WebRTC with hardware H.264 when available; the laptop's receiver window answers.
 * All PeerConnection work runs on the service's WebRTC thread via [exec].
 */
class CameraStreamer(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    private val exec: (() -> Unit) -> Unit,
    private val emit: (String, JSONObject) -> Unit,
    private val operatorName: () -> String,
) {
    var settings = CameraSettings()
        private set
    var running = false
        private set

    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    var track: VideoTrack? = null
        private set

    private var pc: PeerConnection? = null
    private var sender: RtpSender? = null
    private var receiverId: String? = null
    private var remoteSet = false
    private val pendingIce = ArrayList<IceCandidate>()
    private val previewSinks = HashSet<VideoSink>()

    /* ---------------- Capture ---------------- */

    fun start(s: CameraSettings) = exec {
        if (running) stopInternal(sendStop = false)
        settings = s
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { if (s.front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: names.firstOrNull()
        if (name == null) {
            IntercomState.set { it.copy(cameraOn = false, cameraError = "No camera found on this phone") }
            return@exec
        }
        val cap = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("oron-camera", egl.eglBaseContext)
        val src = factory.createVideoSource(false)
        cap.initialize(helper, context, src.capturerObserver)
        cap.startCapture(s.quality.width, s.quality.height, s.fps)
        capturer = cap
        textureHelper = helper
        source = src
        track = factory.createVideoTrack("oron-video", src).also { t -> previewSinks.forEach { t.addSink(it) } }
        running = true
        IntercomState.set {
            it.copy(cameraOn = true, cameraLive = false, cameraSlot = s.slot, cameraError = null, cameraFront = s.front)
        }
        announce()
    }

    /** Tells the laptop which slot this phone feeds. Also used after a WiFi reconnect. */
    fun announce() = exec {
        if (!running) return@exec
        emit(
            "camera-start",
            JSONObject()
                .put("slot", settings.slot)
                .put("name", operatorName())
                .put("width", settings.quality.width)
                .put("height", settings.quality.height)
                .put("fps", settings.fps),
        )
    }

    /** Applies new settings while running (slot change re-pairs; the rest happens live). */
    fun update(s: CameraSettings) = exec {
        if (!running) { settings = s; return@exec }
        val old = settings
        settings = s
        if (s.front != old.front) {
            capturer?.switchCamera(null)
            IntercomState.set { it.copy(cameraFront = s.front) }
        }
        if (s.quality != old.quality || s.fps != old.fps) {
            capturer?.changeCaptureFormat(s.quality.width, s.quality.height, s.fps)
            applyEncoding()
        }
        if (s.slot != old.slot) {
            closePeer()
            IntercomState.set { it.copy(cameraSlot = s.slot, cameraLive = false) }
            announce()
        } else if (s.quality != old.quality || s.fps != old.fps) {
            announce() // refresh the details shown on the director's console
        }
    }

    fun stop() = exec { stopInternal(sendStop = true) }

    private fun stopInternal(sendStop: Boolean) {
        if (sendStop && running) emit("camera-stop", JSONObject())
        closePeer()
        runCatching { capturer?.stopCapture() }
        track?.let { t -> previewSinks.forEach { runCatching { t.removeSink(it) } } }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { capturer?.dispose() }
        runCatching { textureHelper?.dispose() }
        capturer = null; source = null; track = null; textureHelper = null
        running = false
        IntercomState.set { it.copy(cameraOn = false, cameraLive = false) }
    }

    /* ---------------- Preview on the phone ---------------- */

    fun addPreview(sink: VideoSink) = exec {
        previewSinks.add(sink)
        track?.addSink(sink)
    }

    fun removePreview(sink: VideoSink) = exec {
        previewSinks.remove(sink)
        runCatching { track?.removeSink(sink) }
    }

    /* ---------------- Connection to the laptop's receiver ---------------- */

    fun onPair(peer: String) = exec {
        if (!running) return@exec
        closePeer()
        receiverId = peer
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                val cand = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                emit("cam-signal", JSONObject().put("to", peer).put("data", JSONObject().put("candidate", cand)))
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                IntercomState.set { it.copy(cameraLive = state == PeerConnection.PeerConnectionState.CONNECTED) }
                if (state == PeerConnection.PeerConnectionState.FAILED) exec { if (receiverId == peer) onPair(peer) }
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
        }) ?: return@exec
        pc = conn
        sender = track?.let { conn.addTrack(it, listOf("oron-camera")) }
        applyEncoding()
        conn.createOffer(object : Sdp() {
            override fun onCreateSuccess(desc: SessionDescription) = exec {
                val tuned = SessionDescription(desc.type, preferH264(desc.description))
                conn.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        val sdp = JSONObject().put("type", "offer").put("sdp", tuned.description)
                        emit("cam-signal", JSONObject().put("to", peer).put("data", JSONObject().put("sdp", sdp)))
                    }
                }, tuned)
            }
        }, MediaConstraints())
    }

    fun onSignal(from: String, data: JSONObject) = exec {
        val conn = pc ?: return@exec
        if (from != receiverId) return@exec
        val sdp = data.optJSONObject("sdp")
        val cand = data.optJSONObject("candidate")
        if (sdp != null && sdp.optString("type") == "answer") {
            conn.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() = exec {
                    remoteSet = true
                    pendingIce.forEach { conn.addIceCandidate(it) }
                    pendingIce.clear()
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")))
        } else if (cand != null) {
            val ice = IceCandidate(cand.optString("sdpMid", "0"), cand.optInt("sdpMLineIndex", 0), cand.optString("candidate"))
            if (remoteSet) conn.addIceCandidate(ice) else pendingIce.add(ice)
        }
    }

    fun onUnpair() = exec {
        closePeer()
        IntercomState.set { it.copy(cameraLive = false) }
    }

    /** Another phone took this slot, or the laptop rejected it. */
    fun onStoppedByServer(reason: String) = exec {
        stopInternal(sendStop = false)
        IntercomState.set {
            it.copy(cameraError = if (reason == "replaced") "Another phone took camera slot ${settings.slot}" else null)
        }
    }

    fun dispose() = exec { stopInternal(sendStop = false) }

    private fun closePeer() {
        runCatching { pc?.dispose() }
        pc = null
        sender = null
        receiverId = null
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
