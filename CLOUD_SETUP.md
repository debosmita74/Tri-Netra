# COOKIE Cloud Telemetry Setup

This setup lets COOKIE push fresh robot data to a cloud server every 30 seconds.
Your app reads the latest values from the cloud server and sends movement
commands through the same server.

Because all app communication goes through the public cloud URL, the phone and
robot do not need to be on the same WiFi. The robot only needs to be connected
to any saved WiFi network with internet access.

## 1. Deploy the Python cloud server

Deploy `cloud_server.py` to a service that can run Flask, such as Render, Railway,
Fly.io, a VPS, or your own home server.

Install dependencies:

```bash
pip install -r requirements.txt
```

Run locally for testing:

```bash
python cloud_server.py
```

Useful environment variables:

```bash
PORT=5000
COOKIE_API_KEY=choose-a-secret-key
COOKIE_ADMIN_PASSWORD=choose-a-strong-admin-password
FIREBASE_DATABASE_URL=https://cookie-d10f0-default-rtdb.firebaseio.com
FIREBASE_AUTH_TOKEN=your-database-secret-or-service-token
ENABLE_POLL=0
POLL_INTERVAL=30
```

Keep `ENABLE_POLL=0` for a real internet cloud server. The robot will push data
to the cloud. A public cloud server usually cannot fetch `192.168.4.1` directly
because that address only exists inside COOKIE's local WiFi network.

## 2. Configure the main ESP32

In `esp32_main_board.ino`, update:

```cpp
WiFiCredential WIFI_NETWORKS[] = {
  {"YOUR_HOME_WIFI", "YOUR_HOME_PASSWORD"},
  {"YOUR_PHONE_HOTSPOT", "YOUR_HOTSPOT_PASSWORD"},
  {"YOUR_SCHOOL_OR_OTHER_WIFI", "YOUR_OTHER_PASSWORD"}
};

const char* CLOUD_SERVER_URL = "https://cookie-robot-cloud.vercel.app/update";
const char* CLOUD_COMMAND_URL = "https://cookie-robot-cloud.vercel.app/command/next";
const char* CLOUD_EMOTION_URL = "https://cookie-robot-cloud.vercel.app/emotion/next";
const char* CLOUD_CAMERA_URL = "https://cookie-robot-cloud.vercel.app/camera";
const char* CLOUD_API_KEY = "";
```

COOKIE will try the saved WiFi networks and use whichever one is available.
The main ESP32 now runs in `WIFI_STA` mode only. It does not create its own
`COOKIE_BOT` access point; it searches the configured credentials and connects
to whichever network is available.
If you later set `COOKIE_API_KEY` on Vercel, put the same value in
`CLOUD_API_KEY` and in the Android app's `API_KEY`.

The main ESP32 sends this data every 30 seconds:

- `robot_name`
- `voltage`
- `battery_percent`
- `emotion`
- `motor_command`
- `camera_url`
- `uptime_ms`
- `wifi_rssi`
- `connected_ssid`
- `local_ip`
- `free_heap`

## 3. ESP32-CAM to Laptop Human Detection

The ESP32-CAM now serves a local MJPEG stream for the laptop and publishes its
current local stream URL to the cloud. You do not need to repeatedly copy the
camera IP into `human_detection.py`.

Flash `esp32_Cam_Stream/esp32_Cam_Stream.ino` to the ESP32-CAM and check Serial
Monitor for:

```text
Camera source POST status=200
```

The camera source is stored at:

```text
GET https://cookie-robot-cloud.vercel.app/camera/source
```

On the laptop, install the detection dependencies:

```powershell
pip install -r requirements_laptop.txt
```

Run the human detection processor. It fetches the ESP32-CAM stream URL from the
cloud automatically:

```powershell
python human_detection.py
```

What the laptop script does:

- reads the ESP32-CAM stream
- runs YOLO human detection
- draws detection boxes and direction text
- sends movement commands to the cloud `/command` endpoint
- uploads the processed detection frame to `/camera/update`

The main ESP32 receives the laptop's movement commands by polling:

```text
GET https://cookie-robot-cloud.vercel.app/command/next
```

The Android app displays the laptop-processed detection feed from:

```text
GET https://cookie-robot-cloud.vercel.app/camera
```

In `esp32_Cam_Stream/esp32_Cam_Stream.ino`, update the WiFi list:

```cpp
WiFiCredential WIFI_NETWORKS[] = {
  {"YOUR_HOME_WIFI", "YOUR_HOME_PASSWORD"},
  {"YOUR_PHONE_HOTSPOT", "YOUR_HOTSPOT_PASSWORD"},
  {"YOUR_SCHOOL_OR_OTHER_WIFI", "YOUR_OTHER_PASSWORD"}
};
```

Important: the laptop and ESP32-CAM must be on the same WiFi network, because
the laptop reads the ESP32-CAM's local stream. The phone app can be on any
network because it reads the processed feed from the cloud.

Optional fallback: to make the ESP32-CAM upload raw frames directly to the cloud,
set this in `esp32_Cam_Stream/esp32_Cam_Stream.ino`:

```cpp
const bool ENABLE_DIRECT_CLOUD_UPLOAD = true;
```

## 4. App endpoints

Read latest robot status:

```text
GET https://cookie-robot-cloud.vercel.app/status
```

Read latest uploaded camera snapshot:

```text
GET https://cookie-robot-cloud.vercel.app/camera
```

Send movement command from the app:

```text
POST https://cookie-robot-cloud.vercel.app/command
Content-Type: application/json
X-COOKIE-KEY: choose-a-secret-key

{"command":"forward"}
```

Valid commands:

- `forward`
- `backward`
- `left`
- `right`
- `stop`

The main ESP32 checks for new cloud commands every 2 seconds.

Send emotion command from the app:

```text
POST https://cookie-robot-cloud.vercel.app/emotion
Content-Type: application/json

{"emotion":"happy"}
```

Valid emotions:

- `normal`
- `happy`
- `excited`
- `angry`

The main ESP32 checks for new cloud emotions every 2 seconds.

## 5. Android App Admin Login

Admin login is enabled only when `COOKIE_ADMIN_PASSWORD` is set in the cloud
deployment environment. Enter the configured password directly in the app; do
not commit it to source control.

You can override it on Vercel with:

```text
COOKIE_ADMIN_EMAIL
COOKIE_ADMIN_MOBILE
COOKIE_ADMIN_PASSWORD
```

Push telemetry manually for testing:

```bash
curl -X POST https://cookie-robot-cloud.vercel.app/update \
  -H "Content-Type: application/json" \
  -H "X-COOKIE-KEY: choose-a-secret-key" \
  -d "{\"voltage\":7.8,\"battery_percent\":80,\"emotion\":\"NORMAL\"}"
```
