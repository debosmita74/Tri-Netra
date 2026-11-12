/*
============================================================
COOKIE ROBOT
RECTIFIED MAIN ESP32 CODE
(UPDATED FOR YOUR EXACT TB6612FNG CONNECTIONS)
============================================================

MOTOR DRIVER CONNECTIONS
============================================================

TB6612FNG      ESP32

PWMA ---------- GPIO25
AIN1 ---------- GPIO26
AIN2 ---------- GPIO27

PWMB ---------- GPIO14
BIN1 ---------- GPIO12
BIN2 ---------- GPIO13

STBY ---------- GPIO33

============================================================
FEATURES
============================================================

- WiFi station mode with saved network failover
- Professional Web UI
- RoboEyes OLED
- TB6612FNG Motor Control
- Touch Emotion System
- INA219 Battery Telemetry
- Button Battery Display
- YOLO/Laptop Compatible
- Camera Feed Support

============================================================
LIBRARIES REQUIRED
============================================================

- WiFi
- WebServer
- Wire
- Adafruit_GFX
- Adafruit_SSD1306
- Adafruit_INA219
- FluxGarage_RoboEyes

============================================================
*/

#include <WiFi.h>
#include <WiFiMulti.h>
#include <WebServer.h>
#include <Wire.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>

#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Adafruit_INA219.h>

#include <FluxGarage_RoboEyes.h>

// ============================================================
// WIFI STA / CLOUD
// ============================================================

struct WiFiCredential
{
  const char* ssid;
  const char* password;
};

WiFiCredential WIFI_NETWORKS[] = {
  {"Sagnick", "Routh@2002"},
  {"Sagnick's iPhone", "Routh2002"},
  {"TP_6653", "123456@#"}
};

const char* CLOUD_SERVER_URL = "https://cookie-robot-cloud.vercel.app/update";
const char* CLOUD_COMMAND_URL = "https://cookie-robot-cloud.vercel.app/command/next";
const char* CLOUD_EMOTION_URL = "https://cookie-robot-cloud.vercel.app/emotion/next";
const char* CLOUD_CAMERA_URL = "https://cookie-robot-cloud.vercel.app/camera";
const char* CLOUD_API_KEY = "";
const unsigned long CLOUD_POST_INTERVAL_MS = 30000;
const unsigned long CLOUD_COMMAND_POLL_INTERVAL_MS = 2000;
const unsigned long WIFI_RECONNECT_INTERVAL_MS = 10000;
unsigned long lastCloudPost = 0;
unsigned long lastCloudCommandPoll = 0;
unsigned long lastWiFiReconnectAttempt = 0;
unsigned long lastCloudCommandId = 0;
unsigned long lastCloudEmotionId = 0;
bool wifiStaConnected = false;
WiFiMulti wifiMulti;

// ============================================================
// OLED
// ============================================================

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1

Adafruit_SSD1306 display(
  SCREEN_WIDTH,
  SCREEN_HEIGHT,
  &Wire,
  OLED_RESET
);

// ============================================================
// ROBO EYES
// ============================================================

RoboEyes<Adafruit_SSD1306> roboEyes(display);

// ============================================================
// INA219
// ============================================================

Adafruit_INA219 ina219;

// ============================================================
// WEB SERVER
// ============================================================

WebServer server(80);

// ============================================================
// MOTOR PINS
// (YOUR EXACT CONNECTIONS)
// ============================================================

#define PWMA 25
#define AIN1 26
#define AIN2 27

#define PWMB 14
#define BIN1 12
#define BIN2 13

#define STBY 33

// ============================================================
// PWM
// ============================================================

#define PWM_FREQ 5000
#define PWM_RESOLUTION 8

#define PWMA_CHANNEL 0
#define PWMB_CHANNEL 1

// ============================================================
// TOUCH + BUTTON
// ============================================================

#define TOUCH_PIN 4
#define BUTTON_PIN 15

// ============================================================
// VARIABLES
// ============================================================

bool angryMode = false;
bool excitedMode = false;
bool cloudEmotionOverride = false;
String currentEmotion = "NORMAL";
String currentMotorCommand = "stop";

unsigned long emotionTimer = 0;

int tapCount = 0;
unsigned long firstTapTime = 0;

bool showBattery = false;
unsigned long batteryDisplayTimer = 0;

// ============================================================
// CLOUD TELEMETRY
// ============================================================

String getTelemetryJson()
{
  float voltage = ina219.getBusVoltage_V();

  int battery = map(
    voltage * 100,
    330,
    420,
    0,
    100
  );

  battery = constrain(
    battery,
    0,
    100
  );

  String json = "{";
  json += "\"robot_name\":\"COOKIE\",";
  json += "\"voltage\":" + String(voltage, 3) + ",";
  json += "\"battery_percent\":" + String(battery) + ",";
  json += "\"emotion\":\"" + currentEmotion + "\",";
  json += "\"motor_command\":\"" + currentMotorCommand + "\",";
  json += "\"uptime_ms\":" + String(millis()) + ",";
  json += "\"wifi_rssi\":" + String(WiFi.RSSI()) + ",";
  json += "\"connected_ssid\":\"" + WiFi.SSID() + "\",";
  json += "\"local_ip\":\"" + WiFi.localIP().toString() + "\",";
  json += "\"free_heap\":" + String(ESP.getFreeHeap()) + ",";
  json += "\"camera_url\":\"" + String(CLOUD_CAMERA_URL) + "\"";
  json += "}";

  return json;
}

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

  if (WiFi.status() == WL_CONNECTED)
  {
    wifiStaConnected = true;
    Serial.println();
    Serial.print("Connected SSID: ");
    Serial.println(WiFi.SSID());
    Serial.print("Robot internet IP: ");
    Serial.println(WiFi.localIP());
  }
  else
  {
    wifiStaConnected = false;
    Serial.println();
    Serial.println("Cloud WiFi connection failed");
  }
}

void maintainCloudWiFi()
{
  if (WiFi.status() == WL_CONNECTED)
  {
    wifiStaConnected = true;
    return;
  }

  wifiStaConnected = false;

  if (millis() - lastWiFiReconnectAttempt > WIFI_RECONNECT_INTERVAL_MS)
  {
    lastWiFiReconnectAttempt = millis();
    wifiMulti.run();
  }
}

void postTelemetryToCloud()
{
  if (strlen(CLOUD_SERVER_URL) == 0)
    return;

  if (WiFi.status() != WL_CONNECTED)
  {
    wifiStaConnected = false;
    return;
  }

  String url = String(CLOUD_SERVER_URL);

  HTTPClient http;
  WiFiClientSecure secureClient;

  if (url.startsWith("https://"))
  {
    secureClient.setInsecure();
    http.begin(secureClient, url);
  }
  else
  {
    http.begin(url);
  }

  http.addHeader("Content-Type", "application/json");

  if (strlen(CLOUD_API_KEY) > 0)
  {
    http.addHeader("X-COOKIE-KEY", CLOUD_API_KEY);
  }

  String payload = getTelemetryJson();

  int httpCode = http.POST(payload);

  if (httpCode > 0)
  {
    Serial.printf("Cloud POST status=%d\n", httpCode);
  }
  else
  {
    Serial.printf("Cloud POST failed: %d\n", httpCode);
  }

  http.end();
}

void executeCloudCommand(String command)
{
  command.trim();
  command.toLowerCase();

  if (command == "forward")
  {
    currentMotorCommand = "forward";
    moveForward();
  }
  else if (command == "backward")
  {
    currentMotorCommand = "backward";
    moveBackward();
  }
  else if (command == "left")
  {
    currentMotorCommand = "left";
    moveLeft();
  }
  else if (command == "right")
  {
    currentMotorCommand = "right";
    moveRight();
  }
  else if (command == "stop")
  {
    currentMotorCommand = "stop";
    stopMotors();
  }
}

void pollCloudCommand()
{
  if (strlen(CLOUD_COMMAND_URL) == 0)
    return;

  if (WiFi.status() != WL_CONNECTED)
    return;

  HTTPClient http;
  WiFiClientSecure secureClient;
  String url = String(CLOUD_COMMAND_URL);

  if (url.startsWith("https://"))
  {
    secureClient.setInsecure();
    http.begin(secureClient, url);
  }
  else
  {
    http.begin(url);
  }

  if (strlen(CLOUD_API_KEY) > 0)
  {
    http.addHeader("X-COOKIE-KEY", CLOUD_API_KEY);
  }

  int httpCode = http.GET();

  if (httpCode == 200)
  {
    String payload = http.getString();
    payload.trim();

    int separator = payload.indexOf(":");

    if (separator > 0)
    {
      unsigned long commandId =
        payload.substring(0, separator).toInt();

      String command =
        payload.substring(separator + 1);

      if (commandId > lastCloudCommandId)
      {
        lastCloudCommandId = commandId;
        executeCloudCommand(command);
        Serial.println("Cloud command: " + command);
      }
    }
  }

  http.end();
}

void executeCloudEmotion(String emotion)
{
  emotion.trim();
  emotion.toLowerCase();

  angryMode = false;
  excitedMode = false;

  if (emotion == "angry")
  {
    cloudEmotionOverride = true;
    angryMode = true;
    currentEmotion = "ANGRY";
    roboEyes.setMood(ANGRY);
  }
  else if (emotion == "happy")
  {
    cloudEmotionOverride = true;
    excitedMode = true;
    currentEmotion = "HAPPY";
    roboEyes.setMood(HAPPY);
  }
  else if (emotion == "excited")
  {
    cloudEmotionOverride = true;
    excitedMode = true;
    currentEmotion = "EXCITED";
    roboEyes.setMood(HAPPY);
    roboEyes.anim_laugh();
  }
  else
  {
    cloudEmotionOverride = false;
    currentEmotion = "NORMAL";
    roboEyes.setMood(DEFAULT);
  }

  emotionTimer = millis();
}

void pollCloudEmotion()
{
  if (strlen(CLOUD_EMOTION_URL) == 0)
    return;

  if (WiFi.status() != WL_CONNECTED)
    return;

  HTTPClient http;
  WiFiClientSecure secureClient;
  String url = String(CLOUD_EMOTION_URL);

  if (url.startsWith("https://"))
  {
    secureClient.setInsecure();
    http.begin(secureClient, url);
  }
  else
  {
    http.begin(url);
  }

  if (strlen(CLOUD_API_KEY) > 0)
  {
    http.addHeader("X-COOKIE-KEY", CLOUD_API_KEY);
  }

  int httpCode = http.GET();

  if (httpCode == 200)
  {
    String payload = http.getString();
    payload.trim();

    int separator = payload.indexOf(":");

    if (separator > 0)
    {
      unsigned long emotionId =
        payload.substring(0, separator).toInt();

      String emotion =
        payload.substring(separator + 1);

      if (emotionId > lastCloudEmotionId)
      {
        lastCloudEmotionId = emotionId;
        executeCloudEmotion(emotion);
        Serial.println("Cloud emotion: " + emotion);
      }
    }
  }

  http.end();
}

// ============================================================
// MOTOR SETUP
// ============================================================

void setupMotors()
{
  pinMode(AIN1, OUTPUT);
  pinMode(AIN2, OUTPUT);

  pinMode(BIN1, OUTPUT);
  pinMode(BIN2, OUTPUT);

  pinMode(STBY, OUTPUT);

  digitalWrite(STBY, HIGH);

  // ==========================================================
  // PWM
  // ==========================================================

  ledcSetup(
    PWMA_CHANNEL,
    PWM_FREQ,
    PWM_RESOLUTION
  );

  ledcSetup(
    PWMB_CHANNEL,
    PWM_FREQ,
    PWM_RESOLUTION
  );

  ledcAttachPin(
    PWMA,
    PWMA_CHANNEL
  );

  ledcAttachPin(
    PWMB,
    PWMB_CHANNEL
  );

  stopMotors();

  Serial.println("Motors initialized");
}

// ============================================================
// STOP
// ============================================================

void stopMotors()
{
  currentMotorCommand = "stop";

  ledcWrite(PWMA_CHANNEL, 0);
  ledcWrite(PWMB_CHANNEL, 0);

  digitalWrite(AIN1, LOW);
  digitalWrite(AIN2, LOW);

  digitalWrite(BIN1, LOW);
  digitalWrite(BIN2, LOW);
}

// ============================================================
// FORWARD
// ============================================================

void moveForward()
{
  currentMotorCommand = "forward";

  digitalWrite(AIN1, HIGH);
  digitalWrite(AIN2, LOW);

  digitalWrite(BIN1, HIGH);
  digitalWrite(BIN2, LOW);

  ledcWrite(PWMA_CHANNEL, 190);
  ledcWrite(PWMB_CHANNEL, 190);
}

// ============================================================
// BACKWARD
// ============================================================

void moveBackward()
{
  currentMotorCommand = "backward";

  digitalWrite(AIN1, LOW);
  digitalWrite(AIN2, HIGH);

  digitalWrite(BIN1, LOW);
  digitalWrite(BIN2, HIGH);

  ledcWrite(PWMA_CHANNEL, 190);
  ledcWrite(PWMB_CHANNEL, 190);
}

// ============================================================
// LEFT
// ============================================================

void moveLeft()
{
  currentMotorCommand = "left";

  digitalWrite(AIN1, LOW);
  digitalWrite(AIN2, HIGH);

  digitalWrite(BIN1, HIGH);
  digitalWrite(BIN2, LOW);

  ledcWrite(PWMA_CHANNEL, 190);
  ledcWrite(PWMB_CHANNEL, 190);
}

// ============================================================
// RIGHT
// ============================================================

void moveRight()
{
  currentMotorCommand = "right";

  digitalWrite(AIN1, HIGH);
  digitalWrite(AIN2, LOW);

  digitalWrite(BIN1, LOW);
  digitalWrite(BIN2, HIGH);

  ledcWrite(PWMA_CHANNEL, 190);
  ledcWrite(PWMB_CHANNEL, 190);
}

// ============================================================
// WEB PAGE
// ============================================================

String htmlPage()
{
  return R"rawliteral(

<!DOCTYPE html>
<html>

<head>

<title>COOKIE ROBOT</title>

<meta name="viewport"
content="width=device-width, initial-scale=1">

<style>

body{
background:#0f172a;
font-family:Arial;
color:white;
text-align:center;
margin:0;
}

.header{
padding:20px;
font-size:32px;
font-weight:bold;
background:#111827;
}

.card{
background:#1e293b;
padding:20px;
margin:20px;
border-radius:20px;
}

button{
width:120px;
height:60px;
margin:10px;
border:none;
border-radius:15px;
font-size:18px;
font-weight:bold;
background:#2563eb;
color:white;
}

button:hover{
background:#3b82f6;
}

iframe{
width:90%;
height:360px;
border:none;
border-radius:20px;
}

</style>

</head>

<body>

<div class="header">
COOKIE ROBOT
</div>

<div class="card">

<h2>Movement</h2>

<button onclick="send('forward')">
Forward
</button>

<br>

<button onclick="send('left')">
Left
</button>

<button onclick="send('stop')">
Stop
</button>

<button onclick="send('right')">
Right
</button>

<br>

<button onclick="send('backward')">
Backward
</button>

</div>

<div class="card">

<h2>Camera Feed</h2>

<iframe src="http://192.168.4.2"></iframe>

</div>

<script>

function send(cmd)
{
fetch('/'+cmd);
}

</script>

</body>
</html>

)rawliteral";
}

// ============================================================
// ROUTES
// ============================================================

void handleRoot()
{
  server.send(
    200,
    "text/html",
    htmlPage()
  );
}

void handleForward()
{
  moveForward();
  server.send(200,"text/plain","OK");
}

void handleBackward()
{
  moveBackward();
  server.send(200,"text/plain","OK");
}

void handleLeft()
{
  moveLeft();
  server.send(200,"text/plain","OK");
}

void handleRight()
{
  moveRight();
  server.send(200,"text/plain","OK");
}

void sendCorsHeaders()
{
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

void handleOptions()
{
  sendCorsHeaders();
  server.send(204, "text/plain", "");
}

void handleStop()
{
  stopMotors();
  server.send(200,"text/plain","OK");
}

// ============================================================
// TELEMETRY
// ============================================================

void handleTelemetry()
{
  float voltage =
    ina219.getBusVoltage_V();

  int battery =
    map(
      voltage * 100,
      330,
      420,
      0,
      100
    );

  battery =
    constrain(
      battery,
      0,
      100
    );

  String json =
    "{";

  json += "\"voltage\":";
  json += String(voltage, 3);
  json += ",";
  json += "\"battery\":";
  json += battery;
  json += ",";
  json += "\"emotion\":\"";
  json += currentEmotion;
  json += "\",";
  json += "\"motor_command\":\"";
  json += currentMotorCommand;
  json += "\"}";

  sendCorsHeaders();
  server.send(
    200,
    "application/json",
    json
  );
}

void handleStatus()
{
  float voltage =
    ina219.getBusVoltage_V();

  int battery =
    map(
      voltage * 100,
      330,
      420,
      0,
      100
    );

  battery =
    constrain(
      battery,
      0,
      100
    );

  String json =
    "{";

  json += "\"voltage\":";
  json += String(voltage, 3);
  json += ",";
  json += "\"battery_percent\":";
  json += battery;
  json += ",";
  json += "\"camera_url\":\"";
  json += CLOUD_CAMERA_URL;
  json += "\"";
  json += ",";
  json += "\"connected_ssid\":\"";
  json += WiFi.SSID();
  json += "\"";
  json += ",";
  json += "\"local_ip\":\"";
  json += WiFi.localIP().toString();
  json += "\"";
  json += ",";
  json += "\"emotion\":\"";
  json += currentEmotion;
  json += "\",";
  json += "\"motor_command\":\"";
  json += currentMotorCommand;
  json += "\"}";

  sendCorsHeaders();
  server.send(
    200,
    "application/json",
    json
  );
}

// ============================================================
// TOUCH SYSTEM
// ============================================================

void handleTouch()
{
  static bool prevTouch = false;

  bool touch =
    digitalRead(TOUCH_PIN);

  if(touch && !prevTouch)
  {
    if(tapCount == 0)
    {
      firstTapTime = millis();
    }

    tapCount++;

    excitedMode = true;
    angryMode = false;
    cloudEmotionOverride = false;
    currentEmotion = "HAPPY";

    emotionTimer = millis();

    roboEyes.setMood(HAPPY);

    roboEyes.anim_laugh();

    // ========================================================
    // ANGRY MODE
    // ========================================================

    if(
      tapCount >= 4 &&
      millis() - firstTapTime < 20000
    )
    {
      angryMode = true;
      excitedMode = false;
      cloudEmotionOverride = false;
      currentEmotion = "ANGRY";

      roboEyes.setMood(ANGRY);

      moveForward();

      delay(500);

      moveBackward();

      delay(500);

      stopMotors();

      tapCount = 0;
    }
  }

  prevTouch = touch;

  if(millis() - firstTapTime > 20000)
  {
    tapCount = 0;
  }

  if(!cloudEmotionOverride && millis() - emotionTimer > 5000)
  {
    angryMode = false;
    excitedMode = false;
    currentEmotion = "NORMAL";

    roboEyes.setMood(DEFAULT);
  }
}

// ============================================================
// BATTERY SCREEN
// ============================================================

void showBatteryScreen()
{
  float voltage =
    ina219.getBusVoltage_V();

  int battery =
    map(
      voltage * 100,
      330,
      420,
      0,
      100
    );

  display.clearDisplay();

  display.setTextSize(2);

  display.setCursor(10,20);

  display.print("Battery");

  display.setCursor(35,45);

  display.print(battery);

  display.print("%");

  display.display();
}

// ============================================================
// SETUP
// ============================================================

void setup()
{
  Serial.begin(115200);

  // ==========================================================
  // PINS
  // ==========================================================

  pinMode(TOUCH_PIN, INPUT);

  pinMode(BUTTON_PIN, INPUT_PULLUP);

  // ==========================================================
  // OLED
  // ==========================================================

  Wire.begin();

  display.begin(
    SSD1306_SWITCHCAPVCC,
    0x3C
  );

  display.clearDisplay();
  display.display();

  // ==========================================================
  // ROBO EYES
  // ==========================================================

  roboEyes.begin(
    SCREEN_WIDTH,
    SCREEN_HEIGHT,
    100
  );

  roboEyes.setAutoblinker(
    ON,
    3,
    2
  );

  roboEyes.setIdleMode(
    ON,
    2,
    2
  );

  roboEyes.setMood(DEFAULT);

  // ==========================================================
  // MOTORS
  // ==========================================================

  setupMotors();

  // ==========================================================
  // INA219
  // ==========================================================

  ina219.begin();

  // ==========================================================
  // WIFI STA / CLOUD
  // ==========================================================

  WiFi.mode(WIFI_STA);
  setupCloudWiFi();
  connectToCloudWiFi();
  postTelemetryToCloud();
  pollCloudCommand();
  pollCloudEmotion();

  // ==========================================================
  // SERVER ROUTES
  // ==========================================================

  server.on("/", handleRoot);

  server.on("/forward", handleForward);
  server.on("/backward", handleBackward);

  server.on("/left", handleLeft);
  server.on("/right", handleRight);

  server.on("/stop", handleStop);

  server.on("/telemetry", HTTP_GET, handleTelemetry);
  server.on("/telemetry", HTTP_OPTIONS, handleOptions);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/status", HTTP_OPTIONS, handleOptions);

  server.begin();

  Serial.println(
    "COOKIE ROBOT READY"
  );
}

// ============================================================
// LOOP
// ============================================================

void loop()
{
  server.handleClient();

  handleTouch();

  maintainCloudWiFi();

  if (millis() - lastCloudPost > CLOUD_POST_INTERVAL_MS)
  {
    lastCloudPost = millis();
    postTelemetryToCloud();
  }

  if (millis() - lastCloudCommandPoll > CLOUD_COMMAND_POLL_INTERVAL_MS)
  {
    lastCloudCommandPoll = millis();
    pollCloudCommand();
    pollCloudEmotion();
  }

  // ==========================================================
  // BUTTON
  // ==========================================================

  if(
    digitalRead(BUTTON_PIN) == LOW
  )
  {
    showBattery = true;

    batteryDisplayTimer = millis();
  }

  if(showBattery)
  {
    showBatteryScreen();

    if(
      millis() -
      batteryDisplayTimer > 5000
    )
    {
      showBattery = false;
    }
  }
  else
  {
    roboEyes.update();
  }
}
