from datetime import datetime
import base64
import hashlib
import json
import os
import requests
import secrets
from urllib.parse import quote
from flask import Flask, jsonify, request, Response, stream_with_context
from google.oauth2 import service_account
from google.auth.transport.requests import Request as GoogleAuthRequest

app = Flask(__name__)

# Configuration
ROBOT_STATUS_URL = os.getenv("ROBOT_STATUS_URL", "http://192.168.4.1/status")
DEFAULT_CAMERA_URL = os.getenv("PUBLIC_CAMERA_URL", "/camera")
DEFAULT_LAPTOP_STREAM_URL = os.getenv("LAPTOP_STREAM_URL", "http://host.docker.internal:5001/stream")
POLL_INTERVAL = int(os.getenv("POLL_INTERVAL", "30"))
ENABLE_POLL = os.getenv("ENABLE_POLL", "0") == "1"
API_KEY = os.getenv("COOKIE_API_KEY", "")
ADMIN_EMAIL = os.getenv("COOKIE_ADMIN_EMAIL", "admin@cookie.local")
ADMIN_MOBILE = os.getenv("COOKIE_ADMIN_MOBILE", "0000000000")
ADMIN_PASSWORD = os.getenv("COOKIE_ADMIN_PASSWORD", "")

state = {
    "robot_name": "COOKIE",
    "updated_at": None,
    "voltage": None,
    "battery_percent": None,
    "emotion": None,
    "camera_url": DEFAULT_CAMERA_URL,
    "laptop_stream_url": DEFAULT_LAPTOP_STREAM_URL,
    "camera_updated_at": None,
    "robot_status_url": ROBOT_STATUS_URL,
    "uptime_ms": None,
    "wifi_rssi": None,
    "local_ip": None,
    "free_heap": None,
    "source": "manual",
}

latest_camera_frame = None
camera_source_state = {
    "local_ip": None,
    "page_url": None,
    "stream_url": None,
    "ssid": None,
    "updated_at": None,
}
command_state = {
    "id": 0,
    "command": "stop",
    "updated_at": None,
}
mode_state = {
    "mode": "manual",
    "updated_at": None,
}
emotion_state = {
    "id": 0,
    "emotion": "normal",
    "updated_at": None,
}
sos_state = {
    "active": False,
    "email": None,
    "message": None,
    "updated_at": None,
}
VALID_COMMANDS = {"forward", "backward", "left", "right", "stop"}
VALID_MODES = {"manual", "autonomous"}
VALID_EMOTIONS = {"normal", "happy", "angry", "excited"}
memory_users = {}
memory_keys = set()


def utc_now():
    return datetime.utcnow().isoformat() + "Z"


def require_api_key():
    if not API_KEY:
        return None

    provided = request.headers.get("X-COOKIE-KEY") or request.args.get("key")
    if provided != API_KEY:
        return jsonify({"error": "unauthorized"}), 401

    return None


def firebase_config():
    database_url = os.getenv(
        "FIREBASE_DATABASE_URL",
        "https://cookie-d10f0-default-rtdb.firebaseio.com/",
    ).rstrip("/")
    auth_token = os.getenv("FIREBASE_AUTH_TOKEN", "")
    return database_url, auth_token


def firebase_path_for_key(key):
    if ":" in key:
        collection, item = key.split(":", 1)
    else:
        collection, item = "misc", key

    return f"{quote(collection, safe='')}/{quote(item, safe='')}"


def firebase_url_for_path(path):
    database_url, auth_token = firebase_config()
    url = f"{database_url}/{path}.json"
    if auth_token:
        url += f"?auth={quote(auth_token, safe='')}"
    return url


def storage_get(key):
    firebase_url, _ = firebase_config()
    if firebase_url:
        resp = requests.get(
            firebase_url_for_path(firebase_path_for_key(key)),
            timeout=10,
        )
        resp.raise_for_status()
        return resp.json()

    redis_url = os.getenv("UPSTASH_REDIS_REST_URL", "")
    redis_token = os.getenv("UPSTASH_REDIS_REST_TOKEN", "")

    if redis_url and redis_token:
        resp = requests.post(
            redis_url,
            headers={"Authorization": f"Bearer {redis_token}"},
            json=["GET", key],
            timeout=10,
        )
        resp.raise_for_status()
        value = resp.json().get("result")
        return json.loads(value) if value else None

    return memory_users.get(key)


def storage_set(key, value):
    firebase_url, _ = firebase_config()
    if firebase_url:
        resp = requests.put(
            firebase_url_for_path(firebase_path_for_key(key)),
            json=value,
            timeout=10,
        )
        resp.raise_for_status()
        return

    redis_url = os.getenv("UPSTASH_REDIS_REST_URL", "")
    redis_token = os.getenv("UPSTASH_REDIS_REST_TOKEN", "")

    if redis_url and redis_token:
        payload = json.dumps(value)
        resp = requests.post(
            redis_url,
            headers={"Authorization": f"Bearer {redis_token}"},
            json=["SET", key, payload],
            timeout=10,
        )
        resp.raise_for_status()
        return

    memory_users[key] = value
    memory_keys.add(key)


def storage_scan(prefix):
    firebase_url, _ = firebase_config()
    if firebase_url:
        collection = prefix.split(":", 1)[0] if ":" in prefix else prefix
        resp = requests.get(
            firebase_url_for_path(quote(collection, safe="")),
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json() or {}
        return [f"{collection}:{key}" for key in data.keys()]

    redis_url = os.getenv("UPSTASH_REDIS_REST_URL", "")
    redis_token = os.getenv("UPSTASH_REDIS_REST_TOKEN", "")

    if redis_url and redis_token:
        cursor = "0"
        keys = []
        while True:
            resp = requests.post(
                redis_url,
                headers={"Authorization": f"Bearer {redis_token}"},
                json=["SCAN", cursor, "MATCH", f"{prefix}*", "COUNT", "100"],
                timeout=10,
            )
            resp.raise_for_status()
            result = resp.json().get("result") or ["0", []]
            cursor = str(result[0])
            keys.extend(result[1])
            if cursor == "0":
                break
        return keys

    return [key for key in memory_keys if key.startswith(prefix)]


def runtime_get(name, default):
    return storage_get(f"runtime:{name}") or dict(default)


def runtime_set(name, value):
    storage_set(f"runtime:{name}", value)


def user_key(email):
    return "user:" + hashlib.sha256(email.lower().encode("utf-8")).hexdigest()


def face_snapshot_key(email, person_name):
    raw = f"{email.lower()}:{person_name.lower()}:{utc_now()}:{secrets.token_hex(4)}"
    return "face:" + hashlib.sha256(raw.encode("utf-8")).hexdigest()


def login_key(email):
    raw = f"{email.lower()}:{utc_now()}:{secrets.token_hex(4)}"
    return "login:" + hashlib.sha256(raw.encode("utf-8")).hexdigest()


def sos_key(email):
    raw = f"{email.lower()}:{utc_now()}:{secrets.token_hex(4)}"
    return "sos:" + hashlib.sha256(raw.encode("utf-8")).hexdigest()


def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        100000,
    ).hex()
    return salt, digest


def admin_public_user():
    return {
        "name": "COOKIE Admin",
        "email": ADMIN_EMAIL,
        "mobile": ADMIN_MOBILE,
        "address": "Admin",
        "emergency_contact_name": "Admin Emergency",
        "emergency_contact_number": ADMIN_MOBILE,
        "emergency_contact_email": ADMIN_EMAIL,
        "created_at": "built-in",
        "role": "admin",
    }


def public_user(user):
    return {
        "name": user.get("name", ""),
        "email": user.get("email", ""),
        "mobile": user.get("mobile", ""),
        "address": user.get("address", ""),
        "emergency_contact_name": user.get("emergency_contact_name", ""),
        "emergency_contact_number": user.get("emergency_contact_number", ""),
        "emergency_contact_email": user.get("emergency_contact_email", ""),
        "emergency_contact_fcm_token": user.get("emergency_contact_fcm_token", ""),
        "sos_message": user.get("sos_message", "SOS requested from COOKIE app"),
        "fcm_token": user.get("fcm_token", ""),
        "created_at": user.get("created_at"),
        "role": user.get("role", "user"),
    }


def find_user_by_email_or_mobile(email="", mobile=""):
    email = (email or "").strip().lower()
    mobile = (mobile or "").strip()

    if email:
        user = storage_get(user_key(email))
        if user:
            return user

    if mobile:
        for key in storage_scan("user:"):
            user = storage_get(key)
            if user and user.get("mobile", "").strip() == mobile:
                return user

    return None


def sync_emergency_contact_token(user):
    contact_user = find_user_by_email_or_mobile(
        user.get("emergency_contact_email", ""),
        user.get("emergency_contact_number", ""),
    )
    if not contact_user:
        return user

    token = contact_user.get("fcm_token") or contact_user.get("emergency_contact_fcm_token", "")
    if token:
        user["emergency_contact_fcm_token"] = token
        user["emergency_contact_token_updated_at"] = utc_now()
    return user


def backfill_emergency_contact_tokens_for(contact_user):
    contact_email = contact_user.get("email", "").strip().lower()
    contact_mobile = contact_user.get("mobile", "").strip()
    token = contact_user.get("fcm_token", "")

    if not token:
        return

    for key in storage_scan("user:"):
        user = storage_get(key)
        if not user:
            continue

        emergency_email = user.get("emergency_contact_email", "").strip().lower()
        emergency_mobile = user.get("emergency_contact_number", "").strip()
        if (
            (contact_email and emergency_email == contact_email)
            or (contact_mobile and emergency_mobile == contact_mobile)
        ):
            user["emergency_contact_fcm_token"] = token
            user["emergency_contact_token_updated_at"] = utc_now()
            storage_set(key, user)


def record_login(email, mobile, role, success=True):
    storage_set(login_key(email), {
        "email": email,
        "mobile": mobile,
        "role": role,
        "success": success,
        "created_at": utc_now(),
    })


def send_fcm_push(token, title, body, data_payload=None):
    if not token:
        return {"sent": False, "reason": "device token missing"}

    service_account_json = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON", "")
    firebase_project_id = os.getenv("FIREBASE_PROJECT_ID", "")
    if service_account_json:
        try:
            info = json.loads(service_account_json)
            project_id = firebase_project_id or info.get("project_id")
            if not project_id:
                return {"sent": False, "reason": "Firebase project id missing"}

            credentials = service_account.Credentials.from_service_account_info(
                info,
                scopes=["https://www.googleapis.com/auth/firebase.messaging"],
            )
            credentials.refresh(GoogleAuthRequest())

            data_payload = data_payload or {}
            message_data = {
                "type": "sos",
                "title": title,
                "message": body,
            }
            for key, value in data_payload.items():
                if value is not None:
                    message_data[str(key)] = str(value)

            resp = requests.post(
                f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send",
                headers={
                    "Authorization": f"Bearer {credentials.token}",
                    "Content-Type": "application/json; UTF-8",
                },
                json={
                    "message": {
                        "token": token,
                        "notification": {
                            "title": title,
                            "body": body,
                        },
                        "android": {
                            "priority": "HIGH",
                            "notification": {
                                "channel_id": "cookie_sos",
                                "sound": "default",
                                "click_action": "COOKIE_SOS",
                            },
                        },
                        "data": message_data,
                    }
                },
                timeout=10,
            )
            return {
                "sent": resp.ok,
                "api": "fcm_http_v1",
                "status_code": resp.status_code,
                "response": resp.text[:500],
            }
        except Exception as exc:
            return {"sent": False, "api": "fcm_http_v1", "reason": str(exc)}

    server_key = os.getenv("FCM_SERVER_KEY", "")
    if not server_key:
        return {
            "sent": False,
            "reason": "FIREBASE_SERVICE_ACCOUNT_JSON missing; legacy FCM_SERVER_KEY also missing",
        }

    data_payload = data_payload or {}
    legacy_data = {
        "type": "sos",
        "title": title,
        "message": body,
    }
    for key, value in data_payload.items():
        if value is not None:
            legacy_data[str(key)] = str(value)

    resp = requests.post(
        "https://fcm.googleapis.com/fcm/send",
        headers={
            "Authorization": f"key={server_key}",
            "Content-Type": "application/json",
        },
        json={
            "to": token,
            "notification": {
                "title": title,
                "body": body,
            },
            "data": {
                **legacy_data,
            },
        },
        timeout=10,
    )
    return {
        "sent": resp.ok,
        "api": "fcm_legacy",
        "status_code": resp.status_code,
        "response": resp.text[:500],
    }


def set_state(data, source="manual"):
    state["updated_at"] = utc_now()
    state["source"] = source

    if "battery" in data and "battery_percent" not in data:
        state["battery_percent"] = data["battery"]

    for key in (
        "robot_name",
        "voltage",
        "battery_percent",
        "emotion",
        "camera_url",
        "uptime_ms",
        "wifi_rssi",
        "local_ip",
        "free_heap",
    ):
        if key in data:
            state[key] = data[key]

    runtime_set("state", state)


def add_cors_headers(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    return response


@app.after_request
def after_request(response):
    return add_cors_headers(response)


@app.route("/", methods=["GET"])
def index():
    return jsonify({
        "message": "COOKIE cloud bridge server",
        "endpoints": {
            "status": "/status",
            "update": "/update",
            "command": "/command",
            "command_next": "/command/next",
            "mode": "/mode",
            "emotion": "/emotion",
            "emotion_next": "/emotion/next",
            "sos": "/sos",
            "sos_messages": "/sos/messages",
            "device_token": "/device-token",
            "camera": "/camera",
            "camera_update": "/camera/update",
            "camera_source": "/camera/source",
            "register": "/auth/register",
            "login": "/auth/login",
            "profile": "/profile",
            "face_snapshots": "/face-snapshots",
            "admin_data": "/admin/data",
            "stream": "/stream",
            "robot_fetch": "/robot-fetch",
        },
        "note": "Use /update for robot telemetry, /status for app reads, /command for app control, /camera for the latest uploaded ESP32-CAM snapshot.",
    })


@app.route("/status", methods=["GET"])
def status():
    current_state = runtime_get("state", state)
    current_emotion = runtime_get("emotion", emotion_state)
    current_mode = runtime_get("mode", mode_state)
    result = dict(current_state)
    result["emotion"] = current_emotion["emotion"].upper()
    result["control_mode"] = current_mode["mode"]
    result["mode_updated_at"] = current_mode["updated_at"]
    return jsonify(result)


@app.route("/auth/register", methods=["POST", "OPTIONS"])
def register():
    if request.method == "OPTIONS":
        return Response(status=204)

    data = request.get_json(force=True, silent=True)
    required = ("name", "email", "mobile", "password", "face_snapshot", "face_person_name")
    if not data or any(not data.get(field) for field in required):
        return jsonify({"error": "name, email, mobile, password, face_person_name, and face_snapshot are required"}), 400

    email = data["email"].strip().lower()
    key = user_key(email)
    if storage_get(key):
        return jsonify({"error": "account already exists"}), 409

    salt, password_hash = hash_password(data["password"])
    user = {
        "name": data["name"].strip(),
        "email": email,
        "mobile": data["mobile"].strip(),
        "address": data.get("address", "").strip(),
        "emergency_contact_name": data.get("emergency_contact_name", "").strip(),
        "emergency_contact_number": data.get("emergency_contact_number", "").strip(),
        "emergency_contact_email": data.get("emergency_contact_email", "").strip(),
        "emergency_contact_fcm_token": data.get("emergency_contact_fcm_token", "").strip(),
        "sos_message": data.get("sos_message", "SOS requested from COOKIE app").strip(),
        "password_salt": salt,
        "password_hash": password_hash,
        "face_snapshot": data["face_snapshot"],
        "face_person_name": data["face_person_name"].strip(),
        "face_snapshots": [],
        "created_at": utc_now(),
        "role": "user",
    }
    user = sync_emergency_contact_token(user)
    snapshot_key = face_snapshot_key(email, user["face_person_name"])
    snapshot = {
        "email": email,
        "owner_email": email,
        "person_name": user["face_person_name"],
        "details": {
            "source": "register",
            "owner_email": email,
            "person_name": user["face_person_name"],
        },
        "face_snapshot": data["face_snapshot"],
        "created_at": utc_now(),
        "source": "register",
    }
    storage_set(snapshot_key, snapshot)
    user["face_snapshots"].append({
        "key": snapshot_key,
        "person_name": user["face_person_name"],
        "created_at": snapshot["created_at"],
    })
    storage_set(key, user)
    record_login(email, user["mobile"], "user", success=True)

    return jsonify({
        "status": "ok",
        "user": public_user(user),
    })


@app.route("/auth/login", methods=["POST", "OPTIONS"])
def login():
    if request.method == "OPTIONS":
        return Response(status=204)

    data = request.get_json(force=True, silent=True)
    if not data or not data.get("email") or not data.get("password"):
        return jsonify({"error": "email and password are required"}), 400

    email = data["email"].strip().lower()
    mobile = data.get("mobile", "").strip()

    if (
        ADMIN_PASSWORD
        and email == ADMIN_EMAIL.lower()
        and data["password"] == ADMIN_PASSWORD
        and (not mobile or mobile == ADMIN_MOBILE)
    ):
        record_login(email, mobile or ADMIN_MOBILE, "admin", success=True)
        return jsonify({"status": "ok", "user": admin_public_user()})

    if not mobile:
        return jsonify({"error": "mobile is required"}), 400

    user = storage_get(user_key(email))
    if not user:
        return jsonify({"error": "invalid login"}), 401

    if mobile != user["mobile"]:
        return jsonify({"error": "invalid login"}), 401

    _, password_hash = hash_password(data["password"], user["password_salt"])
    if not secrets.compare_digest(password_hash, user["password_hash"]):
        return jsonify({"error": "invalid login"}), 401

    if data.get("face_snapshot") and data.get("face_person_name"):
        person_name = data["face_person_name"].strip()
        snapshot_key = face_snapshot_key(email, person_name)
        snapshot = {
            "email": email,
            "owner_email": email,
            "person_name": person_name,
            "details": {
                "source": "login",
                "owner_email": email,
                "person_name": person_name,
            },
            "face_snapshot": data["face_snapshot"],
            "created_at": utc_now(),
            "source": "login",
        }
        storage_set(snapshot_key, snapshot)
        snapshots = user.get("face_snapshots", [])
        snapshots.append({
            "key": snapshot_key,
            "person_name": person_name,
            "created_at": snapshot["created_at"],
        })
        user["face_snapshots"] = snapshots[-20:]
        storage_set(user_key(email), user)

    record_login(email, mobile, user.get("role", "user"), success=True)
    return jsonify({"status": "ok", "user": public_user(user)})


@app.route("/profile", methods=["GET", "POST", "OPTIONS"])
def profile():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        email = request.args.get("email", "").strip().lower()
        if not email:
            return jsonify({"error": "email query parameter required"}), 400
        if email == ADMIN_EMAIL.lower():
            return jsonify({"status": "ok", "user": admin_public_user()})
        user = storage_get(user_key(email))
        if not user:
            return jsonify({"error": "profile not found"}), 404
        return jsonify({"status": "ok", "user": public_user(user)})

    data = request.get_json(force=True, silent=True)
    if not data or not data.get("email"):
        return jsonify({"error": "email is required"}), 400

    email = data["email"].strip().lower()
    if email == ADMIN_EMAIL.lower():
        return jsonify({"status": "ok", "user": admin_public_user()})

    key = user_key(email)
    user = storage_get(key)
    if not user:
        return jsonify({"error": "profile not found"}), 404

    for field in (
        "name",
        "mobile",
        "address",
        "emergency_contact_name",
        "emergency_contact_number",
        "emergency_contact_email",
        "emergency_contact_fcm_token",
        "sos_message",
        "fcm_token",
    ):
        if field in data:
            user[field] = str(data[field]).strip()

    user["profile_updated_at"] = utc_now()
    user = sync_emergency_contact_token(user)
    storage_set(key, user)
    return jsonify({"status": "ok", "user": public_user(user)})


@app.route("/device-token", methods=["POST", "OPTIONS"])
def device_token():
    if request.method == "OPTIONS":
        return Response(status=204)

    data = request.get_json(force=True, silent=True)
    if not data or not data.get("email") or not data.get("fcm_token"):
        return jsonify({"error": "email and fcm_token are required"}), 400

    email = data["email"].strip().lower()
    if email == ADMIN_EMAIL.lower():
        return jsonify({"error": "admin account cannot store device token"}), 400

    key = user_key(email)
    user = storage_get(key)
    if not user:
        return jsonify({"error": "profile not found"}), 404

    user["fcm_token"] = str(data["fcm_token"]).strip()
    user["device_token_updated_at"] = utc_now()
    storage_set(key, user)
    backfill_emergency_contact_tokens_for(user)
    return jsonify({"status": "ok", "user": public_user(user)})


@app.route("/update", methods=["POST", "OPTIONS"])
def update():
    if request.method == "OPTIONS":
        return Response(status=204)

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    data = request.get_json(force=True, silent=True)
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    set_state(data, source="push")
    return jsonify({"status": "ok", "state": state})


@app.route("/command", methods=["GET", "POST", "OPTIONS"])
def command():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        return jsonify(runtime_get("command", command_state))

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    data = request.get_json(force=True, silent=True)
    if not data or "command" not in data:
        return jsonify({"error": "JSON body with command required"}), 400

    command_value = str(data["command"]).lower()
    if command_value not in VALID_COMMANDS:
        return jsonify({"error": "invalid command", "valid_commands": sorted(VALID_COMMANDS)}), 400

    current_command = runtime_get("command", command_state)
    current_command["id"] += 1
    current_command["command"] = command_value
    current_command["updated_at"] = utc_now()
    runtime_set("command", current_command)
    return jsonify({"status": "ok", "command": current_command})


@app.route("/command/next", methods=["GET"])
def command_next():
    current_command = runtime_get("command", command_state)
    return Response(
        f"{current_command['id']}:{current_command['command']}",
        content_type="text/plain",
    )


@app.route("/mode", methods=["GET", "POST", "OPTIONS"])
def mode():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        return jsonify(runtime_get("mode", mode_state))

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    data = request.get_json(force=True, silent=True)
    if not data or "mode" not in data:
        return jsonify({"error": "JSON body with mode required"}), 400

    mode_value = str(data["mode"]).lower()
    if mode_value not in VALID_MODES:
        return jsonify({"error": "invalid mode", "valid_modes": sorted(VALID_MODES)}), 400

    current_mode = runtime_get("mode", mode_state)
    current_mode["mode"] = mode_value
    current_mode["updated_at"] = utc_now()
    runtime_set("mode", current_mode)
    return jsonify({"status": "ok", "mode": current_mode})


@app.route("/emotion", methods=["GET", "POST", "OPTIONS"])
def emotion():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        return jsonify(runtime_get("emotion", emotion_state))

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    data = request.get_json(force=True, silent=True)
    if not data or "emotion" not in data:
        return jsonify({"error": "JSON body with emotion required"}), 400

    emotion_value = str(data["emotion"]).lower()
    if emotion_value not in VALID_EMOTIONS:
        return jsonify({"error": "invalid emotion", "valid_emotions": sorted(VALID_EMOTIONS)}), 400

    current_emotion = runtime_get("emotion", emotion_state)
    current_emotion["id"] += 1
    current_emotion["emotion"] = emotion_value
    current_emotion["updated_at"] = utc_now()
    runtime_set("emotion", current_emotion)
    state["emotion"] = emotion_value.upper()
    runtime_set("state", state)
    return jsonify({"status": "ok", "emotion": current_emotion})


@app.route("/emotion/next", methods=["GET"])
def emotion_next():
    current_emotion = runtime_get("emotion", emotion_state)
    return Response(
        f"{current_emotion['id']}:{current_emotion['emotion']}",
        content_type="text/plain",
    )


@app.route("/face-snapshots", methods=["GET", "POST", "OPTIONS"])
def face_snapshots():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        email = request.args.get("email", "").strip().lower()
        faces = []
        for key in storage_scan("face:"):
            face = storage_get(key)
            if face and (not email or face.get("owner_email") == email or face.get("email") == email):
                faces.append({
                    "key": key,
                    "email": face.get("email"),
                    "owner_email": face.get("owner_email"),
                    "person_name": face.get("person_name"),
                    "created_at": face.get("created_at"),
                    "source": face.get("source"),
                    "details": face.get("details", {}),
                    "face_snapshot": face.get("face_snapshot"),
                })
        return jsonify({"status": "ok", "faces": faces})

    data = request.get_json(force=True, silent=True)
    required = ("email", "person_name", "face_snapshot")
    if not data or any(not data.get(field) for field in required):
        return jsonify({"error": "email, person_name, and face_snapshot are required"}), 400

    email = data["email"].strip().lower()
    person_name = data["person_name"].strip()
    snapshot_key = face_snapshot_key(email, person_name)
    snapshot = {
        "email": email,
        "owner_email": email,
        "person_name": person_name,
        "details": {
            "source": data.get("source", "app"),
            "owner_email": email,
            "person_name": person_name,
            "notes": data.get("notes", ""),
        },
        "face_snapshot": data["face_snapshot"],
        "created_at": utc_now(),
        "source": data.get("source", "app"),
    }
    storage_set(snapshot_key, snapshot)

    if email != ADMIN_EMAIL.lower():
        key = user_key(email)
        user = storage_get(key)
        if user:
            snapshots = user.get("face_snapshots", [])
            snapshots.append({
                "key": snapshot_key,
                "person_name": person_name,
                "created_at": snapshot["created_at"],
            })
            user["face_snapshots"] = snapshots[-20:]
            storage_set(key, user)

    return jsonify({
        "status": "ok",
        "snapshot": {
            "key": snapshot_key,
            "person_name": person_name,
            "created_at": snapshot["created_at"],
        },
    })


@app.route("/known-faces", methods=["GET"])
def known_faces():
    faces = []
    for key in storage_scan("face:"):
        face = storage_get(key)
        if face:
            faces.append({
                "key": key,
                "person_name": face.get("person_name"),
                "owner_email": face.get("owner_email"),
                "created_at": face.get("created_at"),
                "details": face.get("details", {}),
                "face_snapshot": face.get("face_snapshot"),
            })
    return jsonify({"status": "ok", "known_faces": faces})


@app.route("/sos", methods=["GET", "POST", "OPTIONS"])
def sos():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        return jsonify(runtime_get("sos", sos_state))

    data = request.get_json(force=True, silent=True) or {}
    email = (data.get("email") or "").strip().lower()
    current_sos = dict(sos_state)
    current_sos["active"] = bool(data.get("active", True))
    current_sos["email"] = email or None
    current_sos["message"] = data.get("message", "SOS requested from COOKIE app")
    current_sos["updated_at"] = utc_now()
    current_sos["push"] = {"sent": False, "reason": "sender not found"}
    location = {
        "available": bool(data.get("location_available", False)),
        "latitude": data.get("location_latitude"),
        "longitude": data.get("location_longitude"),
        "accuracy_m": data.get("location_accuracy_m"),
        "provider": data.get("location_provider"),
        "maps_url": data.get("location_maps_url"),
        "updated_at": utc_now(),
    }
    if location["available"] and not location["maps_url"] and location["latitude"] is not None and location["longitude"] is not None:
        location["maps_url"] = f"https://maps.google.com/?q={location['latitude']},{location['longitude']}"
    current_sos["location"] = location

    if email:
        user = storage_get(user_key(email))
        if user:
            user = sync_emergency_contact_token(user)
            storage_set(user_key(email), user)
            current_sos["message"] = data.get(
                "message",
                user.get("sos_message", "SOS requested from COOKIE app"),
            )
            emergency_email = user.get("emergency_contact_email", "")
            emergency_number = user.get("emergency_contact_number", "")
            contact_user = find_user_by_email_or_mobile(emergency_email, emergency_number)
            token = ""
            if contact_user:
                token = contact_user.get("fcm_token") or contact_user.get("emergency_contact_fcm_token", "")
                current_sos["target_user_email"] = contact_user.get("email")
                current_sos["target_user_mobile"] = contact_user.get("mobile")
            if not token:
                token = user.get("emergency_contact_fcm_token", "")
            current_sos["emergency_contact_name"] = user.get("emergency_contact_name")
            current_sos["emergency_contact_number"] = emergency_number
            current_sos["emergency_contact_email"] = emergency_email
            current_sos["sender_name"] = user.get("name", "")
            push_body = f"SOS from {user.get('name', email)}: {current_sos['message']}"
            if location.get("available") and location.get("maps_url"):
                push_body += f" Location: {location['maps_url']}"
            current_sos["key"] = sos_key(email)
            storage_set(current_sos["key"], current_sos)
            current_sos["push"] = send_fcm_push(
                token,
                "COOKIE SOS Alert",
                push_body,
                {
                    "sos_key": current_sos["key"],
                    "sender_email": email,
                    "sender_name": user.get("name", ""),
                    "maps_url": location.get("maps_url", ""),
                    "sos_message": current_sos["message"],
                },
            )
            storage_set(current_sos["key"], current_sos)

    runtime_set("sos", current_sos)
    return jsonify({"status": "ok", "sos": current_sos})


@app.route("/sos/messages", methods=["GET"])
def sos_messages():
    email = request.args.get("email", "").strip().lower()
    if not email:
        return jsonify({"error": "email query parameter required"}), 400

    user = storage_get(user_key(email)) or {}
    mobile = (user.get("mobile") or "").strip()
    messages = []
    for key in storage_scan("sos:"):
        item = storage_get(key)
        if not item:
            continue

        sender_email = (item.get("email") or "").strip().lower()
        contact_email = (item.get("emergency_contact_email") or "").strip().lower()
        target_email = (item.get("target_user_email") or "").strip().lower()
        contact_number = (item.get("emergency_contact_number") or "").strip()
        target_mobile = (item.get("target_user_mobile") or "").strip()
        if (
            sender_email == email
            or contact_email == email
            or target_email == email
            or (mobile and contact_number == mobile)
            or (mobile and target_mobile == mobile)
        ):
            record = dict(item)
            record["key"] = key
            messages.append(record)

    messages.sort(key=lambda item: item.get("updated_at") or "", reverse=True)
    return jsonify({"status": "ok", "messages": messages[:50]})


@app.route("/admin/data", methods=["POST", "OPTIONS"])
def admin_data():
    if request.method == "OPTIONS":
        return Response(status=204)

    data = request.get_json(force=True, silent=True)
    if (
        not data
        or not ADMIN_PASSWORD
        or data.get("email", "").strip().lower() != ADMIN_EMAIL.lower()
        or data.get("password") != ADMIN_PASSWORD
    ):
        return jsonify({"error": "admin login required"}), 401

    users = []
    for key in storage_scan("user:"):
        user = storage_get(key)
        if user:
            item = public_user(user)
            item["face_snapshots"] = user.get("face_snapshots", [])
            users.append(item)

    faces = []
    for key in storage_scan("face:"):
        face = storage_get(key)
        if face:
            faces.append({
                "key": key,
                "email": face.get("email"),
                "person_name": face.get("person_name"),
                "created_at": face.get("created_at"),
                "source": face.get("source"),
                "has_face_snapshot": bool(face.get("face_snapshot")),
            })

    return jsonify({
        "status": "ok",
        "state": runtime_get("state", state),
        "command": runtime_get("command", command_state),
        "mode": runtime_get("mode", mode_state),
        "emotion": runtime_get("emotion", emotion_state),
        "sos": runtime_get("sos", sos_state),
        "users": users,
        "face_snapshots": faces,
    })


@app.route("/camera", methods=["GET"])
def camera():
    frame = latest_camera_frame
    if frame is None:
        stored_frame = runtime_get("camera_frame", {})
        image_base64 = stored_frame.get("image_base64")
        if image_base64:
            frame = base64.b64decode(image_base64)

    if frame is None:
        return jsonify({"error": "no camera frame uploaded yet"}), 404

    response = Response(frame, content_type="image/jpeg")
    response.headers["Cache-Control"] = "no-store"
    return response


@app.route("/camera/source", methods=["GET", "POST", "OPTIONS"])
def camera_source():
    if request.method == "OPTIONS":
        return Response(status=204)

    if request.method == "GET":
        return jsonify(runtime_get("camera_source", camera_source_state))

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    data = request.get_json(force=True, silent=True) or {}
    local_ip = str(data.get("local_ip", "")).strip()
    stream_url = str(data.get("stream_url", "")).strip()
    page_url = str(data.get("page_url", "")).strip()

    if not stream_url and local_ip:
        stream_url = f"http://{local_ip}/stream"
    if not page_url and local_ip:
        page_url = f"http://{local_ip}"

    source = runtime_get("camera_source", camera_source_state)
    source["local_ip"] = local_ip or source.get("local_ip")
    source["stream_url"] = stream_url or source.get("stream_url")
    source["page_url"] = page_url or source.get("page_url")
    source["ssid"] = str(data.get("ssid", source.get("ssid") or "")).strip()
    source["updated_at"] = utc_now()
    runtime_set("camera_source", source)

    state["esp32_cam_ip"] = source["local_ip"]
    state["esp32_cam_stream_url"] = source["stream_url"]
    state["esp32_cam_page_url"] = source["page_url"]
    state["camera_source_updated_at"] = source["updated_at"]
    runtime_set("state", state)

    return jsonify({"status": "ok", "camera_source": source})


@app.route("/camera/update", methods=["POST", "OPTIONS"])
def camera_update():
    global latest_camera_frame

    if request.method == "OPTIONS":
        return Response(status=204)

    auth_error = require_api_key()
    if auth_error:
        return auth_error

    if request.content_type and request.content_type.startswith("image/jpeg"):
        latest_camera_frame = request.get_data()
    else:
        data = request.get_json(force=True, silent=True)
        if not data or "image_base64" not in data:
            return jsonify({"error": "send image/jpeg body or JSON with image_base64"}), 400
        latest_camera_frame = base64.b64decode(data["image_base64"])

    state["camera_updated_at"] = utc_now()
    state["camera_url"] = "/camera"
    runtime_set("camera_frame", {
        "image_base64": base64.b64encode(latest_camera_frame).decode("ascii"),
        "updated_at": state["camera_updated_at"],
    })
    runtime_set("state", state)
    return jsonify({"status": "ok", "camera_updated_at": state["camera_updated_at"]})


@app.route("/robot-fetch", methods=["GET"])
def robot_fetch():
    try:
        resp = requests.get(ROBOT_STATUS_URL, timeout=5)
        resp.raise_for_status()
        data = resp.json()
        set_state(data, source="robot-fetch")
        return jsonify({"status": "ok", "state": state})
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


def stream_mjpeg(url):
    with requests.get(url, stream=True, timeout=10) as resp:
        for chunk in resp.iter_content(chunk_size=1024):
            if chunk:
                yield chunk


@app.route("/stream", methods=["GET"])
def stream():
    camera_url = request.args.get("url") or request.args.get("camera_url")
    if not camera_url:
        camera_url = state.get("laptop_stream_url") or DEFAULT_LAPTOP_STREAM_URL
    if camera_url.startswith("/"):
        camera_url = f"http://127.0.0.1:{os.getenv('PORT', '5000')}{camera_url}"
    return Response(
        stream_with_context(stream_mjpeg(camera_url)),
        content_type="multipart/x-mixed-replace;boundary=frame",
    )


def poll_robot_status():
    while True:
        try:
            resp = requests.get(ROBOT_STATUS_URL, timeout=5)
            resp.raise_for_status()
            data = resp.json()
            set_state(data, source="poll")
        except Exception:
            pass
        finally:
            import time
            time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    if ENABLE_POLL:
        import threading

        thread = threading.Thread(target=poll_robot_status, daemon=True)
        thread.start()

    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5000")), debug=False)
