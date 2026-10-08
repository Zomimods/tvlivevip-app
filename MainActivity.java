package com.sami.tvlivevip;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public class MainActivity extends BridgeActivity {
    private static final String RELEASES_API = "https://api.github.com/repos/Zomimods/tvlivevip-app/releases/latest";

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean updateChecked = false;

    // ---- Anti-tamper guard (v5) ----
    private static final String PKG = "com.sami.tvlivevip";
    // Injected at build time by patch_android.py from the secret signing key. Never edit by hand.
    private static final String CERT_SHA256 = "__CERT_SHA256__";
    // Set to false if you ever need to run the app on an emulator.
    private static final boolean BLOCK_EMULATOR = true;
    private static final String OFFICIAL_URL = "https://github.com/Zomimods/tvlivevip-app/releases/latest";
    private boolean denied = false;
    private final Handler guardHandler = new Handler(Looper.getMainLooper());
    private final Runnable guardTick = new Runnable() {
        @Override
        public void run() {
            if (denied) return;
            if (!verify(MainActivity.this)) { deny(); return; }
            guardHandler.postDelayed(this, 30000L + (long) (Math.random() * 30000L));
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        final boolean genuine = verify(this);
        super.onCreate(savedInstanceState);
        if (!genuine) { deny(); return; }
        try { WebView.setWebContentsDebuggingEnabled(false); } catch (Throwable ignored) { }
        getBridge().getWebView().setWebChromeClient(new BridgeWebChromeClient(getBridge()) {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.addView(view, new FrameLayout.LayoutParams(-1, -1));
                getBridge().getWebView().setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideBars();
            }

            @Override
            public void onHideCustomView() {
                closeFullscreen();
            }
        });
        checkForUpdateAsync();
        guardHandler.postDelayed(guardTick, 5000L);
    }

    private void closeFullscreen() {
        if (customView == null) return;
        FrameLayout decor = (FrameLayout) getWindow().getDecorView();
        decor.removeView(customView);
        customView = null;
        getBridge().getWebView().setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        showBars();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            closeFullscreen();
            return;
        }
        super.onBackPressed();
    }

    // Picture-in-picture: when the user leaves the app while watching in fullscreen.
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (customView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) { }
        }
    }

    // Background playback: keep the process and the web page alive while a video is playing.
    @Override
    public void onPause() {
        try {
            getBridge().getWebView().evaluateJavascript(
                "(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended)})()",
                value -> { if ("true".equals(value)) startKeepAlive(); });
        } catch (Exception ignored) { }
        super.onPause();
        try {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        } catch (Exception ignored) { }
    }

    @Override
    public void onResume() {
        super.onResume();
        stopService(new Intent(this, KeepAliveService.class));
        if (!denied && !verify(this)) deny();
    }

    @Override
    public void onDestroy() {
        guardHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startKeepAlive() {
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) { }
    }

    // Auto-update: compares this build number with the latest GitHub release.
    private void checkForUpdateAsync() {
        if (updateChecked) return;
        updateChecked = true;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject j = new JSONObject(sb.toString());
                int latest = Integer.parseInt(j.getString("tag_name").replaceAll("\\D", ""));
                long current;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    current = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
                else
                    current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                if (latest <= current) return;
                String url = j.optString("html_url");
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null && assets.length() > 0)
                    url = assets.getJSONObject(0).optString("browser_download_url", url);
                final String dl = url;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("تحديث جديد")
                        .setMessage("يتوفر إصدار أحدث من التطبيق. هل تريد تنزيله الآن؟")
                        .setPositiveButton("تحديث", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl))))
                        .setNegativeButton("لاحقاً", null)
                        .show();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    // ---------------- Anti-tamper guard ----------------
    // Same checks are used by the activity (start, resume, every 30-60s) and by KeepAliveService.
    static boolean verify(Context c) {
        try {
            if (!PKG.equals(c.getPackageName())) return false;
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;
            if (Debug.isDebuggerConnected()) return false;
            if (!signatureOk(c)) return false;
            if (hookingDetected()) return false;
            if (BLOCK_EMULATOR && isEmulator()) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean signatureOk(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            sigs = (si == null) ? null : si.getApkContentsSigners();
        } else {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length != 1) return false;
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        final String hexChars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(hexChars.charAt((b >> 4) & 15)).append(hexChars.charAt(b & 15));
        }
        byte[] want = CERT_SHA256.toLowerCase(Locale.ROOT).getBytes("UTF-8");
        return MessageDigest.isEqual(want, sb.toString().getBytes("UTF-8"));
    }

    private static boolean hookingDetected() {
        try { Class.forName("de.robv.android.xposed.XposedBridge"); return true; } catch (Throwable ignored) { }
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.contains("frida") || l.contains("gum-js") || l.contains("libsubstrate")) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isEmulator() {
        String s = (Build.FINGERPRINT + "|" + Build.HARDWARE + "|" + Build.MODEL + "|"
                + Build.PRODUCT + "|" + Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        return s.contains("goldfish") || s.contains("ranchu") || s.contains("vbox86")
                || s.contains("sdk_gphone") || s.contains("android sdk built for")
                || s.contains("emulator") || s.contains("genymotion");
    }

    private void deny() {
        if (denied) return;
        denied = true;
        try {
            WebView wv = getBridge().getWebView();
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.setVisibility(View.GONE);
        } catch (Throwable ignored) { }
        try {
            new AlertDialog.Builder(this)
                .setTitle("نسخة غير موثوقة")
                .setMessage("هذه نسخة غير أصلية أو معدّلة من التطبيق ولن تعمل. حمّل النسخة الرسمية من الرابط.")
                .setCancelable(false)
                .setPositiveButton("تحميل الرسمية", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_URL))); } catch (Throwable ignored2) { }
                    killApp();
                })
                .setNegativeButton("خروج", (d, which) -> killApp())
                .show();
        } catch (Throwable t) {
            killApp();
        }
        guardHandler.postDelayed(this::killApp, 20000L);
    }

    private void killApp() {
        try { finishAndRemoveTask(); } catch (Throwable ignored) { }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void showBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
}
