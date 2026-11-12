/*
============================================================
COOKIE ROBOT
ESP32-CAM LOCAL STREAM SERVER
============================================================

FEATURES
============================================================

- Connects to saved WiFi networks
- Stable MJPEG Streaming
- Sends MJPEG stream to laptop for human detection
- OpenCV Compatible
- YOLOv8 Compatible
- Browser Compatible
- Professional Web UI

============================================================
BOARD SETTINGS
============================================================

BOARD:
AI Thinker ESP32-CAM

PSRAM:
Enabled

Partition Scheme:
Huge APP

Flash Frequency:
80MHz

CPU Frequency:
240MHz

============================================================
STREAM URL
============================================================

Browser on same WiFi:
http://CAMERA_LOCAL_IP

OpenCV on same WiFi:
http://CAMERA_LOCAL_IP/stream

COOKIE app:
Shows the laptop-processed detection feed uploaded by human_detection.py

============================================================
*/

#include "esp_camera.h"
#include <WiFi.h>
#include <WiFiMulti.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include "esp_http_server.h"

// ============================================================
// WIFI
// ============================================================

struct WiFiCredential
{
  const char* ssid;
  const char* password;
};

WiFiCredential WIFI_NETWORKS[] = {
  {"Sagnick", "Routh@2002"},
  {"Sagnick's iPhone", "Routh2002"},
  {"TP_6653", "123456@#"},
  {"YOUR_SCHOOL_OR_OTHER_WIFI", "YOUR_OTHER_PASSWORD"}
};

const IPAddress CAMERA_STATIC_IP(192, 168, 1, 50);
const IPAddress CAMERA_GATEWAY(192, 168, 1, 1);
const IPAddress CAMERA_SUBNET(255, 255, 255, 0);
const IPAddress CAMERA_DNS(8, 8, 8, 8);
const bool ENABLE_STATIC_IP = false;

WiFiMulti wifiMulti;

// ============================================================
// OPTIONAL DIRECT CLOUD SNAPSHOT UPLOAD
// ============================================================

const char* CLOUD_CAMERA_UPDATE_URL = "https://cookie-robot-cloud.vercel.app/camera/update";
const char* CLOUD_CAMERA_SOURCE_URL = "https://cookie-robot-cloud.vercel.app/camera/source";
const char* CLOUD_API_KEY = "";
const unsigned long CLOUD_CAMERA_UPLOAD_INTERVAL_MS = 2000;
const unsigned long WIFI_RECONNECT_INTERVAL_MS = 10000;
const bool ENABLE_DIRECT_CLOUD_UPLOAD = false;
unsigned long lastCloudCameraUpload = 0;
unsigned long lastWiFiReconnectAttempt = 0;
String lastPublishedIp = "";

// ============================================================
// CAMERA MODEL
// AI THINKER ESP32-CAM
// ============================================================

#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0

#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27

#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36

#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5

#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// ============================================================
// HTTP SERVER
// ============================================================

httpd_handle_t camera_httpd = NULL;

// ============================================================
// WIFI HELPERS
// ============================================================

void setupCloudWiFi()
{
  int networkCount = sizeof(WIFI_NETWORKS) / sizeof(WIFI_NETWORKS[0]);

  for (int i = 0; i < networkCount; i++)
  {
    if (strlen(WIFI_NETWORKS[i].ssid) > 0)
    {
      wifiMulti.addAP(
        WIFI_NETWORKS[i].ssid,
        WIFI_NETWORKS[i].password
      );
    }
  }

  if (ENABLE_STATIC_IP && !WiFi.config(CAMERA_STATIC_IP, CAMERA_GATEWAY, CAMERA_SUBNET, CAMERA_DNS))
  {
    Serial.println("Failed to set static IP");
  }

  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.setSleep(false);
}

void connectToCloudWiFi()
{
  Serial.print("Connecting to saved WiFi networks");

  unsigned long start = millis();

  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000)
  {
    wifiMulti.run();
    Serial.print(".");
    delay(500);
  }

  Serial.println();

  if (WiFi.status() == WL_CONNECTED)
  {
    Serial.print("Connected SSID: ");
    Serial.println(WiFi.SSID());
    Serial.print("Camera IP: ");
    Serial.println(WiFi.localIP());
    publishCameraSourceToCloud();
  }
  else
  {
    Serial.println("WiFi connection failed");
  }
}

void maintainCloudWiFi()
{
  if (WiFi.status() == WL_CONNECTED)
  {
    String currentIp = WiFi.localIP().toString();
    if (currentIp != lastPublishedIp)
    {
      publishCameraSourceToCloud();
    }
    return;
  }

  if (millis() - lastWiFiReconnectAttempt > WIFI_RECONNECT_INTERVAL_MS)
  {
    lastWiFiReconnectAttempt = millis();
    wifiMulti.run();
  }
}

void beginCloudRequest(HTTPClient &http, WiFiClientSecure &secureClient, String url)
{
  if (url.startsWith("https://"))
  {
    secureClient.setInsecure();
    http.begin(secureClient, url);
  }
  else
  {
    http.begin(url);
  }
}

void publishCameraSourceToCloud()
{
  if (strlen(CLOUD_CAMERA_SOURCE_URL) == 0)
    return;

  if (WiFi.status() != WL_CONNECTED)
    return;

  String localIp = WiFi.localIP().toString();
  String pageUrl = "http://" + localIp;
  String streamUrl = pageUrl + "/stream";

  HTTPClient http;
  WiFiClientSecure secureClient;
  beginCloudRequest(http, secureClient, String(CLOUD_CAMERA_SOURCE_URL));

  http.addHeader("Content-Type", "application/json");
  if (strlen(CLOUD_API_KEY) > 0)
  {
    http.addHeader("X-COOKIE-KEY", CLOUD_API_KEY);
  }

  String payload = "{";
  payload += "\"local_ip\":\"" + localIp + "\",";
  payload += "\"page_url\":\"" + pageUrl + "\",";
  payload += "\"stream_url\":\"" + streamUrl + "\",";
  payload += "\"ssid\":\"" + WiFi.SSID() + "\"";
  payload += "}";

  int httpCode = http.POST(payload);
  if (httpCode > 0)
  {
    Serial.printf("Camera source POST status=%d\n", httpCode);
    lastPublishedIp = localIp;
  }
  else
  {
    Serial.printf("Camera source POST failed: %d\n", httpCode);
  }

  http.end();
}

// ============================================================
// HTML PAGE
// ============================================================

const char INDEX_HTML[] PROGMEM = R"rawliteral(

<!DOCTYPE html>

<html>

<head>

<title>COOKIE CAMERA</title>

<meta name="viewport"
content="width=device-width, initial-scale=1">

<style>

body{
margin:0;
padding:0;
background:#0f172a;
font-family:Arial;
color:white;
text-align:center;
}

.header{
padding:20px;
font-size:32px;
font-weight:bold;
background:#111827;
box-shadow:0 0 20px rgba(0,0,0,0.5);
}

.container{
padding:20px;
}

.stream{
width:95%;
max-width:900px;
border-radius:20px;
border:4px solid #2563eb;
box-shadow:0 0 30px rgba(0,0,0,0.5);
}

.status{
margin-top:20px;
font-size:20px;
color:#60a5fa;
}

</style>

</head>

<body>

<div class="header">
COOKIE CAMERA
</div>

<div class="container">

<img
id="stream"
class="stream"
src="">

<div class="status">
ESP32-CAM STREAM ACTIVE
</div>

</div>

<script>

window.onload = function(){

  const stream =
    document.getElementById("stream");
  const status =
    document.querySelector(".status");

  const streamUrl =
    `${window.location.protocol}//${window.location.hostname}/stream`;

  stream.src = streamUrl;

  stream.onerror = function(){
    status.textContent =
      "Stream unavailable. Try http://" + window.location.hostname + "/stream";
  };

};

</script>

</body>

</html>

)rawliteral";

// ============================================================
// INDEX PAGE
// ============================================================

esp_err_t index_handler(httpd_req_t *req)
{
  httpd_resp_set_type(
    req,
    "text/html"
  );

  return httpd_resp_send(
    req,
    INDEX_HTML,
    strlen(INDEX_HTML)
  );
}

// ============================================================
// STREAM HANDLER
// ============================================================

static esp_err_t stream_handler(httpd_req_t *req)
{
  camera_fb_t * fb = NULL;

  esp_err_t res = ESP_OK;

  static const char* _STREAM_CONTENT_TYPE =
    "multipart/x-mixed-replace; boundary=frame";

  static const char* _STREAM_BOUNDARY =
    "\r\n--frame\r\n";

  static const char* _STREAM_PART =
    "Content-Type: image/jpeg\r\n"
    "Content-Length: %u\r\n\r\n";

  res = httpd_resp_set_type(
    req,
    _STREAM_CONTENT_TYPE
  );

  if(res != ESP_OK)
  {
    return res;
  }

  httpd_resp_set_hdr(
    req,
    "Cache-Control",
    "no-store, no-cache, must-revalidate, max-age=0"
  );

  httpd_resp_set_hdr(
    req,
    "Pragma",
    "no-cache"
  );

  while(true)
  {
    fb = esp_camera_fb_get();

    if(!fb)
    {
      Serial.println(
        "Camera capture failed"
      );

      continue;
    }

    char part_buf[64];

    size_t hlen = snprintf(
      part_buf,
      64,
      _STREAM_PART,
      fb->len
    );

    res = httpd_resp_send_chunk(
      req,
      _STREAM_BOUNDARY,
      strlen(_STREAM_BOUNDARY)
    );

    if(res == ESP_OK)
    {
      res = httpd_resp_send_chunk(
        req,
        part_buf,
        hlen
      );
    }

    if(res == ESP_OK)
    {
      res = httpd_resp_send_chunk(
        req,
        (const char *)fb->buf,
        fb->len
      );
    }

    esp_camera_fb_return(fb);

    if(res != ESP_OK)
    {
      Serial.println(
        "Stream disconnected"
      );

      break;
    }

    // ========================================================
    // IMPORTANT FOR STABILITY
    // ========================================================

    delay(40);
  }

  return res;
}

// ============================================================
// START CAMERA SERVER
// ============================================================

void startCameraServer()
{
  if(camera_httpd != NULL)
  {
    httpd_stop(camera_httpd);
    camera_httpd = NULL;
  }

  httpd_config_t config =
    HTTPD_DEFAULT_CONFIG();

  config.server_port = 80;

  httpd_uri_t index_uri =
  {
    .uri       = "/",
    .method    = HTTP_GET,
    .handler   = index_handler,
    .user_ctx  = NULL
  };

  httpd_uri_t stream_uri =
  {
    .uri       = "/stream",
    .method    = HTTP_GET,
    .handler   = stream_handler,
    .user_ctx  = NULL
  };

  if(httpd_start(
    &camera_httpd,
    &config
  ) == ESP_OK)
  {
    httpd_register_uri_handler(
      camera_httpd,
      &index_uri
    );

    httpd_register_uri_handler(
      camera_httpd,
      &stream_uri
    );

    Serial.println(
      "Camera server started"
    );
  }
  else
  {
    Serial.println(
      "Camera server failed to start"
    );
  }
}

// ============================================================
// CLOUD SNAPSHOT UPLOAD
// ============================================================

void uploadCameraSnapshot()
{
  if (strlen(CLOUD_CAMERA_UPDATE_URL) == 0)
    return;

  if (WiFi.status() != WL_CONNECTED)
    return;

  camera_fb_t *fb = esp_camera_fb_get();

  if (!fb)
  {
    Serial.println("Cloud camera capture failed");
    return;
  }

  HTTPClient http;
  WiFiClientSecure secureClient;
  String url = String(CLOUD_CAMERA_UPDATE_URL);

  if (url.startsWith("https://"))
  {
    secureClient.setInsecure();
    http.begin(secureClient, url);
  }
  else
  {
    http.begin(url);
  }

  http.addHeader("Content-Type", "image/jpeg");

  if (strlen(CLOUD_API_KEY) > 0)
  {
    http.addHeader("X-COOKIE-KEY", CLOUD_API_KEY);
  }

  int httpCode = http.POST(fb->buf, fb->len);

  if (httpCode > 0)
  {
    Serial.printf("Cloud camera POST status=%d\n", httpCode);
  }
  else
  {
    Serial.printf("Cloud camera POST failed: %d\n", httpCode);
  }

  http.end();
  esp_camera_fb_return(fb);
}

// ============================================================
// SETUP
// ============================================================

void setup()
{
  Serial.begin(115200);

  Serial.println();

  Serial.println(
    "================================"
  );

  Serial.println(
    "COOKIE ESP32-CAM"
  );

  Serial.println(
    "================================"
  );

  // ==========================================================
  // CAMERA CONFIG
  // ==========================================================

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

  config.pin_sscb_sda = SIOD_GPIO_NUM;
  config.pin_sscb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 20000000;

  config.pixel_format = PIXFORMAT_JPEG;

  // ==========================================================
  // STABLE SETTINGS
  // ==========================================================

  config.frame_size = FRAMESIZE_QVGA;

  config.jpeg_quality = 20;

  config.fb_count = 1;

  // ==========================================================
  // INIT CAMERA
  // ==========================================================

  esp_err_t err =
    esp_camera_init(&config);

  if(err != ESP_OK)
  {
    Serial.printf(
      "Camera init failed: 0x%x\n",
      err
    );

    return;
  }

  // ==========================================================
  // CAMERA TUNING
  // ==========================================================

  sensor_t * s =
    esp_camera_sensor_get();

  s->set_framesize(
    s,
    FRAMESIZE_QVGA
  );

  s->set_brightness(s,1);

  s->set_contrast(s,1);

  s->set_saturation(s,0);

  // ==========================================================
  // WIFI
  // ==========================================================

  WiFi.mode(WIFI_STA);
  setupCloudWiFi();
  connectToCloudWiFi();

  // ==========================================================
  // START SERVERS
  // ==========================================================

  startCameraServer();

  Serial.println();

  Serial.println(
    "================================"
  );

  Serial.println(
    "STREAM READY"
  );

  Serial.println();

  Serial.println(
    "Browser:"
  );

  Serial.println(WiFi.localIP());

  Serial.println();

  Serial.println(
    "OpenCV:"
  );

  Serial.print("http://");
  Serial.print(WiFi.localIP());
  Serial.println("/stream");

  Serial.println(
    "================================"
  );
}

// ============================================================
// LOOP
// ============================================================

void loop()
{
  maintainCloudWiFi();

  if (ENABLE_DIRECT_CLOUD_UPLOAD && millis() - lastCloudCameraUpload > CLOUD_CAMERA_UPLOAD_INTERVAL_MS)
  {
    lastCloudCameraUpload = millis();
    uploadCameraSnapshot();
  }

  delay(10);
}
