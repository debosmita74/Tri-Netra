# ============================================================
# COOKIE ROBOT
# HUMAN FOLLOW SYSTEM
# YOLOv8 PERSON DETECTION
# ============================================================

import base64
import os
import sys
import threading
import time

import cv2
import requests
from flask import Flask, Response
from ultralytics import YOLO

# ============================================================
# CONFIGURATION
# ============================================================

ROBOT_IP = os.getenv("ROBOT_IP", "http://192.168.4.1")
ESP32_STREAM_URL = os.getenv("ESP32_STREAM_URL", "")
CLOUD_SERVER_URL = os.getenv("CLOUD_SERVER_URL", "https://cookie-robot-cloud.vercel.app")
CAMERA_SOURCE_ENDPOINT = os.getenv("CAMERA_SOURCE_ENDPOINT", "/camera/source")
CLOUD_API_KEY = os.getenv("COOKIE_API_KEY", "")
COMMAND_TARGET = os.getenv("COMMAND_TARGET", "cloud").lower()
ALLOW_WEBCAM_FALLBACK = os.getenv("ALLOW_WEBCAM_FALLBACK", "0") == "1"
LOCAL_STREAM_HOST = os.getenv("LOCAL_STREAM_HOST", "0.0.0.0")
LOCAL_STREAM_PORT = int(os.getenv("LOCAL_STREAM_PORT", "5001"))
MODEL_NAME = os.getenv("YOLO_MODEL", "yolov8n.pt")
CONFIDENCE_THRESHOLD = float(os.getenv("YOLO_CONFIDENCE", "0.55"))
UPLOAD_INTERVAL_SECONDS = float(os.getenv("UPLOAD_INTERVAL_SECONDS", "0.2"))
COMMAND_INTERVAL_SECONDS = float(os.getenv("COMMAND_INTERVAL_SECONDS", "0.3"))
STREAM_SLEEP_SECONDS = float(os.getenv("STREAM_SLEEP_SECONDS", "0.01"))
DETECTION_IMAGE_SIZE = int(os.getenv("DETECTION_IMAGE_SIZE", "416"))
CENTER_DEADBAND_RATIO = float(os.getenv("CENTER_DEADBAND_RATIO", "0.28"))
COMMAND_CONFIRM_FRAMES = int(os.getenv("COMMAND_CONFIRM_FRAMES", "2"))
NO_PERSON_STOP_FRAMES = int(os.getenv("NO_PERSON_STOP_FRAMES", "4"))
JPEG_QUALITY = int(os.getenv("JPEG_QUALITY", "72"))
CAPTURE_RECONNECT_SECONDS = float(os.getenv("CAPTURE_RECONNECT_SECONDS", "1.0"))
CAMERA_SOURCE_REFRESH_SECONDS = float(os.getenv("CAMERA_SOURCE_REFRESH_SECONDS", "5.0"))
ROTATE_FRAME_180 = os.getenv("ROTATE_FRAME_180", "1") == "1"

# ============================================================
# LOAD YOLO MODEL
# ============================================================

model = YOLO(MODEL_NAME)

# ============================================================
# CAMERA CAPTURE
# ============================================================

current_stream_url = ESP32_STREAM_URL
last_camera_source_fetch = 0.0

if current_stream_url and "CAMERA_LOCAL_IP" in current_stream_url:
    current_stream_url = ""

latest_raw_frame = None
latest_raw_lock = threading.Lock()
capture_running = True


def fetch_camera_stream_url(force=False):
    global current_stream_url, last_camera_source_fetch

    now = time.time()
    if not force and now - last_camera_source_fetch < CAMERA_SOURCE_REFRESH_SECONDS:
        return current_stream_url

    last_camera_source_fetch = now
    try:
        response = requests.get(CLOUD_SERVER_URL + CAMERA_SOURCE_ENDPOINT, timeout=3)
        response.raise_for_status()
        data = response.json()
        stream_url = (data.get("stream_url") or "").strip()
        if stream_url and stream_url != current_stream_url:
            print("Camera stream URL from cloud:", stream_url)
            current_stream_url = stream_url
        return current_stream_url
    except Exception as exc:
        print("Camera source fetch failed:", exc)
        return current_stream_url


def open_capture():
    stream_url = fetch_camera_stream_url(force=True)
    camera_sources = []
    if stream_url:
        camera_sources.append(stream_url)
    if ALLOW_WEBCAM_FALLBACK:
        camera_sources.append(0)

    if not camera_sources:
        print("No ESP32-CAM stream URL yet. Waiting for ESP32-CAM to publish /camera/source.")
        return None

    for source in camera_sources:
        candidate = cv2.VideoCapture(source)
        candidate.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        candidate.set(cv2.CAP_PROP_FPS, 30)
        candidate.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        candidate.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        if candidate.isOpened():
            print(f"Connected to camera source: {source}")
            return candidate
        candidate.release()
    return None


def capture_loop():
    global latest_raw_frame

    cap = None
    opened_url = ""
    while capture_running:
        latest_url = fetch_camera_stream_url()
        if cap is not None and latest_url and latest_url != opened_url:
            print("Camera stream URL changed, reconnecting:", latest_url)
            cap.release()
            cap = None

        if cap is None or not cap.isOpened():
            cap = open_capture()
            if cap is None:
                print("Cannot open camera stream, retrying...")
                time.sleep(CAPTURE_RECONNECT_SECONDS)
                continue
            opened_url = current_stream_url

        ret, frame = cap.read()
        if not ret:
            cap.release()
            cap = None
            time.sleep(0.05)
            continue

        with latest_raw_lock:
            latest_raw_frame = frame

    if cap is not None:
        cap.release()


capture_thread = threading.Thread(target=capture_loop, daemon=True)
capture_thread.start()

# ============================================================
# LIVE STREAM SERVER
# ============================================================

app = Flask(__name__)
latest_frame_bytes = None
latest_frame_lock = threading.Lock()


def set_latest_frame(frame):
    global latest_frame_bytes
    success, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
    if not success:
        return
    with latest_frame_lock:
        latest_frame_bytes = encoded.tobytes()


@app.route("/health")
def health():
    return {"status": "ok", "source": current_stream_url, "camera_source_endpoint": CAMERA_SOURCE_ENDPOINT}


@app.route("/stream")
def stream():
    def generate():
        while True:
            with latest_frame_lock:
                frame_bytes = latest_frame_bytes
            if frame_bytes:
                yield (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    b"Content-Length: " + str(len(frame_bytes)).encode() + b"\r\n\r\n"
                    + frame_bytes + b"\r\n"
                )
            time.sleep(STREAM_SLEEP_SECONDS)

    return Response(generate(), mimetype="multipart/x-mixed-replace; boundary=frame")


def start_stream_server():
    app.run(host=LOCAL_STREAM_HOST, port=LOCAL_STREAM_PORT, debug=False, use_reloader=False)


stream_thread = threading.Thread(target=start_stream_server, daemon=True)
stream_thread.start()

# ============================================================
# COMMAND SYSTEM
# ============================================================

last_command = ""
last_upload_time = 0.0
last_command_time = 0.0
upload_in_progress = False
pending_command = ""
pending_command_count = 0
no_person_count = 0


def send_command(cmd):
    global last_command, last_command_time

    now = time.time()
    if cmd == last_command and now - last_command_time < COMMAND_INTERVAL_SECONDS:
        return

    last_command = cmd
    last_command_time = now

    headers = {"Content-Type": "application/json"}
    if CLOUD_API_KEY:
        headers["X-COOKIE-KEY"] = CLOUD_API_KEY

    if COMMAND_TARGET in ("cloud", "both"):
        try:
            response = requests.post(
                CLOUD_SERVER_URL + "/command",
                json={"command": cmd},
                headers=headers,
                timeout=2,
            )
            print("CLOUD COMMAND:", cmd, response.status_code)
        except Exception as exc:
            print("Cloud command failed:", exc)

    if COMMAND_TARGET in ("local", "both"):
        try:
            response = requests.get(ROBOT_IP + "/" + cmd, timeout=0.5)
            print("LOCAL COMMAND:", cmd, response.status_code)
        except Exception as exc:
            print("Robot local command failed:", exc)


def upload_frame_to_cloud(frame):
    global last_upload_time, upload_in_progress

    now = time.time()
    if upload_in_progress or now - last_upload_time < UPLOAD_INTERVAL_SECONDS:
        return

    last_upload_time = now

    success, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
    if not success:
        return

    payload = {
        "image_base64": base64.b64encode(encoded.tobytes()).decode("ascii")
    }
    headers = {}
    if CLOUD_API_KEY:
        headers["X-COOKIE-KEY"] = CLOUD_API_KEY

    upload_in_progress = True

    def worker():
        global upload_in_progress
        try:
            response = requests.post(
                CLOUD_SERVER_URL + "/camera/update",
                json=payload,
                headers=headers,
                timeout=1.2,
            )
            if response.status_code >= 400:
                print("Cloud frame upload failed:", response.status_code, response.text[:200])
        except Exception as exc:
            print("Cloud upload failed:", exc)
        finally:
            upload_in_progress = False

    threading.Thread(target=worker, daemon=True).start()


def stable_send_command(cmd):
    global pending_command, pending_command_count

    if cmd == pending_command:
        pending_command_count += 1
    else:
        pending_command = cmd
        pending_command_count = 1

    if pending_command_count >= COMMAND_CONFIRM_FRAMES:
        send_command(cmd)


# ============================================================
# MAIN LOOP
# ============================================================

try:
    print("COOKIE human detection running")
    print("Camera source endpoint:", CLOUD_SERVER_URL + CAMERA_SOURCE_ENDPOINT)
    if current_stream_url:
        print("Initial camera stream URL:", current_stream_url)
    print("Cloud:", CLOUD_SERVER_URL)
    print("Command target:", COMMAND_TARGET)
    print(f"Processed local stream: http://127.0.0.1:{LOCAL_STREAM_PORT}/stream")

    while True:
        with latest_raw_lock:
            frame = None if latest_raw_frame is None else latest_raw_frame.copy()

        if frame is None:
            time.sleep(0.02)
            continue

        if ROTATE_FRAME_180:
            frame = cv2.rotate(frame, cv2.ROTATE_180)

        frame = cv2.flip(frame, 1)

        h, w, _ = frame.shape
        center_x = w // 2
        deadband = max(24, int(w * CENTER_DEADBAND_RATIO / 2))
        left_limit = center_x - deadband
        right_limit = center_x + deadband

        cv2.line(frame, (left_limit, 0), (left_limit, h), (0, 0, 255), 2)
        cv2.line(frame, (right_limit, 0), (right_limit, h), (0, 0, 255), 2)
        cv2.putText(frame, "COOKIE HUMAN DETECTION", (20, h - 20), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)

        results = model(frame, imgsz=DETECTION_IMAGE_SIZE, stream=False, verbose=False)

        person_found = False
        biggest_area = 0
        best_box = None

        for result in results:
            for box in result.boxes:
                cls = int(box.cls[0])
                conf = float(box.conf[0])
                if cls == 0 and conf > CONFIDENCE_THRESHOLD:
                    x1, y1, x2, y2 = map(int, box.xyxy[0])
                    area = (x2 - x1) * (y2 - y1)
                    if area > biggest_area:
                        biggest_area = area
                        best_box = (x1, y1, x2, y2)
                        person_found = True

        if person_found:
            no_person_count = 0
            x1, y1, x2, y2 = best_box
            cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 3)
            cx = (x1 + x2) // 2
            cy = (y1 + y2) // 2
            cv2.circle(frame, (cx, cy), 5, (0, 0, 255), -1)
            distance_label = "TRACKING"

            if cx < left_limit:
                direction = "LEFT"
                stable_send_command("left")
            elif cx > right_limit:
                direction = "RIGHT"
                stable_send_command("right")
            else:
                direction = "FORWARD"
                stable_send_command("forward")

            cv2.putText(frame, direction, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
            cv2.putText(frame, distance_label, (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 255, 0), 2)
        else:
            no_person_count += 1
            if no_person_count >= NO_PERSON_STOP_FRAMES:
                stable_send_command("stop")
            cv2.putText(frame, "NO PERSON", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 2)

        set_latest_frame(frame)
        upload_frame_to_cloud(frame)

        cv2.imshow("COOKIE HUMAN FOLLOW", frame)
        if cv2.waitKey(1) & 0xFF == 27:
            send_command("stop")
            break
finally:
    capture_running = False
    send_command("stop")
    cv2.destroyAllWindows()
