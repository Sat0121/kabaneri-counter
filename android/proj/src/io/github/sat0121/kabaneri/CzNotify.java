package io.github.sat0121.kabaneri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通知から無名・生駒のCZポイントを入力するための常駐通知。
 * 表示する内容（現在のポイント・高確・取り消し対象）はカウンター画面から送られてきたものをそのまま使う。
 * アプリが動いていないときに押されたボタンは「未反映」として保存し、次にアプリを開いたときに順に反映する。
 */
final class CzNotify {
    static final String CHANNEL = "cz_input";
    static final int ID = 1;
    static final String ACTION = "io.github.sat0121.kabaneri.CZ";
    private static final String PREFS = "cz_notify";
    private static final String[] TYPES = {"normal", "unknown", "flash", "combo", "allstar"};
    private static final String[] TYPE_NAME = {"通常", "不明", "発光", "複合", "オール"};
    // カウンター本体と同じポイント表 [通常時, 高確中]
    private static final int[][] POINTS = {{1, 15}, {1, 15}, {15, 15}, {15, 30}, {30, 60}};

    private CzNotify() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** カウンター画面から最新の状態を受け取って通知を出し直す。 */
    static void update(Context c, String stateJson) {
        prefs(c).edit().putString("state", stateJson).apply();
        show(c);
    }

    /** アプリを開いたときに、閉じていた通知をまた出せるようにする。 */
    static void reopen(Context c) {
        prefs(c).edit().putBoolean("closed", false).apply();
    }

    static void close(Context c) {
        prefs(c).edit().putBoolean("closed", true).apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    static void cancel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    /** アプリが動いていないときに押されたボタンを保存する。 */
    static void queue(Context c, String op, String t, String type) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray a = new JSONArray(p.getString("pending", "[]"));
            JSONObject o = new JSONObject();
            o.put("op", op);
            o.put("t", t == null ? "" : t);
            o.put("type", type == null ? "" : type);
            a.put(o);
            p.edit().putString("pending", a.toString()).apply();
        } catch (Exception e) {
            // 保存できなければ何もしない
        }
        show(c);
    }

    /** 保存しておいた未反映のボタン操作を取り出して消す。 */
    static String takePending(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("pending", "[]");
        p.edit().remove("pending").apply();
        return s;
    }

    static int pendingCount(Context c) {
        try {
            return new JSONArray(prefs(c).getString("pending", "[]")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "CZポイント入力", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("通知から無名・生駒のCZポイントを入力します");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    static void show(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("closed", false)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(c);
        int mp = 0, ip = 0;
        boolean mh = false, ih = false;
        String undo = "";
        try {
            JSONObject s = new JSONObject(p.getString("state", "{}"));
            JSONObject m = s.optJSONObject("m"), i = s.optJSONObject("i");
            if (m != null) { mp = m.optInt("p"); mh = m.optBoolean("h"); }
            if (i != null) { ip = i.optInt("p"); ih = i.optBoolean("h"); }
            undo = s.optString("u", "");
        } catch (Exception e) {
            // 状態が読めなければ 0pt で出す
        }
        int pending = pendingCount(c);
        String sum = "無名 " + mp + "pt" + (mh ? "（高確）" : "") + "　生駒 " + ip + "pt" + (ih ? "（高確）" : "");

        RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.notif_small);
        small.setTextViewText(R.id.s_pts, sum);
        small.setTextViewText(R.id.s_hint, pending > 0 ? "未反映 " + pending + "件（アプリを開くと反映）" : "下に引くと入力ボタンが出ます");

        RemoteViews big = new RemoteViews(c.getPackageName(), R.layout.notif_big);
        fillRow(c, big, "mumei", mp, mh, R.id.m_pts, R.id.m_high, R.id.m_enter,
                new int[]{R.id.m_normal, R.id.m_unknown, R.id.m_flash, R.id.m_combo, R.id.m_allstar}, 100);
        fillRow(c, big, "ikoma", ip, ih, R.id.i_pts, R.id.i_high, R.id.i_enter,
                new int[]{R.id.i_normal, R.id.i_unknown, R.id.i_flash, R.id.i_combo, R.id.i_allstar}, 200);
        String ul = pending > 0 ? "未反映 " + pending + "件（アプリを開くと反映）" : (undo.length() > 0 ? undo : "取り消せる操作はありません");
        big.setTextViewText(R.id.undo_label, ul);
        big.setOnClickPendingIntent(R.id.undo, op(c, 300, "undo", "", ""));
        big.setOnClickPendingIntent(R.id.close, op(c, 301, "close", "", ""));

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("CZポイント")
                .setContentText(sum)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException e) {
            // 通知が許可されていない
        }
    }

    private static void fillRow(Context c, RemoteViews v, String t, int pts, boolean hi, int ptsId, int highId, int enterId, int[] ids, int base) {
        v.setTextViewText(ptsId, pts + "pt");
        v.setTextViewText(highId, hi ? "高確中" : "高確");
        v.setInt(highId, "setBackgroundResource", hi ? R.drawable.nb_on : R.drawable.nb_btn);
        v.setOnClickPendingIntent(highId, op(c, base + 10, "high", t, ""));
        v.setOnClickPendingIntent(enterId, op(c, base + 11, "enter", t, ""));
        for (int k = 0; k < TYPES.length; k++) {
            // 高確中は「通常」「発光」がないので隠す（アプリの画面と同じ）
            boolean hide = hi && (k == 0 || k == 2);
            v.setViewVisibility(ids[k], hide ? View.GONE : View.VISIBLE);
            v.setTextViewText(ids[k], TYPE_NAME[k] + "\n+" + POINTS[k][hi ? 1 : 0]);
            v.setOnClickPendingIntent(ids[k], op(c, base + k, "add", t, TYPES[k]));
        }
    }

    private static PendingIntent op(Context c, int code, String op, String t, String type) {
        Intent i = new Intent(c, CzReceiver.class).setAction(ACTION).putExtra("op", op).putExtra("t", t).putExtra("type", type);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
