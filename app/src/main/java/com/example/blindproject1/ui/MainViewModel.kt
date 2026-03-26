package com.example.blindproject1.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blindproject1.R
import com.example.blindproject1.audio.AudioEngine
import com.example.blindproject1.audio.TTSManager
import com.example.blindproject1.audio.VoiceCommandManager
import com.example.blindproject1.haptics.HapticManager
import com.example.blindproject1.ml.DetectedObject
import com.example.blindproject1.ml.DetectionPolicy
import com.example.blindproject1.ml.ModelDownloader
import com.example.blindproject1.ml.ObjectDetectorHelper
import com.example.blindproject1.network.Esp32GlassesRepository
import com.example.blindproject1.network.TMapRepository
import com.example.blindproject1.sensors.LocationHelper
import com.example.blindproject1.sensors.OrientationManager
import com.example.blindproject1.sensors.YawCalibrationStore
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@HiltViewModel
class MainViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val hapticManager: HapticManager,
    private val orientationManager: OrientationManager,
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val ttsManager: TTSManager,
    private val voiceCommandManager: VoiceCommandManager,
    private val locationHelper: LocationHelper,
    private val tMapRepository: TMapRepository,
    private val modelDownloader: ModelDownloader,
    private val esp32GlassesRepository: Esp32GlassesRepository,
    private val yawCalibrationStore: YawCalibrationStore,

    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isHighContrastMode = MutableStateFlow(false)
    val isHighContrastMode: StateFlow<Boolean> = _isHighContrastMode.asStateFlow()

    private val _aiSensitivity = MutableStateFlow(0.3f)
    val aiSensitivity: StateFlow<Float> = _aiSensitivity.asStateFlow()

    private val _targetBearing = MutableStateFlow(0f)
    val targetBearing: StateFlow<Float> = _targetBearing.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    private val _relativeBearing = MutableStateFlow(0f)
    val relativeBearing: StateFlow<Float> = _relativeBearing.asStateFlow()

    private val _isDangerMode = MutableStateFlow(false)
    val isDangerMode: StateFlow<Boolean> = _isDangerMode.asStateFlow()

    private val _isOffSidewalk = MutableStateFlow(false)
    val isOffSidewalk: StateFlow<Boolean> = _isOffSidewalk.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _detectedObjects = MutableStateFlow<List<DetectedObject>>(emptyList())
    val detectedObjects: StateFlow<List<DetectedObject>> = _detectedObjects.asStateFlow()

    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isEsp32VisionActive = MutableStateFlow(false)
    val isEsp32VisionActive: StateFlow<Boolean> = _isEsp32VisionActive.asStateFlow()

    private val _esp32CameraStreamUrl = MutableStateFlow("http://192.168.219.101:81/stream")
    val esp32CameraStreamUrl: StateFlow<String> = _esp32CameraStreamUrl.asStateFlow()

    private val _esp32YawDataUrl = MutableStateFlow("http://192.168.219.101/data")
    val esp32YawDataUrl: StateFlow<String> = _esp32YawDataUrl.asStateFlow()

    private val _rawYaw = MutableStateFlow<Float?>(null)
    val rawYaw: StateFlow<Float?> = _rawYaw.asStateFlow()

    private val _yawOffsetDegrees = MutableStateFlow(yawCalibrationStore.loadOffsetDegrees())
    val yawOffsetDegrees: StateFlow<Float> = _yawOffsetDegrees.asStateFlow()

    private val _isYawInverted = MutableStateFlow(yawCalibrationStore.loadInvertYaw())
    val isYawInverted: StateFlow<Boolean> = _isYawInverted.asStateFlow()

    val detectorHelper = objectDetectorHelper

    private var dangerJob: Job? = null
    private var locationJob: Job? = null
    private var esp32FrameJob: Job? = null
    private var esp32YawJob: Job? = null
    private var esp32AnalyzeJob: Job? = null
    private var esp32VisionWatchdogJob: Job? = null
    private var pendingEsp32Frame: Bitmap? = null
    private var routeWaypoints: List<Location> = emptyList()
    private var currentWaypointIndex = 0

    private var isSimulatingVision = false
    private var isWaitingAtCrosswalk = false
    private var lastTrafficLightState = ""
    private var lastTTSWarningTime = 0L

    private val detectionPolicy = DetectionPolicy()

    private var smoothedBaseYaw: Float? = null
    private val recentBaseYawSamples = ArrayDeque<Float>()
    private val calibrationWindowSize = 20

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                locationHelper.getLocationFlow().collectLatest { location ->
                    _currentLocation.value = location
                }
            } catch (e: Exception) {
                Log.e("Location", "Failed to get location", e)
            }
        }

        viewModelScope.launch {
            val success = modelDownloader.downloadModelIfNeeded()
            if (success) {
                objectDetectorHelper.setupObjectDetector(_aiSensitivity.value)
                _isModelReady.value = true
                ttsManager.speak("AI 비전 시스템 준비 완료")
            } else {
                ttsManager.speak("AI 모델 다운로드에 실패했습니다.")
            }
        }
    }

    fun toggleHighContrastMode(enabled: Boolean) {
        _isHighContrastMode.value = enabled
        ttsManager.speak("고대비 모드 ${if (enabled) "켜짐" else "꺼짐"}")
    }

    fun setAiSensitivity(sensitivity: Float) {
        _aiSensitivity.value = sensitivity
        objectDetectorHelper.setThreshold(sensitivity)
    }

    fun setEsp32CameraStreamUrl(url: String) {
        _esp32CameraStreamUrl.value = url.trim()
    }

    fun setEsp32YawDataUrl(url: String) {
        _esp32YawDataUrl.value = url.trim()
    }

    fun startMonitoring() {
        audioEngine.start()
        audioEngine.setRouteActive(_isNavigating.value && !isWaitingAtCrosswalk)
        ensureYawLoopRunning()
    }

    fun stopMonitoring() {
        stopEsp32Vision(speak = false)
        stopYawMonitoring()
        audioEngine.stop()
        stopDangerFeedback()
    }

    private fun ensureYawLoopRunning() {
        if (esp32YawJob?.isActive == true) return
        startEsp32YawLoop(_esp32YawDataUrl.value)
    }

    private fun stopYawMonitoring() {
        esp32YawJob?.cancel()
        esp32YawJob = null
        _rawYaw.value = null
        clearYawSmoothing()
    }

    fun navigateToMapSelection(latLng: LatLng) {
        startRoutingToDestination(
            destLat = latLng.latitude,
            destLon = latLng.longitude,
            destinationName = "지도 선택 위치",
            detailAddress = "지도에서 선택한 위치"
        )
    }

    fun startVoiceCommand() {
        voiceCommandManager.startListening { recognizedText ->
            ttsManager.speak("${recognizedText}를 검색합니다.")

            viewModelScope.launch(Dispatchers.IO) {
                val geocoder = Geocoder(context, Locale.KOREAN)
                try {
                    val addressList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(recognizedText, 1)
                    } else {
                        geocoder.getFromLocationName(recognizedText, 1)
                    }

                    if (!addressList.isNullOrEmpty()) {
                        val destAddress = addressList[0]
                        val detailAddress = destAddress.getAddressLine(0)
                            ?.replace("대한민국", "")
                            ?.trim()
                            ?: "선택된 위치"

                        startRoutingToDestination(
                            destLat = destAddress.latitude,
                            destLon = destAddress.longitude,
                            destinationName = recognizedText,
                            detailAddress = detailAddress
                        )
                    } else {
                        withContext(Dispatchers.Main) {
                            ttsManager.speak("목적지 위치를 찾지 못했습니다. 다시 말씀해주세요.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Geocoding error", e)
                    withContext(Dispatchers.Main) {
                        ttsManager.speak("경로 탐색 중 오류가 발생했습니다.")
                    }
                }
            }
        }
    }

    private fun startRoutingToDestination(
        destLat: Double,
        destLon: Double,
        destinationName: String,
        detailAddress: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentLoc = _currentLocation.value ?: run {
                withContext(Dispatchers.Main) {
                    ttsManager.speak("현재 위치를 찾을 수 없어 안내를 시작할 수 없습니다.")
                }
                return@launch
            }

            val waypoints = tMapRepository.getPedestrianRoute(
                start = currentLoc,
                destLat = destLat,
                destLon = destLon,
                startName = "현재위치",
                endName = destinationName
            )

            withContext(Dispatchers.Main) {
                if (waypoints.isNotEmpty()) {
                    routeWaypoints = waypoints
                    currentWaypointIndex = if (waypoints.size > 1) 1 else 0
                    _isNavigating.value = true
                    audioEngine.setRouteActive(true)
                    ensureYawLoopRunning()

                    if (detailAddress != null) {
                        ttsManager.speak("${detailAddress}로 경로를 찾았습니다. 소리를 따라 이동하세요.")
                    } else {
                        ttsManager.speak("경로를 재탐색했습니다. 소리를 따라 이동하세요.")
                    }

                    startGPSNavigationTask()
                } else {
                    ttsManager.speak("해당 목적지까지의 도보 경로를 찾을 수 없습니다.")
                }
            }
        }
    }

    private fun startGPSNavigationTask() {
        locationJob?.cancel()

        locationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                locationHelper.getLocationFlow().collectLatest { location ->
                    _currentLocation.value = location

                    if (routeWaypoints.isNotEmpty() && currentWaypointIndex < routeWaypoints.size) {
                        val targetWaypoint = routeWaypoints[currentWaypointIndex]

                        val bearing = location.bearingTo(targetWaypoint)
                        val normalizedBearing = normalizeDegrees(bearing)
                        _targetBearing.value = normalizedBearing

                        val distanceToTarget = location.distanceTo(targetWaypoint)
                        audioEngine.setRouteDistanceMeters(distanceToTarget)

                        if (distanceToTarget < 15.0f) {
                            currentWaypointIndex++
                            if (currentWaypointIndex >= routeWaypoints.size) {
                                ttsManager.speak("목적지에 도착했습니다. 안내를 종료합니다.")
                                stopGPSNavigation()
                                return@collectLatest
                            }
                        }

                        if (!isWaitingAtCrosswalk) {
                            updateAudio()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GPS", "Location updates failed", e)
            }
        }
    }

    fun stopGPSNavigation() {
        _isNavigating.value = false
        locationJob?.cancel()
        locationJob = null
        routeWaypoints = emptyList()
        currentWaypointIndex = 0

        audioEngine.setRouteActive(false)
        ttsManager.speak("안내를 중지합니다.")
    }

    fun startEsp32Vision() {
        if (_isEsp32VisionActive.value) return

        if (!_isModelReady.value) {
            ttsManager.speak("아직 AI 모델 준비 중입니다.")
            return
        }

        audioEngine.start()
        ensureYawLoopRunning()

        _isEsp32VisionActive.value = true
        ttsManager.speak("안경 카메라 연결을 시작합니다.")
        startEsp32FrameLoop(_esp32CameraStreamUrl.value)
    }

    fun stopEsp32Vision(speak: Boolean = true) {
        _isEsp32VisionActive.value = false

        esp32FrameJob?.cancel()
        esp32FrameJob = null

        esp32VisionWatchdogJob?.cancel()
        esp32VisionWatchdogJob = null

        esp32AnalyzeJob?.cancel()
        esp32AnalyzeJob = null
        pendingEsp32Frame = null

        _detectedObjects.value = emptyList()
        audioEngine.clearObstacleCue()
        audioEngine.stopDangerSound()

        if (speak) {
            ttsManager.speak("안경 카메라 연결을 종료합니다.")
        }
    }

    private fun startEsp32YawLoop(dataUrl: String) {
        esp32YawJob?.cancel()
        esp32YawJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val yaw = esp32GlassesRepository.fetchYaw(dataUrl)
                if (yaw != null) {
                    withContext(Dispatchers.Main) {
                        _rawYaw.value = normalizeDegrees(yaw)
                        _currentHeading.value = ingestRawYaw(yaw)
                        if (!isWaitingAtCrosswalk) {
                            updateAudio()
                        }
                    }
                }
                delay(50)
            }
        }
    }

    private fun startEsp32FrameLoop(cameraUrl: String) {
        esp32FrameJob?.cancel()
        esp32AnalyzeJob?.cancel()
        pendingEsp32Frame = null

        esp32FrameJob = viewModelScope.launch {
            var receivedAnyFrame = false
            esp32VisionWatchdogJob?.cancel()
            esp32VisionWatchdogJob = launch {
                delay(4000)
                if (!receivedAnyFrame && _isEsp32VisionActive.value) {
                    Log.w("ESP32", "No camera frame received within timeout")
                    stopEsp32Vision(speak = false)
                    ttsManager.speak("안경 카메라 연결에 실패했습니다.")
                }
            }

            try {
                esp32GlassesRepository.cameraFrames(cameraUrl).collect { bitmap ->
                    receivedAnyFrame = true
                    esp32VisionWatchdogJob?.cancel()
                    esp32VisionWatchdogJob = null

                    // 프리뷰는 즉시 갱신
                    _selectedImage.value = bitmap

                    // 탐지는 별도 큐로 순차 처리
                    enqueueEsp32FrameForAnalysis(bitmap)
                }
            } catch (e: Exception) {
                Log.e("ESP32", "Camera frame loop failed", e)
            }

            if (!receivedAnyFrame && _isEsp32VisionActive.value) {
                withContext(Dispatchers.Main) {
                    stopEsp32Vision(speak = false)
                    ttsManager.speak("안경 카메라 영상을 받지 못했습니다.")
                }
            }
        }
    }

    private fun enqueueEsp32FrameForAnalysis(bitmap: Bitmap) {
        if (esp32AnalyzeJob?.isActive == true) {
            pendingEsp32Frame = bitmap
            return
        }

        esp32AnalyzeJob = viewModelScope.launch {
            var frameToAnalyze: Bitmap? = bitmap

            while (frameToAnalyze != null && isActive) {
                val currentFrame = frameToAnalyze

                val results = withContext(Dispatchers.Default) {
                    objectDetectorHelper.detect(currentFrame)
                }

                _detectedObjects.value = results
                processVisionResults(
                    results = results,
                    bitmap = currentFrame,
                    imgWidth = currentFrame.width,
                    imgHeight = currentFrame.height
                )

                frameToAnalyze = pendingEsp32Frame
                pendingEsp32Frame = null
            }
        }
    }

    fun processLiveCameraFrame(results: List<DetectedObject>, bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Main) {
            _detectedObjects.value = results
            _selectedImage.value = bitmap
            processVisionResults(results, bitmap, bitmap.width, bitmap.height)
        }
    }

    private fun processVisionResults(
        results: List<DetectedObject>,
        bitmap: Bitmap,
        imgWidth: Int,
        imgHeight: Int
    ) {
        if (results.isEmpty()) {
            audioEngine.clearObstacleCue()
            if (!isWaitingAtCrosswalk) {
                if (_isDangerMode.value) disableDangerMode()

                if (_isNavigating.value) {
                    updateAudio()
                } else {
                    _relativeBearing.value = 0f
                    audioEngine.setRouteActive(false)
                }
            }
            return
        }

        val currentTime = System.currentTimeMillis()

        for (detection in results) {
            val label = detection.label
            val box = detection.boundingBox
            val heightFraction = box.height() / imgHeight

            if (
                label == "traffic light" &&
                detection.score >= detectionPolicy.minConfidence &&
                heightFraction > detectionPolicy.trafficLightHeightFraction
            ) {
                val colorState = analyzeTrafficLightColor(bitmap, box)

                if (colorState == "RED" && lastTrafficLightState != "RED") {
                    lastTrafficLightState = "RED"
                    isWaitingAtCrosswalk = true
                    audioEngine.setRouteActive(false)
                    audioEngine.clearObstacleCue()
                    disableDangerMode()
                    ttsManager.speak("전방 횡단보도 빨간불입니다. 정지하세요.")
                } else if (colorState == "GREEN" && lastTrafficLightState != "GREEN") {
                    lastTrafficLightState = "GREEN"
                    isWaitingAtCrosswalk = false
                    audioEngine.setRouteActive(_isNavigating.value)
                    ttsManager.speak("초록불입니다. 건너가세요.")
                }
                break
            }
        }

        if (isWaitingAtCrosswalk) return

        val translationMap = mapOf(
            "person" to "사람",
            "car" to "자동차",
            "motorcycle" to "오토바이",
            "bicycle" to "자전거",
            "truck" to "트럭",
            "bus" to "버스",
            "bench" to "장애물",
            "chair" to "장애물"
        )

        val criticalObject = results
            .filter {
                it.label != "traffic light" &&
                        it.label in detectionPolicy.importantLabels &&
                        it.score >= detectionPolicy.minConfidence
            }
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

        if (criticalObject != null) {
            val box = criticalObject.boundingBox
            val label = criticalObject.label.ifEmpty { "물체" }
            val koreanLabel = translationMap[label] ?: label

            val centerX = box.centerX()
            val relativeX = centerX / imgWidth.toFloat()
            val angle = (relativeX - 0.5f) * 90f
            val heightFraction = box.height() / imgHeight.toFloat()

            if (heightFraction > detectionPolicy.nearObjectHeightFraction) {
                audioEngine.setObstacleCue(
                    deg = angle,
                    severity = heightFraction.coerceIn(0.15f, 1f)
                )

                if (!_isDangerMode.value) {
                    enableDangerMode()
                }

                if (currentTime - lastTTSWarningTime > 3000) {
                    ttsManager.speak("전방 5미터 이내 $koreanLabel 주의")
                    lastTTSWarningTime = currentTime
                }
            } else {
                audioEngine.clearObstacleCue()
                if (_isDangerMode.value) disableDangerMode()
                if (_isNavigating.value) updateAudio()
            }
        } else {
            audioEngine.clearObstacleCue()
            if (_isDangerMode.value) disableDangerMode()
            if (_isNavigating.value) updateAudio()
        }
    }

    private fun analyzeTrafficLightColor(bitmap: Bitmap, box: RectF): String {
        val left = box.left.toInt().coerceAtLeast(0)
        val top = box.top.toInt().coerceAtLeast(0)
        val right = box.right.toInt().coerceAtMost(bitmap.width)
        val bottom = box.bottom.toInt().coerceAtMost(bitmap.height)

        if (right <= left || bottom <= top) return "UNKNOWN"

        var redCount = 0
        var greenCount = 0

        val step = 5
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r > 150 && r > g * 1.5 && r > b * 1.5) {
                    redCount++
                } else if (g > 150 && g > r * 1.2 && g > b * 1.2) {
                    greenCount++
                }
            }
        }

        return when {
            redCount > greenCount && redCount > 10 -> "RED"
            greenCount > redCount && greenCount > 10 -> "GREEN"
            else -> "UNKNOWN"
        }
    }

    fun toggleSidewalkStatus() {
        _isOffSidewalk.value = !_isOffSidewalk.value
        if (_isOffSidewalk.value) {
            ttsManager.speak("경고: 차도로 진입했습니다. 안전한 곳으로 이동하세요.")
            enableDangerMode()
        } else {
            disableDangerMode()
        }
    }

    fun updateTargetBearing(bearing: Float) {
        _targetBearing.value = normalizeDegrees(bearing)
        if (!isWaitingAtCrosswalk) {
            updateAudio()
        }
    }

    private fun updateAudio() {
        var diff = _targetBearing.value - _currentHeading.value
        while (diff > 180f) diff -= 360f
        while (diff <= -180f) diff += 360f

        _relativeBearing.value = diff
        audioEngine.setRouteActive(_isNavigating.value && !isWaitingAtCrosswalk)
        audioEngine.setRouteAzimuth(diff)
    }

    fun toggleDanger() {
        if (_isDangerMode.value) {
            disableDangerMode()
        } else {
            audioEngine.setObstacleCue(0f, 1f)
            enableDangerMode()
        }
    }

    private fun enableDangerMode() {
        audioEngine.playDangerSound()
        audioEngine.setNormalVolumeScale(0.25f)
        startDangerFeedback()
        _isDangerMode.value = true
    }

    private fun disableDangerMode() {
        audioEngine.stopDangerSound()
        audioEngine.setNormalVolumeScale(1.0f)
        stopDangerFeedback()
        _isDangerMode.value = false
    }

    private fun startDangerFeedback() {
        dangerJob?.cancel()
        dangerJob = viewModelScope.launch {
            while (isActive) {
                hapticManager.triggerDangerFeedback()
                delay(1500)
            }
        }
    }

    private fun stopDangerFeedback() {
        dangerJob?.cancel()
        dangerJob = null
        hapticManager.triggerNormalFeedback()
    }

    fun loadSampleImage() {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        val bitmap = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.RED }
            canvas.drawRect(20f, 20f, 80f, 80f, paint)
            bmp
        }

        _selectedImage.value = bitmap
        _detectedObjects.value = emptyList()
        isSimulatingVision = false
        audioEngine.setRouteActive(false)
        audioEngine.clearObstacleCue()
        disableDangerMode()
    }

    fun analyzeImage() {
        val bitmap = _selectedImage.value ?: return

        if (!_isModelReady.value) {
            ttsManager.speak("아직 모델 다운로드가 진행중입니다.")
            return
        }

        isSimulatingVision = true
        audioEngine.start()
        audioEngine.setRouteActive(false)

        viewModelScope.launch(Dispatchers.IO) {
            val results = objectDetectorHelper.detect(bitmap)
            withContext(Dispatchers.Main) {
                _detectedObjects.value = results
                processVisionResults(results, bitmap, bitmap.width, bitmap.height)
            }
        }
    }

    fun nudgeYawOffset(deltaDegrees: Float) {
        _yawOffsetDegrees.value = wrap180(_yawOffsetDegrees.value + deltaDegrees)
        persistYawCalibration()
        recalculateHeadingFromLatestYaw(resetSmoothing = false)
    }

    fun toggleYawInversion() {
        _isYawInverted.value = !_isYawInverted.value
        persistYawCalibration()
        recalculateHeadingFromLatestYaw(resetSmoothing = true)
        ttsManager.speak("방위각 반전 ${if (_isYawInverted.value) "켜짐" else "꺼짐"}")
    }

    fun resetYawCalibration() {
        _yawOffsetDegrees.value = 0f
        _isYawInverted.value = false
        persistYawCalibration()
        recalculateHeadingFromLatestYaw(resetSmoothing = true)
        ttsManager.speak("방위각 보정을 초기화했습니다.")
    }

    fun calibrateYawToCurrentTarget() {
        if (esp32YawJob?.isActive != true) {
            ttsManager.speak("안경 자이로가 아직 연결되지 않았습니다.")
            return
        }

        if (!_isNavigating.value) {
            ttsManager.speak("경로 안내 중에만 자동 보정을 사용할 수 있습니다.")
            return
        }

        val baseYaw = getStableBaseYaw() ?: run {
            ttsManager.speak("아직 방위각 데이터가 충분하지 않습니다.")
            return
        }

        val desiredHeading = _targetBearing.value
        _yawOffsetDegrees.value = wrap180(desiredHeading - baseYaw)
        persistYawCalibration()

        _currentHeading.value = correctedHeadingFromBaseYaw(baseYaw)
        updateAudio()
        ttsManager.speak("현재 바라보는 방향을 경로 방향으로 보정했습니다.")
    }

    private fun ingestRawYaw(rawYaw: Float): Float {
        val baseYaw = preprocessRawYawNoOffset(rawYaw)
        val smoothed = smoothCircularDegrees(smoothedBaseYaw, baseYaw, 0.28f)
        smoothedBaseYaw = smoothed
        pushRecentBaseYawSample(smoothed)
        return correctedHeadingFromBaseYaw(smoothed)
    }

    private fun preprocessRawYawNoOffset(rawYaw: Float): Float {
        val normalized = normalizeDegrees(rawYaw)
        return if (_isYawInverted.value) {
            normalizeDegrees(360f - normalized)
        } else {
            normalized
        }
    }

    private fun correctedHeadingFromBaseYaw(baseYaw: Float): Float {
        return normalizeDegrees(baseYaw + _yawOffsetDegrees.value)
    }

    private fun recalculateHeadingFromLatestYaw(resetSmoothing: Boolean) {
        if (resetSmoothing) {
            clearYawSmoothing()
        }

        val raw = _rawYaw.value ?: return
        _currentHeading.value = ingestRawYaw(raw)

        if (!isWaitingAtCrosswalk) {
            updateAudio()
        }
    }

    private fun getStableBaseYaw(): Float? {
        if (recentBaseYawSamples.isNotEmpty()) {
            return circularMeanDegrees(recentBaseYawSamples)
        }

        smoothedBaseYaw?.let { return it }

        val raw = _rawYaw.value ?: return null
        return preprocessRawYawNoOffset(raw)
    }

    private fun pushRecentBaseYawSample(sample: Float) {
        recentBaseYawSamples.addLast(sample)
        while (recentBaseYawSamples.size > calibrationWindowSize) {
            recentBaseYawSamples.removeFirst()
        }
    }

    private fun clearYawSmoothing() {
        smoothedBaseYaw = null
        recentBaseYawSamples.clear()
    }

    private fun persistYawCalibration() {
        yawCalibrationStore.save(
            offsetDegrees = _yawOffsetDegrees.value,
            invertYaw = _isYawInverted.value
        )
    }

    private fun smoothCircularDegrees(current: Float?, target: Float, alpha: Float): Float {
        if (current == null) return target

        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta <= -180f) delta += 360f

        return normalizeDegrees(current + delta * alpha)
    }

    private fun circularMeanDegrees(samples: Collection<Float>): Float {
        var x = 0.0
        var y = 0.0

        for (deg in samples) {
            val rad = Math.toRadians(deg.toDouble())
            x += cos(rad)
            y += sin(rad)
        }

        if (x == 0.0 && y == 0.0) return 0f

        val meanRad = atan2(y, x)
        return normalizeDegrees(Math.toDegrees(meanRad).toFloat())
    }

    private fun wrap180(value: Float): Float {
        var x = value
        while (x > 180f) x -= 360f
        while (x <= -180f) x += 360f
        return x
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
        stopGPSNavigation()
        objectDetectorHelper.clearObjectDetector()
        ttsManager.shutdown()
    }
}
