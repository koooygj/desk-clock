package com.deskclock.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Base64;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String PREFS = "clock";
    static final String KEY_AUTO = "autoLaunch";

    /** 알림 서비스가 화면에 아이콘을 보내기 위해 사용 */
    static MainActivity current;

    private WebView web;
    private boolean pageReady = false;
    private volatile String lastLoc = "";

    // ---- 현재 재생 중인 미디어 ----
    private MediaSessionManager msm;
    private MediaController ctrl;
    private Bitmap lastArtBmp;
    private String lastArtStr = "";

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { pushMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { pushMedia(); }
        @Override public void onSessionDestroyed() { pickSession(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = list -> pickSession();

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int pct = Math.round(level * 100f / scale);
            js("window.nativeBattery && nativeBattery(" + pct + "," + charging + ")");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);   // 잠금화면 위에도 표시 (충전 시 자동 실행용)
        setTurnScreenOn(true);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setGeolocationEnabled(true);

        // 앱 안의 index.html을 https 주소로 제공 (위치 권한이 https에서만 동작)
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushState();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, true);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        updateLocation();
        js("typeof loadWeather === 'function' && loadWeather()");
    }

    /** 마지막으로 알려진 위치를 바로 쓰고, 새 위치도 한 번 요청 */
    private void updateLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = getSystemService(LocationManager.class);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) setLocation(best);
            String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (prov != null) {
                lm.getCurrentLocation(prov, null, getMainExecutor(), l -> { if (l != null) setLocation(l); });
            }
        } catch (SecurityException ignored) {}
    }

    private void setLocation(Location l) {
        boolean first = lastLoc.isEmpty();
        lastLoc = String.format(Locale.US, "{\"lat\":%.4f,\"lon\":%.4f}", l.getLatitude(), l.getLongitude());
        if (first) js("typeof loadWeather === 'function' && loadWeather()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        hideSystemBars();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        updateLocation();
        if (hasNotifAccess(this) && !NotifListener.isConnected()) {
            NotificationListenerService.requestRebind(new ComponentName(this, NotifListener.class));
        }
        startMedia();
        pushState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        stopMedia();
        if (current == this) current = null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** 화면이 다시 보일 때 알림 아이콘 갱신 */
    private void pushState() {
        if (!pageReady) return;
        NotifListener.refresh();
        pushMedia();
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) batteryReceiver.onReceive(this, bat);
        js("document.dispatchEvent(new Event('visibilitychange'))");
    }

    // ---- 인터넷 요청 (OkHttp) ----
    private static List<InetAddress> v4First(String host) throws UnknownHostException {
        List<InetAddress> all = Dns.SYSTEM.lookup(host);
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : all) if (a instanceof Inet4Address) out.add(a);
        for (InetAddress a : all) if (!(a instanceof Inet4Address)) out.add(a);
        return out;
    }

    private static List<InetAddress> v4Only(String host) throws UnknownHostException {
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : Dns.SYSTEM.lookup(host)) if (a instanceof Inet4Address) out.add(a);
        if (out.isEmpty()) throw new UnknownHostException("IPv4 주소 없음: " + host);
        return out;
    }

    static final OkHttpClient HTTP_V4_FIRST = new OkHttpClient.Builder()
            .dns(MainActivity::v4First)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    static final OkHttpClient HTTP_V4_ONLY = HTTP_V4_FIRST.newBuilder()
            .dns(MainActivity::v4Only)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private static String get(OkHttpClient client, Request req) throws IOException {
        try (Response r = client.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            return r.body() != null ? r.body().string() : "";
        }
    }

    private ComponentName listenerName() { return new ComponentName(this, NotifListener.class); }

    private void startMedia() {
        if (!hasNotifAccess(this)) { pushMedia(); return; }
        try {
            msm = getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessionsCb, listenerName(), new Handler(Looper.getMainLooper()));
            pickSession();
        } catch (SecurityException e) {
            msm = null;
            pushMedia();
        }
    }

    private void stopMedia() {
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsCb); } catch (Exception ignored) {}
            msm = null;
        }
        setController(null);
    }

    /** 재생 중인 세션을 우선, 없으면 가장 최근 세션 */
    private void pickSession() {
        List<MediaController> list = Collections.emptyList();
        if (msm != null) {
            try { list = msm.getActiveSessions(listenerName()); } catch (SecurityException ignored) {}
        }
        MediaController pick = null;
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
        }
        if (pick == null && !list.isEmpty()) pick = list.get(0);
        setController(pick);
    }

    private void setController(MediaController c) {
        if (ctrl != null) {
            try { ctrl.unregisterCallback(mediaCb); } catch (Exception ignored) {}
        }
        ctrl = c;
        if (ctrl != null) ctrl.registerCallback(mediaCb, new Handler(Looper.getMainLooper()));
        pushMedia();
    }

    private void pushMedia() {
        MediaMetadata m = ctrl != null ? ctrl.getMetadata() : null;
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) {
            if (m != null) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        }
        if (title == null || title.isEmpty()) {
            js("window.nativeMedia && nativeMedia(null)");
            return;
        }
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null || artist.isEmpty()) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist == null || artist.isEmpty()) artist = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);

        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != lastArtBmp) {
            lastArtBmp = art;
            lastArtStr = art == null ? "" : toDataUrl(art);
        }

        String app = "";
        try {
            app = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(ctrl.getPackageName(), 0)).toString();
        } catch (Exception ignored) {}

        PlaybackState st = ctrl.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;

        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("artist", artist == null ? "" : artist);
            o.put("app", app);
            o.put("art", lastArtStr);
            o.put("playing", playing);
            js("window.nativeMedia && nativeMedia(" + o + ")");
        } catch (Exception ignored) {}
    }

    private static String toDataUrl(Bitmap b) {
        try {
            int size = 192;
            float scale = Math.min(1f, size / (float) Math.max(b.getWidth(), b.getHeight()));
            Bitmap s = scale < 1f
                    ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true)
                    : b;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    void showNotifs(String jsonArray) {
        js("window.nativeNotifs && nativeNotifs(" + jsonArray + ")");
    }

    void js(final String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    static boolean hasNotifAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    static boolean autoLaunchOn(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, false);
    }

    /** 웹 화면(index.html)에서 window.Android 로 호출 */
    class Bridge {
        /** 위치 {"lat":..,"lon":..} 또는 빈 문자열 */
        @JavascriptInterface public String getLocation() { return lastLoc; }

        /** 날씨·지역 이름 요청을 안드로이드에서 직접 처리. 결과는 __nativeFetchDone(id, ok, text) 로 돌려줌 */
        @JavascriptInterface public void fetchText(final int id, final String url) {
            new Thread(() -> {
                boolean ok = false;
                String text;
                try {
                    HttpUrl u = HttpUrl.parse(url);
                    if (u == null || !u.isHttps()
                            || !(u.host().equals("api.open-meteo.com") || u.host().equals("api.bigdatacloud.net"))) {
                        throw new IllegalArgumentException("허용되지 않은 주소");
                    }
                    Request req = new Request.Builder().url(u).header("User-Agent", "DeskClock/1.0").build();
                    try {
                        text = get(HTTP_V4_FIRST, req);
                    } catch (IOException first) {
                        // 일부 공유기·태블릿에서 IPv6 경로가 멈추는 경우가 있어 IPv4로만 한 번 더 시도
                        text = get(HTTP_V4_ONLY, req);
                    }
                    ok = true;
                } catch (Exception e) {
                    text = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                }
                js("window.__nativeFetchDone && __nativeFetchDone(" + id + "," + ok + "," + JSONObject.quote(text) + ")");
            }).start();
        }

        @JavascriptInterface public boolean hasNotifAccess() { return MainActivity.hasNotifAccess(MainActivity.this); }

        @JavascriptInterface public void openNotifAccess() {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }

        @JavascriptInterface public boolean canOverlay() { return Settings.canDrawOverlays(MainActivity.this); }

        @JavascriptInterface public void openOverlay() {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface public boolean getAutoLaunch() { return autoLaunchOn(MainActivity.this); }

        @JavascriptInterface public void setAutoLaunch(boolean on) {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putBoolean(KEY_AUTO, on).apply();
        }

        /** 재생 제어: prev, next, toggle */
        @JavascriptInterface public void mediaAction(final String action) {
            runOnUiThread(() -> {
                if (ctrl == null) return;
                MediaController.TransportControls t = ctrl.getTransportControls();
                if ("prev".equals(action)) t.skipToPrevious();
                else if ("next".equals(action)) t.skipToNext();
                else {
                    PlaybackState st = ctrl.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                }
            });
        }

        /** 배경 색상 (#rrggbb) — 화면 로딩 중에도 같은 색이 보이도록 */
        @JavascriptInterface public void setBg(final String hex) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(hex);
                    web.setBackgroundColor(c);
                    getWindow().setBackgroundDrawable(new ColorDrawable(c));
                } catch (Exception ignored) {}
            });
        }

        /** 0~1 밝기, 음수면 시스템 밝기로 되돌림 */
        @JavascriptInterface public void setBrightness(final float v) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : v;
                getWindow().setAttributes(lp);
            });
        }
    }
}
