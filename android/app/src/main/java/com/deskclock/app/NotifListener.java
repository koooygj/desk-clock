package com.deskclock.app;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 1) 현재 알림들의 작은 아이콘을 모아 시계 화면에 전달
 * 2) 시스템이 계속 살려두는 서비스이므로, 여기서 충전기 연결을 감지해 시계를 자동 실행
 */
public class NotifListener extends NotificationListenerService {

    private static NotifListener instance;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!MainActivity.autoLaunchOn(c) || !Settings.canDrawOverlays(c)) return;
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { c.startActivity(open); } catch (Exception ignored) {}
        }
    };

    @Override
    public void onListenerConnected() {
        instance = this;
        registerReceiver(powerReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        refresh();
    }

    @Override
    public void onListenerDisconnected() {
        try { unregisterReceiver(powerReceiver); } catch (Exception ignored) {}
        if (instance == this) instance = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    static boolean isConnected() { return instance != null; }

    static void refresh() {
        MainActivity a = MainActivity.current;
        NotifListener s = instance;
        if (a == null) return;
        if (s == null) { a.showNotifs("[]"); return; }
        a.showNotifs(s.iconsJson());
    }

    private String iconsJson() {
        JSONArray out = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        StatusBarNotification[] list;
        try { list = getActiveNotifications(); } catch (Exception e) { return "[]"; }
        if (list == null) return "[]";

        for (StatusBarNotification sbn : list) {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            Notification n = sbn.getNotification();
            Icon icon = n.getSmallIcon();
            if (icon == null) continue;
            try {
                Drawable d = icon.loadDrawable(this);
                if (d == null) continue;
                d = d.mutate();
                d.setTint(Color.WHITE);
                Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, 64, 64);
                d.draw(new Canvas(b));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bos);
                out.put("data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
            } catch (Exception ignored) {}
            if (out.length() >= 8) break;
        }
        return out.toString();
    }
}
