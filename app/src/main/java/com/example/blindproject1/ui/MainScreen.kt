package com.example.blindproject1.ui

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.blindproject1.sensors.CameraAnalyzer
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val targetBearing by viewModel.targetBearing.collectAsState()
    val currentHeading by viewModel.currentHeading.collectAsState()
    val relativeBearing by viewModel.relativeBearing.collectAsState()
    val isDangerMode by viewModel.isDangerMode.collectAsState()
    val isOffSidewalk by viewModel.isOffSidewalk.collectAsState()
    val selectedImage by viewModel.selectedImage.collectAsState()
    val detectedObjects by viewModel.detectedObjects.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    
    val isHighContrast by viewModel.isHighContrastMode.collectAsState()
    val aiSensitivity by viewModel.aiSensitivity.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    
    var isCameraActive by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var selectedMapLocation by remember { mutableStateOf<LatLng?>(null) }
    
    var currentTimeString by remember { mutableStateOf("") }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val allPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(Unit) {
        if (!allPermissions.allPermissionsGranted) {
            allPermissions.launchMultiplePermissionRequest()
        }
        while(true) {
            currentTimeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startMonitoring()
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.stopMonitoring()
                    isCameraActive = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val backgroundColor = if (isDangerMode || isOffSidewalk) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.2f).compositeOver(MaterialTheme.colorScheme.background)
    } else {
        MaterialTheme.colorScheme.background
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Text(
                    "설정 (Settings)", 
                    style = MaterialTheme.typography.headlineMedium, 
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                
                // Dark Mode / High Contrast Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("고대비 모드 (High Contrast)", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = isHighContrast,
                        onCheckedChange = { viewModel.toggleHighContrastMode(it) }
                    )
                }
                
                // AI Sensitivity Slider
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "AI 민감도 (기본: 30%)", 
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "높을수록 멀리서도 인식하지만 오류가 늘어날 수 있습니다.", 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = aiSensitivity,
                        onValueChange = { viewModel.setAiSensitivity(it) },
                        valueRange = 0.1f..0.7f,
                        steps = 5,
                        modifier = Modifier.semantics { contentDescription = "AI 민감도 조절 슬라이더" }
                    )
                    Text("현재 값: ${(aiSensitivity * 100).toInt()}%", textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                
                Spacer(Modifier.weight(1f))
                Text(
                    "버전 1.0 (Demo)", 
                    modifier = Modifier.padding(16.dp), 
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("BlindNav", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(currentTimeString, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "메뉴 열기")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = backgroundColor
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                // Voice Command Button
                Button(
                    onClick = {
                        if (allPermissions.allPermissionsGranted) {
                            viewModel.startVoiceCommand()
                        } else {
                            allPermissions.launchMultiplePermissionRequest()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(bottom = 16.dp)
                        .semantics { contentDescription = "음성으로 목적지 검색 버튼" }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "음성 검색",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "(화면을 크게 터치하세요)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { showMap = !showMap },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showMap) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                            contentColor = if (showMap) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).padding(end = 4.dp).height(60.dp)
                    ) {
                        Text(if (showMap) "지도 닫기" else "수동 선택", textAlign = TextAlign.Center)
                    }

                    Button(
                        onClick = { isCameraActive = !isCameraActive },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCameraActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
                            contentColor = if (isCameraActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).padding(start = 4.dp).height(60.dp)
                    ) {
                        Text(if (isCameraActive) "카메라 종료" else "AI 렌즈 켜기", textAlign = TextAlign.Center)
                    }
                }
                
                if (isNavigating) {
                    Button(
                        onClick = { viewModel.stopGPSNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(60.dp).padding(bottom = 16.dp)
                    ) {
                        Text("안내 중지", style = MaterialTheme.typography.titleLarge)
                    }
                }

                if (showMap && allPermissions.allPermissionsGranted) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "지도에서 목적지를 터치하세요",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            val startLatLng = currentLocation?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(37.5665, 126.9780)
                            val cameraPositionState = rememberCameraPositionState {
                                position = CameraPosition.fromLatLngZoom(startLatLng, 15f)
                            }

                            Box(modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(8.dp))) {
                                GoogleMap(
                                    modifier = Modifier.fillMaxSize(),
                                    cameraPositionState = cameraPositionState,
                                    onMapClick = { latLng -> selectedMapLocation = latLng }
                                ) {
                                    selectedMapLocation?.let {
                                        Marker(state = MarkerState(position = it), title = "목적지")
                                    }
                                }
                            }
                            
                            Button(
                                onClick = { 
                                    selectedMapLocation?.let { 
                                        viewModel.navigateToMapSelection(it) 
                                        showMap = false
                                    }
                                },
                                enabled = selectedMapLocation != null,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("이 위치로 안내 시작")
                            }
                        }
                    }
                }

                if (isCameraActive) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                         Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "AI 렌즈 (실시간 분석 중)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                if (allPermissions.allPermissionsGranted) {
                                    AndroidView(
                                        factory = { ctx ->
                                            val previewView = PreviewView(ctx)
                                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                            
                                            cameraProviderFuture.addListener({
                                                val cameraProvider = cameraProviderFuture.get()
                                                val preview = Preview.Builder().build().also {
                                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                                }
                                                
                                                val imageAnalyzer = ImageAnalysis.Builder()
                                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                    .build()
                                                    .also {
                                                        it.setAnalyzer(
                                                            Executors.newSingleThreadExecutor(),
                                                            CameraAnalyzer(viewModel.detectorHelper) { results, bitmap ->
                                                                viewModel.processLiveCameraFrame(results, bitmap)
                                                            }
                                                        )
                                                    }
                                                    
                                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                                try {
                                                    cameraProvider.unbindAll()
                                                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                                                } catch (exc: Exception) { }
                                            }, ContextCompat.getMainExecutor(ctx))
                                            previewView
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    if (selectedImage != null) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val bitmap = selectedImage!!
                                            val scaleX = size.width / bitmap.width
                                            val scaleY = size.height / bitmap.height
                                            
                                            detectedObjects.forEach { detection ->
                                                val box = detection.boundingBox
                                                val label = detection.label
                                                val isTrafficLight = label == "traffic light"
                                                val boxColor = if (isTrafficLight) Color.Yellow else Color.Green
                                                
                                                drawRect(
                                                    color = boxColor,
                                                    topLeft = Offset(box.left * scaleX, box.top * scaleY),
                                                    size = Size(box.width() * scaleX, box.height() * scaleY),
                                                    style = Stroke(width = 6f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Direction Display
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .border(4.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                        .background(Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${targetBearing.roundToInt()}°",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "TARGET",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                
                val directionText = when {
                    abs(relativeBearing) < 20 -> "정방향 (ON TRACK)"
                    abs(relativeBearing) > 135 -> "후방 (BACK)"
                    relativeBearing < 0 -> "좌측 (LEFT)"
                    else -> "우측 (RIGHT)"
                }
                val hintColor = if (abs(relativeBearing) < 20) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                
                Text(
                    text = directionText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = hintColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
