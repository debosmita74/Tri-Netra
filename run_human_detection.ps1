$env:CLOUD_SERVER_URL = if ($env:CLOUD_SERVER_URL) { $env:CLOUD_SERVER_URL } else { "https://cookie-robot-cloud.vercel.app" }
$env:COMMAND_TARGET = if ($env:COMMAND_TARGET) { $env:COMMAND_TARGET } else { "cloud" }
$env:UPLOAD_INTERVAL_SECONDS = if ($env:UPLOAD_INTERVAL_SECONDS) { $env:UPLOAD_INTERVAL_SECONDS } else { "0.5" }

python human_detection.py
