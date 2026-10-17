package ng.futurelabs.oronintercom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What this phone's camera can do (only supported controls are shown to the operator). */
class CameraCaps(
    val zoomMax: Float,
    val evMin: Int,
    val evMax: Int,
    val evStep: Float,
    val manualSensor: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    val awbModes: List<Int>,
    val canLockAe: Boolean,
    val canLockAwb: Boolean,
    val autoFocus: Boolean,
    val minFocusDistance: Float,
    val hasFlash: Boolean,
    val videoStabilization: Boolean,
    val opticalStabilization: Boolean,
    val antibandingModes: List<Int>,
)

/** The operator's chosen camera settings. */
data class CameraControls(
    val zoom: Float = 1f,
    val ev: Int = 0,
    val aeLock: Boolean = false,
    val manualExposure: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 1_000_000_000L / 50,
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val awbLock: Boolean = false,
    val focusManual: Boolean = false,
    val focusDistance: Float = 0f, // diopters: 0 = far (infinity), minFocusDistance = closest
    val torch: Boolean = false,
    val stabilization: Boolean = true,
    val antibanding: Int = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
)

/**
 * Camera2 capturer for WebRTC with full manual control.
 * Frames go through a SurfaceTexture into WebRTC exactly like WebRTC's own Camera2Session does,
 * so the rotation maths (see CameraStreamer.frameRotation) stays the same.
 */
class OronCameraCapturer(
    private val cameraId: String,
    private val front: Boolean,
    private val sensorOrientation: Int,
    private val rotationProvider: () -> Int,
    private val onCaps: (CameraCaps) -> Unit,
    private val onProblem: (String) -> Unit,
) : VideoCapturer {

    private lateinit var appContext: Context
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var handler: Handler? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var width = 1280
    private var height = 720
    private var fps = 30
    @Volatile private var running = false
    private var caps: CameraCaps? = null
    private var focusRegion: MeteringRectangle? = null

    @Volatile var controls: CameraControls = CameraControls()
        private set

    private val cameraManager: CameraManager
        get() = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, context: Context, capturerObserver: CapturerObserver) {
        helper = surfaceTextureHelper
        appContext = context.applicationContext
        observer = capturerObserver
        handler = surfaceTextureHelper.handler
    }

    override fun startCapture(w: Int, h: Int, framerate: Int) {
        width = w
        height = h
        fps = framerate
        handler?.post { openCamera() }
    }

    override fun stopCapture() {
        val h = handler ?: return
        val done = CountDownLatch(1)
        h.post {
            closeCamera()
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
    }

    override fun changeCaptureFormat(w: Int, h: Int, framerate: Int) {
        handler?.post {
            closeCamera()
            width = w
            height = h
            fps = framerate
            openCamera()
        }
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = false

    /** New settings from the operator; applied to the running camera straight away. */
    fun setControls(c: CameraControls) {
        controls = c
        handler?.post { applyRequest() }
    }

    /** Tap-to-focus at a point given in sensor-normalized coordinates (0..1). */
    fun focusAt(sensorX: Float, sensorY: Float) {
        handler?.post { startTapFocus(sensorX, sensorY) }
    }

    /** Back to continuous autofocus. */
    fun clearFocusPoint() {
        handler?.post {
            focusRegion = null
            applyRequest()
        }
    }

    /* ---------------- Opening / closing ---------------- */

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val h = helper ?: return
        try {
            val ch = cameraManager.getCameraCharacteristics(cameraId)
            chars = ch
            val c = readCaps(ch)
            caps = c
            onCaps(c)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val size = chooseSize(sizes, width, height)
            h.setTextureSize(size.width, size.height)
            surface = Surface(h.surfaceTexture)
            h.startListening { frame -> onFrame(frame) }
            running = true
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!running) {
                        d.close()
                        return
                    }
                    device = d
                    createSession()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                    onProblem("camera was taken by another app")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    device = null
                    onProblem("camera error $error")
                }
            }, handler)
            observer?.onCapturerStarted(true)
        } catch (e: Exception) {
            onProblem("could not open camera: ${e.message}")
            observer?.onCapturerStarted(false)
        }
    }

    private fun closeCamera() {
        val wasRunning = running
        running = false
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        runCatching { helper?.stopListening() }
        runCatching { surface?.release() }
        surface = null
        if (wasRunning) observer?.onCapturerStopped()
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val s = surface ?: return
        try {
            d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (!running) {
                        cs.close()
                        return
                    }
                    session = cs
                    applyRequest()
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    onProblem("camera session could not start")
                }
            }, handler)
        } catch (e: Exception) {
            onProblem("camera session error: ${e.message}")
        }
    }

    /* ---------------- Frames to WebRTC ---------------- */

    private fun onFrame(frame: VideoFrame) {
        if (!running) return
        val buffer = frame.buffer
        if (buffer !is TextureBufferImpl) {
            observer?.onFrameCaptured(frame)
            return
        }
        // Same correction WebRTC's Camera2Session applies: undo the SurfaceTexture rotation
        // (and the OS mirroring of the front camera); the frame's rotation then says how to show it.
        val m = android.graphics.Matrix()
        m.preTranslate(0.5f, 0.5f)
        if (front) m.preScale(-1f, 1f)
        m.preRotate(-sensorOrientation.toFloat())
        m.preTranslate(-0.5f, -0.5f)
        val corrected = buffer.applyTransformMatrix(m, buffer.width, buffer.height)
        val out = VideoFrame(corrected, rotationProvider(), frame.timestampNs)
        observer?.onFrameCaptured(out)
        out.release()
    }

    /* ---------------- Capture request with the operator's controls ---------------- */

    private fun applyRequest() {
        val d = device ?: return
        val s = session ?: return
        val target = surface ?: return
        val ch = chars ?: return
        val cp = caps ?: return
        val c = controls
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(target)
            fillRequest(b, ch, cp, c)
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            onProblem("camera settings rejected: ${e.message}")
        }
    }

    private fun fillRequest(b: CaptureRequest.Builder, ch: CameraCharacteristics, cp: CameraCaps, c: CameraControls) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        bestFpsRange(ch)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        // Zoom
        applyZoom(b, ch, c.zoom.coerceIn(1f, cp.zoomMax))

        // Exposure: manual ISO/shutter, or automatic with brightness offset
        if (c.manualExposure && cp.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso.coerceIn(cp.isoMin, cp.isoMax))
            val frameNs = 1_000_000_000L / max(1, fps)
            val hi = max(cp.exposureMinNs, min(cp.exposureMaxNs, frameNs))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.shutterNs.coerceIn(cp.exposureMinNs, hi))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.ev.coerceIn(cp.evMin, cp.evMax))
            if (cp.canLockAe) b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            if (c.antibanding in cp.antibandingModes) b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, c.antibanding)
        }

        // Light (torch works in every exposure mode)
        if (cp.hasFlash) {
            b.set(CaptureRequest.FLASH_MODE, if (c.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }

        // White balance
        val awb = if (c.awbMode in cp.awbModes) c.awbMode else CaptureRequest.CONTROL_AWB_MODE_AUTO
        b.set(CaptureRequest.CONTROL_AWB_MODE, awb)
        if (cp.canLockAwb) b.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)

        // Focus
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val region = focusRegion
        when {
            c.focusManual && cp.minFocusDistance > 0f -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistance.coerceIn(0f, cp.minFocusDistance))
            }
            region != null && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0 && !c.manualExposure) {
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
            }
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            else -> Unit
        }

        // Stabilization
        if (cp.videoStabilization) {
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
        if (cp.opticalStabilization) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (c.stabilization) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, ch: CameraCharacteristics, zoom: Float) {
        if (Build.VERSION.SDK_INT >= 30 && ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            return
        }
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(active, zoom))
    }

    private fun cropFor(active: Rect, zoom: Float): Rect {
        val w = (active.width() / zoom).toInt()
        val h = (active.height() / zoom).toInt()
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun startTapFocus(nx: Float, ny: Float) {
        val ch = chars ?: return
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        // Area of the sensor currently in the picture (zoom crops the centre).
        val area = cropFor(active, controls.zoom.coerceAtLeast(1f))
        val cx = area.left + (nx.coerceIn(0f, 1f) * area.width()).toInt()
        val cy = area.top + (ny.coerceIn(0f, 1f) * area.height()).toInt()
        val half = max(40, area.width() / 12)
        val r = Rect(
            max(active.left, cx - half),
            max(active.top, cy - half),
            min(active.right - 1, cx + half),
            min(active.bottom - 1, cy + half),
        )
        focusRegion = MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        if (controls.focusManual) controls = controls.copy(focusManual = false)
        applyRequest()
        // Kick off a focus scan on that spot.
        val d = device ?: return
        val s = session ?: return
        val target = surface ?: return
        val cp = caps ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(target)
            fillRequest(b, ch, cp, controls)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        } catch (e: Exception) {
            onProblem("tap to focus failed: ${e.message}")
        }
    }

    /* ---------------- Helpers ---------------- */

    private fun readCaps(ch: CameraCharacteristics): CameraCaps {
        val capsList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manual = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsList
        val zoomMax = if (Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                ?: ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        } else {
            ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val stab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList()
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()
        return CameraCaps(
            zoomMax = min(zoomMax, 10f).coerceAtLeast(1f),
            evMin = ev.lower,
            evMax = ev.upper,
            evStep = evStep,
            manualSensor = manual && iso != null && exp != null,
            isoMin = iso?.lower ?: 100,
            isoMax = iso?.upper ?: 100,
            exposureMinNs = exp?.lower ?: 0L,
            exposureMaxNs = exp?.upper ?: 0L,
            awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            canLockAe = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
            canLockAwb = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
            autoFocus = afModes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF },
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            videoStabilization = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in stab,
            opticalStabilization = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in ois,
            antibandingModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList() ?: emptyList(),
        )
    }

    /** Fixed frame rate if the camera offers it (smoothest video), otherwise the closest range. */
    private fun bestFpsRange(ch: CameraCharacteristics): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.minByOrNull { abs(it.upper - fps) * 100 + abs(it.lower - fps) }
    }

    /** Exact size if possible, else the 16:9 size nearest in area. */
    private fun chooseSize(sizes: Array<Size>, w: Int, h: Int): Size {
        if (sizes.isEmpty()) return Size(w, h)
        sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        val target = w.toLong() * h
        val wide = sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }
        val pool = if (wide.isNotEmpty()) wide else sizes.toList()
        return pool.minByOrNull { abs(it.width.toLong() * it.height - target) } ?: sizes[0]
    }
}
