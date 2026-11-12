# COOKIE Laptop Human Detection

## Flow

```text
ESP32-CAM local stream -> laptop YOLO detection -> COOKIE cloud -> Android app
                                      |
                                      -> cloud movement commands -> main ESP32
```

## Run

1. Flash `esp32_Cam_Stream/esp32_Cam_Stream.ino`.
2. Open Serial Monitor and confirm `Camera source POST status=200`.
3. Install laptop dependencies:

```powershell
pip install -r requirements_laptop.txt
```

4. Run detection. The script reads the ESP32-CAM stream URL from the cloud:

```powershell
python human_detection.py
```

Manual override is still supported if needed:

```powershell
$env:ESP32_STREAM_URL="http://CAMERA_LOCAL_IP/stream"
python human_detection.py
```

For smoother or faster detection, tune these optional values before running:

```powershell
$env:YOLO_MODEL="yolov8n.pt"
$env:DETECTION_IMAGE_SIZE="416"
$env:CENTER_DEADBAND_RATIO="0.18"
$env:COMMAND_CONFIRM_FRAMES="2"
$env:NO_PERSON_STOP_FRAMES="4"
$env:UPLOAD_INTERVAL_SECONDS="0.2"
$env:JPEG_QUALITY="72"
```

The script reads the camera in a separate thread and always processes the newest
frame, so stale camera buffers do not slow movement decisions.

The app shows the processed detection feed from:

```text
https://cookie-robot-cloud.vercel.app/camera
```

The robot receives commands through:

```text
https://cookie-robot-cloud.vercel.app/command/next
```
