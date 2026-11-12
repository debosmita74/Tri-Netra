# COOKIE Firebase Database Setup

The Android app talks to the COOKIE cloud API at:

```text
https://cookie-robot-cloud.vercel.app
```

Firebase is used behind that API as the database. The phone app and ESP32 code do
not need Firebase SDKs; they continue using the existing cloud API.

## 1. Create Firebase Realtime Database

1. Open the Firebase Console.
2. Create or select a project.
3. Go to **Build > Realtime Database**.
4. Create a database.
5. Choose a region.
6. Start in locked mode for real use.

Your database URL is:

```text
https://cookie-d10f0-default-rtdb.firebaseio.com
```

## 2. Set Vercel Environment Variables

In the Vercel project `cookie-robot-cloud`, add:

```text
FIREBASE_DATABASE_URL=https://cookie-d10f0-default-rtdb.firebaseio.com
FIREBASE_AUTH_TOKEN=your-database-secret-or-service-token
```

Then redeploy the Vercel project.

If your Firebase rules temporarily allow public read/write during testing, you
can leave `FIREBASE_AUTH_TOKEN` empty. Do not keep public write rules for real
robot use.

## 3. Stored Data

The cloud API stores these collections in Firebase:

```text
user
face
runtime
```

Examples:

```text
user/{hashed_email}
face/{snapshot_id}
runtime/state
runtime/command
runtime/mode
runtime/emotion
runtime/sos
login/{login_event_id}
```

Face records include the stored image payload and details:

```text
face/{snapshot_id}/person_name
face/{snapshot_id}/owner_email
face/{snapshot_id}/face_snapshot
face/{snapshot_id}/details
```

The robot or a companion recognition script can call:

```text
GET https://cookie-robot-cloud.vercel.app/known-faces
```

to download known face snapshots and compare detected faces locally. The cloud
database stores the known-face gallery; the actual face comparison still needs a
face recognition model on the robot/laptop side.

## 4. SOS Push Notifications

SOS push notification uses Firebase Cloud Messaging over internet, so no SIM is
needed. The cloud server sends pushes through the current FCM HTTP v1 API, so
configure this Vercel environment variable:

```text
FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account",...}
```

To create it, open Firebase Console > Project settings > Service accounts >
Generate new private key. Copy the entire downloaded JSON content into the
Vercel environment variable above, then redeploy.

Optional override:

```text
FIREBASE_PROJECT_ID=cookie-d10f0
```

Each registered user profile can store its own `fcm_token`. When `/sos` is
called, the cloud API looks at the sender's `emergency_contact_email` and
`emergency_contact_number`, finds the registered COOKIE user with that same
email or mobile number, and sends the SOS push to that contact user's
`fcm_token`.

The older `emergency_contact_fcm_token` field is still supported as a fallback.
For fully automatic token capture, add Firebase Cloud Messaging to the Android
project with your `google-services.json` and post the generated token to:

```text
POST https://cookie-robot-cloud.vercel.app/device-token
```

Body:

```json
{"email":"contact@example.com","fcm_token":"device-token-from-firebase"}
```

If an SOS does not show as a notification, inspect:

```text
GET https://cookie-robot-cloud.vercel.app/sos
```

The `push` object reports the API used, HTTP status code, and FCM error text.

## 5. Admin Data View

Use the admin endpoint:

```bash
curl -X POST https://cookie-robot-cloud.vercel.app/admin/data \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@cookie.local\",\"password\":\"YOUR_CONFIGURED_ADMIN_PASSWORD\"}"
```

You can also inspect the same data directly in the Firebase Console under
Realtime Database.
