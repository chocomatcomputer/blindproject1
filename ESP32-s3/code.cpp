#include "esp_camera.h"
#include <Wire.h>
#include <MPU9250.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoOTA.h>
#include "secrets.h"

// ==========================================================
// DFRobot FireBeetle 2 ESP32-S3 전용 카메라 핀 설정
// ==========================================================
#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     45
#define SIOD_GPIO_NUM     1
#define SIOC_GPIO_NUM     2
#define Y9_GPIO_NUM       48
#define Y8_GPIO_NUM       46
#define Y7_GPIO_NUM       8
#define Y6_GPIO_NUM       7
#define Y5_GPIO_NUM       4
#define Y4_GPIO_NUM       41
#define Y3_GPIO_NUM       40
#define Y2_GPIO_NUM       39
#define VSYNC_GPIO_NUM    6
#define HREF_GPIO_NUM     42
#define PCLK_GPIO_NUM     5

MPU9250 mpu;
WebServer dataServer(80);     // 자이로 데이터용 웹 서버 (기본 포트)
WiFiServer streamServer(81);  // 카메라 스트리밍용 서버 (포트 81)

// [핸들러] 자이로 데이터 송신 (JSON 형식)
void handleData() {
    String json = "{\"yaw\": " + String(mpu.getYaw(), 2) + "}";

    dataServer.sendHeader("Access-Control-Allow-Origin", "*");
    dataServer.send(200, "application/json", json);
}

// [핸들러] 단일 JPEG 캡처 (앱의 /capture fallback 지원)
void handleCapture() {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
        dataServer.send(500, "text/plain", "camera capture failed");
        return;
    }

    dataServer.sendHeader("Access-Control-Allow-Origin", "*");
    dataServer.sendHeader("Content-Type", "image/jpeg");
    dataServer.sendHeader("Content-Length", String(fb->len));
    dataServer.send(200);

    WiFiClient client = dataServer.client();
    client.write(fb->buf, fb->len);
    esp_camera_fb_return(fb);
}

void connectWifi() {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    Serial.print("WiFi 연결 중");

    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }

    Serial.println("\nWiFi 연결 성공!");
    Serial.print("할당된 IP 주소: ");
    Serial.println(WiFi.localIP());
}

void setupOta() {
    ArduinoOTA.setHostname(OTA_HOSTNAME);
    ArduinoOTA.setPassword(OTA_PASSWORD);

    ArduinoOTA.onStart([]() {
        Serial.println("OTA 시작");
    });

    ArduinoOTA.onEnd([]() {
        Serial.println("\nOTA 완료");
    });

    ArduinoOTA.onProgress([](unsigned int progress, unsigned int total) {
        Serial.printf("OTA 진행률: %u%%\r", (progress * 100U) / total);
    });

    ArduinoOTA.onError([](ota_error_t error) {
        Serial.printf("\nOTA 오류[%u]\n", error);
    });

    ArduinoOTA.begin();
    Serial.print("OTA 준비 완료: ");
    Serial.println(OTA_HOSTNAME);
}

// [FreeRTOS 태스크] 카메라 스트리밍 전담 (포트 81)
void streamTask(void *pvParameters) {
    streamServer.begin();
    while (true) {
        WiFiClient client = streamServer.available();
        if (client) {
            String response = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n";
            client.print(response);

            while (client.connected()) {
                camera_fb_t *fb = esp_camera_fb_get();
                if (!fb) {
                    vTaskDelay(10 / portTICK_PERIOD_MS);
                    continue;
                }

                client.print("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + String(fb->len) + "\r\n\r\n");
                client.write(fb->buf, fb->len);
                client.print("\r\n");
                esp_camera_fb_return(fb);
                
                vTaskDelay(50 / portTICK_PERIOD_MS); // 약 20fps 이하로 제한해 대역폭/발열 완화
            }
            client.stop();
        }
        vTaskDelay(10 / portTICK_PERIOD_MS);
    }
}

void setup() {
    Serial.begin(115200);
    while(!Serial);

    Serial.println("\n--- 스마트 안경 통합 서버 초기화 ---");

    // 1. MPU9250 센서 초기화 (안전한 하드웨어 핀 3, 38 명시적 지정)
    Wire.begin(3, 38);
    delay(1000);
    
    MPU9250Setting setting;
    setting.accel_fs_sel = ACCEL_FS_SEL::A16G;
    setting.gyro_fs_sel = GYRO_FS_SEL::G2000DPS;
    setting.mag_output_bits = MAG_OUTPUT_BITS::M16BITS;
    setting.fifo_sample_rate = FIFO_SAMPLE_RATE::SMPL_200HZ;
    setting.gyro_fchoice = 0x03;
    setting.gyro_dlpf_cfg = GYRO_DLPF_CFG::DLPF_41HZ;
    setting.accel_fchoice = 0x01;
    setting.accel_dlpf_cfg = ACCEL_DLPF_CFG::DLPF_45HZ;

    if (!mpu.setup(0x68, setting)) { 
        Serial.println("MPU9250 연결 실패. 배선을 다시 확인하십시오.");
    } else {
        Serial.println("MPU9250 연결 성공!");
    }

    // 2. 카메라 초기화
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM; 
    config.pin_sccb_scl = SIOC_GPIO_NUM; 
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.frame_size = FRAMESIZE_QVGA;
    config.pixel_format = PIXFORMAT_JPEG;  
    config.grab_mode = CAMERA_GRAB_LATEST;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.jpeg_quality = 10;
    config.fb_count = 2;

    
    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("카메라 초기화 실패: 0x%x\n", err);
    } else {
        Serial.println("카메라 초기화 성공!");
    }

    // 3. WiFi 연결
    connectWifi();

    // 4. OTA 활성화
    setupOta();

    // 5. 데이터 서버 구동
    dataServer.on("/data", handleData);
    dataServer.on("/capture", handleCapture);
    dataServer.begin();

    // 6. 카메라 스트리밍 태스크 생성 (멀티 코어 활용)
    xTaskCreatePinnedToCore(streamTask, "Stream Task", 4096, NULL, 1, NULL, 0);
    
    Serial.println("통합 서버 구동 완료.");
    Serial.println("자이로 데이터 확인: http://" + WiFi.localIP().toString() + "/data");
    Serial.println("JPEG 캡처 확인: http://" + WiFi.localIP().toString() + "/capture");
    Serial.println("카메라 영상 확인: http://" + WiFi.localIP().toString() + ":81/stream 또는 http://" + WiFi.localIP().toString() + ":81");
    Serial.println("OTA 호스트명: " + String(OTA_HOSTNAME));
}

void loop() {
    // 클라이언트의 /data 요청 처리
    dataServer.handleClient();

    // 센서 값 지속적 업데이트
    mpu.update();

    // OTA 요청 처리
    ArduinoOTA.handle();

    // 단순 재연결 로직
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("WiFi 연결 끊김 - 재연결 시도");
        WiFi.disconnect();
        connectWifi();
    }
}
