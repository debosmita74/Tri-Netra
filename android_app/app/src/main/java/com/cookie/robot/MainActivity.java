package com.cookie.robot;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE_URL = "https://cookie-robot-cloud.vercel.app";
    private static final String API_KEY = "";
    private static final String ADMIN_EMAIL = "admin@cookie.local";
    private static final String ADMIN_MOBILE = "0000000000";
    private static final int FACE_CAPTURE_REQUEST = 7;
    private static final int CAMERA_PERMISSION_REQUEST = 8;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 9;
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final String SOS_CHANNEL_ID = "cookie_sos";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Bitmap faceBitmap;
    private ImageView facePreview;
    private ImageView cameraFeed;
    private TextView statusText;
    private TextView voltageText;
    private TextView batteryText;
    private TextView emotionText;
    private TextView wifiText;
    private TextView modeText;
    private Switch modeSwitch;
    private LinearLayout commandPanel;
    private ProgressBar cameraLoading;
    private FrameLayout currentFrame;
    private LinearLayout currentMenu;
    private View menuScrim;
    private String currentPageTitle = "Home";
    private boolean sendSosAfterLocationPermission = false;

    private String pendingAction = "";
    private String pendingName = "";
    private String pendingEmail = "";
    private String pendingMobile = "";
    private String pendingPassword = "";
    private String pendingAddress = "";
    private String pendingEmergencyName = "";
    private String pendingEmergencyNumber = "";
    private String pendingEmergencyEmail = "";
    private EditText facePersonInput;

    private final Runnable statusLoop = new Runnable() {
        @Override
        public void run() {
            fetchStatus();
            refreshHandler.postDelayed(this, 5000);
        }
    };

    private final Runnable cameraLoop = new Runnable() {
        @Override
        public void run() {
            fetchCameraFrame();
            refreshHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cookie_app", MODE_PRIVATE);
        createSosNotificationChannel();

        if (prefs.getBoolean("logged_in", false)) {
            if (!handleLaunchIntent(getIntent())) {
                showDashboard();
            }
            requestNotificationPermission();
            registerFcmToken();
        } else {
            showWelcome();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (prefs != null && prefs.getBoolean("logged_in", false)) {
            handleLaunchIntent(intent);
        }
    }

    private boolean handleLaunchIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        boolean openSos = intent.getBooleanExtra("open_sos_messages", false)
                || "COOKIE_SOS".equals(intent.getAction())
                || "sos".equalsIgnoreCase(intent.getStringExtra("type"));
        if (openSos) {
            showSosMessages();
            return true;
        }
        return false;
    }

    private void createSosNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private void showWelcome() {
        refreshHandler.removeCallbacksAndMessages(null);
        faceBitmap = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(26), dp(48), dp(26), dp(30));
        root.setBackgroundColor(bgColor());

        Space top = new Space(this);
        root.addView(top, new LinearLayout.LayoutParams(1, 0, 1));

        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.app_logo);
        shield.setAdjustViewBounds(true);
        root.addView(shield, new LinearLayout.LayoutParams(dp(230), dp(230)));

        TextView headline = new TextView(this);
        headline.setText("Monitor your robot with\nease");
        headline.setTextColor(Color.WHITE);
        headline.setTextSize(25);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setGravity(Gravity.CENTER);
        headline.setLineSpacing(dp(3), 1.0f);
        root.addView(headline);

        TextView body = new TextView(this);
        body.setText("Don't worry about security when using our\ncompact solution, we've got you covered");
        body.setTextColor(Color.rgb(150, 150, 150));
        body.setTextSize(13);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bodyParams.setMargins(0, dp(20), 0, dp(16));
        root.addView(body, bodyParams);

        Button getStarted = pillButton("Get Started", true);
        getStarted.setOnClickListener(v -> showAuthForm(true));
        root.addView(getStarted, fullWidthParams(dp(54)));

        Button login = outlineButton("Login");
        login.setOnClickListener(v -> showAuthForm(false));
        LinearLayout.LayoutParams loginParams = fullWidthParams(dp(54));
        loginParams.setMargins(0, dp(14), 0, 0);
        root.addView(login, loginParams);

        Space bottom = new Space(this);
        root.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1));

        setPageContent(root, "Home");
    }

    private void showAuthForm(boolean register) {
        faceBitmap = null;
        pendingAction = register ? "register" : "login";

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        TextView title = title(register ? "Create Account" : "Login");
        TextView subtitle = smallText(register
                ? "Enter your details first. Face capture is on the next page."
                : "Use your email, mobile number, and password.");

        EditText name = input("Full name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText mobile = input("Mobile number", InputType.TYPE_CLASS_PHONE);
        EditText password = input("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText address = input("Address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText emergencyName = input("Emergency contact name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText emergencyNumber = input("Emergency contact number", InputType.TYPE_CLASS_PHONE);
        EditText emergencyEmail = input("Emergency contact email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        Button next = pillButton(register ? "Continue" : "Login", true);
        next.setOnClickListener(v -> {
            pendingName = name.getText().toString().trim();
            pendingEmail = email.getText().toString().trim();
            pendingMobile = mobile.getText().toString().trim();
            pendingPassword = password.getText().toString();
            pendingAddress = address.getText().toString().trim();
            pendingEmergencyName = emergencyName.getText().toString().trim();
            pendingEmergencyNumber = emergencyNumber.getText().toString().trim();
            pendingEmergencyEmail = emergencyEmail.getText().toString().trim();

            if (pendingEmail.isEmpty() || pendingPassword.isEmpty()) {
                toast("Enter email and password.");
                return;
            }

            if (register && (pendingName.isEmpty() || pendingMobile.isEmpty())) {
                toast("Enter name and mobile number.");
                return;
            }

            if (!register && pendingMobile.isEmpty()) {
                toast("Enter mobile number.");
                return;
            }

            if (!register) {
                loginUser(false);
                return;
            }

            showFacePage();
        });

        Button back = outlineButton("Back");
        back.setOnClickListener(v -> showWelcome());

        root.addView(title);
        root.addView(subtitle);
        addGap(root, 24);
        if (register) {
            root.addView(name);
        }
        root.addView(email);
        root.addView(mobile);
        root.addView(password);
        if (register) {
            root.addView(address);
            root.addView(emergencyName);
            root.addView(emergencyNumber);
            root.addView(emergencyEmail);
        }
        addGap(root, 16);
        root.addView(next, fullWidthParams(dp(54)));
        LinearLayout.LayoutParams backParams = fullWidthParams(dp(54));
        backParams.setMargins(0, dp(14), 0, 0);
        root.addView(back, backParams);

        setPageContent(scroll, register ? "Create Account" : "Login");
    }

    private void showFacePage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        TextView title = title("Face Snapshot");
        String purpose = pendingAction.equals("register") ? "finish registration" : "save this snapshot to the cloud";
        TextView subtitle = smallText("Enter the person's name and capture a clear face snapshot to " + purpose + ".");

        facePersonInput = input("Person name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);

        facePreview = new ImageView(this);
        facePreview.setBackgroundColor(Color.rgb(18, 18, 18));
        facePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        facePreview.setImageResource(android.R.drawable.ic_menu_camera);
        GradientDrawable previewBg = rounded(Color.rgb(18, 18, 18), dp(26), Color.rgb(70, 70, 70), 1);
        facePreview.setBackground(previewBg);

        Button capture = pillButton("Take Face Snapshot", true);
        capture.setOnClickListener(v -> captureFace());

        Button finish = pillButton(pendingAction.equals("register") ? "Create Account" : "Save Snapshot", true);
        finish.setOnClickListener(v -> {
            if (facePersonInput.getText().toString().trim().isEmpty()) {
                toast("Enter the person's name.");
                return;
            }

            if (faceBitmap == null) {
                toast("Capture your face snapshot first.");
                return;
            }

            if (pendingAction.equals("register")) {
                registerUser();
            } else {
                uploadNamedFaceSnapshot();
            }
        });

        Button back = outlineButton("Back");
        back.setOnClickListener(v -> {
            if (pendingAction.isEmpty()) {
                showDashboard();
            } else {
                showAuthForm(pendingAction.equals("register"));
            }
        });

        root.addView(title);
        root.addView(subtitle);
        addGap(root, 22);
        root.addView(facePersonInput);
        root.addView(facePreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(260)
        ));
        addGap(root, 16);
        root.addView(capture, fullWidthParams(dp(54)));
        LinearLayout.LayoutParams finishParams = fullWidthParams(dp(54));
        finishParams.setMargins(0, dp(14), 0, 0);
        root.addView(finish, finishParams);
        LinearLayout.LayoutParams backParams = fullWidthParams(dp(54));
        backParams.setMargins(0, dp(14), 0, 0);
        root.addView(back, backParams);

        setPageContent(scroll, "Face Snapshot");
    }

    private void showDashboard() {
        refreshHandler.removeCallbacksAndMessages(null);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = title("COOKIE Control");
        Button logout = secondaryButton("Logout");
        logout.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            showWelcome();
        });

        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(logout, new LinearLayout.LayoutParams(dp(112), dp(48)));
        root.addView(top);

        statusText = smallText("Connecting to cloud...");
        root.addView(statusText);

        Button sos = dangerButton("SOS");
        sos.setOnClickListener(v -> sendSos());
        LinearLayout.LayoutParams sosParams = fullWidthParams(dp(58));
        sosParams.setMargins(0, dp(12), 0, dp(4));
        root.addView(sos, sosParams);

        addGap(root, 12);
        root.addView(sectionTitle("Live Robot Feed"));

        cameraLoading = new ProgressBar(this);
        cameraFeed = new ImageView(this);
        cameraFeed.setBackgroundColor(Color.rgb(18, 18, 18));
        cameraFeed.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cameraFeed.setImageResource(android.R.drawable.ic_menu_camera);
        cameraFeed.setBackground(rounded(Color.rgb(18, 18, 18), dp(18), Color.rgb(45, 45, 45), 1));
        root.addView(cameraFeed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(230)
        ));
        root.addView(cameraLoading);

        addGap(root, 14);
        root.addView(sectionTitle("Robot Details"));
        voltageText = infoLine("Voltage", "--");
        batteryText = infoLine("Battery", "--");
        emotionText = infoLine("Emotion", "--");
        wifiText = infoLine("WiFi", "--");
        modeText = infoLine("Mode", "--");
        root.addView(voltageText);
        root.addView(batteryText);
        root.addView(emotionText);
        root.addView(wifiText);
        root.addView(modeText);

        addGap(root, 14);
        root.addView(sectionTitle("Control Mode"));
        modeSwitch = new Switch(this);
        modeSwitch.setText("Autonomous mode");
        modeSwitch.setTextColor(Color.WHITE);
        modeSwitch.setTextSize(16);
        modeSwitch.setPadding(0, dp(8), 0, dp(8));
        modeSwitch.setOnCheckedChangeListener(this::onModeChanged);
        root.addView(modeSwitch);

        Button manual = pillButton("Open Manual Camera Control", true);
        manual.setOnClickListener(v -> showManualControls());
        LinearLayout.LayoutParams manualParams = fullWidthParams(dp(58));
        manualParams.setMargins(0, dp(16), 0, 0);
        root.addView(manual, manualParams);

        setPageContent(scroll, "Dashboard");
        refreshHandler.post(statusLoop);
        refreshHandler.post(cameraLoop);
    }

    private void setPageContent(View content, String pageTitle) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        currentPageTitle = pageTitle;
        currentFrame = new FrameLayout(this);
        currentFrame.setBackgroundColor(bgColor());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bgColor());
        page.setAlpha(0.0f);
        page.setTranslationX(dp(18));

        page.addView(topBar(pageTitle), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
        ));

        page.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        currentFrame.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        menuScrim = new View(this);
        menuScrim.setBackgroundColor(Color.argb(96, 0, 0, 0));
        menuScrim.setVisibility(View.GONE);
        menuScrim.setAlpha(0.0f);
        menuScrim.setOnClickListener(v -> hideMenu());
        currentFrame.addView(menuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        currentMenu = sideMenu();
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                dp(292),
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        menuParams.gravity = Gravity.START;
        currentMenu.setVisibility(View.GONE);
        currentMenu.setTranslationX(-dp(292));
        currentMenu.setAlpha(0.0f);
        currentFrame.addView(currentMenu, menuParams);

        setContentView(currentFrame);

        page.animate()
                .alpha(1.0f)
                .translationX(0)
                .setDuration(240)
                .start();
    }

    private LinearLayout topBar(String pageTitle) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(16), dp(8));
        bar.setBackgroundColor(bgColor());

        Button menu = new Button(this);
        menu.setText("\u2630");
        menu.setTextColor(Color.WHITE);
        menu.setTextSize(26);
        menu.setAllCaps(false);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setOnClickListener(v -> toggleMenu());
        attachPressAnimation(menu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(52), dp(52)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(pageTitle);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        return bar;
    }

    private LinearLayout sideMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(18), dp(28), dp(18), dp(18));
        menu.setBackground(rounded(menuColor(), 0, accentColor(), 1));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_logo);
        logo.setAdjustViewBounds(true);
        menu.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(138)
        ));

        TextView label = new TextView(this);
        label.setText("COOKIE MENU");
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, dp(18));
        menu.addView(label);

        Switch nightToggle = new Switch(this);
        nightToggle.setText("Night mode");
        nightToggle.setTextColor(Color.WHITE);
        nightToggle.setTextSize(15);
        nightToggle.setPadding(dp(8), 0, dp(8), dp(12));
        nightToggle.setChecked(isNightMode());
        nightToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("night_mode", isChecked).apply();
            toast(isChecked ? "Night mode enabled" : "Dark mode enabled");
            if (prefs.getBoolean("logged_in", false)) {
                showDashboard();
            } else {
                showWelcome();
            }
        });
        menu.addView(nightToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        boolean loggedIn = prefs.getBoolean("logged_in", false);

        if (!loggedIn) {
            menu.addView(menuButton("Login", v -> showAuthForm(false)));
            menu.addView(menuButton("Support", v -> showSupport()));
        } else {
            menu.addView(menuButton("Dashboard", v -> showDashboard()));
            menu.addView(menuButton("Profile", v -> showProfile()));
            menu.addView(menuButton("My Faces", v -> showFaceGallery()));
            menu.addView(menuButton("Face Snapshot", v -> {
                pendingAction = "";
                faceBitmap = null;
                showFacePage();
            }));
            menu.addView(menuButton("SOS Messages", v -> showSosMessages()));
            menu.addView(menuButton("Manual Controls", v -> showManualControls()));
            menu.addView(menuButton("Support", v -> showSupport()));
        }

        Space spacer = new Space(this);
        menu.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        if (loggedIn) {
            menu.addView(menuButton("Logout", v -> {
                prefs.edit().clear().apply();
                pendingAction = "";
                showWelcome();
            }));
        }

        return menu;
    }

    private TextView menuButton(String text, View.OnClickListener listener) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextColor(Color.WHITE);
        item.setTextSize(16);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setBackground(rounded(panelColor(), dp(28), Color.WHITE, 1));
        item.setOnClickListener(listener);
        attachPressAnimation(item);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        params.setMargins(0, 0, 0, dp(10));
        item.setLayoutParams(params);
        return item;
    }

    private void toggleMenu() {
        if (currentMenu == null) {
            return;
        }

        if (currentMenu.getVisibility() == View.VISIBLE) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        if (currentMenu == null) {
            return;
        }
        if (menuScrim != null) {
            menuScrim.setVisibility(View.VISIBLE);
            menuScrim.bringToFront();
            menuScrim.animate()
                    .alpha(1.0f)
                    .setDuration(180)
                    .start();
        }
        currentMenu.setVisibility(View.VISIBLE);
        currentMenu.bringToFront();
        currentMenu.setTranslationX(-dp(292));
        currentMenu.setAlpha(0.0f);
        currentMenu.animate()
                .translationX(0)
                .alpha(1.0f)
                .setDuration(260)
                .start();
    }

    private void hideMenu() {
        if (currentMenu == null || currentMenu.getVisibility() != View.VISIBLE) {
            return;
        }
        currentMenu.animate()
                .translationX(-currentMenu.getWidth())
                .alpha(0.0f)
                .setDuration(220)
                .withEndAction(() -> currentMenu.setVisibility(View.GONE))
                .start();
        if (menuScrim != null) {
            menuScrim.animate()
                    .alpha(0.0f)
                    .setDuration(180)
                    .withEndAction(() -> menuScrim.setVisibility(View.GONE))
                    .start();
        }
    }

    private void showSupport() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        root.addView(title("Support"));
        root.addView(smallText("For COOKIE robot assistance, use the admin login first or contact your robot maintainer."));
        addGap(root, 18);
        root.addView(infoLine("Cloud", BASE_URL));
        root.addView(infoLine("Admin email", ADMIN_EMAIL));
        root.addView(infoLine("Emergency", "Use SOS after login"));

        Button login = pillButton("Login", true);
        login.setOnClickListener(v -> showAuthForm(false));
        LinearLayout.LayoutParams loginParams = fullWidthParams(dp(54));
        loginParams.setMargins(0, dp(22), 0, 0);
        root.addView(login, loginParams);

        setPageContent(scroll, "Support");
    }

    private void showManualControls() {
        refreshHandler.removeCallbacksAndMessages(null);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        cameraFeed = new ImageView(this);
        cameraFeed.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cameraFeed.setBackgroundColor(Color.BLACK);
        cameraFeed.setImageResource(android.R.drawable.ic_menu_camera);
        root.addView(cameraFeed, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        cameraLoading = new ProgressBar(this);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        loadingParams.gravity = Gravity.CENTER;
        root.addView(cameraLoading, loadingParams);

        Button close = overlayButton("×");
        close.setOnClickListener(v -> showDashboard());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(58), dp(58));
        closeParams.gravity = Gravity.TOP | Gravity.RIGHT;
        closeParams.setMargins(0, dp(12), dp(12), 0);
        root.addView(close, closeParams);

        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setOrientation(LinearLayout.VERTICAL);
        leftControls.setGravity(Gravity.CENTER);
        Button forward = overlayButton("↑");
        Button stop = overlayButton("■");
        Button backward = overlayButton("↓");
        forward.setOnClickListener(v -> sendCommand("forward"));
        stop.setOnClickListener(v -> sendCommand("stop"));
        backward.setOnClickListener(v -> sendCommand("backward"));
        leftControls.addView(forward, overlayParams());
        leftControls.addView(stop, overlayParams());
        leftControls.addView(backward, overlayParams());
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT);
        leftParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        leftParams.setMargins(dp(20), 0, 0, 0);
        root.addView(leftControls, leftParams);

        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setOrientation(LinearLayout.VERTICAL);
        rightControls.setGravity(Gravity.CENTER);
        Button left = overlayButton("←");
        Button right = overlayButton("→");
        left.setOnClickListener(v -> sendCommand("left"));
        right.setOnClickListener(v -> sendCommand("right"));
        rightControls.addView(left, overlayParams());
        rightControls.addView(right, overlayParams());
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT);
        rightParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        rightParams.setMargins(0, 0, dp(20), 0);
        root.addView(rightControls, rightParams);

        LinearLayout emotionControls = new LinearLayout(this);
        emotionControls.setGravity(Gravity.CENTER);
        String[] emotions = {"●", "☺", "◇", "!"};
        String[] values = {"normal", "happy", "excited", "angry"};
        for (int i = 0; i < emotions.length; i++) {
            final String emotion = values[i];
            Button button = overlayButton(emotions[i]);
            button.setOnClickListener(v -> sendEmotion(emotion));
            emotionControls.addView(button, overlayParams());
        }
        FrameLayout.LayoutParams emotionParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(82));
        emotionParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        emotionParams.setMargins(0, 0, 0, dp(16));
        root.addView(emotionControls, emotionParams);

        setContentView(root);
        refreshHandler.post(cameraLoop);
    }

    private void showProfile() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        EditText name = input("Name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText mobile = input("Mobile number", InputType.TYPE_CLASS_PHONE);
        EditText email = input("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText address = input("Address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText emergencyName = input("Emergency contact name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        EditText emergencyNumber = input("Emergency contact number", InputType.TYPE_CLASS_PHONE);
        EditText emergencyEmail = input("Emergency contact email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText sosMessage = input("Preset SOS message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText fcmToken = input("This device FCM push token", InputType.TYPE_CLASS_TEXT);

        email.setText(prefs.getString("email", ""));
        name.setText(prefs.getString("name", ""));
        mobile.setText(prefs.getString("mobile", ""));
        address.setText(prefs.getString("address", ""));
        emergencyName.setText(prefs.getString("emergency_contact_name", ""));
        emergencyNumber.setText(prefs.getString("emergency_contact_number", ""));
        emergencyEmail.setText(prefs.getString("emergency_contact_email", ""));
        sosMessage.setText(prefs.getString("sos_message", "SOS requested from COOKIE app"));
        fcmToken.setText(prefs.getString("fcm_token", ""));
        email.setEnabled(false);

        Button save = pillButton("Save Profile", true);
        save.setOnClickListener(v -> saveProfile(
                name.getText().toString(),
                mobile.getText().toString(),
                email.getText().toString(),
                address.getText().toString(),
                emergencyName.getText().toString(),
                emergencyNumber.getText().toString(),
                emergencyEmail.getText().toString(),
                sosMessage.getText().toString(),
                fcmToken.getText().toString()
        ));

        root.addView(title("Profile"));
        root.addView(smallText("These details are saved to the COOKIE cloud."));
        addGap(root, 18);
        root.addView(name);
        root.addView(mobile);
        root.addView(email);
        root.addView(address);
        root.addView(emergencyName);
        root.addView(emergencyNumber);
        root.addView(emergencyEmail);
        root.addView(sosMessage);
        root.addView(fcmToken);
        root.addView(save, fullWidthParams(dp(54)));

        setPageContent(scroll, "Profile");
        fetchProfile(name, mobile, address, emergencyName, emergencyNumber, emergencyEmail, sosMessage, fcmToken);
    }

    private void showFaceGallery() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        root.addView(title("My Faces"));
        root.addView(smallText("Faces saved to your COOKIE cloud profile."));
        addGap(root, 12);

        setPageContent(scroll, "My Faces");

        String email = prefs.getString("email", "");
        getText("/face-snapshots?email=" + email, result -> {
            if (result.error != null) {
                root.addView(smallText("Could not load faces: " + result.error));
                return;
            }

            try {
                org.json.JSONArray faces = new JSONObject(result.body).optJSONArray("faces");
                if (faces == null || faces.length() == 0) {
                    root.addView(smallText("No faces added yet."));
                    return;
                }

                for (int i = 0; i < faces.length(); i++) {
                    JSONObject face = faces.getJSONObject(i);
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(12), dp(12), dp(12), dp(12));
                    card.setBackground(rounded(panelColor(), dp(18), borderColor(), 1));

                    ImageView image = new ImageView(this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Bitmap bitmap = base64ToBitmap(face.optString("face_snapshot", ""));
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                    } else {
                        image.setImageResource(android.R.drawable.ic_menu_camera);
                    }
                    card.addView(image, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(180)
                    ));

                    card.addView(infoLine("Name", face.optString("person_name", "--")));
                    card.addView(infoLine("Added", face.optString("created_at", "--")));
                    card.addView(infoLine("Source", face.optString("source", "--")));

                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(0, dp(12), 0, 0);
                    root.addView(card, params);
                }
            } catch (Exception e) {
                root.addView(smallText("Bad face gallery response."));
            }
        });
    }

    private void showSosMessages() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = rootLayout();
        scroll.addView(root);

        root.addView(title("SOS Messages"));
        root.addView(smallText("SOS alerts connected to your COOKIE profile."));
        addGap(root, 12);

        Button refresh = secondaryButton("Refresh");
        refresh.setOnClickListener(v -> showSosMessages());
        root.addView(refresh, fullWidthParams(dp(52)));
        addGap(root, 12);

        TextView loading = smallText("Loading SOS messages...");
        root.addView(loading);

        setPageContent(scroll, "SOS Messages");

        String email = prefs.getString("email", "");
        getText("/sos/messages?email=" + Uri.encode(email), result -> {
            root.removeView(loading);
            if (result.error != null) {
                root.addView(smallText("Could not load SOS messages: " + result.error));
                return;
            }

            try {
                JSONArray messages = new JSONObject(result.body).optJSONArray("messages");
                if (messages == null || messages.length() == 0) {
                    root.addView(smallText("No SOS messages found yet."));
                    return;
                }

                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.getJSONObject(i);
                    JSONObject location = message.optJSONObject("location");
                    String mapsUrl = location == null ? "" : location.optString("maps_url", "");

                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(14), dp(14), dp(14), dp(14));
                    card.setBackground(rounded(panelColor(), dp(18), borderColor(), 1));

                    card.addView(infoLine("From", displayOrFallback(
                            message.optString("sender_name", ""),
                            message.optString("email", "--")
                    )));
                    card.addView(infoLine("Sender email", message.optString("email", "--")));
                    card.addView(infoLine("Emergency contact", displayOrFallback(
                            message.optString("emergency_contact_name", ""),
                            message.optString("emergency_contact_email", "--")
                    )));
                    card.addView(infoLine("Time", message.optString("updated_at", "--")));
                    card.addView(infoLine("Message", message.optString("message", "--")));

                    if (!mapsUrl.isEmpty()) {
                        TextView link = smallText(mapsUrl);
                        link.setTextColor(accentColor());
                        link.setPadding(0, dp(8), 0, dp(8));
                        card.addView(link);

                        Button openMap = primaryButton("Open Google Maps");
                        openMap.setOnClickListener(v -> openMapsLink(mapsUrl));
                        card.addView(openMap, fullWidthParams(dp(52)));
                    } else {
                        card.addView(smallText("No location link was attached to this SOS."));
                    }

                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(0, 0, 0, dp(12));
                    root.addView(card, params);
                }
            } catch (Exception e) {
                root.addView(smallText("Bad SOS message response."));
            }
        });
    }

    private void addCommandButtons() {
        Button forward = primaryButton("Forward");
        Button left = primaryButton("Left");
        Button stop = dangerButton("Stop");
        Button right = primaryButton("Right");
        Button backward = primaryButton("Backward");

        forward.setOnClickListener(v -> sendCommand("forward"));
        left.setOnClickListener(v -> sendCommand("left"));
        stop.setOnClickListener(v -> sendCommand("stop"));
        right.setOnClickListener(v -> sendCommand("right"));
        backward.setOnClickListener(v -> sendCommand("backward"));

        LinearLayout row1 = row();
        row1.addView(forward, buttonWeight());

        LinearLayout row2 = row();
        row2.addView(left, buttonWeight());
        row2.addView(stop, buttonWeight());
        row2.addView(right, buttonWeight());

        LinearLayout row3 = row();
        row3.addView(backward, buttonWeight());

        commandPanel.addView(row1);
        commandPanel.addView(row2);
        commandPanel.addView(row3);
    }

    private void addEmotionButtons(LinearLayout root) {
        root.addView(sectionTitle("Emotion"));

        LinearLayout row1 = row();
        Button normal = secondaryButton("Normal");
        Button happy = primaryButton("Happy");
        normal.setOnClickListener(v -> sendEmotion("normal"));
        happy.setOnClickListener(v -> sendEmotion("happy"));
        row1.addView(normal, buttonWeight());
        row1.addView(happy, buttonWeight());

        LinearLayout row2 = row();
        Button excited = primaryButton("Excited");
        Button angry = dangerButton("Angry");
        excited.setOnClickListener(v -> sendEmotion("excited"));
        angry.setOnClickListener(v -> sendEmotion("angry"));
        row2.addView(excited, buttonWeight());
        row2.addView(angry, buttonWeight());

        root.addView(row1);
        root.addView(row2);
    }

    private void onModeChanged(CompoundButton buttonView, boolean isChecked) {
        String mode = isChecked ? "autonomous" : "manual";
        setCommandsEnabled(!isChecked);
        postJson("/mode", "{\"mode\":\"" + mode + "\"}", result -> {
            if (result.error != null) {
                toast("Mode update failed: " + result.error);
                return;
            }
            toast(isChecked ? "Autonomous mode enabled" : "Manual mode enabled");
            fetchStatus();
        });
    }

    private void setCommandsEnabled(boolean enabled) {
        if (commandPanel == null) {
            return;
        }
        setEnabledRecursive(commandPanel, enabled);
        commandPanel.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
        }
    }

    private void registerUser() {
        if (faceBitmap == null) {
            toast("Capture a face snapshot first.");
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("name", pendingName);
            body.put("email", pendingEmail);
            body.put("mobile", pendingMobile);
            body.put("password", pendingPassword);
            body.put("address", pendingAddress);
            body.put("emergency_contact_name", pendingEmergencyName);
            body.put("emergency_contact_number", pendingEmergencyNumber);
            body.put("emergency_contact_email", pendingEmergencyEmail);
            body.put("face_person_name", facePersonInput.getText().toString().trim());
            body.put("face_snapshot", bitmapToBase64(faceBitmap));
        } catch (Exception e) {
            toast("Could not prepare registration.");
            return;
        }

        postJson("/auth/register", body.toString(), result -> {
            if (result.error != null) {
                toast("Register failed: " + result.error);
                return;
            }
            saveSessionFromResponse(result.body, pendingEmail);
            showDashboard();
        });
    }

    private void loginUser(boolean includeFace) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", pendingEmail);
            body.put("mobile", pendingMobile);
            body.put("password", pendingPassword);
            if (includeFace && faceBitmap != null) {
                body.put("face_person_name", facePersonInput.getText().toString().trim());
                body.put("face_snapshot", bitmapToBase64(faceBitmap));
            }
        } catch (Exception e) {
            toast("Could not prepare login.");
            return;
        }

        postJson("/auth/login", body.toString(), result -> {
            if (result.error != null) {
                toast("Login failed: " + result.error);
                return;
            }
            saveSessionFromResponse(result.body, pendingEmail);
            showDashboard();
        });
    }

    private void uploadNamedFaceSnapshot() {
        JSONObject body = new JSONObject();
        try {
            body.put("email", prefs.getString("email", pendingEmail));
            body.put("person_name", facePersonInput.getText().toString().trim());
            body.put("face_snapshot", bitmapToBase64(faceBitmap));
        } catch (Exception e) {
            toast("Could not prepare snapshot.");
            return;
        }

        postJson("/face-snapshots", body.toString(), result -> {
            if (result.error != null) {
                toast("Snapshot upload failed: " + result.error);
                return;
            }
            toast("Face snapshot saved to cloud.");
            showDashboard();
        });
    }

    private void saveProfile(String name, String mobile, String email, String address, String emergencyName, String emergencyNumber, String emergencyEmail, String sosMessage, String fcmToken) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name.trim());
            body.put("mobile", mobile.trim());
            body.put("email", email.trim());
            body.put("address", address.trim());
            body.put("emergency_contact_name", emergencyName.trim());
            body.put("emergency_contact_number", emergencyNumber.trim());
            body.put("emergency_contact_email", emergencyEmail.trim());
            body.put("sos_message", sosMessage.trim());
            body.put("fcm_token", fcmToken.trim());
        } catch (Exception e) {
            toast("Could not prepare profile.");
            return;
        }

        postJson("/profile", body.toString(), result -> {
            if (result.error != null) {
                toast("Profile save failed: " + result.error);
                return;
            }
            saveSessionFromResponse(result.body, email);
            toast("Profile saved to cloud.");
        });
    }

    private void fetchProfile(EditText name, EditText mobile, EditText address, EditText emergencyName, EditText emergencyNumber, EditText emergencyEmail, EditText sosMessage, EditText fcmToken) {
        String email = prefs.getString("email", "");
        if (email.isEmpty()) {
            return;
        }

        getText("/profile?email=" + email, result -> {
            if (result.error == null) {
                saveSessionFromResponse(result.body, email);
                name.setText(prefs.getString("name", ""));
                mobile.setText(prefs.getString("mobile", ""));
                address.setText(prefs.getString("address", ""));
                emergencyName.setText(prefs.getString("emergency_contact_name", ""));
                emergencyNumber.setText(prefs.getString("emergency_contact_number", ""));
                emergencyEmail.setText(prefs.getString("emergency_contact_email", ""));
                sosMessage.setText(prefs.getString("sos_message", "SOS requested from COOKIE app"));
                fcmToken.setText(prefs.getString("fcm_token", ""));
            }
        });
    }

    private void sendSos() {
        if (!hasLocationPermission()) {
            sendSosAfterLocationPermission = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
            return;
        }

        JSONObject body = new JSONObject();
        try {
            Location location = getBestLastKnownLocation();
            String message = prefs.getString("sos_message", "SOS requested from COOKIE app");
            body.put("active", true);
            body.put("email", prefs.getString("email", ""));
            body.put("message", message);
            if (location != null) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();
                String mapsUrl = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", latitude, longitude);
                body.put("location_available", true);
                body.put("location_latitude", latitude);
                body.put("location_longitude", longitude);
                body.put("location_accuracy_m", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                body.put("location_provider", location.getProvider());
                body.put("location_maps_url", mapsUrl);
            } else {
                body.put("location_available", false);
            }
        } catch (Exception e) {
            toast("Could not prepare SOS.");
            return;
        }

        postJson("/sos", body.toString(), result -> {
            if (result.error != null) {
                toast("SOS failed: " + result.error);
            } else {
                toast("SOS sent to cloud.");
            }
        });
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Location getBestLastKnownLocation() {
        if (!hasLocationPermission()) {
            return null;
        }

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }

        Location best = null;
        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };

        for (String provider : providers) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location == null) {
                    continue;
                }
                if (best == null
                        || location.getTime() > best.getTime()
                        || (location.hasAccuracy() && (!best.hasAccuracy() || location.getAccuracy() < best.getAccuracy()))) {
                    best = location;
                }
            } catch (SecurityException ignored) {
            } catch (IllegalArgumentException ignored) {
            }
        }

        return best;
    }

    private void saveSessionFromResponse(String body, String fallbackEmail) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean("logged_in", true)
                .putString("email", fallbackEmail);

        try {
            JSONObject response = new JSONObject(body);
            JSONObject user = response.optJSONObject("user");
            if (user != null) {
                editor.putString("name", user.optString("name", ""));
                editor.putString("email", user.optString("email", fallbackEmail));
                editor.putString("mobile", user.optString("mobile", ""));
                editor.putString("address", user.optString("address", ""));
                editor.putString("emergency_contact_name", user.optString("emergency_contact_name", ""));
                editor.putString("emergency_contact_number", user.optString("emergency_contact_number", ""));
                editor.putString("emergency_contact_email", user.optString("emergency_contact_email", ""));
                editor.putString("sos_message", user.optString("sos_message", "SOS requested from COOKIE app"));
                editor.putString("fcm_token", user.optString("fcm_token", ""));
            }
        } catch (Exception ignored) {
        }

        editor.apply();
        requestNotificationPermission();
        registerFcmToken();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void registerFcmToken() {
        String email = prefs.getString("email", "");
        if (email.isEmpty() || ADMIN_EMAIL.equalsIgnoreCase(email)) {
            return;
        }

        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null) {
                        return;
                    }
                    uploadDeviceToken(task.getResult());
                });
    }

    private void uploadDeviceToken(String token) {
        String email = prefs.getString("email", "");
        if (email.isEmpty() || ADMIN_EMAIL.equalsIgnoreCase(email) || token == null || token.isEmpty()) {
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("fcm_token", token);
        } catch (Exception e) {
            return;
        }

        postJson("/device-token", body.toString(), result -> {
            if (result.error == null) {
                prefs.edit().putString("fcm_token", token).apply();
            }
        });
    }

    private void captureFace() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, FACE_CAPTURE_REQUEST);
        } else {
            toast("No camera app found.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            captureFace();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (sendSosAfterLocationPermission) {
                sendSosAfterLocationPermission = false;
                if (granted) {
                    sendSos();
                } else {
                    toast("Location permission denied. Sending SOS without location.");
                    sendSosWithoutLocation();
                }
            }
        }
    }

    private void sendSosWithoutLocation() {
        JSONObject body = new JSONObject();
        try {
            body.put("active", true);
            body.put("email", prefs.getString("email", ""));
            body.put("message", prefs.getString("sos_message", "SOS requested from COOKIE app"));
            body.put("location_available", false);
        } catch (Exception e) {
            toast("Could not prepare SOS.");
            return;
        }

        postJson("/sos", body.toString(), result -> {
            if (result.error != null) {
                toast("SOS failed: " + result.error);
            } else {
                toast("SOS sent to cloud.");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FACE_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            Bundle extras = data.getExtras();
            if (extras != null) {
                faceBitmap = (Bitmap) extras.get("data");
                if (facePreview != null) {
                    facePreview.setImageBitmap(faceBitmap);
                }
            }
        }
    }

    private void fetchStatus() {
        getText("/status", result -> {
            if (result.error != null) {
                statusText.setText("Cloud offline: " + result.error);
                return;
            }

            try {
                JSONObject status = new JSONObject(result.body);
                String voltage = status.opt("voltage") == JSONObject.NULL ? "--" : status.optString("voltage", "--") + " V";
                String battery = status.opt("battery_percent") == JSONObject.NULL ? "--" : status.optString("battery_percent", "--") + "%";
                String emotion = status.optString("emotion", "--");
                String wifi = status.optString("connected_ssid", "--");
                String mode = status.optString("control_mode", "manual");

                voltageText.setText(formatInfo("Voltage", voltage));
                batteryText.setText(formatInfo("Battery", battery));
                emotionText.setText(formatInfo("Emotion", emotion));
                wifiText.setText(formatInfo("WiFi", wifi));
                modeText.setText(formatInfo("Mode", mode.toUpperCase(Locale.US)));
                statusText.setText("Connected to COOKIE cloud");

                boolean autonomous = "autonomous".equalsIgnoreCase(mode);
                if (modeSwitch.isChecked() != autonomous) {
                    modeSwitch.setOnCheckedChangeListener(null);
                    modeSwitch.setChecked(autonomous);
                    modeSwitch.setOnCheckedChangeListener(this::onModeChanged);
                }
                setCommandsEnabled(!autonomous);
            } catch (Exception e) {
                statusText.setText("Bad status response");
            }
        });
    }

    private void fetchCameraFrame() {
        executor.execute(() -> {
            try {
                URL url = new URL(BASE_URL + "/camera?t=" + System.currentTimeMillis());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                if (connection.getResponseCode() != 200) {
                    throw new Exception("No frame yet");
                }

                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                connection.disconnect();

                ui.post(() -> {
                    cameraLoading.setVisibility(View.GONE);
                    cameraFeed.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    cameraLoading.setVisibility(View.GONE);
                    if (cameraFeed.getDrawable() == null) {
                        cameraFeed.setImageResource(android.R.drawable.ic_menu_camera);
                    }
                });
            }
        });
    }

    private void sendCommand(String command) {
        postJson("/command", "{\"command\":\"" + command + "\"}", result -> {
            if (result.error != null) {
                toast("Command failed: " + result.error);
            } else {
                toast("Sent " + command);
            }
        });
    }

    private void sendEmotion(String emotion) {
        postJson("/emotion", "{\"emotion\":\"" + emotion + "\"}", result -> {
            if (result.error != null) {
                toast("Emotion failed: " + result.error);
            } else {
                toast("Emotion set to " + emotion);
                fetchStatus();
            }
        });
    }

    private void getText(String path, ResultCallback callback) {
        executor.execute(() -> {
            HttpResult result = new HttpResult();
            try {
                URL url = new URL(BASE_URL + path);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                result.code = connection.getResponseCode();
                InputStream input = result.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                result.body = readAll(input);
                connection.disconnect();
                if (result.code >= 400) {
                    result.error = result.body;
                }
            } catch (Exception e) {
                result.error = e.getMessage();
            }
            ui.post(() -> callback.onResult(result));
        });
    }

    private void postJson(String path, String json, ResultCallback callback) {
        executor.execute(() -> {
            HttpResult result = new HttpResult();
            try {
                URL url = new URL(BASE_URL + path);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                if (!API_KEY.isEmpty()) {
                    connection.setRequestProperty("X-COOKIE-KEY", API_KEY);
                }

                OutputStream output = connection.getOutputStream();
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();

                result.code = connection.getResponseCode();
                InputStream input = result.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                result.body = readAll(input);
                connection.disconnect();
                if (result.code >= 400) {
                    result.error = result.body;
                }
            } catch (Exception e) {
                result.error = e.getMessage();
            }
            ui.post(() -> callback.onResult(result));
        });
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int read;
        while ((read = input.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        input.close();
        return buffer.toString("UTF-8");
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output);
        return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap base64ToBitmap(String value) {
        try {
            String clean = value;
            int comma = clean.indexOf(",");
            if (comma >= 0) {
                clean = clean.substring(comma + 1);
            }
            byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout rootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(28));
        root.setBackgroundColor(bgColor());
        return root;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(28);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.START);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(6), 0, dp(8));
        return view;
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(subtleTextColor());
        view.setTextSize(14);
        return view;
    }

    private TextView infoLine(String label, String value) {
        TextView view = smallText(formatInfo(label, value));
        view.setTextSize(16);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private String formatInfo(String label, String value) {
        return label + ": " + value;
    }

    private String displayOrFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void openMapsLink(String mapsUrl) {
        if (mapsUrl == null || mapsUrl.trim().isEmpty()) {
            toast("No map link available.");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            startActivity(intent);
        } catch (Exception e) {
            toast("Could not open map link.");
        }
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(subtleTextColor());
        edit.setTypeface(Typeface.DEFAULT);
        edit.setBackground(rounded(panelColor(), dp(28), borderColor(), 1));
        edit.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams params = fullWidthParams(dp(54));
        params.setMargins(0, 0, 0, dp(12));
        edit.setLayoutParams(params);
        return edit;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.BLACK);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(Color.WHITE, dp(28), Color.WHITE, 0));
        attachPressAnimation(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(Color.TRANSPARENT, dp(28), Color.WHITE, 1));
        attachPressAnimation(button);
        return button;
    }

    private Button dangerButton(String text) {
        Button button = primaryButton(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(Color.rgb(210, 52, 52), dp(28), Color.TRANSPARENT, 0));
        return button;
    }

    private Button pillButton(String text, boolean filled) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(filled ? Color.BLACK : Color.WHITE);
        button.setBackground(rounded(filled ? Color.WHITE : Color.TRANSPARENT, dp(28), Color.WHITE, filled ? 0 : 1));
        attachPressAnimation(button);
        return button;
    }

    private Button outlineButton(String text) {
        return pillButton(text, false);
    }

    private Button overlayButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(28);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(Color.argb(178, 0, 0, 0), dp(36), Color.argb(210, 255, 255, 255), 1));
        attachPressAnimation(button);
        return button;
    }

    private LinearLayout.LayoutParams overlayParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72), dp(72));
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        return params;
    }

    private boolean isNightMode() {
        return prefs != null && prefs.getBoolean("night_mode", false);
    }

    private int bgColor() {
        return isNightMode() ? Color.rgb(3, 14, 14) : Color.BLACK;
    }

    private int menuColor() {
        return isNightMode() ? Color.rgb(4, 24, 24) : Color.rgb(8, 10, 10);
    }

    private int panelColor() {
        return isNightMode() ? Color.rgb(8, 34, 33) : Color.rgb(18, 18, 18);
    }

    private int borderColor() {
        return isNightMode() ? Color.rgb(31, 132, 123) : Color.rgb(75, 75, 75);
    }

    private int accentColor() {
        return isNightMode() ? Color.rgb(20, 168, 154) : Color.rgb(31, 190, 145);
    }

    private int subtleTextColor() {
        return isNightMode() ? Color.rgb(139, 178, 174) : Color.rgb(155, 155, 155);
    }

    private void attachPressAnimation(View view) {
        view.setOnTouchListener((pressedView, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pressedView.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(90)
                        .start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                pressedView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(130)
                        .start();
            }
            return false;
        });
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(dp(strokeWidth), strokeColor);
        }
        return drawable;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams buttonWeight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams fullWidthParams(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private void addGap(LinearLayout root, int dp) {
        Space space = new Space(this);
        root.addView(space, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface ResultCallback {
        void onResult(HttpResult result);
    }

    private static class HttpResult {
        int code;
        String body = "";
        String error;
    }
}

