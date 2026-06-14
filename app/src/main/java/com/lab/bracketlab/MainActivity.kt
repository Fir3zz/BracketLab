package com.lab.bracketlab

import android.Manifest
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lab.bracketlab.processing.model.ProcessingIntent
import com.lab.bracketlab.processing.model.AlignToggle
import com.lab.bracketlab.processing.model.AstroState
import com.lab.bracketlab.processing.model.OutputDepth
import com.lab.bracketlab.processing.model.ResolvedAlignmentMode
import com.lab.bracketlab.processing.model.StackingConfig
import com.lab.bracketlab.processing.model.StackingOperation
import com.lab.bracketlab.processing.align.OpenCvDiagnostics
import com.lab.bracketlab.processing.align.star.StarDetectionDiagnostic
import com.lab.bracketlab.processing.align.star.StarDetectionSelfTest
import com.lab.bracketlab.processing.align.star.StarMatchingDiagnostic
import com.lab.bracketlab.processing.align.star.StarMatchingSelfTest
import com.lab.bracketlab.processing.align.star.warp.StarAlignedRaw16Diagnostic
import com.lab.bracketlab.processing.align.star.warp.StarAlignedRaw16SelfTest
import com.lab.bracketlab.processing.calibration.DarkAggregationPolicy
import com.lab.bracketlab.processing.calibration.DarkCalibrationInput
import com.lab.bracketlab.processing.calibration.DarkCalibrationOptions
import com.lab.bracketlab.processing.calibration.DarkPolicy
import com.lab.bracketlab.processing.calibration.DarkStorageEstimator
import com.lab.bracketlab.processing.calibration.MasterDark
import com.lab.bracketlab.processing.calibration.MasterDarkMetadataJson
import com.lab.bracketlab.processing.calibration.MasterDarkProcessor
import com.lab.bracketlab.processing.calibration.MissingMasterDarkBehavior
import com.lab.bracketlab.processing.debug.DevRawDiagnosticMode
import com.lab.bracketlab.processing.debug.MasterDarkDiagnostic
import com.lab.bracketlab.processing.debug.RealAlignedStackDiagnostic
import com.lab.bracketlab.processing.hdri.HdrI32Diagnostic
import com.lab.bracketlab.processing.hdri.HdrI32StorageEstimator
import com.lab.bracketlab.processing.io.DcimOutputPublisher
import com.lab.bracketlab.processing.model.DarkPolicy as StackingDarkPolicy
import com.lab.bracketlab.processing.pipeline.CapturedRawSequence
import com.lab.bracketlab.processing.pipeline.CapturedRawSequenceAdapter
import com.lab.bracketlab.processing.pipeline.ProductionAlignmentMode
import com.lab.bracketlab.processing.pipeline.ProductionOutputOptions
import com.lab.bracketlab.processing.pipeline.ProductionStackMode
import com.lab.bracketlab.processing.pipeline.StackModeProductionCoordinator
import com.lab.bracketlab.processing.pipeline.StackModeProductionRequest
import com.lab.bracketlab.processing.pipeline.StackingPlanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "BracketLab"
        const val REQ_PERM = 100
        private const val LIVE_FOCUS_ZOOM = 10.0f
        private const val THERMAL_UPDATE_MS = 5_000L
        private const val DARK_THERMAL_UPDATE_MS = 1_000L
        private const val MAX_RAW_CAPTURE_ATTEMPTS = 2
        private const val LONG_EXPOSURE_SETTLE_THRESHOLD_NS = 1_000_000_000L
        private const val LONG_EXPOSURE_SETTLE_MS = 300L
        private const val EXTENDED_EXPOSURE_SETTLE_MS = 5_000L
        private const val LOG_MAX_LINES = 18
        private val LOG_WARNING_COLOR = android.graphics.Color.parseColor("#E23EE2")
        private val LOG_ERROR_COLOR = android.graphics.Color.parseColor("#C82E3E")
        private const val TIFF_TAG_AS_SHOT_NEUTRAL = 0xC628
        val PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                }

                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }.toTypedArray()

        /** Checks whether a camera-id bit is enabled while probing hidden modules. */
        fun getBit(bit: Int, num: Int): Boolean = (num shr bit) and 1 == 1
    }

    private data class WbOption(
        val mode: Int,
        val label: String,
        val name: String,
        val gains: RggbChannelVector?
    )

    private data class CameraOption(
        val id: String,
        val label: String
    )

    private enum class LogTone {
        NORMAL,
        WARNING,
        ERROR
    }

    private data class LogEntry(
        val message: String,
        val tone: LogTone
    )

    private data class PendingDarkCaptureSession(
        val lightSequence: CapturedRawSequence,
        val initialCameraTemperatureCelsius: Float?,
        val outputRelativeDirectory: String,
        val captureFolder: String?
    )

    // ── Camera ────────────────────────────────────
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private var chars: CameraCharacteristics? = null
    private val cameraLock = Semaphore(1)
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler
    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler


    // ── Save queue ────────────────────────────────
    private sealed class SaveJob {
        data class Frame(
            val image: Image,
            val result: TotalCaptureResult,
            val seq: Int,
            val frame: Int,
            val expNs: Long,
            val iso: Int,
            val wbGains: RggbChannelVector?,
            val dngOrientation: Int,
            val dngOrientationDegrees: Int,
            val slot: Semaphore
        ) : SaveJob()

        object Stop : SaveJob()
    }

    private val saveQueue = LinkedBlockingQueue<SaveJob>(64)
    private var rawImageSlots = Semaphore(1)
    private var saveThread: Thread? = null
    @Volatile
    private var running = false
    private val capturePauseLock = Object()
    @Volatile
    private var capturePauseWaiting = false
    private var oisEnabled = false
    private var folderModeEnabled = false
    private var currentCaptureFolder: String? = null
    private var currentStackOutputRelativePath: String? = null
    @Volatile
    private var latestCameraTemperatureCelsius: Float? = null
    @Volatile
    private var pendingDarkCaptureSession: PendingDarkCaptureSession? = null
    @Volatile
    private var darkCaptureInProgress = false
    private var lastDarkTemperatureStatus: DarkCaptureTemperatureStatus? = null
    private var afLockedForCapture = false
    private var afLockedCamId: String? = null
    private var afLockSurface: Surface? = null
    private var afLockTexture: SurfaceTexture? = null

    // ── UI ────────────────────────────────────────
    private lateinit var etExposures: EditText
    private lateinit var etCameraId: Button
    private lateinit var etIso: Button
    private lateinit var etInterval: EditText
    private lateinit var etFocus: EditText
    private lateinit var etDelay: EditText
    private lateinit var btnStackModes: Button
    private lateinit var btnDev: Button
    private lateinit var btnPreview: Button
    private lateinit var btnStart: Button
    private lateinit var btnAF: Button
    private lateinit var btnOis: Button
    private lateinit var btnWB: Button
    private lateinit var btnFolderMode: Button
    private lateinit var btnResetSettings: Button
    private lateinit var btnHelp: Button
    private lateinit var btnLiveFocus: Button
    private lateinit var btnFocusBracket: Button
    private lateinit var btnFocusStart: Button
    private lateinit var btnFocusEnd: Button
    private lateinit var btnAE: Button
    private lateinit var btnAeBiasMinus: Button
    private lateinit var btnAeBiasPlus: Button
    private lateinit var btnClearExposures: Button
    private lateinit var tvExposureGuide: TextView
    private lateinit var etFocusStart: EditText
    private lateinit var etFocusEnd: EditText
    private lateinit var etFocusFrames: EditText
    private lateinit var settingsStore: UiSettingsStore
    private var restoringSettings = false
    private var selectedWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    // HDR compute section
    private lateinit var etHighlights: EditText
    private lateinit var etShadows: EditText
    private lateinit var etEv: EditText
    private lateinit var tvBrackets: TextView
    private lateinit var btnGetHigh: Button
    private lateinit var btnGetShadow: Button
    private lateinit var btnCompute: Button

    // Log area / preview
    private lateinit var tvThermals: TextView
    private lateinit var tvLog: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var framingPreviewContainer: View
    private lateinit var framingPreview: AutoFitTextureView
    private lateinit var sbPreviewExposure: SeekBar
    private lateinit var sbPreviewZoom: SeekBar
    private lateinit var livePreview: TextureView
    private lateinit var sbLiveFocus: SeekBar
    private lateinit var previewOverlay: FrameLayout
    private lateinit var stackModesOverlay: FrameLayout
    private lateinit var btnStackModesClose: Button
    private lateinit var btnModeFramesOnly: Button
    private lateinit var btnModeStack: Button
    private lateinit var btnModeHdri: Button
    private lateinit var btnModeStarTrail: Button
    private lateinit var btnAlignmentOff: Button
    private lateinit var btnAlignmentLandscape: Button
    private lateinit var btnAlignmentStars: Button
    private lateinit var btnDitherOff: Button
    private lateinit var btnDitherManual: Button
    private lateinit var btnUseDarks: Button
    private lateinit var btnKeepSourceFrames: Button
    private lateinit var btnOutputDng: Button
    private lateinit var btnOutputXisf: Button
    private lateinit var btnOutputFits: Button
    private lateinit var btnStackModesDefaults: Button
    private lateinit var btnStackModesApply: Button
    private lateinit var tvStackModesSummary: TextView
    private lateinit var helpOverlay: FrameLayout
    private lateinit var btnHelpClose: Button
    private lateinit var tvHelpContent: TextView
    private lateinit var orientationListener: OrientationEventListener
    private var deviceOrientationDegrees = OrientationEventListener.ORIENTATION_UNKNOWN
    @Volatile
    private var liveFocusRunning = false
    private var liveFocusSurface: Surface? = null
    @Volatile
    private var framingPreviewRunning = false
    private var framingPreviewSurface: Surface? = null
    private var framingPreviewSize: Size? = null
    private var framingPreviewRequestBuilder: CaptureRequest.Builder? = null
    private var framingPreviewAeCompensation = 0
    private var framingPreviewZoom = 1f
    private var framingPreviewTransformSignature: String? = null
    private var cameraInventoryLogged = false
    private var liveFocusMaxDistance = 0f
    private var liveFocusTargetDistance = 0f
    private var liveFocusMeasuredDistance = 0f
    private var liveFocusSaveField: EditText? = null
    private var liveFocusSaveLabel = "Focus"
    private var focusStartReady = false
    private var focusEndReady = false
    private var exposuresKeyboardVisible = false
    private var aeBiasEv = 0.0
    private var stackingConfig = StackingConfig()
    private var stackModesState = StackModesUiState()
    private var stackModesDraft = stackModesState
    private val logEntries = mutableListOf<LogEntry>()
    private val thermalReader = ThermalReader()
    private val thermalHandler = Handler(Looper.getMainLooper())
    private val thermalExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var devDiagnosticMode: DevRawDiagnosticMode? = null
    @Volatile
    private var thermalUpdatesRunning = false
    private val thermalUpdateRunnable = object : Runnable {
        override fun run() {
            if (!thermalUpdatesRunning) return
            runCatching {
                thermalExecutor.execute {
                    val reading = thermalReader.read()
                    runOnUiThread {
                        latestCameraTemperatureCelsius = reading.cameraUsrCelsius
                        if (thermalUpdatesRunning && ::tvThermals.isInitialized) {
                            tvThermals.text = formatThermalReading(reading)
                        }
                        refreshPendingDarkTemperature(reading.cameraUsrCelsius)
                    }
                }
            }
            thermalHandler.postDelayed(
                this,
                if (pendingDarkCaptureSession != null) {
                    DARK_THERMAL_UPDATE_MS
                } else {
                    THERMAL_UPDATE_MS
                }
            )
        }
    }

    // ══════════════════════════════════════════════
    /** Initializes the screen, camera service, saved settings, and permissions. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        bindViews()
        setupOrientationTracking()
        checkPermissions()
        startOpenCvDiagnostics()
    }

    /** Connects XML views to fields and wires all button/listener behavior. */
    private fun bindViews() {
        settingsStore = UiSettingsStore(getSharedPreferences(SettingKeys.PREFS_NAME, MODE_PRIVATE))

        etExposures = findViewById(R.id.etExposures)
        etCameraId = findViewById(R.id.etCameraId)
        etIso = findViewById(R.id.etIso)
        etInterval = findViewById(R.id.etInterval)
        etFocus = findViewById(R.id.etFocus)
        etDelay = findViewById(R.id.etDelay)
        btnStackModes = findViewById(R.id.btnStackModes)
        btnDev = findViewById(R.id.btnDev)
        btnDev.visibility = if (isDebugBuild()) View.VISIBLE else View.GONE
        btnPreview = findViewById(R.id.btnPreview)
        btnStart = findViewById(R.id.btnStart)
        btnAF = findViewById(R.id.btnAF)
        btnOis = findViewById(R.id.btnOis)
        etHighlights = findViewById(R.id.etHighlights)
        etShadows = findViewById(R.id.etShadows)
        etEv = findViewById(R.id.etEv)
        tvBrackets = findViewById(R.id.tvBrackets)
        btnGetHigh = findViewById(R.id.btnGetHigh)
        btnGetShadow = findViewById(R.id.btnGetShadow)
        btnCompute = findViewById(R.id.btnCompute)
        tvThermals = findViewById(R.id.tvThermals)
        tvLog = findViewById(R.id.tvLog)
        ivPreview = findViewById(R.id.ivPreview)
        framingPreviewContainer = findViewById(R.id.framingPreviewContainer)
        framingPreview = findViewById(R.id.framingPreview)
        sbPreviewExposure = findViewById(R.id.sbPreviewExposure)
        sbPreviewZoom = findViewById(R.id.sbPreviewZoom)
        livePreview = findViewById(R.id.livePreview)
        sbLiveFocus = findViewById(R.id.sbLiveFocus)
        previewOverlay = findViewById(R.id.previewOverlay)
        stackModesOverlay = findViewById(R.id.stackModesOverlay)
        btnStackModesClose = findViewById(R.id.btnStackModesClose)
        btnModeFramesOnly = findViewById(R.id.btnModeFramesOnly)
        btnModeStack = findViewById(R.id.btnModeStack)
        btnModeHdri = findViewById(R.id.btnModeHdri)
        btnModeStarTrail = findViewById(R.id.btnModeStarTrail)
        btnAlignmentOff = findViewById(R.id.btnAlignmentOff)
        btnAlignmentLandscape = findViewById(R.id.btnAlignmentLandscape)
        btnAlignmentStars = findViewById(R.id.btnAlignmentStars)
        btnDitherOff = findViewById(R.id.btnDitherOff)
        btnDitherManual = findViewById(R.id.btnDitherManual)
        btnUseDarks = findViewById(R.id.btnUseDarks)
        btnKeepSourceFrames = findViewById(R.id.btnKeepSourceFrames)
        btnOutputDng = findViewById(R.id.btnOutputDng)
        btnOutputXisf = findViewById(R.id.btnOutputXisf)
        btnOutputFits = findViewById(R.id.btnOutputFits)
        btnStackModesDefaults = findViewById(R.id.btnStackModesDefaults)
        btnStackModesApply = findViewById(R.id.btnStackModesApply)
        tvStackModesSummary = findViewById(R.id.tvStackModesSummary)
        helpOverlay = findViewById(R.id.helpOverlay)
        btnHelpClose = findViewById(R.id.btnHelpClose)
        tvHelpContent = findViewById(R.id.tvHelpContent)
        btnWB = findViewById(R.id.btnWB)
        btnFolderMode = findViewById(R.id.btnFolderMode)
        btnResetSettings = findViewById(R.id.btnResetSettings)
        btnHelp = findViewById(R.id.btnHelp)
        btnLiveFocus = findViewById(R.id.btnLiveFocus)
        btnFocusBracket = findViewById(R.id.btnFocusBracket)
        btnFocusStart = findViewById(R.id.btnFocusStart)
        btnFocusEnd = findViewById(R.id.btnFocusEnd)
        btnAE = findViewById(R.id.btnAE)
        btnAeBiasMinus = findViewById(R.id.btnAeBiasMinus)
        btnAeBiasPlus = findViewById(R.id.btnAeBiasPlus)
        btnClearExposures = findViewById(R.id.btnClearExposures)
        tvExposureGuide = findViewById(R.id.tvExposureGuide)
        etFocusStart = findViewById(R.id.etFocusStart)
        etFocusEnd = findViewById(R.id.etFocusEnd)
        etFocusFrames = findViewById(R.id.etFocusFrames)

        applyDefaultSettings()
        loadSavedSettings()
        setupSettingsAutosave()
        setupExposureShorthand()
        tvHelpContent.text = buildHelpText()

        btnStackModes.setOnClickListener { showStackModes() }
        btnStackModesClose.setOnClickListener { hideStackModes() }
        btnModeFramesOnly.setOnClickListener {
            updateStackModesDraft(processing = StackProcessingSelection.FRAMES_ONLY)
        }
        btnModeStack.setOnClickListener {
            updateStackModesDraft(processing = StackProcessingSelection.STACK)
        }
        btnModeHdri.setOnClickListener {
            updateStackModesDraft(processing = StackProcessingSelection.HDR)
        }
        btnModeStarTrail.setOnClickListener {
            updateStackModesDraft(processing = StackProcessingSelection.STAR_TRAIL)
        }
        btnAlignmentOff.setOnClickListener {
            updateStackModesDraft(alignment = StackAlignmentSelection.OFF)
        }
        btnAlignmentLandscape.setOnClickListener {
            updateStackModesDraft(alignment = StackAlignmentSelection.LANDSCAPE)
        }
        btnAlignmentStars.setOnClickListener {
            updateStackModesDraft(alignment = StackAlignmentSelection.STARS)
        }
        btnDitherOff.setOnClickListener {
            updateStackModesDraft(dithering = StackDitheringSelection.OFF)
        }
        btnDitherManual.setOnClickListener {
            updateStackModesDraft(dithering = StackDitheringSelection.MANUAL_ASSISTED)
        }
        btnUseDarks.setOnClickListener {
            stackModesDraft =
                stackModesDraft.copy(useDarks = !stackModesDraft.useDarks).normalized()
            updateStackModesOverlay()
        }
        btnKeepSourceFrames.setOnClickListener {
            stackModesDraft =
                stackModesDraft.copy(keepSourceFrames = !stackModesDraft.keepSourceFrames).normalized()
            updateStackModesOverlay()
        }
        btnOutputDng.setOnClickListener {
            stackModesDraft = stackModesDraft.copy(outputDng = !stackModesDraft.outputDng).normalized()
            updateStackModesOverlay()
        }
        btnOutputXisf.setOnClickListener {
            stackModesDraft = stackModesDraft.copy(outputXisf = !stackModesDraft.outputXisf).normalized()
            updateStackModesOverlay()
        }
        btnOutputFits.setOnClickListener {
            stackModesDraft = stackModesDraft.copy(outputFits = !stackModesDraft.outputFits).normalized()
            updateStackModesOverlay()
        }
        btnStackModesDefaults.setOnClickListener {
            stackModesDraft = StackModesUiState()
            updateStackModesOverlay()
        }
        btnStackModesApply.setOnClickListener { applyStackModesDraft() }
        btnDev.setOnClickListener { showDevMenu() }
        btnPreview.setOnClickListener { toggleFramingPreview() }
        btnStart.setOnClickListener { startCapture() }
        btnAF.setOnClickListener { startAutoFocusLock() }
        btnOis.setOnClickListener { toggleOis() }
        etCameraId.setOnClickListener { showCameraIdMenu() }
        etIso.setOnClickListener { showIsoMenu() }
        btnWB.setOnClickListener { showWhiteBalanceMenu() }
        btnFolderMode.setOnClickListener { toggleFolderMode() }
        btnResetSettings.setOnClickListener { resetSettings() }
        btnHelp.setOnClickListener { showHelp() }
        btnHelpClose.setOnClickListener { hideHelp() }
        btnLiveFocus.setOnClickListener { startLiveFocus(etFocus, "Focus") }
        btnFocusBracket.setOnClickListener { computeFocusBracket(showLog = true) }
        btnFocusStart.setOnClickListener { startLiveFocus(etFocusStart, "Focus Start") }
        btnFocusEnd.setOnClickListener { startLiveFocus(etFocusEnd, "Focus End") }
        btnAE.setOnClickListener { startAeMeasure() }
        btnAeBiasMinus.setOnClickListener { changeAeBias(-0.5) }
        btnAeBiasPlus.setOnClickListener { changeAeBias(0.5) }
        btnClearExposures.setOnClickListener { clearExposures() }
        btnGetHigh.setOnClickListener { startGetPreview(etHighlights, "Highlights") }
        btnGetShadow.setOnClickListener { startGetPreview(etShadows, "Shadows") }
        btnCompute.setOnClickListener { computeBrackets() }

        // Reset brackets display when EV changes
        etEv.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                tvBrackets.text = "—"
            }
        })
        startThermalUpdates()
    }

    /** Starts periodic temperature polling for the top status strip. */
    private fun startThermalUpdates() {
        if (thermalUpdatesRunning) return
        thermalUpdatesRunning = true
        tvThermals.text = formatThermalReading(ThermalReader.Reading(null, null))
        thermalHandler.post(thermalUpdateRunnable)
    }

    /** Stops temperature polling and releases the thermal background executor. */
    private fun stopThermalUpdates() {
        thermalUpdatesRunning = false
        thermalHandler.removeCallbacks(thermalUpdateRunnable)
        thermalExecutor.shutdownNow()
    }

    /** Updates the post-light dark-capture gate from the latest camera-usr reading. */
    private fun refreshPendingDarkTemperature(currentCelsius: Float?) {
        val pending = pendingDarkCaptureSession ?: return
        if (darkCaptureInProgress || running || !::btnStart.isInitialized) return
        val decision =
            DarkCaptureTemperatureGate.evaluate(
                pending.initialCameraTemperatureCelsius,
                currentCelsius
            )
        btnStart.isEnabled = true
        when (decision.status) {
            DarkCaptureTemperatureStatus.WAITING -> {
                btnStart.text = "Capture Darks\nWarning: Temperature"
                applyButtonStroke(btnStart, 0xFFFFFF00.toInt())
            }
            DarkCaptureTemperatureStatus.READY -> {
                btnStart.text = "Capture Darks\nTemperature Ready"
                applyButtonStroke(btnStart, 0xFF00FF00.toInt())
            }
            DarkCaptureTemperatureStatus.UNAVAILABLE -> {
                btnStart.text = "Capture Darks\nTemperature unavailable"
                applyButtonStroke(btnStart, 0xFFFFFF00.toInt())
            }
        }
        if (decision.status != lastDarkTemperatureStatus) {
            lastDarkTemperatureStatus = decision.status
            when (decision.status) {
                DarkCaptureTemperatureStatus.WAITING ->
                    log(
                        "Warning: wait for camera-usr to cool to ${
                            formatTemperatureForLog(decision.readyLimitCelsius)
                        }; current ${formatTemperatureForLog(decision.currentCelsius)}"
                    )
                DarkCaptureTemperatureStatus.READY ->
                    log(
                        "Temperature Ready: camera-usr ${
                            formatTemperatureForLog(decision.currentCelsius)
                        }. Cover the lens and press Capture Darks"
                    )
                DarkCaptureTemperatureStatus.UNAVAILABLE ->
                    log(
                        "Warning: camera-usr unavailable; temperature matching cannot be verified"
                    )
            }
        }
    }

    /** Blocks a dark capture while camera-usr is still warmer than the light-session baseline. */
    private fun pendingDarkCaptureMayStart(): Boolean {
        val pending = pendingDarkCaptureSession ?: return false
        val decision =
            DarkCaptureTemperatureGate.evaluate(
                pending.initialCameraTemperatureCelsius,
                latestCameraTemperatureCelsius
            )
        return when (decision.status) {
            DarkCaptureTemperatureStatus.READY -> true
            DarkCaptureTemperatureStatus.UNAVAILABLE -> {
                log("Warning: starting darks without camera-usr temperature verification")
                true
            }
            DarkCaptureTemperatureStatus.WAITING -> {
                log(
                    "Warning: camera-usr is ${formatTemperatureForLog(decision.currentCelsius)}; " +
                        "wait for ${formatTemperatureForLog(decision.readyLimitCelsius)}"
                )
                refreshPendingDarkTemperature(latestCameraTemperatureCelsius)
                false
            }
        }
    }

    private fun formatTemperatureForLog(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f°C", it) } ?: "--°C"

    /** Runs the native OpenCV smoke test without blocking the UI thread. */
    private fun startOpenCvDiagnostics() {
        diagnosticsExecutor.execute {
            OpenCvDiagnostics.runPhaseCorrelationCheck().forEach { log(it) }
        }
    }

    /** Shows the temporary debug menu used while backend prompts are under development. */
    private fun showDevMenu() {
        if (!isDebugBuild()) return
        val popup = PopupMenu(this, btnDev)
        popup.menu.add(0, 1, 0, "Arm aligned RAW test")
        popup.menu.add(0, 2, 1, "Capture MasterDark")
        popup.menu.add(0, 3, 2, "Apply MasterDark")
        popup.menu.add(0, 4, 3, "Run HDR Masters + Linear RGB DNG")
        popup.menu.add(0, 5, 4, "Run Star Detection Diagnostic")
        popup.menu.add(0, 8, 5, "Run Star Matching / RANSAC Diagnostic")
        popup.menu.add(0, 9, 6, "Run Star-Aligned RAW16 Stack")
        popup.menu.add(0, 6, 7, "Cancel armed test")
            .isEnabled = devDiagnosticMode != null
        popup.menu.add(0, 7, 8, "Run backend self-tests")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.ALIGNED_STACK)
                    true
                }
                2 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.CAPTURE_MASTER_DARK)
                    true
                }
                3 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.APPLY_MASTER_DARK)
                    true
                }
                4 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.HDRI32_MERGE)
                    true
                }
                5 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.STAR_DETECTION)
                    true
                }
                8 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.STAR_MATCHING)
                    true
                }
                9 -> {
                    armDevDiagnostic(DevRawDiagnosticMode.STAR_ALIGNED_STACK)
                    true
                }
                6 -> {
                    devDiagnosticMode = null
                    updateDevButton()
                    log("DEV diagnostic cancelled")
                    true
                }
                7 -> {
                    log("DEV backend self-tests started")
                    startOpenCvDiagnostics()
                    diagnosticsExecutor.execute {
                        StarDetectionSelfTest.runAll().logLines().forEach { log(it) }
                        StarMatchingSelfTest.runAll().logLines().forEach { log(it) }
                        StarAlignedRaw16SelfTest.runAll().logLines().forEach { log(it) }
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Arms one debug-only real-capture diagnostic for the next RAW sequence. */
    private fun armDevDiagnostic(mode: DevRawDiagnosticMode) {
        if (
            running ||
            liveFocusRunning ||
            RealAlignedStackDiagnostic.isProcessing() ||
            MasterDarkDiagnostic.isProcessing() ||
            HdrI32Diagnostic.isProcessing() ||
            StarDetectionDiagnostic.isProcessing() ||
            StarMatchingDiagnostic.isProcessing() ||
            StarAlignedRaw16Diagnostic.isProcessing()
        ) {
            log("DEV diagnostic unavailable during capture or processing")
            return
        }
        devDiagnosticMode = mode
        updateDevButton()
        log("DEV diagnostic armed: ${devModeLabel(mode)}")
        when (mode) {
            DevRawDiagnosticMode.ALIGNED_STACK ->
                log("Next capture: use >=7 same-exposure RAW frames")
            DevRawDiagnosticMode.CAPTURE_MASTER_DARK ->
                log("Cover the lens and capture >=7 fixed ISO/exposure frames")
            DevRawDiagnosticMode.APPLY_MASTER_DARK ->
                log("Capture lights with the same camera, ISO and exposure")
            DevRawDiagnosticMode.HDRI32_MERGE ->
                log("Next capture: fixed ISO with at least 2 different exposures")
            DevRawDiagnosticMode.STAR_DETECTION ->
                log("Next capture: fixed exposure/focus star sequence; no stack will be written")
            DevRawDiagnosticMode.STAR_MATCHING ->
                log("Next capture: use >=3 fixed exposure/focus star frames; only transforms will be reported")
            DevRawDiagnosticMode.STAR_ALIGNED_STACK ->
                log("Next capture: use >=3 fixed exposure/focus star frames; one aligned Bayer DNG will be written")
        }
    }

    /** Creates the safe RAW-sequence adapter used only by the developer validation path. */
    private fun prepareDevAlignedStackAdapter(camId: String): CapturedRawSequenceAdapter? {
        val mode = devDiagnosticMode
        if (!isDebugBuild() || mode == null) return null
        devDiagnosticMode = null
        updateDevButton()
        val c = runCatching { cameraManager.getCameraCharacteristics(camId) }
            .onFailure { log("DEV camera metadata error: ${it.message}") }
            .getOrNull()
            ?: return null
        val folderTag = currentCaptureFolder ?: SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val outputDir = File(baseDir, "BracketLab_DEV_$folderTag")
        log("DEV output: ${outputDir.absolutePath}")
        return CapturedRawSequenceAdapter(
            cameraId = camId,
            cameraCharacteristics = c,
            outputDirectoryPath = outputDir.absolutePath,
            diagnosticMode = mode,
            hdriDarkCalibrationRequested =
                stackingConfig.darkPolicy != StackingDarkPolicy.OFF
        )
    }

    /** Creates the file-backed capture adapter for a committed Stack Modes job. */
    private fun prepareProductionStackAdapter(camId: String): CapturedRawSequenceAdapter? {
        if (stackModesState.processing == StackProcessingSelection.FRAMES_ONLY) return null
        val cameraCharacteristics =
            runCatching { cameraManager.getCameraCharacteristics(camId) }
                .onFailure { log("Stack Modes camera metadata error: ${it.message}") }
                .getOrNull()
                ?: return null
        val session =
            currentCaptureFolder
                ?: StackModeProductionCoordinator.sessionName(System.currentTimeMillis())
        val base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val workBase = File(base, "BracketLab_Work")
        cleanupStaleProductionWork(workBase)
        val working = File(workBase, session)
        log("Stack Modes working storage: ${working.absolutePath}")
        return CapturedRawSequenceAdapter(
            cameraId = camId,
            cameraCharacteristics = cameraCharacteristics,
            outputDirectoryPath = working.absolutePath,
            diagnosticMode = null
        )
    }

    /** Creates an isolated file-backed adapter for same-session dark frames. */
    private fun prepareProductionDarkAdapter(camId: String): CapturedRawSequenceAdapter? {
        val cameraCharacteristics =
            runCatching { cameraManager.getCameraCharacteristics(camId) }
                .onFailure { log("Stack Modes dark metadata error: ${it.message}") }
                .getOrNull()
                ?: return null
        val pending = pendingDarkCaptureSession ?: return null
        val base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val working =
            File(
                base,
                "BracketLab_Work${File.separator}${pending.captureFolder ?: "session"}" +
                    "${File.separator}darks"
            )
        log("Stack Modes dark working storage: ${working.absolutePath}")
        return CapturedRawSequenceAdapter(
            cameraId = camId,
            cameraCharacteristics = cameraCharacteristics,
            outputDirectoryPath = working.absolutePath,
            diagnosticMode = null,
            isDarkCalibrationSequence = true
        )
    }

    /** Runs the selected production backend after every RAW and metadata pair is safely stored. */
    private fun runProductionStackMode(
        sequence: CapturedRawSequence,
        masterDark: MasterDark? = null
    ) {
        val relativePath =
            currentStackOutputRelativePath
                ?: run {
                    log("Stack Modes output folder is unavailable")
                    sequence.cleanupTemporaryRawFrames()
                    return
                }
        val mode =
            when (stackModesState.processing) {
                StackProcessingSelection.STACK -> ProductionStackMode.STACK
                StackProcessingSelection.HDR -> ProductionStackMode.HDR
                StackProcessingSelection.STAR_TRAIL -> ProductionStackMode.STAR_TRAIL
                StackProcessingSelection.FRAMES_ONLY -> {
                    sequence.cleanupTemporaryRawFrames()
                    return
                }
            }
        val alignment =
            when (stackModesState.alignment) {
                StackAlignmentSelection.OFF -> ProductionAlignmentMode.OFF
                StackAlignmentSelection.LANDSCAPE -> ProductionAlignmentMode.LANDSCAPE
                StackAlignmentSelection.STARS -> ProductionAlignmentMode.STARS
            }
        log(
            "Processing ${sequence.frameCount} frames: " +
                "${stackModesState.processing.name}/${stackModesState.alignment.name}"
        )
        val result =
            StackModeProductionCoordinator(applicationContext).process(
                StackModeProductionRequest(
                    sequence = sequence,
                    mode = mode,
                    alignment = alignment,
                    outputs =
                        ProductionOutputOptions(
                            dng = stackModesState.outputDng,
                            xisf = stackModesState.outputXisf,
                            fits = stackModesState.outputFits
                        ),
                    dcimRelativeDirectory = relativePath,
                    workingDirectory = File(sequence.outputDirectoryPath, ".production"),
                    darkCalibration =
                        masterDark?.let {
                            DarkCalibrationInput.use(
                                it,
                                DarkCalibrationOptions(
                                    darkPolicy = DarkPolicy.USE_COMPATIBLE_MASTER_DARK,
                                    missingMasterDarkBehavior = MissingMasterDarkBehavior.FAIL
                                )
                            )
                        } ?: DarkCalibrationInput.OFF
                )
            )
        result.warnings.forEach { log("Warning: $it") }
        result.outputs.forEach {
            if (it.success) {
                val destination =
                    it.storageDirectory ?: it.path ?: it.uri?.toString() ?: relativePath
                log(
                    "Saved ${it.displayName} " +
                        "(${it.bytes} bytes) to $destination"
                )
            } else {
                log("Output failed ${it.displayName}: ${it.failureMessage}")
            }
        }
        log(
            if (result.success) {
                "Stack Modes processing complete"
            } else {
                "Stack Modes processing failed: ${result.failureMessage}"
            }
        )
        val workRoot = File(sequence.outputDirectoryPath)
        val workCleaned =
            runCatching {
                !workRoot.exists() || workRoot.deleteRecursively()
            }.getOrDefault(false)
        val workBase = workRoot.parentFile
        if (workCleaned && workBase?.listFiles()?.isEmpty() == true) {
            runCatching { workBase.delete() }
        }
        log("Stack Modes temporary storage cleanup=$workCleaned")
    }

    /** Removes abandoned production work from earlier completed or failed sessions. */
    private fun cleanupStaleProductionWork(workBase: File) {
        if (!workBase.exists()) return
        val cleaned =
            runCatching {
                workBase.listFiles()
                    ?.fold(true) { success, child ->
                        (!child.exists() || child.deleteRecursively()) && success
                    } ?: false
            }.getOrDefault(false)
        if (cleaned && workBase.listFiles()?.isEmpty() == true) {
            runCatching { workBase.delete() }
        }
        log("Stale Stack Modes work cleanup=$cleaned")
    }

    /** Holds completed lights until temperature-matched darks are captured. */
    private fun armPendingDarkCapture(
        lightSequence: CapturedRawSequence,
        initialCameraTemperatureCelsius: Float?
    ) {
        if (lightSequence.frameCount <= 0) {
            log("Dark calibration cancelled: no light frames were retained")
            lightSequence.cleanupTemporaryRawFrames()
            return
        }
        pendingDarkCaptureSession =
            PendingDarkCaptureSession(
                lightSequence = lightSequence,
                initialCameraTemperatureCelsius = initialCameraTemperatureCelsius,
                outputRelativeDirectory =
                    currentStackOutputRelativePath ?: "DCIM/BracketLab/Stack",
                captureFolder = currentCaptureFolder
            )
        lastDarkTemperatureStatus = null
        log("Light frames complete: ${lightSequence.frameCount}")
        log(
            "Light start camera-usr: " +
                formatTemperatureForLog(initialCameraTemperatureCelsius)
        )
        log("Wait for Temperature Ready, cover the lens, then press Capture Darks")
    }

    /** Builds and stores a same-session MasterDark, then processes the retained lights. */
    private fun completeDarkCalibrationAndProcess(darkSequence: CapturedRawSequence) {
        val pending = pendingDarkCaptureSession
            ?: run {
                log("Dark calibration failed: retained light session is unavailable")
                darkSequence.cleanupTemporaryRawFrames()
                return
            }
        val lightSequence = pending.lightSequence
        val working =
            File(darkSequence.outputDirectoryPath, ".masterdark_build").also(File::mkdirs)
        var completed = false
        try {
            require(darkSequence.frameCount == lightSequence.frameCount) {
                "Dark frame count ${darkSequence.frameCount} differs from light frame count ${lightSequence.frameCount}."
            }
            log("Creating MasterDark from ${darkSequence.frameCount} frames")
            val created =
                MasterDarkProcessor(working).createMasterDark(
                    darkSequence.toRawStack(),
                    DarkCalibrationOptions(
                        darkPolicy = DarkPolicy.CAPTURE_MASTER_DARK,
                        aggregationPolicy = DarkAggregationPolicy.AUTO,
                        minimumDarkFrames = 1,
                        allowSingleDarkFrame = true,
                        appVersion = null
                    )
                )
            require(created.success && created.masterDark != null) {
                created.failureMessage ?: created.failureCode?.name ?: "MasterDark creation failed."
            }
            val masterDark = created.masterDark
            publishMasterDarkArtifacts(masterDark, working)
            log(
                "MasterDark ready: ${masterDark.metadata.frameCount} frames, " +
                    "ISO ${masterDark.metadata.iso}, " +
                    "${fmtExpNs(masterDark.metadata.exposureTimeNs)}"
            )
            log(
                "Dark capture camera-usr: " +
                    formatTemperatureForLog(latestCameraTemperatureCelsius)
            )
            currentStackOutputRelativePath = pending.outputRelativeDirectory
            currentCaptureFolder = pending.captureFolder
            runProductionStackMode(lightSequence, masterDark)
            completed = true
        } catch (error: Throwable) {
            Log.e(TAG, "completeDarkCalibrationAndProcess", error)
            log("Dark calibration failed: ${error.message}")
            log("Retained light frames are still available; retry Capture Darks")
        } finally {
            darkSequence.cleanupTemporaryRawFrames()
            runCatching {
                if (working.exists()) working.deleteRecursively()
            }
            if (completed) pendingDarkCaptureSession = null
            darkCaptureInProgress = false
            if (completed) lastDarkTemperatureStatus = null
        }
    }

    /** Publishes the same-session MasterDark RAW and sidecar into public Documents. */
    private fun publishMasterDarkArtifacts(masterDark: MasterDark, workingDirectory: File) {
        val metadata = masterDark.metadata
        val camera = (metadata.cameraId ?: "unknown").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val relativeDirectory =
            "Documents/BracketLab/MasterDarks/camera_$camera/" +
                "ISO_${metadata.iso}/EXP_${metadata.exposureTimeNs}ns"
        val metadataFile =
            File(workingDirectory, "${metadata.id}.json").apply {
                writeText(MasterDarkMetadataJson.encode(metadata), Charsets.UTF_8)
            }
        val publisher = DcimOutputPublisher(applicationContext)
        val outputs =
            listOf(
                publisher.publish(
                    source = masterDark.rawFile,
                    relativeDirectory = relativeDirectory,
                    displayName = metadata.rawFilename
                ),
                publisher.publish(
                    source = metadataFile,
                    relativeDirectory = relativeDirectory,
                    displayName = metadataFile.name,
                    mimeType = "application/json"
                )
            )
        outputs.forEach { output ->
            if (output.success) {
                log(
                    "Saved ${output.displayName} (${output.bytes} bytes) to " +
                        (output.storageDirectory ?: relativeDirectory)
                )
            } else {
                log("MasterDark output failed ${output.displayName}: ${output.failureMessage}")
            }
        }
    }

    /** Maps each production mode to a stable session tree used by DCIM and Documents outputs. */
    private fun stackModeDcimRelativePath(session: String): String {
        val category =
            when {
                stackModesState.processing == StackProcessingSelection.HDR -> "HDR"
                stackModesState.processing == StackProcessingSelection.STAR_TRAIL ->
                    "Astro/StarTrail"
                stackModesState.processing == StackProcessingSelection.STACK &&
                    stackModesState.alignment == StackAlignmentSelection.STARS -> "Astro"
                stackModesState.processing == StackProcessingSelection.STACK &&
                    stackModesState.alignment == StackAlignmentSelection.LANDSCAPE -> "Landscape"
                stackModesState.processing == StackProcessingSelection.STACK -> "Stack"
                else -> "Frames"
            }
        return "DCIM/BracketLab/$category/$session"
    }

    /** Runs the selected real RAW diagnostic after the save queue drains. */
    private fun runDevAlignedStackDiagnostic(adapter: CapturedRawSequenceAdapter) {
        val diagnosticMode = adapter.diagnosticMode ?: return
        val sequence = adapter.snapshot()
        log("DEV ${devModeLabel(diagnosticMode)} processing ${sequence.frameCount} frames")
        when (diagnosticMode) {
            DevRawDiagnosticMode.ALIGNED_STACK -> {
                val result = RealAlignedStackDiagnostic().run(sequence)
                result.logLines.forEach { log(it) }
                log(if (result.success) "DEV aligned validation PASS" else "DEV aligned validation FAIL")
            }
            DevRawDiagnosticMode.CAPTURE_MASTER_DARK -> {
                val result = MasterDarkDiagnostic(masterDarkRoot()).capture(sequence)
                result.logLines.forEach { log(it) }
                log(if (result.success) "DEV MasterDark creation PASS" else "DEV MasterDark creation FAIL")
            }
            DevRawDiagnosticMode.APPLY_MASTER_DARK -> {
                val result = MasterDarkDiagnostic(masterDarkRoot()).apply(sequence)
                result.logLines.forEach { log(it) }
                log(if (result.success) "DEV MasterDark application PASS" else "DEV MasterDark application FAIL")
            }
            DevRawDiagnosticMode.HDRI32_MERGE -> {
                val result = HdrI32Diagnostic(hdri32Root()).run(
                    sequence,
                    darkCalibrationRequested = adapter.hdriDarkCalibrationRequested
                )
                result.logLines.forEach { log(it) }
                log(
                    if (result.success) {
                        "DEV HDR masters + Linear RGB DNG PASS"
                    } else {
                        "DEV HDR masters + Linear RGB DNG FAIL"
                    }
                )
            }
            DevRawDiagnosticMode.STAR_DETECTION -> {
                val result = StarDetectionDiagnostic(starDetectionRoot()).run(sequence)
                result.logLines.forEach { log(it) }
                log(
                    if (result.success) {
                        "DEV star detection PASS"
                    } else {
                        "DEV star detection FAIL"
                    }
                )
            }
            DevRawDiagnosticMode.STAR_MATCHING -> {
                val result = StarMatchingDiagnostic(starDetectionRoot()).run(sequence)
                result.logLines.forEach { log(it) }
                log(
                    if (result.success) {
                        "DEV star matching / RANSAC PASS"
                    } else {
                        "DEV star matching / RANSAC FAIL"
                    }
                )
            }
            DevRawDiagnosticMode.STAR_ALIGNED_STACK -> {
                val result = StarAlignedRaw16Diagnostic(starDetectionRoot()).run(sequence)
                result.logLines.forEach { log(it) }
                log(
                    if (result.success) {
                        "DEV star-aligned RAW16 stack PASS"
                    } else {
                        "DEV star-aligned RAW16 stack FAIL"
                    }
                )
            }
        }
    }

    private fun masterDarkRoot(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        return File(baseDir, "BracketLab_MasterDarks")
    }

    private fun hdri32Root(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        return File(baseDir, "BracketLab_HDR32")
    }

    private fun starDetectionRoot(): File {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        return File(baseDir, "BracketLab_StarAlign")
    }

    private fun devModeLabel(mode: DevRawDiagnosticMode): String =
        when (mode) {
            DevRawDiagnosticMode.ALIGNED_STACK -> "aligned RAW"
            DevRawDiagnosticMode.CAPTURE_MASTER_DARK -> "MasterDark capture"
            DevRawDiagnosticMode.APPLY_MASTER_DARK -> "MasterDark apply"
            DevRawDiagnosticMode.HDRI32_MERGE -> "HDR masters + Linear RGB DNG"
            DevRawDiagnosticMode.STAR_DETECTION -> "star detection"
            DevRawDiagnosticMode.STAR_MATCHING -> "star matching / RANSAC"
            DevRawDiagnosticMode.STAR_ALIGNED_STACK -> "star-aligned RAW16 stack"
        }

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Formats both thermal sensors into the compact status label. */
    private fun formatThermalReading(reading: ThermalReader.Reading): String =
        "skin: ${formatThermalValue(reading.skinCelsius)} camera-usr: ${formatThermalValue(reading.cameraUsrCelsius)}"

    /** Formats one thermal value, using a placeholder when the sensor is unavailable. */
    private fun formatThermalValue(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f\u00B0C", it).replace('.', ',') } ?: "--\u00B0C"

    /** Displays the in-app help overlay above the capture controls. */
    private fun showHelp() {
        if (::stackModesOverlay.isInitialized) stackModesOverlay.visibility = View.GONE
        helpOverlay.visibility = View.VISIBLE
        helpOverlay.bringToFront()
    }

    /** Hides the in-app help overlay. */
    private fun hideHelp() {
        helpOverlay.visibility = View.GONE
    }

    /** Builds the full help text shown in the overlay. */
    private fun buildHelpText(): String = """
BRACKETLAB HELP

SUGGESTED USE

1. Select the Camera ID first.
2. Set WB, OIS, ISO, focus, frame gap, and delay.
3. Use Preview, AE, AF, Get, or Compute only after the correct camera is selected.
4. For stacking, keep exposure and focus stable between frames.
5. For tripod work, use OIS OFF when possible.

TOP TEMPERATURE

skin:
General device surface/body temperature. If the phone does not expose a sensor named skin, BracketLab uses modem-skin-usr as fallback.

camera-usr:
Camera thermal sensor exposed by the device.

--°C:
The sensor could not be read or the device blocked access.

During long sequences, rising temperature can increase noise, hot pixels, color drift, and thermal throttling. If the phone gets too warm, wait before starting another capture.

EXPOSURES

Exposure list used by Capture.

You can type values manually, let AE fill them, use Compute for HDR bracketing, or use shorthand:

1/30+3, 1/50+2

This becomes:

1/30
1/30
1/30
1/50
1/50

Clear empties Exposures and the log.

WB

Auto WB leaves white balance automatic.

Manual WB locks a selected value, helping all frames keep the same color response.

FOLDER

Selects or creates the folder where sequences are saved.

CAMERA ID

Selects the camera module.

Use it before AE, AF, Preview, Live Focus, or Capture.

OIS

Optical stabilization.

For handheld captures, OIS ON can help.

For tripod stacking, OIS OFF is usually better because the stabilization motor can shift frames slightly.

RANGE

Short:
Shortest exposure, used to protect highlights.

Long:
Longest exposure, used to recover shadows.

Get:
Shows a short preview of that exposure so you can check clipping or dark areas.

BRACKET PARAMETERS

EV:
Spacing between bracket frames.

0.5 EV gives more frames and smoother transitions.

1.0 EV gives fewer frames and lighter sequences.

Compute:
Builds the exposure list between Short and Long.

LIVE FOCUS

Opens a preview with a focus slider and zoom crop.

When the slider is released, the selected focus value is written into Focus.

FOCUS BRACKETING

Focus Start:
Nearest focus point.

Focus End:
Farthest focus point.

Frames:
Number of focus steps.

Get Values:
Creates evenly spaced focus values between Start and End.

If you are only stacking at one focus point, use AF normally and do not generate Focus Start/End values.

AE

Measures exposure automatically and fills exposure plus ISO.

The - and + buttons apply AE bias in 0.5 EV steps before metering.

For stacking, a brighter ETTR-style exposure can improve signal, as long as highlights are not clipped.

AF

Runs autofocus once, reads the final focus state, then holds focus so continuous AF does not move during capture.

CAPTURE PARAMETERS

ISO:
Sensor gain.

Focus:
Manual focus distance used for capture.

Interval:
Time between frames.

Delay:
Wait before the capture starts.

PREVIEW

Opens a 3:4 preview from the 4:3 sensor.

Exposure slider:
Brightens or darkens preview only.

Zoom slider:
Helps inspect focus and detail.

Touching preview closes it.

CAPTURE

Starts the capture sequence using the current settings.

Exposure pause:
Use pause(n) with repeated exposures, for example:
15+8, pause(4)

After four frames, Capture becomes a green Resume button. Move the tripod if
needed, then press Resume to continue without closing the capture session.
""".trimIndent()

    @Suppress("DEPRECATION")
    /** Closes overlays first, then falls back to the normal Android back action. */
    override fun onBackPressed() {
        if (
            ::stackModesOverlay.isInitialized &&
            stackModesOverlay.visibility == View.VISIBLE
        ) {
            hideStackModes()
            return
        }
        if (::helpOverlay.isInitialized && helpOverlay.visibility == View.VISIBLE) {
            hideHelp()
            return
        }
        super.onBackPressed()
    }

    /** Returns the text fields that should be persisted between app launches. */
    private fun settingsFields(): List<Pair<String, TextView>> = listOf(
        SettingKeys.EXPOSURES to etExposures,
        SettingKeys.CAMERA_ID to etCameraId,
        SettingKeys.ISO to etIso,
        SettingKeys.INTERVAL to etInterval,
        SettingKeys.FOCUS to etFocus,
        SettingKeys.DELAY to etDelay,
        SettingKeys.HIGHLIGHTS to etHighlights,
        SettingKeys.SHADOWS to etShadows,
        SettingKeys.EV to etEv,
        SettingKeys.FOCUS_FRAMES to etFocusFrames
    )

    /** Applies the default capture, focus, HDR, and toggle values. */
    private fun applyDefaultSettings() {
        restoringSettings = true
        etExposures.setText("")
        etCameraId.setText("ID")
        etIso.setText("ISO")
        etInterval.setText("0")
        etFocus.setText("0.0")
        etDelay.setText("1")
        etHighlights.setText("")
        etShadows.setText("")
        etEv.setText("1.0")
        etFocusStart.setText("")
        etFocusEnd.setText("")
        etFocusFrames.setText("")
        folderModeEnabled = false
        oisEnabled = false
        stackingConfig = StackingConfig.DEFAULT
        stackModesState = StackModesUiState()
        stackModesDraft = stackModesState
        selectedWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        focusStartReady = false
        focusEndReady = false
        tvBrackets.text = "-"
        restoringSettings = false
        updateCameraIdButton()
        updateIsoButton()
        updateWbButton()
        updateFolderButton()
        updateOisButton()
        updateStackModeButtons()
        applyDefaultButtonChrome()
    }

    /** Restores saved UI state and refreshes dependent buttons. */
    private fun loadSavedSettings() {
        restoringSettings = true
        settingsStore.loadFields(settingsFields())
        folderModeEnabled = settingsStore.loadFolderMode(folderModeEnabled)
        oisEnabled = settingsStore.loadOis(oisEnabled)
        selectedWbMode = settingsStore.loadWbMode(selectedWbMode)
        restoringSettings = false
        updateCameraIdButton()
        updateIsoButton()
        updateWbButton()
        updateFolderButton()
        updateOisButton()
        updateStackModeButtons()
        applyDefaultButtonChrome()
    }

    /** Saves settings automatically whenever editable fields change. */
    private fun setupSettingsAutosave() {
        settingsFields().forEach { (_, field) ->
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    saveSettings()
                }
            })
        }
    }

    /** Watches exposure edits and keeps the shorthand expansion guide current. */
    private fun setupExposureShorthand() {
        updateExposureGuide()
        etExposures.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateExposureGuide()
            }
        })
        etExposures.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) expandExposureShorthand()
        }

        val root = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (exposuresKeyboardVisible && !keyboardVisible && etExposures.hasFocus()) {
                etExposures.post { expandExposureShorthand() }
            }
            exposuresKeyboardVisible = keyboardVisible
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Shows whether the exposure shorthand can be expanded. */
    private fun updateExposureGuide() {
        tvExposureGuide.visibility =
            if (etExposures.text.toString().isBlank()) View.VISIBLE else View.GONE
    }

    /** Expands repeated exposure shorthand into one exposure per output line. */
    private fun expandExposureShorthand() {
        if (restoringSettings) return
        val original = etExposures.text.toString()
        if ('+' !in original && !original.contains("pause", ignoreCase = true)) return

        val expanded = buildExpandedExposures(original)
            ?: run {
                log("Invalid exposure shorthand")
                return
            }

        if (expanded == original.trim()) return
        etExposures.setText(expanded)
        etExposures.setSelection(etExposures.text.length)
        val sequence = ExposureSequenceParser.parse(expanded, ::parseExpString)
        tvBrackets.text =
            sequence?.let {
                buildString {
                    append("${it.exposureCount} frames")
                    if (it.pauseCount > 0) append(" / ${it.pauseCount} pause")
                }
            } ?: "-"
        saveSettings()
    }

    /** Expands exposure repetition and optional pause(n) markers. */
    private fun buildExpandedExposures(text: String): String? =
        ExposureSequenceParser.expand(text, ::parseExpString)

    /** Persists the current UI settings unless a restore is in progress. */
    private fun saveSettings() {
        if (restoringSettings) return
        settingsStore.saveFieldsAndToggles(settingsFields(), folderModeEnabled, oisEnabled)
        settingsStore.saveWbMode(selectedWbMode)
    }

    /** Resets all capture controls to their default startup values. */
    private fun resetSettings() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        restoringSettings = true
        etExposures.setText("")
        etCameraId.setText("ID")
        etIso.setText("ISO")
        etInterval.setText("0")
        etFocus.setText("0")
        etDelay.setText("0")
        etHighlights.setText("")
        etShadows.setText("")
        etEv.setText("0")
        etFocusStart.setText("")
        etFocusEnd.setText("")
        etFocusFrames.setText("")
        folderModeEnabled = false
        oisEnabled = false
        stackingConfig = StackingConfig.DEFAULT
        selectedWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
        focusStartReady = false
        focusEndReady = false
        tvBrackets.text = "-"
        restoringSettings = false

        updateCameraIdButton()
        updateIsoButton()
        updateWbButton()
        updateFolderButton()
        updateOisButton()
        updateStackModeButtons()
        applyDefaultButtonChrome()
        saveSettings()
        log("Settings reset")
    }

    /** Toggles optical image stabilization for future captures. */
    private fun toggleOis() {
        oisEnabled = !oisEnabled
        updateOisButton()
        saveSettings()
        log("OIS: ${if (oisEnabled) "ON" else "OFF"}")
    }

    /** Updates the OIS button label and color from the current state. */
    private fun updateOisButton() {
        btnOis.text = if (oisEnabled) "OIS\nON" else "OIS\nOFF"
        applyButtonStroke(
            btnOis,
            if (oisEnabled) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
        )
    }

    /** Restores default button outlines before state-specific colors are applied. */
    private fun applyDefaultButtonChrome() {
        listOf(btnAF, btnPreview, btnStart).forEach {
            applyButtonStroke(it, 0xFFFFFFFF.toInt())
        }
        updateStackModeButtons()
        updateAeBiasButtons()
    }

    /** Opens the large Stack Modes editor with a disposable draft state. */
    private fun showStackModes() {
        if (pendingDarkCaptureSession != null) {
            log("Finish the pending dark calibration session first")
            return
        }
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }
        stopFramingPreviewForCapture()
        if (::helpOverlay.isInitialized) helpOverlay.visibility = View.GONE
        stackModesDraft = stackModesState
        updateStackModesOverlay()
        stackModesOverlay.visibility = View.VISIBLE
        stackModesOverlay.bringToFront()
    }

    /** Closes Stack Modes and discards changes that were not applied. */
    private fun hideStackModes() {
        stackModesDraft = stackModesState
        stackModesOverlay.visibility = View.GONE
    }

    /** Replaces selected portions of the Stack Modes draft and reapplies constraints. */
    private fun updateStackModesDraft(
        processing: StackProcessingSelection? = null,
        alignment: StackAlignmentSelection? = null,
        dithering: StackDitheringSelection? = null
    ) {
        stackModesDraft =
            stackModesDraft.copy(
                processing = processing ?: stackModesDraft.processing,
                alignment = alignment ?: stackModesDraft.alignment,
                dithering = dithering ?: stackModesDraft.dithering
            ).normalized()
        updateStackModesOverlay()
    }

    /** Commits the UI selection and maps currently supported choices to StackingConfig. */
    private fun applyStackModesDraft() {
        stackModesState = stackModesDraft.normalized()
        val starAlignment = stackModesState.alignment == StackAlignmentSelection.STARS
        val landscapeAlignment = stackModesState.alignment == StackAlignmentSelection.LANDSCAPE
        stackingConfig =
            stackingConfig.copy(
                operation =
                    when (stackModesState.processing) {
                        StackProcessingSelection.STACK -> StackingOperation.NORMAL
                        StackProcessingSelection.HDR -> StackingOperation.HDRI
                        StackProcessingSelection.FRAMES_ONLY,
                        StackProcessingSelection.STAR_TRAIL -> StackingOperation.OFF
                    },
                outputDepth =
                    if (stackModesState.processing == StackProcessingSelection.HDR) {
                        OutputDepth.FLOAT32_32F
                    } else {
                        OutputDepth.RAW16_16I
                    },
                alignToggle =
                    if (landscapeAlignment) AlignToggle.AUTO else AlignToggle.OFF,
                astroState =
                    if (starAlignment) AstroState.STAR else AstroState.OFF,
                darkPolicy =
                    if (stackModesState.useDarks) {
                        StackingDarkPolicy.USE_COMPATIBLE_MASTER_DARK
                    } else {
                        StackingDarkPolicy.OFF
                    }
            )
        updateStackModeButtons()
        hideStackModes()
        log("Stack Modes: ${stackModesLogLabel(stackModesState)}")
        if (stackModesState.dithering != StackDitheringSelection.OFF) {
            log("Manual dithering armed; use pause in the exposure list")
        }
        if (stackModesState.processing == StackProcessingSelection.STAR_TRAIL) {
            log("Star Trail uses a fixed-camera maximum RAW16 composite")
        }
    }

    /** Refreshes every Stack Modes button, constraint and result summary. */
    private fun updateStackModesOverlay() {
        stackModesDraft = stackModesDraft.normalized()
        val state = stackModesDraft
        val starTrail = state.processing == StackProcessingSelection.STAR_TRAIL
        val hdr = state.processing == StackProcessingSelection.HDR
        val framesOnly = state.processing == StackProcessingSelection.FRAMES_ONLY
        val manualDitherAllowed =
            !starTrail &&
                (framesOnly || state.alignment == StackAlignmentSelection.STARS)

        setStackModeChoice(btnModeFramesOnly, state.processing == StackProcessingSelection.FRAMES_ONLY)
        setStackModeChoice(btnModeStack, state.processing == StackProcessingSelection.STACK)
        setStackModeChoice(btnModeHdri, hdr)
        setStackModeChoice(btnModeStarTrail, starTrail)

        setStackModeChoice(btnAlignmentOff, state.alignment == StackAlignmentSelection.OFF, !starTrail)
        setStackModeChoice(
            btnAlignmentLandscape,
            state.alignment == StackAlignmentSelection.LANDSCAPE,
            !starTrail
        )
        setStackModeChoice(
            btnAlignmentStars,
            state.alignment == StackAlignmentSelection.STARS,
            !starTrail && !hdr
        )

        setStackModeChoice(btnDitherOff, state.dithering == StackDitheringSelection.OFF)
        setStackModeChoice(
            btnDitherManual,
            state.dithering == StackDitheringSelection.MANUAL_ASSISTED,
            manualDitherAllowed
        )

        val darkCalibrationAllowed = !framesOnly && !hdr
        btnUseDarks.text = "Use darks"
        setStackModeChoice(btnUseDarks, state.useDarks, darkCalibrationAllowed)

        btnKeepSourceFrames.text =
            "Keep Source Frames: ${if (state.keepSourceFrames) "ON" else "OFF"}"
        setStackModeChoice(btnKeepSourceFrames, state.keepSourceFrames, !framesOnly)

        btnOutputDng.text = "DNG: ${if (state.outputDng) "ON" else "OFF"}"
        btnOutputXisf.text = "XISF: ${if (state.outputXisf) "ON" else "OFF"}"
        btnOutputFits.text = "FITS: ${if (state.outputFits) "ON" else "OFF"}"
        setStackModeChoice(btnOutputDng, state.outputDng, !framesOnly)
        setStackModeChoice(btnOutputXisf, state.outputXisf, !framesOnly)
        setStackModeChoice(btnOutputFits, state.outputFits, !framesOnly)

        tvStackModesSummary.text = stackModesSummary(state)
    }

    /** Styles one exclusive/toggle control and dims unavailable combinations. */
    private fun setStackModeChoice(button: Button, selected: Boolean, enabled: Boolean = true) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
        applyButtonStroke(
            button,
            when {
                !enabled -> 0xFF555555.toInt()
                selected -> 0xFF00FF00.toInt()
                else -> 0xFFFF0000.toInt()
            }
        )
    }

    /** Describes the exact result represented by the current Stack Modes draft. */
    private fun stackModesSummary(state: StackModesUiState): String {
        val processing =
            when (state.processing) {
                StackProcessingSelection.FRAMES_ONLY -> "Frames Only"
                StackProcessingSelection.STACK -> "Stack"
                StackProcessingSelection.HDR -> "HDR"
                StackProcessingSelection.STAR_TRAIL -> "Star Trail"
            }
        val alignment =
            when (state.alignment) {
                StackAlignmentSelection.OFF -> "Off"
                StackAlignmentSelection.LANDSCAPE -> "Landscape"
                StackAlignmentSelection.STARS -> "Stars"
            }
        val outputs =
            if (state.processing == StackProcessingSelection.FRAMES_ONLY) {
                "Source DNG"
            } else {
                buildList {
                    if (state.outputDng) {
                        add(
                            if (state.processing == StackProcessingSelection.HDR) {
                                "DNG (Linear RGB Float16)"
                            } else {
                                "DNG (Bayer RAW16)"
                            }
                        )
                    }
                    if (state.outputXisf) add("XISF")
                    if (state.outputFits) add("FITS")
                }.joinToString()
            }
        return """
PROCESSING  $processing
ALIGNMENT   $alignment
DITHERING   ${if (state.dithering == StackDitheringSelection.OFF) "Off" else "Manual Assisted"}
CALIBRATION ${if (state.useDarks) "Use darks" else "Off"}
SOURCE FRAMES  ${if (state.keepSourceFrames) "Save individual DNGs" else "Do not publish individual DNGs"}
OUTPUTS        $outputs
""".trimIndent()
    }

    /** Formats the committed selection for the main log. */
    private fun stackModesLogLabel(state: StackModesUiState): String =
        "${state.processing.name} / ${state.alignment.name} / ${state.dithering.name} / " +
            "DARKS_${if (state.useDarks) "ON" else "OFF"}"

    /** Updates the single top button with red default or green custom-state outline. */
    private fun updateStackModeButtons() {
        applyButtonStroke(
            btnStackModes,
            if (stackModesState != StackModesUiState()) {
                0xFF00FF00.toInt()
            } else {
                0xFFFF0000.toInt()
            }
        )
        updateDevButton()
    }

    /** Shows whether the next capture is armed for the real aligned-stack diagnostic. */
    private fun updateDevButton() {
        if (!::btnDev.isInitialized || !isDebugBuild()) return
        btnDev.text = if (devDiagnosticMode != null) "DEV*" else "DEV"
        applyButtonStroke(
            btnDev,
            if (devDiagnosticMode != null) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
        )
    }

    /** Clears readiness flags for focus-bracket start/end values. */
    private fun clearFocusBracketLock() {
        focusStartReady = false
        focusEndReady = false
        etFocusStart.setText("")
        etFocusEnd.setText("")
    }

    /** Clears the exposure list and current log output. */
    private fun clearExposures() {
        etExposures.setText("")
        tvBrackets.text = "-"
        clearLog()
        saveSettings()
    }

    /** Adjusts the AE exposure bias used when measuring highlights or shadows. */
    private fun changeAeBias(deltaEv: Double) {
        aeBiasEv = (aeBiasEv + deltaEv).coerceIn(-4.0, 4.0)
        updateAeBiasButtons()
        log("AE bias ${formatSignedEv(aeBiasEv)} EV")
    }

    /** Refreshes AE bias button labels and accent colors. */
    private fun updateAeBiasButtons() {
        val t = ((aeBiasEv + 4.0) / 8.0).coerceIn(0.0, 1.0)
        val channel = (48 + t * 207).roundToInt().coerceIn(48, 255)
        val color = 0xFF000000.toInt() or
                (channel shl 16) or
                (channel shl 8) or
                channel
        applyButtonStroke(btnAeBiasMinus, color)
        applyButtonStroke(btnAeBiasPlus, color)
    }

    /** Formats EV values with an explicit sign for button labels. */
    private fun formatSignedEv(value: Double): String =
        "%+.1f".format(Locale.US, value)

    /** Tracks device orientation so previews and DNG metadata rotate correctly. */
    private fun setupOrientationTracking() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    deviceOrientationDegrees = orientation
                    if (framingPreviewRunning) {
                        runOnUiThread {
                            configureTransform(framingPreview.width, framingPreview.height)
                        }
                    }
                }
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }


    /** Requests camera/media permissions required for capture and saving. */
    private fun checkPermissions() {
        if (PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            })
            log("Permissions granted!")
        else
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERM)
    }

    /** Starts camera background threads after the user grants permissions. */
    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_PERM)
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) log("Permissions granted!")
            else log("Permissions denied!")
    }

    /** Releases camera, preview, thermal, and orientation resources on shutdown. */
    override fun onDestroy() {
        running = false
        releaseCapturePause()
        if (::orientationListener.isInitialized) orientationListener.disable()
        stopThermalUpdates()
        diagnosticsExecutor.shutdownNow()
        super.onDestroy()
        liveFocusRunning = false
        stopFramingPreviewForCapture()
        runCatching { liveFocusSurface?.release() }
        liveFocusSurface = null
        pendingDarkCaptureSession?.lightSequence?.cleanupTemporaryRawFrames()
        pendingDarkCaptureSession = null
        closeEverything()
    }

    // ══════════════════════════════════════════════
    //  HDR COMPUTE
    // ══════════════════════════════════════════════

    /** Computes HDR exposure brackets from the highlight, shadow, and EV inputs. */
    private fun computeBrackets() {
        val highNs = parseExpString(etHighlights.text.toString())
            ?: run { log("Invalid Highlights"); return }
        val lowNs = parseExpString(etShadows.text.toString())
            ?: run { log("Invalid Shadows"); return }
        val ev = etEv.text.toString().replace(',', '.').toDoubleOrNull()
            ?: run { log("Invalid EV"); return }

        if (highNs >= lowNs) {
            log("Highlights must be lower than shadows"); return
        }
        if (ev <= 0.0) {
            log("EV must be higher than 0"); return
        }

        val brackets = buildHdrBrackets(highNs, lowNs, ev)

        log("--- Compute ---")
        log("Highlights: ${fmtExpNs(highNs)}  Shadows: ${fmtExpNs(lowNs)}")
        log("Range: ${"%.1f".format(brackets.rangeStops)} stops  EV: $ev  Frames: ${brackets.frames}")
        log("Real spacing: ${"%.3f".format(brackets.actualSpacing)} stops")
        log("List: ${brackets.exposures.joinToString(" / ") { fmtExpNs(it) }}")

        val listText = brackets.exposures.joinToString("\n") { expNsToInputText(it) }
        runOnUiThread {
            etExposures.setText(listText)
            tvBrackets.text = "${brackets.frames} frames"
        }
    }

    /** Reads and validates focus-bracket values from the UI. */
    private fun readFocusBracketValues(showErrors: Boolean): List<Float>? {
        if (!focusStartReady || !focusEndReady) {
            if (showErrors) log("Focus Start/End not set")
            return null
        }

        val startText = etFocusStart.text.toString().trim()
        val endText = etFocusEnd.text.toString().trim()
        val framesText = etFocusFrames.text.toString().trim()
        if (startText.isEmpty() && endText.isEmpty() && framesText.isEmpty()) return null

        val start = startText.replace(',', '.').toFloatOrNull()
        val end = endText.replace(',', '.').toFloatOrNull()
        val frames = framesText.toIntOrNull()

        if (start == null || end == null || frames == null) {
            if (showErrors) log("Invalid focus bracket")
            return null
        }
        if (frames < 2) {
            if (showErrors) log("Frames must be 2 or higher")
            return null
        }

        return buildFocusBrackets(start, end, frames)
    }

    /** Generates focus-bracket distances and optionally logs the result. */
    private fun computeFocusBracket(showLog: Boolean): List<Float>? {
        if (!focusStartReady || !focusEndReady) {
            val frames = etFocusFrames.text.toString().toIntOrNull()
            if (frames == null || frames < 1) {
                if (showLog) log("Invalid Frames")
                return null
            }
            if (showLog) {
                syncExposuresToFocusFrames(frames)
                log("--- Stacking ---")
                log("Frames: $frames")
                log("Aguardando inicio da captura")
            }
            return null
        }

        val values = readFocusBracketValues(showErrors = showLog) ?: return null
        if (showLog) {
            syncExposuresToFocusFrames(values.size)
        }
        if (showLog) {
            log("--- fBracket ---")
            log("Frames: ${values.size}")
            log(values.joinToString(" / ") { "%.4f".format(Locale.US, it) })
            log("Aguardando inicio da captura")
        }
        return values
    }

    /** Repeats the exposure list so it lines up with the focus-bracket frame count. */
    private fun syncExposuresToFocusFrames(frames: Int) {
        val firstExposureText = etExposures.text.toString().lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && parseExpString(it) != null }

        if (firstExposureText == null) {
            log("No exposure reference")
            return
        }

        val listText = List(frames) { firstExposureText }.joinToString("\n")
        etExposures.setText(listText)
        tvBrackets.text = "$frames frames"
    }

    private data class AeReading(val expNs: Long, val iso: Int)
    private data class AeAdjustedReading(
        val expNs: Long,
        val iso: Int,
        val requestedFactor: Double,
        val actualEv: Double
    )

    /** Starts an auto-exposure measurement and writes the adjusted value to the UI. */
    private fun startAeMeasure() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        stopFramingPreviewForCapture()
        val camId = selectedCameraId()
        val frames = etFocusFrames.text.toString().toIntOrNull()
            ?: parseExposures().size.takeIf { it > 0 }
            ?: 1

        setControlsEnabled(false)
        log("AE measuring ${formatSignedEv(aeBiasEv)} EV")
        startBgThreads()

        Thread({
            try {
                val ae = measureAutoExposure(camId)
                val adjusted = ae?.let { applyAeBias(it) }
                if (adjusted != null) {
                    val expText = expNsToInputText(adjusted.expNs)
                    val safeFrames = frames.coerceAtLeast(1)
                    val listText = List(safeFrames) { expText }.joinToString("\n")
                    runOnUiThread {
                        etIso.setText(adjusted.iso.toString())
                        etExposures.setText(listText)
                        tvBrackets.text = "$safeFrames frames"
                    }
                    log(
                        "AE ${fmtExpNs(ae.expNs)} ISO=${ae.iso} -> " +
                                "${fmtExpNs(adjusted.expNs)} ISO=${adjusted.iso} " +
                                "bias=${formatSignedEv(adjusted.actualEv)} EV"
                    )
                } else {
                    log("AE failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AE", e)
                log("AE error: ${e.message}")
            } finally {
                closeEverything()
                runOnUiThread { setControlsEnabled(true) }
            }
        }, "ae-thread").start()
    }

    /** Applies the configured EV bias to an AE reading while respecting sensor limits. */
    private fun applyAeBias(reading: AeReading): AeAdjustedReading {
        val c = chars ?: return AeAdjustedReading(reading.expNs, reading.iso, 1.0, 0.0)
        val requestedFactor = 2.0.pow(aeBiasEv)
        if (requestedFactor == 1.0) {
            return AeAdjustedReading(reading.expNs, reading.iso, requestedFactor, 0.0)
        }

        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minExp = expRange?.lower ?: 1L
        val maxExp = expRange?.upper ?: Long.MAX_VALUE
        val minIso = isoRange?.lower ?: 1
        val maxIso = isoRange?.upper ?: Int.MAX_VALUE

        val targetSignal = reading.expNs.toDouble() * reading.iso.toDouble() * requestedFactor
        var adjustedExp = (reading.expNs.toDouble() * requestedFactor)
            .roundToLong()
            .coerceIn(minExp, maxExp)
        var adjustedIso = (targetSignal / adjustedExp)
            .roundToInt()
            .coerceIn(minIso, maxIso)

        adjustedExp = (targetSignal / adjustedIso)
            .roundToLong()
            .coerceIn(minExp, maxExp)

        val actualFactor =
            (adjustedExp.toDouble() * adjustedIso.toDouble()) /
                    (reading.expNs.toDouble() * reading.iso.toDouble())
        val actualEv = kotlin.math.log(actualFactor, 2.0)
        return AeAdjustedReading(adjustedExp, adjustedIso, requestedFactor, actualEv)
    }

    /** Opens a camera session long enough to collect one stable auto-exposure result. */
    private fun measureAutoExposure(camId: String): AeReading? {
        closeEverything()
        startBgThreads()
        if (!openSelectedCamera(camId)) return null

        val texture = SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480) }
        val surface = Surface(texture)
        try {
            if (!createSession(listOf(surface))) return null

            val latch = CountDownLatch(1)
            val reading = AtomicReference<AeReading?>(null)
            var seenFrames = 0
            val focus = etFocus.text.toString().replace(',', '.').toFloatOrNull() ?: 0f

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    res: TotalCaptureResult
                ) {
                    val exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val iso = res.get(CaptureResult.SENSOR_SENSITIVITY)
                    if (exp != null && iso != null) {
                        reading.set(AeReading(exp, iso))
                        seenFrames++
                    }

                    val aeState = res.get(CaptureResult.CONTROL_AE_STATE)
                    if (seenFrames >= 10 && (
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                        aeState == null
                                )
                    ) {
                        latch.countDown()
                    }
                }
            }

            val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyOisMode()
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            }.build()

            captureSession!!.setRepeatingRequest(req, callback, camHandler)
            latch.await(3500, TimeUnit.MILLISECONDS)
            return reading.get()
        } finally {
            runCatching { surface.release() }
            runCatching { texture.release() }
        }
    }

    /** Captures a JPEG preview for a measured highlight or shadow exposure. */
    private fun startGetPreview(expField: EditText, label: String) {
        if (running || liveFocusRunning) {
            log("Capture in progress..."); return
        }
        stopFramingPreviewForCapture()
        val expNs = parseExpString(expField.text.toString())
            ?: run { log("Invalid exposure at $label"); return }
        val iso = etIso.text.toString().toIntOrNull() ?: 400
        val camId = selectedCameraId()
        val focus = etFocus.text.toString().replace(',', '.').toFloatOrNull() ?: 0f

        setControlsEnabled(false)
        log("$label — ${fmtExpNs(expNs)} ISO=$iso f=$focus")
        startBgThreads()

        Thread({
            try {
                captureJpegPreview(camId, expNs, iso, focus)
            } catch (e: Exception) {
                Log.e(TAG, "getPreview", e); log("Error preview: ${e.message}")
            } finally {
                closeEverything()
                runOnUiThread { setControlsEnabled(true) }
            }
        }, "preview-thread").start()
    }

    /** Chooses a JPEG preview size that is large enough but still quick to decode. */
    private fun choosePreviewJpegSize(sizes: Array<Size>): Size {
        val targetW = 1440
        val targetH = 1080
        val targetArea = targetW * targetH

        val exact = sizes.firstOrNull {
            (it.width == targetW && it.height == targetH) ||
                    (it.width == targetH && it.height == targetW)
        }

        if (exact != null) return exact

        return sizes
            .filter { isFourThree(it) }
            .minByOrNull {
                abs((it.width * it.height) - targetArea)
            }
            ?: sizes.minByOrNull {
                abs((it.width * it.height) - targetArea)
            }!!
    }

    /** Checks whether a preview size is close to a 4:3 aspect ratio. */
    private fun isFourThree(size: Size): Boolean {
        val longSide = max(size.width, size.height)
        val shortSide = min(size.width, size.height)
        return abs(longSide * 3 - shortSide * 4) <= 8
    }

    /** Rotates landscape preview images according to the current phone side. */
    private fun rotateLandscapePreviewByPhoneSide(source: Bitmap): Bitmap {
        if (source.isRecycled) return source

        val isLandscape = source.width > source.height
        if (!isLandscape) return source

        val phoneRotation = roundDeviceOrientationToRightAngle()

        val previewRotation = when (phoneRotation) {
            90 -> 90f   // celular deitado para um lado
            270 -> -90f   // celular deitado para o outro lado
            else -> 90f
        }

        val matrix = Matrix().apply {
            postRotate(previewRotation)
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    /** Captures one manual JPEG preview using the selected exposure, ISO, and focus. */
    private fun captureJpegPreview(camId: String, expNs: Long, iso: Int, focus: Float) {
        if (!openSelectedCamera(camId)) return

        // Pick a decent JPEG size — not too large (slow), not too small (useless)
        val jpegSizes = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?: run { log("No JPEG sizes"); return }

        // Target ~2MP for fast decode and display
        val jpegSize = choosePreviewJpegSize(jpegSizes)
        val jpegReader =
            ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        log("JPEG preview: ${jpegSize.width}×${jpegSize.height}")

        if (!createSession(listOf(jpegReader.surface))) {
            jpegReader.close(); return
        }

        val imgLatch = CountDownLatch(1)
        val resLatch = CountDownLatch(1)
        val imageRef = AtomicReference<Image?>(null)
        val resultRef = AtomicReference<TotalCaptureResult?>(null)

        jpegReader.setOnImageAvailableListener({ r ->
            imageRef.set(r.acquireLatestImage()); imgLatch.countDown()
        }, bgHandler)

        val req = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(jpegReader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            applyWhiteBalance()
            disableProcessingIfAvailable(this)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    applyOisMode()
                }

        captureSession!!.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                res: TotalCaptureResult
            ) {
                resultRef.set(res); resLatch.countDown()
            }

            override fun onCaptureFailed(
                s: CameraCaptureSession,
                r: CaptureRequest,
                f: CaptureFailure
            ) {
                log("JPEG capture failed reason=${f.reason}"); resLatch.countDown(); imgLatch.countDown()
            }
        }, bgHandler)

        val timeoutSec = (expNs / 1_000_000_000L) + 15L
        resLatch.await(timeoutSec, TimeUnit.SECONDS)
        imgLatch.await(10, TimeUnit.SECONDS)

        val image = imageRef.get()
        if (image == null) {
            log("No JPEG image"); jpegReader.close(); return
        }

        // Decode JPEG bytes to Bitmap
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        image.close()
        jpegReader.close()

        val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: run {
                log("Fail on decoding JPEG")
                return
            }

        val correctedBitmap = rotateBitmapForPreview(decodedBitmap)

        if (correctedBitmap !== decodedBitmap && !decodedBitmap.isRecycled) {
            decodedBitmap.recycle()
        }

// gira só o preview paisagem para facilitar visualização na tela travada em retrato
        val previewBitmap = rotateLandscapePreviewByPhoneSide(correctedBitmap)

        if (previewBitmap !== correctedBitmap && !correctedBitmap.isRecycled) {
            correctedBitmap.recycle()
        }

        log("Preview captured - 2s timeout")
        showPreviewBitmap(previewBitmap)
    }

    /** Toggles the live framing preview overlay on and off. */
    private fun toggleFramingPreview() {
        if (framingPreviewRunning) {
            stopFramingPreview()
        } else {
            startFramingPreview()
        }
    }

    private data class PreviewCameraTarget(
        val openCameraId: String,
        val physicalCameraId: String?
    )

    /** Starts the framing preview after checking capture state and camera choice. */
    private fun startFramingPreview() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        logCameraInventoryOnce()
        framingPreviewRunning = true
        framingPreviewTransformSignature = null
        btnPreview.text = "Stop Preview"
        showFramingPreviewView()

        if (framingPreview.isAvailable) {
            beginFramingPreview(framingPreview.surfaceTexture)
        } else {
            framingPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    beginFramingPreview(surface)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    configureTransform(width, height)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stopFramingPreview()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    /** Shows the framing preview UI and moves it above the controls. */
    private fun showFramingPreviewView() {
        updateFramingPreviewLayout()
        previewOverlay.isClickable = false
        ivPreview.setImageDrawable(null)
        ivPreview.visibility = View.GONE
        livePreview.visibility = View.GONE
        sbLiveFocus.visibility = View.GONE
        framingPreview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                stopFramingPreview()
            }
            false
        }
        framingPreviewContainer.visibility = View.VISIBLE
        previewOverlay.visibility = View.VISIBLE
    }

    /** Sizes the framing preview container for the current screen orientation. */
    private fun updateFramingPreviewLayout() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val sliderHeight = dp(84)
        var width = screenW
        var previewHeight = (width * 4f / 3f).roundToInt()
        if (previewHeight + sliderHeight > screenH) {
            previewHeight = (screenH - sliderHeight).coerceAtLeast(dp(120))
            width = (previewHeight * 3f / 4f).roundToInt()
        }
        previewOverlay.layoutParams = (previewOverlay.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            this.height = previewHeight + sliderHeight
            this.gravity = android.view.Gravity.CENTER
            this.topMargin = 0
        }
        framingPreview.setAspectRatio(3, 4)
    }

    /** Hides the framing preview UI and clears its texture listener. */
    private fun hideFramingPreviewView() {
        btnPreview.text = "Preview"
        framingPreview.setOnTouchListener(null)
        previewOverlay.setOnTouchListener(null)
        sbPreviewExposure.setOnSeekBarChangeListener(null)
        sbPreviewZoom.setOnSeekBarChangeListener(null)
        framingPreviewContainer.visibility = View.GONE
        ivPreview.visibility = View.VISIBLE
        previewOverlay.visibility = View.GONE
        livePreview.visibility = View.GONE
        sbLiveFocus.visibility = View.GONE
    }

    /** Opens the selected camera and attaches it to the framing preview surface. */
    private fun beginFramingPreview(surfaceTexture: SurfaceTexture?) {
        val texture = surfaceTexture ?: run {
            stopFramingPreview()
            return
        }

        Thread({
            try {
                closeEverything()
                startBgThreads()

                val requestedCameraId = selectedCameraId()
                val target = resolvePreviewCameraTarget(requestedCameraId)
                if (!openSelectedCamera(target.openCameraId)) {
                    stopFramingPreview()
                    return@Thread
                }

                val map = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSize = map
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.let { chooseOptimalSize(it) }
                    ?: Size(1440, 1080)
                framingPreviewSize = previewSize
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                framingPreviewSurface = Surface(texture)

                runOnUiThread {
                    setupPreviewExposureSlider(chars!!)
                    setupPreviewZoomSlider(chars!!)
                    configureTransform(framingPreview.width, framingPreview.height)
                }

                val surface = framingPreviewSurface ?: return@Thread
                val sessionOk =
                    createCameraPreviewSession(surface, target.physicalCameraId) ||
                            (target.physicalCameraId != null && run {
                                log("Physical preview failed, fallback logical ${target.openCameraId}")
                                createCameraPreviewSession(surface, null)
                            })

                if (!sessionOk) {
                    stopFramingPreview()
                    return@Thread
                }

                log(
                    "Preview ID $requestedCameraId opened=${target.openCameraId}" +
                            (target.physicalCameraId?.let { " physical=$it" } ?: "") +
                            " ${previewSize.width}x${previewSize.height}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "framingPreview", e)
                log("Preview error: ${e.message}")
                stopFramingPreview()
            }
        }, "camera2-preview-thread").start()
    }

    /** Configures the exposure-compensation slider for the preview camera. */
    private fun setupPreviewExposureSlider(c: CameraCharacteristics) {
        val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (range == null || range.lower == range.upper) {
            sbPreviewExposure.visibility = View.GONE
            return
        }

        val lower = range.lower
        val upper = range.upper
        framingPreviewAeCompensation = framingPreviewAeCompensation.coerceIn(lower, upper)
        sbPreviewExposure.visibility = View.VISIBLE
        sbPreviewExposure.max = upper - lower
        sbPreviewExposure.progress = framingPreviewAeCompensation - lower
        sbPreviewExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    applyPreviewExposureCompensation(lower + progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    /** Applies a new AE compensation index to the active preview request. */
    private fun applyPreviewExposureCompensation(index: Int) {
        framingPreviewAeCompensation = index
        val builder = framingPreviewRequestBuilder ?: return
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
        runCatching {
            captureSession?.setRepeatingRequest(builder.build(), null, camHandler)
        }.onFailure {
            log("Preview EV failed: ${it.message}")
        }
    }

    /** Configures the zoom slider using the camera maximum digital zoom. */
    private fun setupPreviewZoomSlider(c: CameraCharacteristics) {
        val maxZoom = min(10f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        if (maxZoom <= 1f) {
            framingPreviewZoom = 1f
            sbPreviewZoom.visibility = View.GONE
            return
        }

        framingPreviewZoom = framingPreviewZoom.coerceIn(1f, maxZoom)
        sbPreviewZoom.visibility = View.VISIBLE
        sbPreviewZoom.max = 900
        sbPreviewZoom.progress = (((framingPreviewZoom - 1f) / (maxZoom - 1f)) * sbPreviewZoom.max)
            .roundToInt()
            .coerceIn(0, sbPreviewZoom.max)
        sbPreviewZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoom = 1f + (progress / sbPreviewZoom.max.toFloat()) * (maxZoom - 1f)
                    applyPreviewZoom(zoom)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    /** Applies digital zoom to the active preview request. */
    private fun applyPreviewZoom(zoom: Float) {
        val c = chars ?: return
        val maxZoom = min(10f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        framingPreviewZoom = zoom.coerceIn(1f, maxZoom)
        val builder = framingPreviewRequestBuilder ?: return
        applyPreviewZoomCrop(builder, c)
        runCatching {
            captureSession?.setRepeatingRequest(builder.build(), null, camHandler)
        }.onFailure {
            log("Preview zoom failed: ${it.message}")
        }
    }

    /** Writes the calculated zoom crop rectangle into a capture request. */
    private fun applyPreviewZoomCrop(
        builder: CaptureRequest.Builder,
        c: CameraCharacteristics
    ) {
        zoomCropRect(c, framingPreviewZoom)?.let {
            builder.set(CaptureRequest.SCALER_CROP_REGION, it)
        }
    }

    /** Resolves logical and physical camera IDs used for the preview stream. */
    private fun resolvePreviewCameraTarget(cameraId: String): PreviewCameraTarget {
        val publicIds = cameraManager.cameraIdList.toMutableList()
        if (cameraId in publicIds) return PreviewCameraTarget(cameraId, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            publicIds.forEach { logicalId ->
                val physicalIds =
                    runCatching {
                        cameraManager.getCameraCharacteristics(logicalId)
                            .physicalCameraIds
                            .toMutableSet()
                    }.getOrDefault(mutableSetOf())
                if (cameraId in physicalIds) {
                    return PreviewCameraTarget(logicalId, cameraId)
                }
            }
        }

        runCatching { cameraManager.getCameraCharacteristics(cameraId) }
            .getOrNull()
            ?: throw IllegalArgumentException("Camera $cameraId not found")
        return PreviewCameraTarget(cameraId, null)
    }

    /** Stops the framing preview and restores the normal capture controls. */
    private fun stopFramingPreview() {
        if (!framingPreviewRunning && framingPreviewSurface == null) {
            runOnUiThread { hideFramingPreviewView() }
            return
        }
        framingPreviewRunning = false
        runOnUiThread { hideFramingPreviewView() }
        runCatching { captureSession?.stopRepeating() }
        runCatching { framingPreviewSurface?.release() }
        framingPreviewSurface = null
        framingPreviewRequestBuilder = null
        closeEverything()
    }

    /** Stops preview safely before starting a capture or focus operation. */
    private fun stopFramingPreviewForCapture() {
        if (!framingPreviewRunning && framingPreviewSurface == null) return
        framingPreviewRunning = false
        hideFramingPreviewView()
        runCatching { captureSession?.stopRepeating() }
        runCatching { framingPreviewSurface?.release() }
        framingPreviewSurface = null
        framingPreviewRequestBuilder = null
        closeEverything()
    }

    /** Chooses the best preview stream size for the screen. */
    private fun chooseOptimalSize(sizes: Array<Size>): Size {
        sizes.firstOrNull { it.width == 1440 && it.height == 1080 }?.let { return it }

        val targetArea = 1440 * 1080
        val fourThreeLandscape = sizes.filter { isFourThree(it) && it.width >= it.height }
        val fourThree = sizes.filter { isFourThree(it) }
        return (fourThreeLandscape.ifEmpty { fourThree }.ifEmpty { sizes.toList() })
            .minByOrNull { abs((it.width * it.height) - targetArea) }!!
    }

    /** Creates a repeating Camera2 preview session for the framing surface. */
    private fun createCameraPreviewSession(surface: Surface, physicalCameraId: String?): Boolean {
        val device = cameraDevice ?: return false
        val c = chars ?: return false
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyPreviewRequestControls(this, c)
            applyOisMode(c)
        }
        framingPreviewRequestBuilder = builder

        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!framingPreviewRunning) {
                    session.close()
                    latch.countDown()
                    return
                }
                captureSession = session
                runCatching {
                    session.setRepeatingRequest(builder.build(), null, camHandler)
                    ok = true
                }.onFailure {
                    log("Preview repeating failed: ${it.message}")
                }
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                log("Preview session failed")
                latch.countDown()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val output = android.hardware.camera2.params.OutputConfiguration(surface)
            if (physicalCameraId != null) {
                output.setPhysicalCameraId(physicalCameraId)
            }
            device.createCaptureSession(
                android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    Executors.newSingleThreadExecutor(),
                    cb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), cb, camHandler)
        }

        latch.await(10, TimeUnit.SECONDS)
        return ok
    }

    /** Applies focus, zoom, AE, OIS, frame rate, and processing settings to preview. */
    private fun applyPreviewRequestControls(
        builder: CaptureRequest.Builder,
        c: CameraCharacteristics
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, framingPreviewAeCompensation)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        applyPreviewZoomCrop(builder, c)

        val stabilizationModes =
            c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (stabilizationModes != null &&
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF in stabilizationModes
        ) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        } else {
            log("Preview stabilization OFF unsupported")
        }

        val noiseModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (noiseModes != null && CaptureRequest.NOISE_REDUCTION_MODE_OFF in noiseModes) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        } else {
            log("Preview NR OFF unsupported")
        }

        val edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edgeModes != null && CaptureRequest.EDGE_MODE_OFF in edgeModes) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        } else {
            log("Preview EDGE OFF unsupported")
        }

        val hotPixelModes = c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
        if (hotPixelModes != null && CaptureRequest.HOT_PIXEL_MODE_OFF in hotPixelModes) {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
        } else {
            log("Preview HOT PIXEL OFF unsupported")
        }

        val aberrationModes =
            c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        if (aberrationModes != null &&
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF in aberrationModes
        ) {
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        } else {
            log("Preview aberration OFF unsupported")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val shadingModes = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            if (shadingModes != null && CaptureRequest.SHADING_MODE_OFF in shadingModes) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            } else {
                log("Preview shading OFF unsupported")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val distortionModes =
                c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            if (distortionModes != null &&
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF in distortionModes
            ) {
                builder.set(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                )
            } else {
                log("Preview distortion OFF unsupported")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rotateCropModes =
                c.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            if (rotateCropModes != null &&
                CaptureRequest.SCALER_ROTATE_AND_CROP_NONE in rotateCropModes
            ) {
                builder.set(
                    CaptureRequest.SCALER_ROTATE_AND_CROP,
                    CaptureRequest.SCALER_ROTATE_AND_CROP_NONE
                )
            } else {
                log("Preview rotate/crop NONE unsupported")
            }
        }
    }

    /** Rotates and scales the TextureView so preview pixels match the display. */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val size = framingPreviewSize ?: return
        val c = chars ?: return
        if (viewWidth == 0 || viewHeight == 0) return

        val physicalOrientation = roundDeviceOrientationToRightAngle()
        val deviceOrientation = deviceOrientationDegreesForPreviewFormula(physicalOrientation)
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing =
            c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
        val relativeRotation =
            normalizeDegrees(sensorOrientation - deviceOrientation * sign)
        val matrixRotationBefore = -relativeRotation.toFloat()
        val bucket = orientationBucket(physicalOrientation)
        val bucketCorrection = previewMatrixBucketCorrection(physicalOrientation)
        val frontCorrection =
            if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 180f else 0f
        val matrixRotation = matrixRotationBefore + bucketCorrection + frontCorrection
        val rightAngleRotation = normalizeDegrees(matrixRotation.roundToInt())
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val rotatedBufferWidth: Float
        val rotatedBufferHeight: Float
        val bufferRect =
            if (rightAngleRotation == 90 || rightAngleRotation == 270) {
                rotatedBufferWidth = size.height.toFloat()
                rotatedBufferHeight = size.width.toFloat()
                RectF(0f, 0f, rotatedBufferWidth, rotatedBufferHeight)
            } else {
                rotatedBufferWidth = size.width.toFloat()
                rotatedBufferHeight = size.height.toFloat()
                RectF(0f, 0f, rotatedBufferWidth, rotatedBufferHeight)
            }
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)

        val scale = max(
            viewWidth.toFloat() / rotatedBufferWidth,
            viewHeight.toFloat() / rotatedBufferHeight
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(matrixRotation, centerX, centerY)
        framingPreview.setTransform(matrix)

        val signature =
            "${size.width}x${size.height}|${viewWidth}x$viewHeight|" +
                    "$sensorOrientation|$lensFacing|$physicalOrientation|$deviceOrientation|" +
                    "$relativeRotation|$matrixRotationBefore|$frontCorrection|$matrixRotation|" +
                    "${rotatedBufferWidth.roundToInt()}x${rotatedBufferHeight.roundToInt()}|$scale"
        if (signature != framingPreviewTransformSignature) {
            framingPreviewTransformSignature = signature
            log(
                "Preview transform size=${size.width}x${size.height} view=${viewWidth}x$viewHeight " +
                        "bucket=$bucket " +
                        "sensor=$sensorOrientation facing=${lensFacingLabel(lensFacing)} " +
                        "physical=$physicalOrientation device=$deviceOrientation " +
                        "relative=$relativeRotation matrixBefore=$matrixRotationBefore " +
                        "frontCorrection=$frontCorrection " +
                        "matrixAfter=$matrixRotation " +
                        "rotated=${rotatedBufferWidth.roundToInt()}x${rotatedBufferHeight.roundToInt()} " +
                        "scale=${"%.4f".format(Locale.US, scale)} mode=center-crop"
            )
        }
    }

    /** Returns the empirical correction used by preview matrix orientation buckets. */
    private fun previewMatrixBucketCorrection(physicalOrientation: Int): Float =
        when (physicalOrientation) {
            0 -> 90f
            90 -> 0f
            180 -> 90f
            270 -> 180f
            else -> 0f
        }

    /** Groups physical sensor orientation into a readable preview bucket label. */
    private fun orientationBucket(physicalOrientation: Int): String =
        when (physicalOrientation) {
            0 -> "portrait"
            90 -> "landscape-left"
            180 -> "portrait-reverse"
            270 -> "landscape-right"
            else -> "unknown-$physicalOrientation"
        }

    /** Converts the current device rotation into the value used by preview math. */
    private fun deviceOrientationDegreesForPreviewFormula(physicalOrientation: Int): Int =
        when (physicalOrientation) {
            90 -> 270
            270 -> 90
            else -> physicalOrientation
        }

    /** Normalizes degree values into the 0..359 range. */
    private fun normalizeDegrees(degrees: Int): Int =
        ((degrees % 360) + 360) % 360

    /** Converts Camera2 lens-facing constants into log-friendly labels. */
    private fun lensFacingLabel(value: Int): String =
        when {
            value == CameraCharacteristics.LENS_FACING_FRONT -> "front"
            value == CameraCharacteristics.LENS_FACING_BACK -> "back"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    value == CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }

    /** Computes the DNG orientation angle from sensor and device rotation. */
    private fun currentDngOrientationDegrees(c: CameraCharacteristics?): Int {
        val characteristics = c ?: return 0
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
        val physicalOrientation = roundDeviceOrientationToRightAngle()
        val displayOrientation = deviceOrientationDegreesForPreviewFormula(physicalOrientation)
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
        return normalizeDegrees(
            sensorOrientation +
                    displayOrientation * sign +
                    frontFacingRotationCorrection(characteristics)
        )
    }

    /** Applies the device-specific front-camera correction needed by this app's output path. */
    private fun frontFacingRotationCorrection(c: CameraCharacteristics): Int =
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            180
        } else {
            0
        }

    /** Maps a degree rotation to the EXIF orientation flag stored in DNG files. */
    private fun exifOrientationForDegrees(degrees: Int): Int =
        when (normalizeDegrees(degrees)) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

    /** Formats a DNG orientation flag into a readable log label. */
    private fun dngOrientationLabel(orientation: Int): String =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> "ROTATE_90"
            ExifInterface.ORIENTATION_ROTATE_180 -> "ROTATE_180"
            ExifInterface.ORIENTATION_ROTATE_270 -> "ROTATE_270"
            ExifInterface.ORIENTATION_NORMAL -> "NORMAL"
            else -> orientation.toString()
        }

    /** Logs camera IDs, RAW sizes, and processing capabilities once per app session. */
    private fun logCameraInventoryOnce() {
        if (cameraInventoryLogged) return
        cameraInventoryLogged = true

        runCatching {
            val ids = cameraManager.cameraIdList.toMutableList()
            log("--- Camera2 IDs ---")
            ids.forEach { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                val facingValue = c.get(CameraCharacteristics.LENS_FACING)
                val facing = when {
                    facingValue == CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    facingValue == CameraCharacteristics.LENS_FACING_BACK -> "back"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            facingValue == CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toMutableList()
                    ?.joinToString(",")
                    ?: "null"
                val physicalIds =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        c.physicalCameraIds.toMutableSet().joinToString(",").ifEmpty { "-" }
                    } else {
                        "-"
                    }
                val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.toMutableList()
                    ?.sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
                    ?.take(18)
                    ?.joinToString(",") { "${it.width}x${it.height}" }
                    ?: "null"
                val edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                    ?.toMutableList()
                    ?.joinToString(",")
                    ?: "null"
                val noiseModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?.toMutableList()
                    ?.joinToString(",")
                    ?: "null"
                val stabilizationModes =
                    c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.toMutableList()
                        ?.joinToString(",")
                        ?: "null"

                log("ID $id facing=$facing caps=$caps physical=$physicalIds")
                log("sizes: $sizes")
                log("edge=$edgeModes nr=$noiseModes stab=$stabilizationModes")
            }
        }.onFailure {
            log("Camera inventory failed: ${it.message}")
        }
    }

    /** Toggles whether each capture sequence gets its own MediaStore folder name. */
    private fun toggleFolderMode() {
        folderModeEnabled = !folderModeEnabled
        updateFolderButton()
        saveSettings()

        log("Folder mode: ${if (folderModeEnabled) "ON" else "OFF"}")
    }

    /** Updates the folder-mode button outline based on the current toggle. */
    private fun updateFolderButton() {
        applyButtonStroke(
            btnFolderMode,
            if (folderModeEnabled) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
        )
    }

    /** Shows the selectable list of RAW-capable camera IDs. */
    private fun showCameraIdMenu() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        val options = availableCameraOptions()
        if (options.isEmpty()) {
            log("Camera list unavailable")
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF222222.toInt())
        }
        val popupWidth = min(
            (resources.displayMetrics.widthPixels * 0.82f).roundToInt(),
            dp(260)
        )
        lateinit var popup: PopupWindow

        options.forEach { option ->
            val row = TextView(this).apply {
                text = option.label
                typeface = etCameraId.typeface
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(30)
                )
                setOnClickListener {
                    etCameraId.setText(option.id)
                    updateCameraIdButton()
                    saveSettings()
                    popup.dismiss()
                    log("Camera ${option.label}")
                }
            }
            box.addView(row)
        }

        popup = PopupWindow(
            box,
            popupWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(0xFF222222.toInt()))
            isOutsideTouchable = true
        }
        popup.showAsDropDown(etCameraId)
    }

    /** Builds the visible camera menu from normal and discovered hidden RAW cameras. */
    private fun availableCameraOptions(): List<CameraOption> =
        (cameraManager.cameraIdList.toList() + hiddenRawCameraIds())
            .distinct()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { id ->
                runCatching {
                    val c = cameraManager.getCameraCharacteristics(id)
                    if (!cameraSupportsRaw(c)) return@runCatching null
                    CameraOption(id, cameraOptionLabel(id, c))
                }.getOrNull()
            }
            .filterNotNull()

    /** Scans possible vendor camera IDs to find RAW modules missing from cameraIdList. */
    private fun hiddenRawCameraIds(): List<String> =
        (50..120)
            .filterNot { getBit(6, it) }
            .map { it.toString() }
            .filter { id ->
                id !in cameraManager.cameraIdList &&
                        runCatching {
                            cameraSupportsRaw(cameraManager.getCameraCharacteristics(id))
                        }.getOrDefault(false)
            }

    /** Checks whether a camera exposes RAW capability and RAW output sizes. */
    private fun cameraSupportsRaw(c: CameraCharacteristics): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasRawCapability =
            caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        val rawSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
        return hasRawCapability && !rawSizes.isNullOrEmpty()
    }

    /** Builds the camera menu label with ID, lens side, focal length, and aperture. */
    private fun cameraOptionLabel(id: String, c: CameraCharacteristics): String {
        val focalMm = equivalentFocalLengthMm(c)
        val aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.minOrNull()
        val facingValue = c.get(CameraCharacteristics.LENS_FACING)
        val facing = when {
            facingValue == CameraCharacteristics.LENS_FACING_FRONT -> "selfie"
            facingValue == CameraCharacteristics.LENS_FACING_BACK -> "rear"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    facingValue == CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "camera"
        }

        val focalText = focalMm?.let { "${it}mm" } ?: "?mm"
        val apertureText = aperture?.let { "f/${"%.1f".format(Locale.US, it)}" } ?: "f/?"
        return "($id) $focalText $apertureText ($facing)"
    }

    /** Estimates 35mm-equivalent focal length from focal length and sensor width. */
    private fun equivalentFocalLengthMm(c: CameraCharacteristics): Int? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val sensorDiagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
        if (sensorDiagonal <= 0f) return null
        return (focal * 43.2666f / sensorDiagonal).roundToInt()
    }

    /** Reads the currently selected camera ID, falling back to camera 0. */
    private fun selectedCameraId(): String {
        val text = etCameraId.text.toString().trim()
        return if (text.isEmpty() || text.equals("ID", ignoreCase = true)) "0" else text
    }

    /** Updates the camera selector text from the current camera characteristics. */
    private fun updateCameraIdButton() {
        if (etCameraId.text.toString().trim().isEmpty()) {
            etCameraId.text = "ID"
        }
        etCameraId.setTextColor(0xFFFFFFFF.toInt())
    }

    /** Shows ISO choices derived from the selected camera's sensitivity range. */
    private fun showIsoMenu() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        val camId = selectedCameraId()
        val values = availableIsoValues(camId)
        if (values.isEmpty()) {
            log("ISO list unavailable")
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF222222.toInt())
        }
        val popupWidth = max(etIso.width, dp(64))
        lateinit var popup: PopupWindow

        values.forEach { value ->
            val row = TextView(this).apply {
                text = value.toString()
                typeface = etIso.typeface
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(28)
                )
                setOnClickListener {
                    etIso.setText(value.toString())
                    updateIsoButton()
                    saveSettings()
                    popup.dismiss()
                    log("ISO $value")
                }
            }
            box.addView(row)
        }

        val popupHeight = min(
            dp(280),
            (resources.displayMetrics.heightPixels * 0.45f).roundToInt()
        )
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF222222.toInt())
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            isFillViewport = false
            addView(
                box,
                FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        popup = PopupWindow(
            scroll,
            popupWidth,
            popupHeight,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(0xFF222222.toInt()))
            isOutsideTouchable = true
        }
        popup.showAsDropDown(etIso)
    }

    /** Builds a practical ISO list constrained by the camera sensitivity range. */
    private fun availableIsoValues(camId: String): List<Int> {
        val common = listOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
            8000, 10000, 12800, 16000, 20000, 25600, 32000
        )
        val range = runCatching {
            cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        }.getOrNull() ?: return common

        return (common + range.lower + range.upper)
            .filter { it in range.lower..range.upper }
            .distinct()
            .sorted()
    }

    /** Refreshes the ISO button label from its current value. */
    private fun updateIsoButton() {
        if (etIso.text.toString().trim().toIntOrNull() == null) {
            etIso.text = "ISO"
        }
        etIso.setTextColor(0xFF000000.toInt())
    }

    /** Shows available white-balance choices for the selected camera. */
    private fun showWhiteBalanceMenu() {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        val camId = selectedCameraId()
        val options = availableWhiteBalanceOptions(camId)
        if (options.isEmpty()) {
            log("Manual WB unavailable")
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF222222.toInt())
        }
        val popupWidth = max(btnWB.width, dp(64))
        lateinit var popup: PopupWindow

        options.forEach { option ->
            val row = TextView(this).apply {
                text = option.label
                typeface = btnWB.typeface
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(28)
                )
                setOnClickListener {
                    selectedWbMode = option.mode
                    updateWbButton()
                    saveSettings()
                    popup.dismiss()
                    log("WB ${option.label} (${option.name})")
                }
            }
            box.addView(row)
        }

        popup = PopupWindow(
            box,
            popupWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(0xFF222222.toInt()))
            isOutsideTouchable = true
        }
        popup.showAsDropDown(btnWB)
    }

    /** Builds white-balance options supported by the camera, including manual gains. */
    private fun availableWhiteBalanceOptions(camId: String): List<WbOption> {
        val c = runCatching { cameraManager.getCameraCharacteristics(camId) }.getOrNull()
            ?: return emptyList()
        val available = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: return emptyList()
        val modes = available.toSet()
        val manualOptions = listOf(
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE,
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
        ).filter { it in modes }
            .mapNotNull { whiteBalanceOptionForMode(it) }

        return listOf(WbOption(CaptureRequest.CONTROL_AWB_MODE_AUTO, "Auto", "Auto", null)) +
                manualOptions
    }

    /** Finds a display option for a saved white-balance mode. */
    private fun whiteBalanceOptionForMode(mode: Int): WbOption? =
        when (mode) {
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT ->
                WbOption(mode, "2300", "Incandescent", RggbChannelVector(1.35f, 1f, 1f, 3.20f))

            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT ->
                WbOption(mode, "3000", "Warm Fluorescent", RggbChannelVector(1.55f, 1f, 1f, 2.55f))

            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT ->
                WbOption(mode, "4000", "Fluorescent", RggbChannelVector(1.80f, 1f, 1f, 2.05f))

            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT ->
                WbOption(mode, "5300", "Daylight", RggbChannelVector(2.10f, 1f, 1f, 1.60f))

            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT ->
                WbOption(mode, "6500", "Cloudy", RggbChannelVector(2.30f, 1f, 1f, 1.38f))

            CaptureRequest.CONTROL_AWB_MODE_SHADE ->
                WbOption(mode, "7500", "Shade", RggbChannelVector(2.50f, 1f, 1f, 1.22f))

            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT ->
                WbOption(mode, "10000", "Twilight", RggbChannelVector(2.80f, 1f, 1f, 1.05f))

            else -> null
        }

    /** Updates the white-balance button label and accent color. */
    private fun updateWbButton() {
        val option = whiteBalanceOptionForMode(selectedWbMode)
        btnWB.text = option?.label ?: "Auto WB"
        applyButtonStroke(btnWB, whiteBalanceStrokeColor(option?.label))
    }

    /** Chooses a button outline color for the selected white-balance label. */
    private fun whiteBalanceStrokeColor(label: String?): Int =
        when (label) {
            "2300" -> 0xFF1E63FF.toInt()
            "3000" -> 0xFF64B5FF.toInt()
            "4000" -> 0xFFC8ECFF.toInt()
            "5300" -> 0xFFFFFFFF.toInt()
            "6500" -> 0xFFFFF0C8.toInt()
            "7500" -> 0xFFFFD58A.toInt()
            "10000" -> 0xFFFFB04A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    /** Applies a consistent rounded outline to a stateful button. */
    private fun applyButtonStroke(button: Button, strokeColor: Int) {
        button.backgroundTintList = null
        button.background = GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(dp(1), strokeColor)
        }
        button.setTextColor(0xFFFFFFFF.toInt())
    }

    /** Falls back to auto white balance if the selected mode is unsupported. */
    private fun validatedWbMode(c: CameraCharacteristics?): Int {
        val available = c?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        return if (available == null || selectedWbMode in available) {
            selectedWbMode
        } else {
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
    }

    /** Returns true when the current WB choice should use manual color gains. */
    private fun manualWhiteBalanceSelected(c: CameraCharacteristics? = chars): Boolean =
        whiteBalanceOptionForMode(validatedWbMode(c)) != null

    /** Applies auto or manual white balance settings to a capture request. */
    private fun CaptureRequest.Builder.applyWhiteBalance(c: CameraCharacteristics? = chars) {
        val mode = validatedWbMode(c)
        val option = whiteBalanceOptionForMode(mode)
        if (option == null) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            return
        }

        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        set(CaptureRequest.COLOR_CORRECTION_GAINS, option.gains)
    }

    /** Logs the effective white-balance mode and gains returned by Camera2. */
    private fun logWhiteBalanceResult(result: TotalCaptureResult) {
        val mode = result.get(CaptureResult.CONTROL_AWB_MODE)
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val correctionMode = result.get(CaptureResult.COLOR_CORRECTION_MODE)
        val modeText =
            if (mode == CaptureRequest.CONTROL_AWB_MODE_OFF) "OFF"
            else whiteBalanceOptionForMode(mode ?: -1)?.label ?: "AUTO"
        val gainsText =
            if (gains == null) "null"
            else "%.2f %.2f %.2f %.2f".format(
                Locale.US,
                gains.red,
                gains.greenEven,
                gains.greenOdd,
                gains.blue
            )
        log("WB result: $modeText  cc=$correctionMode  gains=$gainsText")
    }

    /** Converts density-independent pixels to physical pixels. */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    /** Opens the live-focus overlay for selecting a manual focus distance. */
    private fun startLiveFocus(saveField: EditText, label: String) {
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        stopFramingPreviewForCapture()
        liveFocusSaveField = saveField
        liveFocusSaveLabel = label
        liveFocusRunning = true
        setControlsEnabled(false)
        showLiveFocusOverlay()

        if (livePreview.isAvailable) {
            beginLiveFocus(livePreview.surfaceTexture)
        } else {
            livePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    beginLiveFocus(surface)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    stopLiveFocus(saveFocus = false)
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    /** Displays and sizes the live-focus preview overlay. */
    private fun showLiveFocusOverlay() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val previewH = (screenW * 3f / 4f).roundToInt()

        previewOverlay.layoutParams = previewOverlay.layoutParams.apply {
            width = screenW
            height = previewH
            if (this is FrameLayout.LayoutParams) {
                gravity = android.view.Gravity.TOP
                topMargin = dp(34)
            }
        }

        ivPreview.setImageDrawable(null)
        framingPreviewContainer.visibility = View.GONE
        ivPreview.visibility = View.GONE
        livePreview.visibility = View.VISIBLE
        sbLiveFocus.visibility = View.VISIBLE
        previewOverlay.visibility = View.VISIBLE
    }

    /** Starts a camera preview session dedicated to manual live-focus adjustment. */
    private fun beginLiveFocus(surfaceTexture: SurfaceTexture?) {
        val texture = surfaceTexture ?: run {
            stopLiveFocus(saveFocus = false)
            return
        }

        Thread({
            try {
                startBgThreads()

                val camId = selectedCameraId()
                if (!openSelectedCamera(camId)) {
                    stopLiveFocus(saveFocus = false)
                    return@Thread
                }

                val map = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val previewSize = map
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.let { chooseLivePreviewSize(it) }
                    ?: Size(1440, 1080)

                liveFocusMaxDistance =
                    chars!!.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                if (liveFocusMaxDistance <= 0f) {
                    log("Manual focus unavailable")
                    stopLiveFocus(saveFocus = false)
                    return@Thread
                }

                val initialFocus = (
                        liveFocusSaveField?.text?.toString()
                            ?.replace(',', '.')
                            ?.toFloatOrNull()
                            ?: etFocus.text.toString()
                                .replace(',', '.')
                                .toFloatOrNull()
                            ?: 0f
                        ).coerceIn(0f, liveFocusMaxDistance)

                liveFocusTargetDistance = initialFocus
                liveFocusMeasuredDistance = initialFocus

                runOnUiThread {
                    sbLiveFocus.progress = focusToProgress(initialFocus)
                    sbLiveFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                        ) {
                            if (!fromUser || !liveFocusRunning) return
                            liveFocusTargetDistance = progressToFocus(progress)
                            updateLiveFocusRequest()
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {
                            stopLiveFocus(saveFocus = true)
                        }
                    })
                }

                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                liveFocusSurface = Surface(texture)

                if (!liveFocusRunning) {
                    runCatching { liveFocusSurface?.release() }
                    liveFocusSurface = null
                    return@Thread
                }

                if (!createSession(listOf(liveFocusSurface!!))) {
                    stopLiveFocus(saveFocus = false)
                    return@Thread
                }

                if (!liveFocusRunning) {
                    runCatching { liveFocusSurface?.release() }
                    liveFocusSurface = null
                    return@Thread
                }

                val afFocus = runLiveFocusAfAssist(initialFocus)
                liveFocusTargetDistance = afFocus
                liveFocusMeasuredDistance = afFocus
                runOnUiThread {
                    sbLiveFocus.progress = focusToProgress(afFocus)
                }

                updateLiveFocusRequest()
                log("Live Focus")
            } catch (e: Exception) {
                Log.e(TAG, "liveFocus", e)
                log("Live Focus error: ${e.message}")
                stopLiveFocus(saveFocus = false)
            }
        }, "live-focus-thread").start()
    }

    /** Chooses a responsive preview size for the live-focus overlay. */
    private fun chooseLivePreviewSize(sizes: Array<Size>): Size {
        val targetArea = 1440 * 1080
        return sizes
            .filter { isFourThree(it) }
            .minByOrNull { abs((it.width * it.height) - targetArea) }
            ?: sizes.minByOrNull { abs((it.width * it.height) - targetArea) }!!
    }

    /** Runs a short AF assist pass to seed live focus with a measured distance. */
    private fun runLiveFocusAfAssist(fallbackFocus: Float): Float {
        val surface = liveFocusSurface ?: return fallbackFocus
        val device = cameraDevice ?: return fallbackFocus
        val session = captureSession ?: return fallbackFocus
        val c = chars ?: return fallbackFocus

        val focusRef = AtomicReference<Float?>(null)
        val stableLatch = CountDownLatch(1)
        val lockMode = java.util.concurrent.atomic.AtomicBoolean(false)
        val focusTolerance = max(0.001f, liveFocusMaxDistance * 0.0005f)
        var lastFocus: Float? = null
        var stableFrames = 0

        val afCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                res: TotalCaptureResult
            ) {
                res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                    val focus = it.coerceIn(0f, liveFocusMaxDistance)
                    focusRef.set(focus)

                    if (lockMode.get()) {
                        val previous = lastFocus
                        stableFrames =
                            if (previous != null && abs(focus - previous) <= focusTolerance) {
                                stableFrames + 1
                            } else {
                                0
                            }
                        lastFocus = focus
                    }
                }

                when (res.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        if (lockMode.get() && stableFrames >= 8) stableLatch.countDown()
                    }
                }
            }
        }

        /** Applies the shared live-focus preview settings to a capture request. */
        fun CaptureRequest.Builder.applyLivePreviewBase() {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            applyWhiteBalance(c)
            applyOisMode(c)
            zoomCropRect(c, LIVE_FOCUS_ZOOM)?.let {
                set(CaptureRequest.SCALER_CROP_REGION, it)
            }
            bestThirtyFpsRange(c)?.let {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
        }

        runCatching {
            val continuousReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                applyLivePreviewBase()
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.setRepeatingRequest(continuousReq, afCallback, camHandler)
            Thread.sleep(2200)

            lockMode.set(true)
            val lockReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                applyLivePreviewBase()
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()
            session.capture(lockReq, afCallback, camHandler)

            val lockedReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                applyLivePreviewBase()
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }.build()
            session.setRepeatingRequest(lockedReq, afCallback, camHandler)
            stableLatch.await(6500, TimeUnit.MILLISECONDS)
            Thread.sleep(250)
        }.onFailure {
            Log.e(TAG, "liveFocusAf", it)
        }

        val afFocus = focusRef.get()
        return if (afFocus != null && afFocus >= 0f) {
            log("AF focus ${"%.4f".format(Locale.US, afFocus)}")
            afFocus
        } else {
            fallbackFocus
        }
    }

    /** Sends the latest focus slider value to the running live-focus preview. */
    private fun updateLiveFocusRequest() {
        if (!liveFocusRunning) return

        val surface = liveFocusSurface ?: return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val c = chars ?: return

        runCatching {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                applyWhiteBalance(c)
                applyOisMode(c)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, liveFocusTargetDistance)
                zoomCropRect(c, LIVE_FOCUS_ZOOM)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
                bestThirtyFpsRange(c)?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }

            session.setRepeatingRequest(
                req.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        res: TotalCaptureResult
                    ) {
                        liveFocusMeasuredDistance =
                            res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                ?: liveFocusTargetDistance
                    }
                },
                camHandler
            )
        }.onFailure {
            Log.e(TAG, "updateLiveFocusRequest", it)
        }
    }

    /** Stops the live-focus overlay and optionally saves the measured distance. */
    private fun stopLiveFocus(saveFocus: Boolean) {
        if (!liveFocusRunning) return
        liveFocusRunning = false

        val finalFocus = liveFocusTargetDistance

        Thread({
            runCatching { captureSession?.stopRepeating() }
            closeEverything()
            runCatching { liveFocusSurface?.release() }
            liveFocusSurface = null

            runOnUiThread {
                sbLiveFocus.setOnSeekBarChangeListener(null)
                sbLiveFocus.visibility = View.GONE
                livePreview.visibility = View.GONE
                ivPreview.visibility = View.VISIBLE
                previewOverlay.visibility = View.GONE

                if (saveFocus) {
                    val value = "%.4f".format(Locale.US, finalFocus)
                    val field = liveFocusSaveField ?: etFocus
                    field.setText(value)
                    when (field) {
                        etFocusStart -> focusStartReady = true
                        etFocusEnd -> focusEndReady = true
                    }
                    log("$liveFocusSaveLabel $value")
                }

                liveFocusSaveField = null
                liveFocusSaveLabel = "Focus"
                setControlsEnabled(true)
            }
        }, "live-focus-stop").start()
    }

    /** Converts slider progress into a manual focus distance. */
    private fun progressToFocus(progress: Int): Float =
        (progress / sbLiveFocus.max.toFloat() * liveFocusMaxDistance)
            .coerceIn(0f, liveFocusMaxDistance)

    /** Converts a manual focus distance back into slider progress. */
    private fun focusToProgress(focus: Float): Int =
        if (liveFocusMaxDistance <= 0f) {
            0
        } else {
            ((focus.coerceIn(0f, liveFocusMaxDistance) / liveFocusMaxDistance) *
                    sbLiveFocus.max).roundToInt()
        }

    /** Calculates the Camera2 crop rectangle for a requested digital zoom. */
    private fun zoomCropRect(c: CameraCharacteristics, zoom: Float): Rect? {
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val maxZoom = max(1f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: zoom)
        val safeZoom = zoom.coerceIn(1f, maxZoom)
        val cropW = (active.width() / safeZoom).roundToInt()
        val cropH = (active.height() / safeZoom).roundToInt()
        val left = active.left + (active.width() - cropW) / 2
        val top = active.top + (active.height() - cropH) / 2
        return Rect(left, top, left + cropW, top + cropH)
    }

    /** Selects the best available FPS range that supports 30 fps. */
    private fun bestThirtyFpsRange(c: CameraCharacteristics): Range<Int>? {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }
                .minByOrNull { it.upper - it.lower }
    }

    /** Applies the requested OIS mode when the camera advertises support for it. */
    private fun CaptureRequest.Builder.applyOisMode(
        c: CameraCharacteristics? = chars,
        logResult: Boolean = false
    ) {
        val modes = c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val value =
            if (oisEnabled && modes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true)
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            else
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF

        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, value)
        if (logResult) {
            val support =
                if (modes == null) "not reported"
                else modes.joinToString(prefix = "[", postfix = "]")
            log("OIS request: ${oisModeLabel(value)} support=$support")
        }
    }

    /** Formats an OIS mode constant for readable logs. */
    private fun oisModeLabel(mode: Int?): String =
        when (mode) {
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
            null -> "null"
            else -> mode.toString()
        }

    /** Disables edge, noise, hot-pixel, shading, tonemap, and distortion processing when possible. */
    private fun disableProcessingIfAvailable(
        builder: CaptureRequest.Builder,
        c: CameraCharacteristics? = chars
    ) {
        builder.applyOisMode(c)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)

        val noiseModes = c?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (noiseModes == null || CaptureRequest.NOISE_REDUCTION_MODE_OFF in noiseModes) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        }

        val edgeModes = c?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edgeModes == null || CaptureRequest.EDGE_MODE_OFF in edgeModes) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }

        val hotPixelModes = c?.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
        if (hotPixelModes == null || CaptureRequest.HOT_PIXEL_MODE_OFF in hotPixelModes) {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val shadingModes = c?.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            if (shadingModes == null || CaptureRequest.SHADING_MODE_OFF in shadingModes) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            }
        }

        val aberrationModes = c?.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        if (aberrationModes == null ||
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF in aberrationModes
        ) {
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        }

        val tonemapModes = c?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        if (tonemapModes == null || CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            builder.set(
                CaptureRequest.TONEMAP_CURVE,
                TonemapCurve(
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f)
                )
            )
        }

        builder.set(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF
        )
        builder.set(CaptureRequest.BLACK_LEVEL_LOCK, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val distortionModes =
                c?.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            if (distortionModes == null ||
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF in distortionModes
            ) {
                builder.set(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                )
            }
        }
    }

    /** Enables or disables user controls while long-running camera work is active. */
    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            btnGetHigh,
            btnGetShadow,
            btnCompute,
            btnStackModes,
            btnDev,
            btnPreview,
            btnStart,
            btnAF,
            btnOis,
            etCameraId,
            etIso,
            btnWB,
            btnFolderMode,
            btnResetSettings,
            btnHelp,
            btnLiveFocus,
            btnFocusBracket,
            btnFocusStart,
            btnFocusEnd,
            btnAE,
            btnAeBiasMinus,
            btnAeBiasPlus,
            btnClearExposures
        ).forEach { it.isEnabled = enabled }
    }

    /** Shows a captured preview bitmap in the preview overlay. */
    private fun showPreviewBitmap(bitmap: Bitmap) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()

        val targetHeight = (screenW / ratio)
            .roundToInt()
            .coerceAtMost((screenH * 0.65f).roundToInt())

        runOnUiThread {
            previewOverlay.layoutParams = previewOverlay.layoutParams.apply {
                width = screenW
                height = targetHeight
                if (this is FrameLayout.LayoutParams) {
                    gravity = android.view.Gravity.TOP
                    topMargin = dp(34)
                }
            }

            livePreview.visibility = View.GONE
            framingPreviewContainer.visibility = View.GONE
            sbLiveFocus.visibility = View.GONE
            ivPreview.visibility = View.VISIBLE
            ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            ivPreview.adjustViewBounds = true
            ivPreview.setImageBitmap(bitmap)

            previewOverlay.visibility = View.VISIBLE
        }

        Thread.sleep(2000)

        runOnUiThread {
            ivPreview.setImageDrawable(null)
            previewOverlay.visibility = View.GONE

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /** Rotates a preview bitmap using the current camera and device orientation. */
    private fun rotateBitmapForPreview(source: Bitmap): Bitmap {
        if (source.isRecycled) return source

        val rotation = getPreviewRotationDegrees()

        if (rotation == 0f) {
            return source
        }

        val matrix = Matrix().apply {
            postRotate(rotation)
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }

    /** Calculates the bitmap rotation needed for the preview image. */
    private fun getPreviewRotationDegrees(): Float {
        val c = chars ?: return 0f

        val sensorOrientation =
            c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val facing =
            c.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK

        val deviceOrientation = roundDeviceOrientationToRightAngle()

        val sign =
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1

        return normalizeDegrees(
            sensorOrientation -
                    sign * deviceOrientation +
                    frontFacingRotationCorrection(c)
        ).toFloat()
    }

    /** Rounds the current device orientation to the nearest right angle. */
    private fun roundDeviceOrientationToRightAngle(): Int {
        val orientation = deviceOrientationDegrees

        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }

        return (((orientation + 45) / 90) * 90) % 360
    }

    // ══════════════════════════════════════════════
    //  AUTOFOCUS
    // ══════════════════════════════════════════════

    /** Starts autofocus and keeps the resulting focus lock for capture. */
    private fun startAutoFocusLock() {
        if (running || liveFocusRunning) {
            log("Capture in progress"); return
        }
        stopFramingPreviewForCapture()
        val camId = selectedCameraId()
        btnAF.isEnabled = false; btnAF.text = "focusing..."
        btnStackModes.isEnabled = false
        btnDev.isEnabled = false
        btnPreview.isEnabled = false
        etCameraId.isEnabled = false
        etIso.isEnabled = false
        btnWB.isEnabled = false
        btnFolderMode.isEnabled = false
        btnResetSettings.isEnabled = false
        btnHelp.isEnabled = false
        btnLiveFocus.isEnabled = false
        btnFocusBracket.isEnabled = false
        btnFocusStart.isEnabled = false
        btnFocusEnd.isEnabled = false
        btnAE.isEnabled = false
        btnAeBiasMinus.isEnabled = false
        btnAeBiasPlus.isEnabled = false
        log("AF lock $camId")
        startBgThreads()
        Thread({
            var locked = false
            try {
                locked = runAutoFocusLock(camId)
            } catch (e: Exception) {
                Log.e(TAG, "AF lock", e); log("AF error: ${e.message}")
            } finally {
                if (!locked) closeEverything()
                runOnUiThread {
                    btnAF.isEnabled = true
                    btnAF.text = if (locked) "AF LOCK" else "AF"
                    btnStackModes.isEnabled = true
                    btnDev.isEnabled = true
                    btnPreview.isEnabled = true
                    etCameraId.isEnabled = true
                    etIso.isEnabled = true
                    btnWB.isEnabled = true
                    btnFolderMode.isEnabled = true
                    btnResetSettings.isEnabled = true
                    btnHelp.isEnabled = true
                    btnLiveFocus.isEnabled = true
                    btnFocusBracket.isEnabled = true
                    btnFocusStart.isEnabled = true
                    btnFocusEnd.isEnabled = true
                    btnAE.isEnabled = true
                    btnAeBiasMinus.isEnabled = true
                    btnAeBiasPlus.isEnabled = true
                }
            }
        }, "af-lock-thread").start()
    }

    /** Runs the Camera2 autofocus sequence used by capture-time focus lock. */
    private fun runAutoFocusLock(camId: String): Boolean {
        runOnUiThread { clearFocusBracketLock() }
        closeEverything()
        startBgThreads()
        if (!openSelectedCamera(camId)) return false

        val dummyST = SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480) }
        val dummySurf = Surface(dummyST)
        if (!createSession(listOf(dummySurf))) {
            dummySurf.release()
            dummyST.release()
            return false
        }

        val lockLatch = CountDownLatch(1)
        val focusDistRef = AtomicReference<Float?>(null)
        var lastState = -1
        var lastFocus: Float? = null
        var stableFrames = 0

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                res: TotalCaptureResult
            ) {
                val state = res.get(CaptureResult.CONTROL_AF_STATE) ?: return
                val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                if (state != lastState) {
                    lastState = state
                    log("AF state $state")
                }
                if (dist != null) {
                    focusDistRef.set(dist)
                    val previous = lastFocus
                    stableFrames =
                        if (previous != null && abs(dist - previous) <= 0.001f) stableFrames + 1
                        else 0
                    lastFocus = dist
                }
                if ((state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) &&
                    stableFrames >= 8
                ) lockLatch.countDown()
            }
        }

        captureSession!!.capture(
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(dummySurf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyOisMode()
            }.build(),
            callback,
            camHandler
        )

        captureSession!!.setRepeatingRequest(
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(dummySurf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyOisMode()
            }.build(),
            callback,
            camHandler
        )

        val locked = lockLatch.await(8, TimeUnit.SECONDS)
        val finalDist = focusDistRef.get()
        if (finalDist == null) {
            dummySurf.release()
            dummyST.release()
            log("AF: no focusing distance")
            return false
        }

        afLockedForCapture = locked
        afLockedCamId = camId
        afLockSurface = dummySurf
        afLockTexture = dummyST

        val rounded = "%.4f".format(Locale.US, finalDist).toFloat()
        log("AF ${if (locked) "LOCKED" else "estimated"} - $rounded")
        runOnUiThread { etFocus.setText("%.4f".format(Locale.US, rounded)) }
        return locked
    }

    /** Starts a one-shot autofocus pass without preserving the lock for capture. */
    private fun startAutoFocus() {
        if (running || liveFocusRunning) {
            log("Capture in progress"); return
        }
        stopFramingPreviewForCapture()
        val camId = selectedCameraId()
        btnAF.isEnabled = false; btnAF.text = "focusing..."
        btnStackModes.isEnabled = false
        btnPreview.isEnabled = false
        etCameraId.isEnabled = false
        etIso.isEnabled = false
        btnWB.isEnabled = false
        btnFolderMode.isEnabled = false
        btnResetSettings.isEnabled = false
        btnHelp.isEnabled = false
        btnLiveFocus.isEnabled = false
        btnFocusBracket.isEnabled = false
        btnFocusStart.isEnabled = false
        btnFocusEnd.isEnabled = false
        btnAE.isEnabled = false
        btnAeBiasMinus.isEnabled = false
        btnAeBiasPlus.isEnabled = false
        log("AF reading $camId")
        startBgThreads()
        Thread({
            try {
                runAutoFocus(camId)
            } catch (e: Exception) {
                Log.e(TAG, "AF", e); log("AF error: ${e.message}")
            } finally {
                closeEverything()
                runOnUiThread {
                    btnAF.isEnabled = true
                    btnAF.text = "AF"
                    btnStackModes.isEnabled = true
                    btnPreview.isEnabled = true
                    etCameraId.isEnabled = true
                    etIso.isEnabled = true
                    btnWB.isEnabled = true
                    btnFolderMode.isEnabled = true
                    btnResetSettings.isEnabled = true
                    btnHelp.isEnabled = true
                    btnLiveFocus.isEnabled = true
                    btnFocusBracket.isEnabled = true
                    btnFocusStart.isEnabled = true
                    btnFocusEnd.isEnabled = true
                    btnAE.isEnabled = true
                    btnAeBiasMinus.isEnabled = true
                    btnAeBiasPlus.isEnabled = true
                }
            }
        }, "af-thread").start()
    }

    /** Runs the Camera2 autofocus trigger and logs the resulting focus state. */
    private fun runAutoFocus(camId: String) {
        runOnUiThread { clearFocusBracketLock() }
        if (!openSelectedCamera(camId)) return
        val dummyST = android.graphics.SurfaceTexture(0).also { it.setDefaultBufferSize(640, 480) }
        val dummySurf = android.view.Surface(dummyST)

        if (!createSession(listOf(dummySurf))) {
            dummySurf.release(); dummyST.release(); return
        }

        val afStateName = mapOf(
            CaptureResult.CONTROL_AF_STATE_INACTIVE to "INACTIVE",
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN to "PASSIVE_SCAN",
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED to "PASSIVE_FOCUSED",
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN to "ACTIVE_SCAN",
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED to "FOCUSED_LOCKED",
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED to "NOT_FOCUSED_LOCKED",
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED to "PASSIVE_UNFOCUSED"
        )

        val afLatch = CountDownLatch(1)
        val focusDistRef = AtomicReference<Float?>(null)
        var lastState = -1

        val contReq = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(dummySurf)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            applyOisMode()
        }.build()

        captureSession!!.setRepeatingRequest(
            contReq,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    res: TotalCaptureResult
                ) {
                    val state = res.get(CaptureResult.CONTROL_AF_STATE) ?: return
                    val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (state != lastState) {
                        lastState = state
                        log(
                            "  AF: ${afStateName[state] ?: state}  dist=${
                                dist?.let {
                                    "%.4f".format(
                                        Locale.US,
                                        it
                                    )
                                } ?: "null"
                            }")
                    }
                    if (dist != null) focusDistRef.set(dist)
                    if (state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                        state == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
                    ) afLatch.countDown()
                }
            },
            camHandler
        )

        val passiveDone = afLatch.await(12, TimeUnit.SECONDS)
        captureSession!!.stopRepeating()
        if (!passiveDone) log("PASSIVE_AF_CONVERGENCE_TIMEOUT")

        val lockLatch = CountDownLatch(1)
        val lockedDistRef = AtomicReference<Float?>(null)

        captureSession!!.capture(
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(dummySurf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyOisMode()
            }.build(), null, camHandler
        )

        captureSession!!.setRepeatingRequest(
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(dummySurf)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyOisMode()
            }.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    res: TotalCaptureResult
                ) {
                    val state = res.get(CaptureResult.CONTROL_AF_STATE) ?: return
                    val dist = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    if (state != lastState) {
                        lastState = state
                        log(
                            "  AF lock: ${afStateName[state] ?: state}  dist=${
                                dist?.let {
                                    "%.4f".format(
                                        Locale.US,
                                        it
                                    )
                                } ?: "null"
                            }")
                    }
                    if (dist != null) lockedDistRef.set(dist)
                    if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) lockLatch.countDown()
                }
            }, camHandler
        )

        val locked = lockLatch.await(8, TimeUnit.SECONDS)
        captureSession!!.stopRepeating()
        runCatching { dummySurf.release(); dummyST.release() }

        val finalDist = lockedDistRef.get() ?: focusDistRef.get()
        if (finalDist == null) {
            log("AF: no focusing distance"); return
        }

        val rounded = "%.4f".format(Locale.US, finalDist).toFloat()
        log("AF ${if (locked) "LOCKED" else "estimated"} — $rounded")
        runOnUiThread { etFocus.setText("%.4f".format(Locale.US, rounded)) }
    }

    // ══════════════════════════════════════════════
    //  CAPTURE
    // ══════════════════════════════════════════════

    /** Parses exposure and pause steps, expanding compact syntax when needed. */
    private fun parseExposureSequence(): ExposureSequence? {
        val original = etExposures.text.toString()
        val expanded =
            ExposureSequenceParser.expand(original, ::parseExpString)
                ?: run {
                    log("Invalid exposure or pause sequence")
                    return null
                }
        val sequence =
            ExposureSequenceParser.parse(expanded, ::parseExpString)
                ?: run {
                    log("Invalid exposure or pause position")
                    return null
                }
        if (expanded != original.trim()) {
            etExposures.setText(expanded)
            etExposures.setSelection(etExposures.text.length)
            saveSettings()
        }
        tvBrackets.text =
            buildString {
                append("${sequence.exposureCount} frames")
                if (sequence.pauseCount > 0) append(" / ${sequence.pauseCount} pause")
            }
        return sequence
    }

    /** Returns only exposure durations for controls that do not execute pauses. */
    private fun parseExposures(): List<Long> =
        parseExposureSequence()?.exposures.orEmpty()

    /** Validates UI inputs and starts the RAW bracketing capture sequence. */
    private fun startCapture() {
        if (capturePauseWaiting) {
            resumeCaptureSequence()
            return
        }
        if (running || liveFocusRunning) {
            log("Capture in progress")
            return
        }

        val pendingDarkAtStart = pendingDarkCaptureSession
        val capturingDarks = pendingDarkAtStart != null
        if (capturingDarks && !pendingDarkCaptureMayStart()) return

        stopFramingPreviewForCapture()
        if (!capturingDarks) clearLog()
        var exposureSequence: ExposureSequence
        var exposures: List<Long>
        val focusValues: List<Float>?
        val iso: Int
        val camId: String
        val intervalSec: Long
        val focusDist: Float
        val delaySec: Long

        if (capturingDarks) {
            val pending = requireNotNull(pendingDarkAtStart)
            val records = pending.lightSequence.records.sortedBy { it.frameIndex }
            if (records.isEmpty()) {
                log("Dark calibration failed: retained lights are empty")
                return
            }
            exposures = records.map { it.exposureTimeNs }
            exposureSequence =
                ExposureSequence(
                    exposures.map {
                        ExposureSequenceStep.Exposure(
                            label = expNsToInputText(it),
                            exposureTimeNs = it
                        )
                    }
                )
            focusValues = null
            iso = records.first().iso
            camId = pending.lightSequence.cameraId
            intervalSec = 0L
            focusDist = 0f
            delaySec = 0L
            log(
                "Dark capture prepared from ${records.size} retained lights: " +
                    "camera=$camId ISO=$iso exposure=${fmtExpNs(exposures.first())}"
            )
        } else {
            exposureSequence = parseExposureSequence() ?: return
            exposures = exposureSequence.exposures
            if (exposures.isEmpty()) {
                log("No valid exposure!"); return
            }
            focusValues =
                if (focusStartReady || focusEndReady) {
                    readFocusBracketValues(showErrors = true) ?: return
                } else {
                    null
                }
            iso = etIso.text.toString().toIntOrNull() ?: 400
            camId = selectedCameraId()
            intervalSec = etInterval.text.toString().toLongOrNull() ?: 0L
            focusDist = etFocus.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
            delaySec = etDelay.text.toString().toLongOrNull() ?: 2L
        }
        if (focusValues != null) {
            if (exposures.size == 1 && focusValues.size > 1) {
                exposures = List(focusValues.size) { exposures.first() }
                exposureSequence =
                    ExposureSequence(
                        exposures.mapIndexed { index, exposure ->
                            ExposureSequenceStep.Exposure(
                                label = expNsToInputText(exposure),
                                exposureTimeNs = exposures[index]
                            )
                        }
                    )
            }
            if (exposures.size != focusValues.size) {
                log("Focus frames must match exposures")
                return
            }
            if (afLockedForCapture) {
                closeEverything()
            }
            log("--- fBracket ---")
            log(focusValues.joinToString(" / ") { "%.4f".format(Locale.US, it) })
            log("Aguardando inicio da captura")
        }
        if (
            !capturingDarks &&
            stackModesState.dithering == StackDitheringSelection.MANUAL_ASSISTED &&
            exposureSequence.pauseCount == 0
        ) {
            log("Manual dithering requires at least one pause in the exposure list")
            return
        }
        if (
            !capturingDarks &&
            stackModesState.processing == StackProcessingSelection.HDR &&
            (exposures.size < 2 || exposures.distinct().size < 2)
        ) {
            log("HDR requires at least 2 different exposure times")
            return
        }
        if (
            !capturingDarks &&
            stackModesState.processing in
                setOf(StackProcessingSelection.STACK, StackProcessingSelection.STAR_TRAIL) &&
            exposures.distinct().size != 1
        ) {
            log("${stackModesState.processing.name} requires equal exposure times")
            return
        }
        if (
            !capturingDarks &&
            stackModesState.processing != StackProcessingSelection.FRAMES_ONLY &&
            focusValues != null
        ) {
            log("Stack Modes processing requires fixed focus; disable Focus Bracket")
            return
        }
        if (
            !capturingDarks &&
            stackModesState.useDarks &&
            !hasMasterDarkCaptureStorage(camId, exposures.size)
        ) {
            return
        }
        if (
            capturingDarks &&
            !hasMasterDarkCaptureStorage(camId, exposures.size)
        ) {
            return
        }
        if (
            devDiagnosticMode == DevRawDiagnosticMode.HDRI32_MERGE &&
            (exposures.size < 2 || exposures.distinct().size < 2)
        ) {
            log("DEV HDR requires at least 2 different exposure times")
            return
        }
        if (
            devDiagnosticMode == DevRawDiagnosticMode.CAPTURE_MASTER_DARK &&
            !hasMasterDarkCaptureStorage(camId, exposures.size)
        ) {
            return
        }
        if (
            devDiagnosticMode == DevRawDiagnosticMode.HDRI32_MERGE &&
            !hasHdrI32CaptureStorage(camId, exposures.size)
        ) {
            return
        }

        if (afLockedForCapture && afLockedCamId != camId) {
            closeEverything()
        }

        running = true
        darkCaptureInProgress = capturingDarks
        releaseCapturePause()
        btnStart.text = "Capture"
        applyButtonStroke(btnStart, 0xFFFFFFFF.toInt())
        btnStackModes.isEnabled = false
        btnDev.isEnabled = false
        btnPreview.isEnabled = false
        btnStart.isEnabled = false
        btnAF.isEnabled = false
        etCameraId.isEnabled = false
        etIso.isEnabled = false
        btnWB.isEnabled = false
        btnFolderMode.isEnabled = false
        btnResetSettings.isEnabled = false
        btnHelp.isEnabled = false
        btnLiveFocus.isEnabled = false
        btnFocusBracket.isEnabled = false
        btnFocusStart.isEnabled = false
        btnFocusEnd.isEnabled = false
        btnAE.isEnabled = false
        btnAeBiasMinus.isEnabled = false
        btnAeBiasPlus.isEnabled = false
        startBgThreads()

        val lightStartTemperatureCelsius =
            if (capturingDarks) {
                requireNotNull(pendingDarkAtStart).initialCameraTemperatureCelsius
            } else if (stackModesState.useDarks) {
                latestCameraTemperatureCelsius
            } else {
                null
            }
        if (!capturingDarks) {
            val captureTimestamp =
                SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.US).format(Date())
            currentCaptureFolder =
                if (
                    folderModeEnabled ||
                    stackModesState.processing != StackProcessingSelection.FRAMES_ONLY
                ) {
                    captureTimestamp
                } else {
                    null
                }
            currentStackOutputRelativePath =
                stackModeDcimRelativePath(currentCaptureFolder ?: captureTimestamp)
            if (stackModesState.useDarks) {
                log(
                    "Light start camera-usr stored: " +
                        formatTemperatureForLog(lightStartTemperatureCelsius)
                )
            }
        }

        if (currentCaptureFolder != null) {
            log("Folder: $currentCaptureFolder")
        }
        val devAlignedStackAdapter =
            if (capturingDarks) null else prepareDevAlignedStackAdapter(camId)
        val productionStackAdapter =
            when {
                capturingDarks -> prepareProductionDarkAdapter(camId)
                devAlignedStackAdapter == null -> prepareProductionStackAdapter(camId)
                else -> null
            }
        val capturedSequenceAdapter = devAlignedStackAdapter ?: productionStackAdapter
        val normalStackProcessor = createNormalStackProcessor(exposures, focusValues, camId)

        saveQueue.clear()
        saveThread = Thread({
            try {
                loop@ while (true) {
                    when (val job = saveQueue.take()) {
                        is SaveJob.Frame ->
                            saveDng(
                                job.image,
                                job.result,
                                job.seq,
                                job.frame,
                                job.expNs,
                                job.iso,
                                job.wbGains,
                                job.dngOrientation,
                                job.dngOrientationDegrees,
                                normalStackProcessor,
                                capturedSequenceAdapter,
                                job.slot
                            )

                        SaveJob.Stop -> {
                            normalStackProcessor?.finish()
                            when {
                                devAlignedStackAdapter != null ->
                                    runDevAlignedStackDiagnostic(devAlignedStackAdapter)
                                productionStackAdapter != null && capturingDarks ->
                                    completeDarkCalibrationAndProcess(
                                        productionStackAdapter.snapshot()
                                    )
                                productionStackAdapter != null && stackModesState.useDarks ->
                                    armPendingDarkCapture(
                                        productionStackAdapter.snapshot(),
                                        lightStartTemperatureCelsius
                                    )
                                productionStackAdapter != null ->
                                    runProductionStackMode(productionStackAdapter.snapshot())
                            }
                            break@loop
                        }
                    }
                }
                log("Save complete")
            } catch (e: InterruptedException) {
                log("Save interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "saveThread", e); log("Save error: ${e.message}")
            }
        }, "save-thread").also { it.start() }

        Thread({
            try {
                runBracketing(
                    exposureSequence.steps,
                    focusValues,
                    camId,
                    iso,
                    intervalSec,
                    focusDist,
                    delaySec
                )
            } catch (e: InterruptedException) {
                log("Interrupted.")
            } catch (e: Exception) {
                Log.e(TAG, "runBracketing", e); log("Error: ${e.message}")
            } finally {
                try {
                    saveQueue.put(SaveJob.Stop)
                } catch (e: Exception) {
                    Log.e(TAG, "poison", e)
                }
                try {
                    log("Saving...")
                    val saveTimeoutMs =
                        if (
                            normalStackProcessor != null ||
                            devAlignedStackAdapter != null ||
                            productionStackAdapter != null
                        ) {
                            900_000L
                        } else {
                            120_000L
                        }
                    saveThread?.join(saveTimeoutMs)
                    if (saveThread?.isAlive == true) log("Still saving")
                } catch (e: Exception) {
                    Log.e(TAG, "join", e)
                }
                try {
                    closeEverything()
                } catch (e: Exception) {
                    Log.e(TAG, "close", e)
                }
                runOnUiThread {
                    running = false
                    releaseCapturePause()
                    darkCaptureInProgress = false
                    if (pendingDarkCaptureSession != null) {
                        btnStackModes.isEnabled = false
                        btnDev.isEnabled = false
                        btnPreview.isEnabled = false
                        btnAF.isEnabled = false
                        etCameraId.isEnabled = false
                        etIso.isEnabled = false
                        btnWB.isEnabled = false
                        btnFolderMode.isEnabled = false
                        btnResetSettings.isEnabled = false
                        btnHelp.isEnabled = false
                        btnLiveFocus.isEnabled = false
                        btnFocusBracket.isEnabled = false
                        btnFocusStart.isEnabled = false
                        btnFocusEnd.isEnabled = false
                        btnAE.isEnabled = false
                        btnAeBiasMinus.isEnabled = false
                        btnAeBiasPlus.isEnabled = false
                        refreshPendingDarkTemperature(latestCameraTemperatureCelsius)
                    } else {
                        btnStackModes.isEnabled = true
                        btnDev.isEnabled = true
                        btnStart.text = "Capture"
                        applyButtonStroke(btnStart, 0xFFFFFFFF.toInt())
                        btnStart.isEnabled = true
                        btnPreview.isEnabled = true
                        btnAF.isEnabled = true
                        etCameraId.isEnabled = true
                        etIso.isEnabled = true
                        btnWB.isEnabled = true
                        btnFolderMode.isEnabled = true
                        btnResetSettings.isEnabled = true
                        btnHelp.isEnabled = true
                        btnLiveFocus.isEnabled = true
                        btnFocusBracket.isEnabled = true
                        btnFocusStart.isEnabled = true
                        btnFocusEnd.isEnabled = true
                        btnAE.isEnabled = true
                        btnAeBiasMinus.isEnabled = true
                        btnAeBiasPlus.isEnabled = true
                    }
                }
            }
        }, "bracketing-thread").start()
    }

    private fun hasMasterDarkCaptureStorage(cameraId: String, frameCount: Int): Boolean {
        val rawSize =
            runCatching {
                cameraManager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            }.getOrNull()
        if (rawSize == null) {
            log("MasterDark storage pre-flight unavailable")
            return true
        }
        val estimate = DarkStorageEstimator.estimate(
            width = rawSize.width,
            height = rawSize.height,
            darkFrameCount = frameCount,
            outputDirectory = masterDarkRoot()
        )
        log(
            "MasterDark storage: required=${estimate.totalRequiredBytes} " +
                "available=${estimate.availableBytes}"
        )
        if (!estimate.sufficient) {
            log("MasterDark capture cancelled: insufficient storage")
        }
        return estimate.sufficient
    }

    private fun hasHdrI32CaptureStorage(cameraId: String, frameCount: Int): Boolean {
        val rawSize =
            runCatching {
                cameraManager
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            }.getOrNull()
        if (rawSize == null) {
            log("DEV HDR storage pre-flight unavailable")
            return true
        }
        val estimate = HdrI32StorageEstimator.estimate(
            width = rawSize.width,
            height = rawSize.height,
            frameCount = frameCount,
            outputDirectory = hdri32Root()
        )
        log(
            "DEV HDR storage: required=${estimate.totalRequiredBytes} " +
                "available=${estimate.availableBytes}"
        )
        if (!estimate.sufficient) {
            log("DEV HDR capture cancelled: insufficient storage")
        }
        return estimate.sufficient
    }

    private fun createNormalStackProcessor(
        exposures: List<Long>,
        focusValues: List<Float>?,
        camId: String
    ): NormalStackProcessor? {
        if (stackModesState.processing != StackProcessingSelection.FRAMES_ONLY) {
            return null
        }
        val plan = StackingPlanner.resolve(stackingConfig)
        plan.warnings.forEach { log(it) }
        plan.unsupportedReason?.let { log(it) }

        return when (plan.intent) {
            ProcessingIntent.SINGLE_CAPTURE -> null
            ProcessingIntent.HDRI_MERGE -> {
                log("HDR processing is controlled by Stack Modes")
                null
            }
            ProcessingIntent.STAR_STACK -> {
                log("Star Stack processing is controlled by Stack Modes")
                null
            }
            ProcessingIntent.DARK_CAPTURE -> {
                log("MasterDark capture is available through DEV diagnostics")
                null
            }
            ProcessingIntent.NORMAL_STACK -> {
                if (plan.alignmentMode != ResolvedAlignmentMode.NONE) {
                    log(
                        "Aligned Normal Stack is controlled by Stack Modes"
                    )
                    null
                } else if (focusValues != null) {
                    log("Normal Stack skipped: focus bracket active")
                    null
                } else if (exposures.distinct().size != 1) {
                    log("Normal Stack skipped: exposures must match")
                    null
                } else {
                    val stackChars = runCatching {
                        cameraManager.getCameraCharacteristics(camId)
                    }.getOrNull()
                    val wbGains = whiteBalanceOptionForMode(validatedWbMode(stackChars))?.gains
                    log("Normal Stack armed")
                    NormalStackProcessor(
                        applicationContext,
                        currentCaptureFolder,
                        stackChars,
                        wbGains,
                        ::log
                    )
                }
            }
        }
    }

    /** Wakes a capture sequence that is waiting at a Pause step. */
    private fun resumeCaptureSequence() {
        synchronized(capturePauseLock) {
            if (!capturePauseWaiting) return
            capturePauseWaiting = false
            capturePauseLock.notifyAll()
        }
        btnStart.text = "Capture"
        btnStart.isEnabled = false
        applyButtonStroke(btnStart, 0xFFFFFFFF.toInt())
        log("Capture resumed")
    }

    /** Releases any waiting capture thread during completion, failure, or shutdown. */
    private fun releaseCapturePause() {
        synchronized(capturePauseLock) {
            capturePauseWaiting = false
            capturePauseLock.notifyAll()
        }
    }

    /** Waits without closing the camera until the user presses the green Resume button. */
    private fun waitForCaptureResume(completedFrames: Int, totalFrames: Int): Boolean {
        synchronized(capturePauseLock) {
            capturePauseWaiting = true
        }
        log("Pause: $completedFrames/$totalFrames frames captured - waiting Resume")
        runOnUiThread {
            btnStart.text = "Resume"
            btnStart.isEnabled = true
            applyButtonStroke(btnStart, 0xFF00FF00.toInt())
        }
        synchronized(capturePauseLock) {
            while (capturePauseWaiting && running) {
                capturePauseLock.wait()
            }
        }
        return running
    }

    /** Captures the configured exposure/focus steps as a RAW DNG sequence. */
    private fun runBracketing(
        steps: List<ExposureSequenceStep>, focusValues: List<Float>?, camId: String, iso: Int,
        intervalSec: Long, focusDist: Float, delaySec: Long
    ) {
        val totalFrames = steps.count { it is ExposureSequenceStep.Exposure }
        if (totalFrames <= 0) {
            log("No exposure frames")
            return
        }
        if (!openSelectedCamera(camId)) return
        val rawSizes = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes.isNullOrEmpty()) {
            log("RAW_SENSOR not suported!"); return
        }
        val rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height }!!
        val readerBuf = (totalFrames + 2).coerceIn(3, 8)
        rawImageSlots = Semaphore((readerBuf - 1).coerceAtLeast(1))
        imageReader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            readerBuf
        )
        log("RAW ${rawSize.width}x${rawSize.height}")
        val captureSurfaces =
            if (afLockedForCapture && afLockSurface != null) {
                listOf(imageReader.surface, afLockSurface!!)
            } else {
                listOf(imageReader.surface)
            }
        if (!createSession(captureSurfaces)) return
        if (afLockedForCapture) holdAfLockRepeating()

        chars!!.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.let { log("Expo ${fmtNs(it.lower)}-${fmtNs(it.upper)}") }
        chars!!.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { log("ISO ${it.lower}-${it.upper}") }
        val oisModes = chars!!.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        log(
            "OIS: ${
                when {
                    oisModes == null -> "not reported"
                    !oisEnabled -> "OFF"
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes -> "ON"
                    else -> "OFF (no support)"
                }
            }"
        )
        log("WB: ${whiteBalanceOptionForMode(validatedWbMode(chars))?.label ?: "AUTO"}")

        log("$totalFrames frames  f=$focusDist")
        focusValues?.let {
            log("Focus: ${it.joinToString(" / ") { value -> "%.4f".format(Locale.US, value) }}")
        }
        if (delaySec > 0) {
            log("Delay ${delaySec}s..."); Thread.sleep(delaySec * 1000)
        }

        val seq = 1
        var exposureIndex = 0
        var capturedFrameCount = 0
        for ((stepIndex, step) in steps.withIndex()) {
            if (!running) break
            when (step) {
                is ExposureSequenceStep.Exposure -> {
                    val frameFocus = focusValues?.getOrNull(exposureIndex) ?: focusDist
                    val frameNumber = exposureIndex + 1
                    var captured = false
                    for (attempt in 1..MAX_RAW_CAPTURE_ATTEMPTS) {
                        captured = captureOneRaw(
                            step.exposureTimeNs,
                            iso,
                            frameFocus,
                            seq,
                            frameNumber,
                            totalFrames
                        )
                        if (captured || !running) break
                        if (attempt < MAX_RAW_CAPTURE_ATTEMPTS) {
                            log("Retrying shot $frameNumber/$totalFrames")
                            Thread.sleep(LONG_EXPOSURE_SETTLE_MS)
                        }
                    }
                    if (!captured && running) {
                        log(
                            "Shot $frameNumber/$totalFrames skipped after " +
                                "$MAX_RAW_CAPTURE_ATTEMPTS attempts"
                        )
                    } else if (captured) {
                        capturedFrameCount++
                    }
                    if (
                        captured &&
                        step.exposureTimeNs >= LONG_EXPOSURE_SETTLE_THRESHOLD_NS &&
                        frameNumber < totalFrames
                    ) {
                        // Some Camera2 HALs need a brief idle interval after a long RAW exposure.
                        val advertisedMaximumExposure =
                            chars
                                ?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                ?.upper
                        val settleMs =
                            if (
                                advertisedMaximumExposure != null &&
                                step.exposureTimeNs > advertisedMaximumExposure
                            ) {
                                EXTENDED_EXPOSURE_SETTLE_MS
                            } else {
                                LONG_EXPOSURE_SETTLE_MS
                            }
                        log("RAW pipeline settle ${settleMs}ms")
                        Thread.sleep(settleMs)
                    }
                    exposureIndex++

                    val nextStep = steps.getOrNull(stepIndex + 1)
                    if (
                        running &&
                        intervalSec > 0L &&
                        nextStep is ExposureSequenceStep.Exposure
                    ) {
                        log("Frame gap ${intervalSec}s")
                        var remainingSeconds = intervalSec
                        while (running && remainingSeconds > 0L) {
                            Thread.sleep(1000L)
                            remainingSeconds--
                        }
                    }
                }

                ExposureSequenceStep.Pause -> {
                    if (!waitForCaptureResume(exposureIndex, totalFrames)) break
                }
            }
        }
        log("Captured $capturedFrameCount/$totalFrames frames")
    }

    /** Keeps a repeating preview request alive so the AF lock is retained. */
    private fun holdAfLockRepeating() {
        val surface = afLockSurface ?: return
        val device = cameraDevice ?: return
        runCatching {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyWhiteBalance()
                disableProcessingIfAvailable(this)
            }.build()
            captureSession?.setRepeatingRequest(req, null, camHandler)
        }.onFailure {
            Log.e(TAG, "holdAfLock", it)
            log("AF hold error: ${it.message}")
        }
    }

    /** Captures one RAW frame and queues it for background DNG saving. */
    private fun captureOneRaw(
        expNs: Long,
        iso: Int,
        focusDist: Float,
        seq: Int,
        idx: Int,
        total: Int
    ): Boolean {
        val imageSlot = rawImageSlots
        imageSlot.acquire()
        var handedToSaver = false
        val captureFinished = AtomicBoolean(false)

        try {
            log("Shot $idx/$total ${fmtExpNs(expNs)}")
            val dngOrientationDegrees = currentDngOrientationDegrees(chars)
            val dngOrientation = exifOrientationForDegrees(dngOrientationDegrees)
            val resultLatch = CountDownLatch(1)
            val imageLatch = CountDownLatch(1)
            val resultRef = AtomicReference<TotalCaptureResult?>(null)
            val failureRef = AtomicReference<CaptureFailure?>(null)
            val imageRef = AtomicReference<Image?>(null)

            imageReader.setOnImageAvailableListener(
                { r ->
                    try {
                        val image = r.acquireLatestImage()
                        if (captureFinished.get()) {
                            image?.close()
                        } else {
                            imageRef.getAndSet(image)?.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "rawImage", e)
                        log("Image error: ${e.message}")
                    } finally {
                        imageLatch.countDown()
                    }
                },
                bgHandler
            )

            val req =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    val manualWb = manualWhiteBalanceSelected()
                    set(
                        CaptureRequest.CONTROL_MODE,
                        if (manualWb) CaptureRequest.CONTROL_MODE_OFF_KEEP_STATE
                        else CaptureRequest.CONTROL_MODE_AUTO
                    )
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        if (afLockedForCapture) CaptureRequest.CONTROL_AF_MODE_AUTO
                        else CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    applyWhiteBalance()
                    disableProcessingIfAvailable(this)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    if (!afLockedForCapture) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
                    }
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    applyOisMode(logResult = idx == 1)
                }

            captureSession!!.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    res: TotalCaptureResult
                ) {
                    if (idx == 1) {
                        logWhiteBalanceResult(res)
                        log("OIS result: ${oisModeLabel(res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))}")
                    }
                    resultRef.set(res); resultLatch.countDown()
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    r: CaptureRequest,
                    f: CaptureFailure
                ) {
                    failureRef.set(f)
                    resultLatch.countDown()
                }
            }, bgHandler)

            val gotRes = resultLatch.await((expNs / 1_000_000_000L) + 20L, TimeUnit.SECONDS)
            val failure = failureRef.get()
            val gotImg =
                if (failure == null) {
                    imageLatch.await(10, TimeUnit.SECONDS)
                } else {
                    imageLatch.count == 0L
                }
            val result = resultRef.get()
            val image = imageRef.get()

            when {
                !gotRes -> {
                    log("Result timeout $idx/$total")
                    image?.close()
                }

                failure != null -> {
                    val reason =
                        when (failure.reason) {
                            CaptureFailure.REASON_ERROR -> "ERROR"
                            CaptureFailure.REASON_FLUSHED -> "FLUSHED"
                            else -> failure.reason.toString()
                        }
                    log(
                        "Shot failed $idx/$total reason=$reason " +
                            "frame=${failure.frameNumber} sequence=${failure.sequenceId} " +
                            "imageCaptured=${failure.wasImageCaptured()}"
                    )
                    image?.close()
                }

                !gotImg || image == null -> log("Image timeout $idx/$total")

                result == null -> {
                    log("Result null $idx/$total")
                    image.close()
                }

                else -> {
                    val wbGains = whiteBalanceOptionForMode(validatedWbMode(chars))?.gains
                    handedToSaver = saveQueue.offer(
                        SaveJob.Frame(
                            image,
                            result,
                            seq,
                            idx,
                            expNs,
                            iso,
                            wbGains,
                            dngOrientation,
                            dngOrientationDegrees,
                            imageSlot
                        )
                    )
                    if (!handedToSaver) {
                        log("Save queue full")
                        image.close()
                    }
                }
            }
        } finally {
            captureFinished.set(true)
            runCatching { imageReader.setOnImageAvailableListener(null, null) }
            if (!handedToSaver) imageSlot.release()
        }
        return handedToSaver
    }

    // ══════════════════════════════════════════════
    //  SAVE DNG
    // ══════════════════════════════════════════════

    /** Saves one queued RAW image as a DNG file through MediaStore or app storage. */
    private fun saveDng(
        image: Image,
        result: TotalCaptureResult,
        seq: Int,
        frame: Int,
        expNs: Long,
        iso: Int,
        wbGains: RggbChannelVector?,
        dngOrientation: Int,
        dngOrientationDegrees: Int,
        normalStackProcessor: NormalStackProcessor?,
        capturedSequenceAdapter: CapturedRawSequenceAdapter?,
        slot: Semaphore
    ) {
        try {
            val c = chars ?: run { log("Camera data missing"); return }
            capturedSequenceAdapter?.let { adapter ->
                runCatching {
                    adapter.accept(
                        image = image,
                        result = result,
                        sequenceNumber = seq,
                        captureFrameNumber = frame,
                        requestedExposureTimeNs = expNs,
                        requestedIso = iso,
                        dngOrientation = dngOrientation,
                        dngOrientationDegrees = dngOrientationDegrees
                    )
                }.onSuccess { accept ->
                    log(accept.message)
                }.onFailure {
                    log("RAW frame copy error: ${it.message}")
                }
            }
            if (
                capturedSequenceAdapter?.diagnosticMode == DevRawDiagnosticMode.STAR_DETECTION ||
                capturedSequenceAdapter?.diagnosticMode == DevRawDiagnosticMode.STAR_MATCHING ||
                capturedSequenceAdapter?.diagnosticMode == DevRawDiagnosticMode.STAR_ALIGNED_STACK
            ) {
                log("DEV star frame retained for backend diagnostic")
                return
            }
            val productionCapture =
                capturedSequenceAdapter != null &&
                    capturedSequenceAdapter.diagnosticMode == null
            if (capturedSequenceAdapter?.isDarkCalibrationSequence == true) {
                log("Dark RAW retained for same-session MasterDark")
                return
            }
            if (productionCapture && !stackModesState.keepSourceFrames) {
                log("Source DNG skipped; RAW retained only for selected processing")
                return
            }
            val dng = DngCreator(c, result)
            dng.setOrientation(dngOrientation)
            val name = buildDngName(seq, frame, expNs, iso)
            val dngBytes = createDngBytes(dng, image, wbGains)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val folderPath =
                    if (productionCapture) {
                        "${requireNotNull(currentStackOutputRelativePath)}/Frames"
                    } else {
                        currentStackOutputRelativePath ?: "DCIM/BracketLab/Frames"
                    }

                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, folderPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: run { log("Save insert failed"); return }
                contentResolver.openOutputStream(uri)?.use { it.write(dngBytes) }
                    ?: run { log("Save stream failed"); return }
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                @Suppress("DEPRECATION")
                val subFolder =
                    (if (productionCapture) {
                        "${requireNotNull(currentStackOutputRelativePath)}/Frames"
                    } else {
                        currentStackOutputRelativePath ?: "DCIM/BracketLab/Frames"
                    }).removePrefix("DCIM/")

                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    subFolder
                ).also { it.mkdirs() }
                FileOutputStream(File(dir, name)).use { it.write(dngBytes) }
            }
            normalStackProcessor?.let { processor ->
                runCatching { processor.accept(image, result, dngOrientation, expNs, iso) }
                    .onFailure { log("Normal Stack error: ${it.message}") }
            }
            log("Saved $name orient=${dngOrientationLabel(dngOrientation)} ${dngOrientationDegrees}deg")
        } catch (e: Exception) {
            Log.e(TAG, "saveDng f=$frame", e); log("Save failed: ${e.message}")
        } finally {
            runCatching { image.close() }
            slot.release()
        }
    }

    // ══════════════════════════════════════════════
    /** Encodes Camera2 RAW image data and capture metadata into DNG bytes. */
    private fun createDngBytes(
        dng: DngCreator,
        image: Image,
        wbGains: RggbChannelVector?
    ): ByteArray {
        val bytes = ByteArrayOutputStream().use { out ->
            dng.writeImage(out, image)
            out.toByteArray()
        }

        if (wbGains != null) {
            val patched = patchDngAsShotNeutral(bytes, wbGains)
            log(if (patched) "DNG WB metadata patched" else "DNG WB patch skipped")
        }

        return bytes
    }

    /** Patches the DNG AsShotNeutral tag using the selected manual WB gains. */
    private fun patchDngAsShotNeutral(bytes: ByteArray, gains: RggbChannelVector): Boolean {
        if (bytes.size < 8) return false
        val littleEndian = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> return false
        }
        if (readUInt16(bytes, 2, littleEndian) != 42) return false

        val green = (gains.greenEven + gains.greenOdd) / 2f
        val neutral = floatArrayOf(
            (green / gains.red).coerceAtLeast(0.0001f),
            1f,
            (green / gains.blue).coerceAtLeast(0.0001f)
        )

        var ifdOffset = readUInt32(bytes, 4, littleEndian)
        var visited = 0
        while (ifdOffset > 0 && visited < 16) {
            if (ifdOffset + 2 > bytes.size) return false
            val entries = readUInt16(bytes, ifdOffset, littleEndian)
            val entriesStart = ifdOffset + 2
            val nextIfdOffset = entriesStart + entries * 12
            if (nextIfdOffset + 4 > bytes.size) return false

            repeat(entries) { index ->
                val entry = entriesStart + index * 12
                val tag = readUInt16(bytes, entry, littleEndian)
                if (tag == TIFF_TAG_AS_SHOT_NEUTRAL) {
                    val type = readUInt16(bytes, entry + 2, littleEndian)
                    val count = readUInt32(bytes, entry + 4, littleEndian)
                    if (count < 3 || type !in intArrayOf(5, 10)) return@repeat

                    val valueOffset = readUInt32(bytes, entry + 8, littleEndian)
                    if (valueOffset + 24 > bytes.size) return@repeat

                    writeRational(bytes, valueOffset, neutral[0], littleEndian)
                    writeRational(bytes, valueOffset + 8, neutral[1], littleEndian)
                    writeRational(bytes, valueOffset + 16, neutral[2], littleEndian)
                    return true
                }
            }

            ifdOffset = readUInt32(bytes, nextIfdOffset, littleEndian)
            visited++
        }
        return false
    }

    /** Reads an unsigned 16-bit TIFF value from DNG bytes. */
    private fun readUInt16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 2 > bytes.size) return 0
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    /** Reads an unsigned 32-bit TIFF value from DNG bytes. */
    private fun readUInt32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 4 > bytes.size) return 0
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = bytes[offset + 1].toLong() and 0xFF
        val b2 = bytes[offset + 2].toLong() and 0xFF
        val b3 = bytes[offset + 3].toLong() and 0xFF
        val value =
            if (littleEndian) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return value.toInt()
    }

    /** Writes a TIFF rational value into DNG bytes. */
    private fun writeRational(bytes: ByteArray, offset: Int, value: Float, littleEndian: Boolean) {
        val denominator = 1_000_000
        val numerator = (value * denominator).roundToInt().coerceAtLeast(1)
        writeInt32(bytes, offset, numerator, littleEndian)
        writeInt32(bytes, offset + 4, denominator, littleEndian)
    }

    /** Writes a signed 32-bit TIFF value into DNG bytes. */
    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (offset + 4 > bytes.size) return
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value ushr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }
    }

    //  CAMERA HELPERS
    // -------------------------------------

    /** Verifies the requested camera ID and opens it for capture or preview work. */
    private fun openSelectedCamera(camId: String): Boolean {
        log("Camera $camId")
        if (cameraDevice != null && chars != null && afLockedCamId == camId) {
            log("Camera ready")
            return true
        }
        val found = (0..120).filterNot { getBit(6, it) }.mapNotNull { n ->
            runCatching { cameraManager.getCameraCharacteristics(n.toString()); n.toString() }.getOrNull()
        }
        if (camId !in found) {
            log("Camera $camId not found"); return false
        }
        chars = cameraManager.getCameraCharacteristics(camId)
        if (!cameraLock.tryAcquire(5, TimeUnit.SECONDS)) {
            log("Lock timeout"); return false
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            log("No permission"); cameraLock.release(); return false
        }
        val latch = CountDownLatch(1);
        var err: String? = null
        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                cameraLock.release(); cameraDevice = c; latch.countDown()
            }

            override fun onDisconnected(c: CameraDevice) {
                cameraLock.release(); c.close(); cameraDevice = null; err =
                    "Disconnected"; latch.countDown()
            }

            override fun onError(c: CameraDevice, e: Int) {
                cameraLock.release(); c.close(); cameraDevice = null; err =
                    "Error $e"; latch.countDown()
            }
        }, camHandler)
        latch.await(10, TimeUnit.SECONDS)
        if (err != null || cameraDevice == null) {
            log(" ${err ?: "null"}"); return false
        }
        log("Camera ready"); return true
    }

    /** Creates a Camera2 capture session for the provided output surfaces. */
    private fun createSession(surfaces: List<android.view.Surface>): Boolean {
        val latch = CountDownLatch(1);
        var ok = false
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                captureSession = s; ok = true; latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                log("onConfigureFailed"); latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val configs = surfaces.map { android.hardware.camera2.params.OutputConfiguration(it) }
            cameraDevice!!.createCaptureSession(
                android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    configs, java.util.concurrent.Executors.newSingleThreadExecutor(), cb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            cameraDevice!!.createCaptureSession(surfaces, cb, camHandler)
        }
        latch.await(10, TimeUnit.SECONDS)
        if (!ok) log("Session not set")
        return ok
    }

    /** Starts background threads used by camera callbacks and file saving. */
    private fun startBgThreads() {
        if (!::bgThread.isInitialized || !bgThread.isAlive) {
            bgThread = HandlerThread("cam-bg").also { it.start() }
            bgHandler = Handler(bgThread.looper)
        }
        if (!::camThread.isInitialized || !camThread.isAlive) {
            camThread = HandlerThread("cam-cb").also { it.start() }
            camHandler = Handler(camThread.looper)
        }
    }

    /** Closes capture sessions, camera devices, readers, surfaces, and worker threads. */
    private fun closeEverything() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close(); captureSession = null }
        runCatching { cameraDevice?.close(); cameraDevice = null }
        runCatching { if (::imageReader.isInitialized) imageReader.close() }
        runCatching { framingPreviewSurface?.release(); framingPreviewSurface = null }
        framingPreviewRequestBuilder = null
        runCatching { afLockSurface?.release(); afLockSurface = null }
        runCatching { afLockTexture?.release(); afLockTexture = null }
        afLockedForCapture = false
        afLockedCamId = null
        runCatching {
            if (::bgThread.isInitialized) {
                bgThread.quitSafely(); bgThread.join(2000)
            }
        }
        runCatching {
            if (::camThread.isInitialized) {
                camThread.quitSafely(); camThread.join(2000)
            }
        }
    }

    // ── Formatting ────────────────────────────────
    /** Builds the final DNG filename from sequence, frame, exposure, and ISO. */
    private fun buildDngName(seq: Int, frame: Int, expNs: Long, iso: Int): String {
        val seqTag = seq.toString().padStart(2, '0')
        val frameTag = frame.toString().padStart(2, '0')
        val body = "S${seqTag}_F${frameTag}_${fmtExpFile(expNs)}_I$iso"
        val prefix =
            if (currentCaptureFolder == null) {
                "${SimpleDateFormat("HHmmssSSS", Locale.US).format(Date())}_"
            } else {
                ""
            }
        return "$prefix$body.dng"
    }

    /** Formats exposure time for safe use inside file names. */
    private fun fmtExpFile(ns: Long): String {
        val s = ns / 1_000_000_000.0
        return if (s < 1.0) {
            "E${(1.0 / s).roundToInt()}"
        } else {
            val sec = "%.3f".format(Locale.US, s)
                .trimEnd('0')
                .trimEnd('.')
                .replace('.', 'p')
            "E${sec}s"
        }
    }

    /** Adds a message to the on-screen rolling log and Android logcat. */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            logEntries.add(0, LogEntry(msg, logTone(msg)))
            while (logEntries.size > LOG_MAX_LINES) {
                logEntries.removeAt(logEntries.lastIndex)
            }
            tvLog.text = buildLogText()
        }
    }

    /** Clears the on-screen log buffer. */
    private fun clearLog() {
        logEntries.clear()
        tvLog.text = ""
    }

    /** Builds the colored log text shown in the status panel. */
    private fun buildLogText(): CharSequence {
        val builder = SpannableStringBuilder()
        logEntries.forEachIndexed { index, entry ->
            if (index > 0) builder.append('\n')
            val start = builder.length
            builder.append(entry.message)
            val color = when (entry.tone) {
                LogTone.WARNING -> LOG_WARNING_COLOR
                LogTone.ERROR -> LOG_ERROR_COLOR
                LogTone.NORMAL -> null
            }
            if (color != null) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return builder
    }

    /** Classifies a log message as normal, warning, or error. */
    private fun logTone(msg: String): LogTone {
        val text = msg.trim().lowercase(Locale.US)
        return when {
            isErrorLog(text) -> LogTone.ERROR
            isWarningLog(text) -> LogTone.WARNING
            else -> LogTone.NORMAL
        }
    }

    /** Detects messages that should be rendered as errors. */
    private fun isErrorLog(text: String): Boolean =
        text.contains("error") ||
            text.contains("self-test: fail") ||
            text.contains("failed") ||
            text.startsWith("fail ") ||
            text.contains(" denied") ||
            text.contains("not found") ||
            text.contains("interrupted") ||
            text.startsWith("lock timeout") ||
            text.startsWith("result timeout") ||
            text.startsWith("image timeout") ||
            text.contains("not suported") ||
            text.contains("not supported")

    /** Detects messages that should be rendered as warnings. */
    private fun isWarningLog(text: String): Boolean =
        text.startsWith("warning") ||
            text.contains(" warning") ||
            text.startsWith("invalid") ||
            text.contains(" invalid") ||
            text.contains(" must ") ||
            text.startsWith("must ") ||
            text.startsWith("no ") ||
            text.contains(" unavailable") ||
            text.contains(" unsupported") ||
            text.contains("not set") ||
            text.contains("missing") ||
            text.contains("skipped")
}
