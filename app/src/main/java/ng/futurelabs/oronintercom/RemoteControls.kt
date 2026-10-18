package ng.futurelabs.oronintercom

import android.hardware.camera2.CaptureRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera controls for the laptop's console.
 * The phone describes what its lens can do as a list of controls (sliders, choices, switches)
 * and the console sends back `{ id, value }` changes. The same list format is used by the
 * web (browser) camera, so the console needs no phone-specific code.
 */
object RemoteControls {

    private val SHUTTERS = listOf(25, 30, 50, 60, 100, 120, 250, 500, 1000, 2000)

    private val AWB_NAMES = linkedMapOf(
        CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
        CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade",
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent",
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
    )

    private val FLICKER_NAMES = linkedMapOf(
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO to "Auto",
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ to "50 Hz",
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ to "60 Hz",
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF to "Off",
    )

    private fun range(id: String, group: String, label: String, min: Double, max: Double, step: Double, value: Double, unit: String = "", enabled: Boolean = true) =
        JSONObject().put("id", id).put("group", group).put("label", label).put("type", "range")
            .put("min", min).put("max", max).put("step", step).put("value", value).put("unit", unit).put("enabled", enabled)

    private fun toggle(id: String, group: String, label: String, value: Boolean) =
        JSONObject().put("id", id).put("group", group).put("label", label).put("type", "toggle").put("value", value)

    private fun select(id: String, group: String, label: String, options: List<Pair<Any, String>>, value: Any, enabled: Boolean = true): JSONObject {
        val opts = JSONArray()
        options.forEach { (v, l) -> opts.put(JSONObject().put("v", v).put("label", l)) }
        return JSONObject().put("id", id).put("group", group).put("label", label).put("type", "select")
            .put("options", opts).put("value", value).put("enabled", enabled)
    }

    /** Everything this lens supports, with the current values. */
    fun describe(cp: CameraCaps, c: CameraControls, front: Boolean): JSONArray {
        val out = JSONArray()
        out.put(select("lens", "Lens", "Lens", listOf("back" to "Back", "front" to "Front"), if (front) "front" else "back"))
        if (cp.zoomMax > 1.01f) out.put(range("zoom", "Lens", "Zoom", 1.0, cp.zoomMax.toDouble(), 0.1, c.zoom.toDouble(), "x"))

        if (cp.evMax > cp.evMin) {
            out.put(
                range("ev", "Exposure", "Brightness", cp.evMin.toDouble(), cp.evMax.toDouble(), 1.0, c.ev.toDouble(), "EV×${"%.2f".format(cp.evStep)}", !c.manualExposure)
                    .put("scale", cp.evStep.toDouble()),
            )
        }
        if (cp.canLockAe) out.put(toggle("aeLock", "Exposure", "Lock exposure", c.aeLock))
        if (cp.manualSensor) {
            out.put(toggle("manualExposure", "Exposure", "Manual ISO & shutter", c.manualExposure))
            out.put(range("iso", "Exposure", "ISO", cp.isoMin.toDouble(), cp.isoMax.toDouble(), 50.0, c.iso.toDouble(), "", c.manualExposure))
            val speeds = SHUTTERS.filter { 1_000_000_000L / it in cp.exposureMinNs..cp.exposureMaxNs }
            if (speeds.isNotEmpty()) {
                val cur = (1_000_000_000L / c.shutterNs).toInt()
                out.put(select("shutter", "Exposure", "Shutter", speeds.map { it to "1/$it" }, cur, c.manualExposure))
            }
        }
        if (cp.antibandingModes.size > 1) {
            out.put(select("antibanding", "Exposure", "Anti-flicker", FLICKER_NAMES.filterKeys { it in cp.antibandingModes }.map { it.key to it.value }, c.antibanding))
        }

        val awb = AWB_NAMES.filterKeys { it in cp.awbModes }
        if (awb.size > 1) out.put(select("awbMode", "White balance", "White balance", awb.map { it.key to it.value }, c.awbMode))
        if (cp.canLockAwb) out.put(toggle("awbLock", "White balance", "Lock colours", c.awbLock))

        if (cp.minFocusDistance > 0f) {
            out.put(toggle("focusManual", "Focus", "Manual focus", c.focusManual))
            out.put(range("focusDistance", "Focus", "Focus (far → near)", 0.0, cp.minFocusDistance.toDouble(), cp.minFocusDistance / 100.0, c.focusDistance.toDouble(), "", c.focusManual))
        }
        if (cp.hasFlash) out.put(toggle("torch", "Light", "Light (torch)", c.torch))
        if (cp.videoStabilization || cp.opticalStabilization) out.put(toggle("stabilization", "Light", "Stabilisation", c.stabilization))
        return out
    }

    /** A change from the console. Returns the new controls, or null if [id] isn't a control. */
    fun apply(c: CameraControls, cp: CameraCaps?, id: String, value: Any?): CameraControls? {
        fun num() = (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull()
        fun bool() = value == true || value?.toString() == "true"
        return when (id) {
            "zoom" -> num()?.let { c.copy(zoom = it.toFloat().coerceIn(1f, cp?.zoomMax ?: 10f)) }
            "ev" -> num()?.let { c.copy(ev = it.toInt().coerceIn(cp?.evMin ?: -20, cp?.evMax ?: 20), manualExposure = false) }
            "aeLock" -> c.copy(aeLock = bool())
            "manualExposure" -> c.copy(manualExposure = bool())
            "iso" -> num()?.let { c.copy(iso = it.toInt().coerceIn(cp?.isoMin ?: 50, cp?.isoMax ?: 6400), manualExposure = true) }
            "shutter" -> num()?.let { if (it >= 1) c.copy(shutterNs = (1_000_000_000L / it.toLong()), manualExposure = true) else null }
            "antibanding" -> num()?.let { c.copy(antibanding = it.toInt()) }
            "awbMode" -> num()?.let { c.copy(awbMode = it.toInt()) }
            "awbLock" -> c.copy(awbLock = bool())
            "focusManual" -> c.copy(focusManual = bool())
            "focusDistance" -> num()?.let { c.copy(focusDistance = it.toFloat().coerceIn(0f, cp?.minFocusDistance ?: 10f), focusManual = true) }
            "torch" -> c.copy(torch = bool())
            "stabilization" -> c.copy(stabilization = bool())
            "reset" -> CameraControls()
            else -> null
        }
    }
}
