# COOKIE Android App

Native Android app for the COOKIE surveillance and home assistance robot.

Cloud server:

```text
https://cookie-robot-cloud.vercel.app
```

## Features

- Register account with email, mobile number, password, and face snapshot.
- Login with email, mobile number, and password.
- Registration/profile collects name, address, emergency contact name, emergency
  contact number, and emergency contact email.
- Welcome screen styled like the supplied reference image.
- Supplied `NETRA` image used as the app launcher icon and visible page logo.
- Hamburger side menu is login-gated. Before login it only shows Login and
  Support. After login it shows Dashboard, Profile, Face Snapshot, Manual
  Controls, Support, and Logout.
- Smooth page fade/slide transitions, button press scale feedback, and animated
  hamburger drawer open/close.
- Side menu includes a persisted Dark/Night mode toggle. Dark mode is the
  default black UI; Night mode shifts panels and accents to a softer teal-black
  palette.
- Face snapshot capture is on a separate page after account details and asks for
  the person's name before uploading the snapshot to the cloud.
- Standalone Face Snapshot menu option lets a logged-in user save named face
  snapshots to the cloud.
- Dashboard includes an SOS button that posts to `/sos`.
- Profile includes a preset SOS message; the SOS button sends that saved message
  to the emergency contact.
- SOS can trigger Firebase Cloud Messaging push when `FCM_SERVER_KEY` and an
  emergency contact user's device `fcm_token` are configured. The sender's
  emergency contact email/mobile is matched to a registered COOKIE user, and
  that user's device token receives the push over internet.
- Manual controls open as a landscape camera-overlay page with symbol buttons.
- My Faces page lists the saved face pictures and details for the logged-in user.
- Known face data is stored in Firebase and exposed through `/known-faces` for a
  robot-side face-recognition script to compare known vs unknown faces.
- View latest robot camera snapshot from `/camera`.
- View robot telemetry from `/status`:
  - battery voltage
  - battery percentage
  - robot emotion
  - connected WiFi
  - control mode
- Manual movement controls:
  - forward
  - backward
  - left
  - right
  - stop
- Manual emotion controls:
  - normal
  - happy
  - excited
  - angry
- Manual/autonomous mode toggle through `/mode`.
- Profile editor saves to `/profile`.
- Profile editor includes this device's FCM push token field for SOS delivery.

## Admin Data View

Use the admin endpoint to view data currently stored by the cloud API:

```bash
curl -X POST https://cookie-robot-cloud.vercel.app/admin/data \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@cookie.local\",\"password\":\"YOUR_CONFIGURED_ADMIN_PASSWORD\"}"
```

It returns robot status, command state, mode, SOS state, users, and face snapshot
metadata. Face image payloads are not returned in this admin listing to keep the
response manageable.

If `FIREBASE_DATABASE_URL` is configured on Vercel, this data is persistent and
can also be viewed in Firebase Console > Realtime Database. Without Firebase,
Vercel function memory can reset.

## Build

Open this `android_app` folder in Android Studio and run the `app` configuration.

## Admin Login

Admin login is enabled only when `COOKIE_ADMIN_PASSWORD` is configured in the
cloud deployment. Enter the configured credentials in the app; the password is
not included in the client or source repository. Admin login does not require a
face snapshot. Normal user login and registration still use the separate face
snapshot page.

The app currently uses:

```java
private static final String BASE_URL = "https://cookie-robot-cloud.vercel.app";
private static final String API_KEY = "";
```

If you add `COOKIE_API_KEY` on Vercel, put the same value in `API_KEY` in:

```text
app/src/main/java/com/cookie/robot/MainActivity.java
```

## Important Cloud Storage Note

The deployed API supports persistent Firebase Realtime Database storage when
these Vercel environment variables are configured:

```text
FIREBASE_DATABASE_URL
FIREBASE_AUTH_TOKEN
```

Without Firebase, registration/login uses temporary server memory. That is fine
for a quick test but can reset when Vercel restarts a serverless function.
