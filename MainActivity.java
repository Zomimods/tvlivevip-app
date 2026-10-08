package com.sami.tvlivevip;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {
    private static final String RELEASES_API = "https://api.github.com/repos/Zomimods/tvlivevip-app/releases/latest";

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean updateChecked = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
