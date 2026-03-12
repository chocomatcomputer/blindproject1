package com.example.blindproject1.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.location.Address
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
import com.example.blindproject1.ml.ModelDownloader
import com.example.blindproject1.ml.ObjectDetectorHelper
import com.example.blindproject1.network.TMapRepository
import com.example.blindproject1.sensors.LocationHelper
import com.example.blindproject1.sensors.OrientationManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

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
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Settings State
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

    private val _detectedObjects = MutableStateFlow<List<Detection>>(emptyList())
    val detectedObjects: StateFlow<List<Detection>> = _detectedObjects.asStateFlow()

    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage.asStateFlow()
    
    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    val detectorHelper = objectDetectorHelper

    private var dangerJob: Job? = null
    private var monitoringJob: Job? = null
    private var locationJob: Job? = null
    
    private var routeWaypoints: List<Location> = emptyList()
    private var currentWaypointIndex = 0
    private var destinationNameForReroute = ""
    private var destinationLatForReroute = 0.0
    private var destinationLonForReroute = 0.0
    
    private var isSimulatingVision = false
    private var isWaitingAtCrosswalk = false
    private var lastTrafficLightState = ""
    private var lastTTSWarningTime = 0L

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
        val mode = if(enabled) "켜짐" else "꺼짐"
        ttsManager.speak("고대비 모드 $mode")
    }
    
    fun setAiSensitivity(sensitivity: Float) {
        _aiSensitivity.value = sensitivity
        objectDetectorHelper.setThreshold(sensitivity)
    }

    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        audioEngine.start()
        isSimulatingVision = false
        
        monitoringJob = viewModelScope.launch {
            orientationManager.azimuthFlow.collectLatest { azimuth ->
                if (!isSimulatingVision) {
                    _currentHeading.value = azimuth
                    if (!isWaitingAtCrosswalk) {
                        updateAudio()
                    }
                }
            }
        }
    }

    fun navigateToMapSelection(latLng: LatLng) {
        startRoutingToDestination(latLng.latitude, latLng.longitude, "지도 선택 위치", "지도에서 선택한 위치")
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
                        val detailAddress = destAddress.getAddressLine(0)?.replace("대한민국", "")?.trim() ?: "선택된 위치"
                        
                        startRoutingToDestination(destAddress.latitude, destAddress.longitude, recognizedText, detailAddress)
                    } else {
                        withContext(Dispatchers.Main) { ttsManager.speak("목적지 위치를 찾지 못했습니다. 다시 말씀해주세요.") }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Geocoding error", e)
                    withContext(Dispatchers.Main) { ttsManager.speak("경로 탐색 중 오류가 발생했습니다.") }
                }
            }
        }
    }
    
    private fun startRoutingToDestination(destLat: Double, destLon: Double, destinationName: String, detailAddress: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentLoc = _currentLocation.value ?: run {
                withContext(Dispatchers.Main) { ttsManager.speak("현재 위치를 찾을 수 없어 안내를 시작할 수 없습니다.") }
                return@launch
            }
            
            val waypoints = tMapRepository.getPedestrianRoute(
                currentLoc, destLat, destLon, "현재위치", destinationName
            )
            
            withContext(Dispatchers.Main) {
                if (waypoints.isNotEmpty()) {
                    routeWaypoints = waypoints
                    currentWaypointIndex = if (waypoints.size > 1) 1 else 0
                    _isNavigating.value = true
                    
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
                        val normalizedBearing = (bearing + 360) % 360
                        _targetBearing.value = normalizedBearing
                        
                        val distanceToTarget = location.distanceTo(targetWaypoint)
                        
                        if (distanceToTarget < 15.0) {
                            currentWaypointIndex++
                            if (currentWaypointIndex >= routeWaypoints.size) {
                                ttsManager.speak("목적지에 도착했습니다. 안내를 종료합니다.")
                                stopGPSNavigation()
                                return@collectLatest
                            }
                        }
                        
                        if (!isSimulatingVision && !isWaitingAtCrosswalk) updateAudio()
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
        ttsManager.speak("안내를 중지합니다.")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        audioEngine.stop()
        stopDangerFeedback()
    }
    
    fun processLiveCameraFrame(results: List<Detection>, bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Main) {
            _detectedObjects.value = results
            _selectedImage.value = bitmap
            processVisionResults(results, bitmap, bitmap.width, bitmap.height)
        }
    }
    
    private fun processVisionResults(results: List<Detection>, bitmap: Bitmap, imgWidth: Int, imgHeight: Int) {
        if (results.isEmpty()) {
            if (!isWaitingAtCrosswalk) {
                disableDangerMode()
                if (!_isNavigating.value) {
                    _relativeBearing.value = 0f
                    audioEngine.updateDirection(0f)
                } else {
                    updateAudio() 
                }
            }
            return
        }

        var trafficLightDetected = false
        val currentTime = System.currentTimeMillis()

        for (detection in results) {
            val label = detection.categories.firstOrNull()?.label ?: continue
            val box = detection.boundingBox
            val heightFraction = box.height() / imgHeight

            if (label == "traffic light" && heightFraction > 0.1) { 
                trafficLightDetected = true
                val colorState = analyzeTrafficLightColor(bitmap, box)
                
                if (colorState == "RED" && lastTrafficLightState != "RED") {
                    lastTrafficLightState = "RED"
                    isWaitingAtCrosswalk = true
                    ttsManager.speak("전방 횡단보도 빨간불입니다. 정지하세요.")
                    audioEngine.stop() 
                } else if (colorState == "GREEN" && lastTrafficLightState != "GREEN") {
                    lastTrafficLightState = "GREEN"
                    isWaitingAtCrosswalk = false
                    ttsManager.speak("초록불입니다. 건너가세요.")
                    audioEngine.start() 
                }
                break 
            }
        }

        if (isWaitingAtCrosswalk) return

        val importantLabels = listOf("person", "car", "motorcycle", "bicycle", "truck", "bus")
        val translationMap = mapOf(
            "person" to "사람", "car" to "자동차", "motorcycle" to "오토바이",
            "bicycle" to "자전거", "truck" to "트럭", "bus" to "버스"
        )
        
        val criticalObject = results
            .filter { importantLabels.contains(it.categories.firstOrNull()?.label) }
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

        if (criticalObject != null) {
            val box = criticalObject.boundingBox
            val label = criticalObject.categories.firstOrNull()?.label ?: "물체"
            val koreanLabel = translationMap[label] ?: label
            
            val centerX = box.centerX()
            val relativeX = (centerX / imgWidth)
            val angle = (relativeX - 0.5f) * 90 
            
            val heightFraction = box.height() / imgHeight
            
            if (heightFraction > 0.15) { 
                _relativeBearing.value = angle
                audioEngine.updateDirection(angle)
                if (!_isDangerMode.value) {
                    enableDangerMode()
                }
                
                if (currentTime - lastTTSWarningTime > 3000) {
                    ttsManager.speak("전방 5미터 이내 $koreanLabel 주의")
                    lastTTSWarningTime = currentTime
                }
            } else {
                if (_isDangerMode.value) disableDangerMode()
                if (_isNavigating.value) updateAudio()
            }
        } else {
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
                }
                else if (g > 150 && g > r * 1.2 && g > b * 1.2) {
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
        _targetBearing.value = bearing
        if (!isSimulatingVision && !isWaitingAtCrosswalk) {
            updateAudio()
        }
    }
    
    private fun updateAudio() {
        var diff = _targetBearing.value - _currentHeading.value
        while (diff > 180) diff -= 360
        while (diff <= -180) diff += 360
        
        _relativeBearing.value = diff
        audioEngine.updateDirection(diff)
    }

    fun toggleDanger() {
        val newDangerState = !_isDangerMode.value
        _isDangerMode.value = newDangerState
        
        if (newDangerState) {
            enableDangerMode()
        } else {
            disableDangerMode()
        }
    }

    private fun enableDangerMode() {
        audioEngine.playDangerSound()
        audioEngine.setNormalVolumeScale(0.2f)
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
            while (true) {
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

        viewModelScope.launch(Dispatchers.IO) {
            val results = objectDetectorHelper.detect(bitmap)
            withContext(Dispatchers.Main) {
                _detectedObjects.value = results
                processVisionResults(results, bitmap, bitmap.width, bitmap.height)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
        stopGPSNavigation()
        objectDetectorHelper.clearObjectDetector()
        ttsManager.shutdown()
    }
}