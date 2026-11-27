package com.deskclock.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

/**
 * 타이머가 끝나는 시각에 안드로이드 알람으로 깨어나 알림을 띄움.
 * 앱이 닫혀 있거나 다른 앱을 쓰는 중에도 울림. 시계 앱이 화면에 떠 있으면 앱이 직접 울리므로 건너뜀.
 */
public class TimerReceiver extends BroadcastReceiver {

    static final String CHANNEL = "timer_done";
    static final int NOTIF_ID = 1001;
    static final String ACTION_FIRE = "com.deskclock.app.TIMER_FIRE";
    static final String ACTION_STOP = "com.deskclock.app.TIMER_STOP";

    @Override
    public void onReceive(Context c, Intent i) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (ACTION_STOP.equals(i.getAction())) {
            nm.cancel(NOTIF_ID);
            return;
        }
        if (MainActivity.current != null) {
            MainActivity.current.js("typeof renderTools === 'function' && renderTools()");
            return;
        }
        ensureChannel(c);
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(c, 1,
                new Intent(c, TimerReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("타이머")
                .setContentText("시간이 다 됐어요")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "끄기", stop).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT;   // 끄거나 누를 때까지 소리 반복
        try { nm.notify(NOTIF_ID, n); } catch (SecurityException ignored) {}
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "타이머 종료", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("타이머 시간이 다 됐을 때 울리는 알림");
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 400, 600});
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent firePending(Context c) {
        return PendingIntent.getBroadcast(c, 2, new Intent(c, TimerReceiver.class).setAction(ACTION_FIRE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** endAt(밀리초, 현재 시각 기준 epoch)에 울리도록 예약 */
    static void schedule(Context c, long endAt) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent fire = firePending(c);
        PendingIntent show = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(endAt, show), fire);
        } catch (SecurityException e) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fire); }
            catch (SecurityException e2) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, fire); }
        }
    }

    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(firePending(c));
        c.getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }
}
