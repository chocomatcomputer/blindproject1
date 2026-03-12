# Project Plan

시각장애인을 위한 AI 기반 보행 보조 앱. 카메라로 촬영된(또는 샘플) 이미지를 TFLite 객체 인식 모델로 분석하여 장애물의 위치와 거리를 파악하고, 이를 3D 공간 음향으로 변환하여 사용자에게 경고 및 안내를 제공하는 프로토타입.

## Project Brief

# 프로젝트 브리프: BlindNav Demo (AI Vision & Spatial Audio)

이 프로젝트는 시각장애인을 위한 공간 음향 네비게이션 앱의 확장 버전으로, **카메라 기반의 객체 인식(AI Vision)** 기술을 도입하여 장애물의 위치를 파악하고 이를 공간 음향으로 변환하여 사용자에게 전달하는 것을 목표로 합니다. 현재 단계에서는 실제 카메라 연동 대신 **정지 이미지(Sample Image)**를 활용하여 AI 모델의 동작과 오디오 피드백 루프를 검증합니다.

### 주요 기능 (Features)

1.  **AI 객체 인식 (Object Detection)**
    *   TensorFlow Lite (TFLite) 기반의 경량화 모델(EfficientDet Lite0 또는 YOLOv8 Nano)을 사용하여 이미지 내의 주요 객체(사람, 자동차, 의자 등)를 실시간으로 탐지합니다.
    *   감지된 객체의 **종류(Label)**, **위치(Bounding Box)**, **신뢰도(Confidence)** 정보를 추출합니다.

2.  **공간 음향 피드백 변환 (Vision-to-Audio)**
    *   **방향(Direction):** 화면 내 객체의 X 좌표(Center X)를 분석하여 사용자 기준의 상대적 방위각(Azimuth)으로 변환합니다. (예: 화면 왼쪽 끝 = -45도, 오른쪽 끝 = +45도).
    *   **거리(Distance):** 객체의 Bounding Box 크기(면적 또는 높이)를 통해 대략적인 거리를 추정합니다.
    *   **청각적 알림:**
        *   **위험(Danger):** 가까운 거리의 장애물은 긴급 경고음 및 햅틱 피드백을 발생시킵니다.
        *   **주의(Warning):** 먼 거리의 장애물은 부드러운 알림음을 해당 방향에서 재생합니다.

3.  **검증용 시뮬레이션 UI**
    *   사용자가 테스트용 샘플 이미지를 선택하거나 로드할 수 있는 UI를 제공합니다.
    *   이미지 위에 AI가 감지한 객체의 경계 상자(Bounding Box)를 오버레이(Overlay)하여 시각적으로 확인합니다.
    *   "분석(Analyze)" 버튼을 누르면 분석 결과에 따라 즉시 공간 음향이 재생됩니다.

4.  **기존 기능 통합**
    *   기존에 구현된 `AudioEngine`(360도 패닝, Cone of Sound)과 `HapticManager`를 재사용하여 일관된 사용자 경험을 제공합니다.
    *   센서 기반 헤드 트래킹 기능과 연동하여, 가상의 장애물 위치가 고정되도록 처리할 수 있습니다 (심화 단계).

### 기술 스택 (Tech Stack)
*   **AI/ML:** TensorFlow Lite Task Vision, Pre-trained TFLite Model (COCO Dataset).
*   **Android:** CameraX (추후 확장 대비), Canvas/Custom View (Overlay), Bitmap Processing.
*   **Audio:** Existing `AudioEngine` (AudioTrack, Spatial Panning).

## Implementation Steps
**Total Duration:** 52m 57s

### 1: Develop Spatial Audio & Haptics Engine
- **Status:** COMPLETED
- **Updates:** Implemented AudioEngine using AudioTrack (440Hz sine wave loop) with dynamic stereo panning based on angle (-90 to +90). Implemented HapticManager with distinct vibration patterns for normal and danger feedback. Added VIBRATE permission. Verified build success.
- **Acceptance Criteria:**
  - AudioEngine class created using AudioTrack or SoundPool
  - Functionality to play sound with adjustable Left/Right volume (panning)
  - HapticFeedbackManager implemented for vibration patterns
  - Unit tests for logic if applicable
- **Duration:** 5m 5s

### 2: Implement High Contrast UI & Direction Controls
- **Status:** COMPLETED
- **Updates:** Implemented High Contrast UI with MainScreen (Slider/Button), MainViewModel (state + audio/haptic calls), and Hilt DI setup. Verified build success.
- **Acceptance Criteria:**
  - Jetpack Compose screen with High Contrast Theme (Black background, Neon accents)
  - Direction Control UI (Slider or Dial) implemented
  - Danger/Status toggle button implemented
  - The implemented UI must match the design provided in input_images/ui_design_blindnav.jpg
- **Duration:** 12m 34s

### 3: Integrate Logic & Navigation Simulation
- **Status:** COMPLETED
- **Updates:** Connect the UI (ViewModel) to the Audio & Haptic Engine. When the slider moves, update the AudioTrack's stereo volume immediately to simulate 3D direction. When Danger mode is active, play a distinct alert sound and trigger vibration.
- **Acceptance Criteria:**
  - UI controls real-time audio panning
  - Danger mode triggers specific sound and haptics
  - App functions as a simulator: changing direction changes sound balance
- **Duration:** 2m 55s

### 4: Run and Verify (Accessibility & Stability)
- **Status:** COMPLETED
- **Updates:** Verified app with Critic Agent. Identified accessibility issues (missing contentDescription) and visual stability issues. Fixed them in a refinement loop. App builds and runs.
- **Acceptance Criteria:**
  - App runs without crashing
  - TalkBack labels are present on all controls
  - Edge-to-edge display implemented
  - Build passes
- **Duration:** 9m 27s

### 5: 센서 연동 헤드 트래킹 및 360 오디오 구현
- **Status:** COMPLETED
- **Updates:** SensorManager를 통해 기기의 방위각(Azimuth)을 실시간으로 수집하고, 목표 방향과의 차이를 계산하여 AudioEngine에 전달합니다. AudioEngine을 개선하여 좌우 패닝 곡선을 가파르게 조정하고, 후방(Back) 180도 영역에 대해서는 볼륨 감쇠 및 Low-pass 효과(구현 가능 시) 또는 Pitch 조절을 통해 전방과 구분감을 줍니다. UI를 수정하여 슬라이더로 목표 절대 방위각(0~360도)을 설정하게 하고, 현재 기기의 방향을 텍스트/그래픽으로 표시합니다.
- **Acceptance Criteria:**
  - 기기 회전 시 소리의 상대적 방향이 변경됨 (Head Tracking 동작)
  - 전방(Front)과 후방(Back)의 청각적 구분(볼륨/음색) 구현
  - 슬라이더로 목표 방위각 설정 가능
  - The implemented UI must match the design provided in input_images/ui_design_blindnav.jpg
- **Duration:** 2m 57s

### 6: 최종 실행 및 안정성 검증
- **Status:** COMPLETED
- **Updates:** 센서 권한 획득 및 라이프사이클 처리(onPause 시 센서 해제 등)가 올바른지 확인합니다. 앱 전반의 안정성을 테스트하고, TalkBack이 켜진 상태에서도 UI 컨트롤이 가능한지 점검합니다.
- **Acceptance Criteria:**
  - 앱 실행 시 충돌 없음 (Stability Verified)
  - TalkBack 접근성 라벨링 적용 확인
  - 빌드 성공 및 모든 테스트 통과
- **Duration:** 3m 14s

### 7: 오디오 비프음 변경 및 방향성 필터(Cone of Sound) 구현
- **Status:** COMPLETED
- **Updates:** AudioEngine을 수정하여 지속음 대신 '뚜... 뚜...' 하는 간헐적 비프음을 재생하도록 변경합니다. 목표 방향을 중심으로 ±20도 범위 내에서만 소리가 들리도록 'Cone of Sound' 로직을 적용하고, 범위를 벗어나면 소리를 음소거(Mute)하여 사용자가 소리를 찾아 몸을 돌리도록 유도합니다.
- **Acceptance Criteria:**
  - 지속음이 아닌 간헐적 비프음(Intermittent Beep) 재생
  - 목표 방향 ±20도 내에서만 소리 들림 (Directional Gating)
  - 범위 이탈 시 소리 차단 확인
- **Duration:** 2m

### 8: 사용자 안내 추가 및 최종 검증
- **Status:** COMPLETED
- **Updates:** 헤드 트래킹이 폰의 센서를 기준으로 함을 알리는 안내 문구(Toast 또는 UI 텍스트)를 추가합니다. 변경된 오디오 로직이 시각장애인 보행 시나리오에 적합한지 검증하고, 앱의 안정성을 최종 확인합니다.
- **Acceptance Criteria:**
  - 폰의 방향이 기준임을 알리는 안내 제공
  - 앱 실행 시 충돌 없음 및 TalkBack 호환 확인
  - 최종 빌드 성공
- **Duration:** 1m 51s

### 9: TFLite 객체 인식 모델 연동 및 헬퍼 클래스 구현
- **Status:** COMPLETED
- **Updates:** build.gradle에 TensorFlow Lite Task Vision 라이브러리를 추가하고, 사전 학습된 efficientdet_lite0.tflite 모델을 assets 폴더에 배치합니다. 비트맵 이미지를 입력받아 객체(Label, BoundingBox)를 탐지하는 ObjectDetectorHelper 클래스를 구현합니다.
- **Acceptance Criteria:**
  - TFLite 의존성 추가 및 모델 파일 로드 성공
  - ObjectDetectorHelper 클래스 구현
  - 비트맵 입력 시 객체 리스트 반환 확인
- **Duration:** 9m 38s

### 10: 시각-청각 변환 로직 및 시뮬레이션 UI 구현
- **Status:** COMPLETED
- **Updates:** 샘플 이미지를 표시하고 '분석' 버튼을 누르면 객체 인식 결과(박스)를 오버레이하는 UI를 만듭니다. 감지된 객체의 X좌표를 방위각으로, 크기를 거리로 변환하여 AudioEngine을 통해 공간 음향(위험/주의)을 재생하는 로직을 연결합니다. 최종적으로 앱이 충돌 없이 동작하는지 검증합니다.
- **Acceptance Criteria:**
  - 샘플 이미지 위 Bounding Box 표시
  - 객체 위치에 따른 올바른 방향(Azimuth)의 소리 재생
  - 객체 크기(거리)에 따른 경고음 차별화
  - 통합 테스트 및 앱 안정성 확인
  - The implemented UI must match the design provided in input_images/ui_design_blindnav.jpg
- **Duration:** 3m 16s

