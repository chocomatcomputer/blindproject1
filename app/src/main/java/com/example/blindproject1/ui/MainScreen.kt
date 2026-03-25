package com.example.blindproject1.ui

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val isEsp32VisionActive by viewModel.isEsp32VisionActive.collectAsState()
    val esp32CameraStreamUrl by viewModel.esp32CameraStreamUrl.collectAsState()
    val esp32YawDataUrl by viewModel.esp32YawDataUrl.collectAsState()
    val rawYaw by viewModel.rawYaw.collectAsState()
    val yawOffsetDegrees by viewModel.yawOffsetDegrees.collectAsState()
    val isYawInverted by viewModel.isYawInverted.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current

    var showMap by remember { mutableStateOf(false) }
    var selectedMapLocation by remember { mutableStateOf<LatLng?>(null) }
    var currentTimeString by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val allPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(Unit) {
        if (!allPermissions.allPermissionsGranted) {
            allPermissions.launchMultiplePermissionRequest()
        }

        while (true) {
            currentTimeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startMonitoring()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopMonitoring()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val backgroundColor = if (isDangerMode || isOffSidewalk) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
            .compositeOver(MaterialTheme.colorScheme.background)
    } else {
        MaterialTheme.colorScheme.background
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "설정 (Settings)",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "고대비 모드 (High Contrast)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = isHighContrast,
                        onCheckedChange = { viewModel.toggleHighContrastMode(it) }
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AI 민감도 (기본: 30%)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "높을수록 멀리서도 인식하지만 오류가 늘어날 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = aiSensitivity,
                        onValueChange = { viewModel.setAiSensitivity(it) },
                        valueRange = 0.1f..0.7f,
                        steps = 5,
                        modifier = Modifier.semantics {
                            contentDescription = "AI 민감도 조절 슬라이더"
                        }
                    )
                    Text(
                        text = "현재 값: ${(aiSensitivity * 100).toInt()}%",
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "안경 자이로 보정",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = "RAW yaw: ${rawYaw?.roundToInt()?.let { "$it°" } ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        text = "보정 후 heading: ${currentHeading.roundToInt()}°",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Offset: ${yawOffsetDegrees.roundToInt()}°",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.calibrateYawToCurrentTarget() },
                            enabled = isEsp32VisionActive && isNavigating,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("현재 방향 = 경로 방향")
                        }

                        Button(
                            onClick = { viewModel.resetYawCalibration() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("초기화")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.nudgeYawOffset(-5f) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("-5°")
                        }

                        Button(
                            onClick = { viewModel.nudgeYawOffset(-1f) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("-1°")
                        }

                        Button(
                            onClick = { viewModel.nudgeYawOffset(+1f) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+1°")
                        }

                        Button(
                            onClick = { viewModel.nudgeYawOffset(+5f) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("+5°")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "yaw 반전",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = isYawInverted,
                            onCheckedChange = { viewModel.toggleYawInversion() }
                        )
                    }

                    Text(
                        text = "카메라: $esp32CameraStreamUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    Text(
                        text = "자이로: $esp32YawDataUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Text(
                        text = "팁: 경로 안내 중 정면을 보고 있을 때 ‘현재 방향 = 경로 방향’을 누르면 자동 보정됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "버전 1.0 (Demo)",
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
                            Text(
                                text = "BlindNav",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = currentTimeString,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "메뉴 열기"
                            )
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
                        .semantics {
                            contentDescription = "음성으로 목적지 검색 버튼"
                        }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { showMap = !showMap },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showMap) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (showMap) {
                                MaterialTheme.colorScheme.onSecondary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp)
                            .height(60.dp)
                    ) {
                        Text(
                            text = if (showMap) "지도 닫기" else "수동 선택",
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = {
                            if (isEsp32VisionActive) {
                                viewModel.stopEsp32Vision()
                            } else {
                                viewModel.startEsp32Vision()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEsp32VisionActive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (isEsp32VisionActive) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .height(60.dp)
                    ) {
                        Text(
                            text = if (isEsp32VisionActive) "AI 렌즈 종료" else "AI 렌즈 켜기",
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (isNavigating) {
                    Button(
                        onClick = { viewModel.stopGPSNavigation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "안내 중지",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                if (showMap && allPermissions.allPermissionsGranted) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "지도에서 목적지를 터치하세요",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            val startLatLng = currentLocation?.let {
                                LatLng(it.latitude, it.longitude)
                            } ?: LatLng(37.5665, 126.9780)

                            val cameraPositionState = rememberCameraPositionState {
                                position = CameraPosition.fromLatLngZoom(startLatLng, 15f)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(250.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                GoogleMap(
                                    modifier = Modifier.fillMaxSize(),
                                    cameraPositionState = cameraPositionState,
                                    onMapClick = { latLng -> selectedMapLocation = latLng }
                                ) {
                                    selectedMapLocation?.let {
                                        Marker(
                                            state = MarkerState(position = it),
                                            title = "목적지"
                                        )
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text("이 위치로 안내 시작")
                            }
                        }
                    }
                }

                if (isEsp32VisionActive) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "AI 렌즈 (ESP32 실시간 분석 중)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Text(
                                text = "카메라 스트림: $esp32CameraStreamUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            Text(
                                text = "yaw 데이터: $esp32YawDataUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                val bitmap = selectedImage

                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "ESP32 camera preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.FillBounds
                                    )

                                    Canvas(modifier = Modifier.fillMaxSize()) {
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
                                } else {
                                    Text(
                                        text = "ESP32 MJPEG 스트림 수신 대기 중...",
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

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

                val hintColor = if (abs(relativeBearing) < 20) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                }

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