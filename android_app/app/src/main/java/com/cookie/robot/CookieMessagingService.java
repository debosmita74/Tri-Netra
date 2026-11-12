package com.cookie.robot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CookieMessagingService extends FirebaseMessagingService {
    private static final String BASE_URL = "https://cookie-robot-cloud.vercel.app";
    private static final String ADMIN_EMAIL = "admin@cookie.local";
    private static final String SOS_CHANNEL_ID = "cookie_sos";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        SharedPreferences prefs = getSharedPreferences("cookie_app", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();

        String email = prefs.getString("email", "");
        if (email.isEmpty() || ADMIN_EMAIL.equalsIgnoreCase(email)) {
            return;
        }

        uploadDeviceToken(email, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        String title = "COOKIE SOS Alert";
        String body = "SOS received. Check COOKIE app immediately.";

        if (message.getNotification() != null) {
            if (message.getNotification().getTitle() != null) {
                title = message.getNotification().getTitle();
            }
            if (message.getNotification().getBody() != null) {
                body = message.getNotification().getBody();
            }
        }

        if (message.getData().containsKey("title")) {
            title = message.getData().get("title");
        }
        if (message.getData().containsKey("message")) {
            body = message.getData().get("message");
        }

        showSosNotification(
                title,
                body,
                message.getData().get("sos_key"),
                message.getData().get("maps_url")
        );
    }

    private void showSosNotification(String title, String body, String sosKey, String mapsUrl) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    SOS_CHANNEL_ID,
                    "COOKIE SOS",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Emergency alerts from COOKIE");
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("COOKIE_SOS");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("open_sos_messages", true);
        intent.putExtra("sos_key", sosKey == null ? "" : sosKey);
        intent.putExtra("maps_url", mapsUrl == null ? "" : mapsUrl);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, SOS_CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setSmallIcon(R.drawable.ic_cookie)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();

        manager.notify((int) System.currentTimeMillis(), notification);
    }

    private void uploadDeviceToken(String email, String token) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("email", email);
                body.put("fcm_token", token);

                URL url = new URL(BASE_URL + "/device-token");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");

                OutputStream output = connection.getOutputStream();
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();

                InputStream input = connection.getResponseCode() >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                if (input != null) {
                    input.close();
                }
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }).start();
    }
}
