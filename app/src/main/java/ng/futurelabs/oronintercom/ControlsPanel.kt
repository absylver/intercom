package ng.futurelabs.oronintercom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.hardware.camera2.CaptureRequest
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Camera controls shown over the full-screen picture: a row of tabs (Zoom, Exposure, ISO & Shutter,
 * White balance, Focus, Light, More) with the selected tab's sliders/buttons above it.
 * Only controls the phone's camera supports are shown.
 */
class ControlsPanel(
    private val ctx: Context,
    container: LinearLayout,
    private val apply: (CameraControls) -> Unit,
) {
    private var caps: CameraCaps? = null
    private var c = CameraControls()
    private var fps = 30
    private var tab = TAB_ZOOM
    private var dragging = false

    private val detail = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(10))
    }
    private val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabRow)
    }

    init {
        container.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(tabScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Called whenever the camera's abilities or the current settings change. */
    fun update(newCaps: CameraCaps?, controls: CameraControls, frameRate: Int) {
        val capsChanged = newCaps !== caps
        val changed = capsChanged || controls != c || frameRate != fps
        caps = newCaps
        c = controls
        fps = frameRate
        if (capsChanged) buildTabs()
        if (changed && !dragging) buildDetail()
    }

    private fun set(next: CameraControls) {
        c = next
        apply(next)
    }

    /* ---------------- Tabs ---------------- */

    private fun availableTabs(): List<String> {
        val cp = caps ?: return emptyList()
        val tabs = ArrayList<String>()
        if (cp.zoomMax > 1.05f) tabs += TAB_ZOOM
        tabs += TAB_EXPOSURE
        if (cp.manualSensor) tabs += TAB_MANUAL
        tabs += TAB_WB
        if (cp.autoFocus || cp.minFocusDistance > 0f) tabs += TAB_FOCUS
        if (cp.hasFlash) tabs += TAB_LIGHT
        tabs += TAB_MORE
        return tabs
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        val tabs = availableTabs()
        if (tabs.isEmpty()) {
            detail.removeAllViews()
            detail.addView(note("Starting camera…"))
            return
        }
        if (tab !in tabs) tab = tabs.first()
        tabs.forEach { name ->
            tabRow.addView(chip(name, name == tab) {
                tab = name
                buildTabs()
                buildDetail()
            })
        }
    }

    /* ---------------- Detail for the selected tab ---------------- */

    private fun buildDetail() {
        val cp = caps ?: return
        detail.removeAllViews()
        when (tab) {
            TAB_ZOOM -> zoomDetail(cp)
            TAB_EXPOSURE -> exposureDetail(cp)
            TAB_MANUAL -> manualDetail(cp)
            TAB_WB -> wbDetail(cp)
            TAB_FOCUS -> focusDetail(cp)
            TAB_LIGHT -> lightDetail()
            TAB_MORE -> moreDetail(cp)
        }
    }

    private fun zoomDetail(cp: CameraCaps) {
        val max = cp.zoomMax
        val value = valueLabel("ZOOM", "%.1f×".format(c.zoom))
        detail.addView(value)
        // Logarithmic slider: fine control near 1×.
        val toProgress = { z: Float -> (ln(z.coerceIn(1f, max)) / ln(max) * 1000f).roundToInt() }
        val fromProgress = { p: Int -> exp(ln(max) * p / 1000f) }
        detail.addView(slider(1000, toProgress(c.zoom), onMove = { p ->
            val z = fromProgress(p)
            (value.getChildAt(1) as TextView).text = "%.1f×".format(z)
            set(c.copy(zoom = z))
        }))
        detail.addView(chipRow(listOf(1f, 2f, 3f, 5f).filter { it <= max + 0.01f }.map { z ->
            opt("${z.toInt()}×", kotlin.math.abs(c.zoom - z) < 0.05f) { set(c.copy(zoom = z)) }
        }))
    }

    private fun exposureDetail(cp: CameraCaps) {
        if (c.manualExposure) {
            detail.addView(note("Manual ISO & shutter is on. Switch it to AUTO to use brightness here."))
            return
        }
        if (cp.evMax > cp.evMin) {
            val fmt = { ev: Int -> "%+.1f EV".format(ev * cp.evStep) }
            val value = valueLabel("BRIGHTNESS", fmt(c.ev))
            detail.addView(value)
            detail.addView(slider(cp.evMax - cp.evMin, c.ev - cp.evMin, onMove = { p ->
                val ev = p + cp.evMin
                (value.getChildAt(1) as TextView).text = fmt(ev)
                set(c.copy(ev = ev))
            }))
        }
        val chips = ArrayList<Opt>()
        if (cp.canLockAe) chips += opt("LOCK EXPOSURE", c.aeLock) { set(c.copy(aeLock = !c.aeLock)) }
        chips += opt("RESET", false) { set(c.copy(ev = 0, aeLock = false)) }
        detail.addView(chipRow(chips))
    }

    private fun manualDetail(cp: CameraCaps) {
        detail.addView(chipRow(listOf(
            opt("AUTO", !c.manualExposure) { set(c.copy(manualExposure = false)) },
            opt("MANUAL", c.manualExposure) { set(c.copy(manualExposure = true)) },
        )))
        if (!c.manualExposure) {
            detail.addView(note("Automatic exposure. Pick MANUAL to set ISO and shutter speed yourself."))
            return
        }
        detail.addView(sectionLabel("ISO"))
        val isos = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400).filter { it in cp.isoMin..cp.isoMax }
        detail.addView(chipRow(isos.map { v -> opt("$v", c.iso == v) { set(c.copy(iso = v)) } }))
        detail.addView(sectionLabel("SHUTTER  ·  1/50 or 1/100 stops flicker under 50 Hz lights"))
        val frameNs = 1_000_000_000L / fps.coerceAtLeast(1)
        val speeds = listOf(25, 30, 50, 60, 100, 120, 250, 500, 1000, 2000)
            .map { it to 1_000_000_000L / it }
            .filter { (_, ns) -> ns in cp.exposureMinNs..cp.exposureMaxNs && ns <= frameNs }
        detail.addView(chipRow(speeds.map { (d, ns) -> opt("1/$d", c.shutterNs == ns) { set(c.copy(shutterNs = ns)) } }))
    }

    private fun wbDetail(cp: CameraCaps) {
        val names = linkedMapOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO to "AUTO",
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "DAYLIGHT",
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "CLOUDY",
            CaptureRequest.CONTROL_AWB_MODE_SHADE to "SHADE",
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "TUNGSTEN",
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "FLUORESCENT",
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to "WARM FLUOR.",
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "TWILIGHT",
        )
        detail.addView(sectionLabel("WHITE BALANCE"))
        detail.addView(chipRow(names.filterKeys { it in cp.awbModes }.map { (mode, label) ->
            opt(label, c.awbMode == mode) { set(c.copy(awbMode = mode)) }
        }))
        if (cp.canLockAwb) {
            detail.addView(chipRow(listOf(opt("LOCK COLOURS", c.awbLock) { set(c.copy(awbLock = !c.awbLock)) })))
        }
    }

    private fun focusDetail(cp: CameraCaps) {
        val modes = ArrayList<Opt>()
        if (cp.autoFocus) modes += opt("AUTO", !c.focusManual) { set(c.copy(focusManual = false)) }
        if (cp.minFocusDistance > 0f) modes += opt("MANUAL", c.focusManual) { set(c.copy(focusManual = true)) }
        detail.addView(chipRow(modes))
        if (c.focusManual && cp.minFocusDistance > 0f) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(small("FAR"))
            val s = slider(1000, (c.focusDistance / cp.minFocusDistance * 1000f).roundToInt(), onMove = { p ->
                set(c.copy(focusDistance = cp.minFocusDistance * p / 1000f))
            })
            row.addView(s, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(small("NEAR"))
            detail.addView(row)
        } else if (cp.autoFocus) {
            detail.addView(note("Tap the picture to focus on a spot. Pick MANUAL to hold focus at a fixed distance."))
        }
    }

    private fun lightDetail() {
        detail.addView(chipRow(listOf(
            opt("LIGHT OFF", !c.torch) { set(c.copy(torch = false)) },
            opt("LIGHT ON", c.torch) { set(c.copy(torch = true)) },
        )))
        detail.addView(note("Uses the phone's flash as a steady video light."))
    }

    private fun moreDetail(cp: CameraCaps) {
        if (cp.videoStabilization || cp.opticalStabilization) {
            detail.addView(sectionLabel("STABILIZATION"))
            detail.addView(chipRow(listOf(
                opt("ON", c.stabilization) { set(c.copy(stabilization = true)) },
                opt("OFF (tripod)", !c.stabilization) { set(c.copy(stabilization = false)) },
            )))
        }
        val flicker = linkedMapOf(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO to "AUTO",
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ to "50 Hz",
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ to "60 Hz",
        ).filterKeys { it in cp.antibandingModes }
        if (flicker.isNotEmpty()) {
            detail.addView(sectionLabel("ANTI-FLICKER  ·  Nigeria uses 50 Hz"))
            detail.addView(chipRow(flicker.map { (mode, label) -> opt(label, c.antibanding == mode) { set(c.copy(antibanding = mode)) } }))
        }
        detail.addView(chipRow(listOf(opt("RESET ALL", false) { set(CameraControls()) })))
    }

    /* ---------------- Small view helpers ---------------- */

    /** One button in a row: label, whether it is the current choice, what it does. */
    private data class Opt(val label: String, val selected: Boolean, val onClick: () -> Unit)

    private fun opt(label: String, selected: Boolean, onClick: () -> Unit) = Opt(label, selected, onClick)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private val font: Typeface? by lazy { ResourcesCompat.getFont(ctx, R.font.montserrat) }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        isSelected = selected
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.chip)
        setTextColor(ContextCompat.getColorStateList(ctx, R.color.chip_text))
        textSize = 11f
        letterSpacing = 0.08f
        typeface = font
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        minWidth = dp(44)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        }
    }

    private fun chipRow(items: List<Opt>): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        items.forEach { (label, sel, click) -> row.addView(chip(label, sel, click)) }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun slider(max: Int, value: Int, onMove: (Int) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max.coerceAtLeast(1)
        progress = value.coerceIn(0, this.max)
        val green = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.green))
        progressTintList = green
        thumbTintList = green
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onMove(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                buildDetail()
            }
        })
    }

    private fun valueLabel(name: String, value: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(small(name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = value
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = font
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun sectionLabel(text: String): TextView = small(text).apply { setPadding(0, dp(8), 0, dp(2)) }

    private fun small(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.muted))
        textSize = 10f
        letterSpacing = 0.14f
        typeface = font
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun note(text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.muted))
        textSize = 12f
        typeface = font
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    companion object {
        const val TAB_ZOOM = "ZOOM"
        const val TAB_EXPOSURE = "EXPOSURE"
        const val TAB_MANUAL = "ISO & SHUTTER"
        const val TAB_WB = "WHITE BALANCE"
        const val TAB_FOCUS = "FOCUS"
        const val TAB_LIGHT = "LIGHT"
        const val TAB_MORE = "MORE"
    }
}
