package io.github.sat0121.kabaneri;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 2つの画面を持つアプリ。
 *  - カウンター: 同梱した index.html。通信なしで動く。
 *  - 台選び: 公開中の asabu-kabaneri サイトをそのまま表示する。通信できれば毎回最新を取得し、
 *    取得した内容を保存しておくので、通信できないときは最後に取得した内容を表示する。
 * 1.4 から: 通知に無名・生駒のCZポイント入力ボタンを出す（CzNotify / CzReceiver）。
 */
public class MainActivity extends Activity {
    static final String PICKER_URL = "https://sat0121.github.io/asabu-kabaneri/";
    private static final String PICKER_HOST = "sat0121.github.io";
    private static final String PICKER_PATH = "/asabu-kabaneri";
    private static final long STALE_MS = 10 * 60 * 1000L; // これより古ければ開き直したときに取り直す
    private static WeakReference<MainActivity> LIVE = new WeakReference<MainActivity>(null);

    private FrameLayout root;
    private WebView counter;
    private WebView picker;
    private Fetcher fetcher;
    private String shellJs;
    private volatile boolean pickerFromCache;
    private volatile long pickerSavedAt;
    private long pickerLoadedAt;
    private volatile boolean counterReady;
    private int counterStatusColor = Color.parseColor("#ededf4");
    private boolean counterDark;

    static MainActivity live() {
        return LIVE.get();
    }

    boolean counterReady() {
        return counterReady && !isDestroyed();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LIVE = new WeakReference<MainActivity>(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new FrameLayout(this);
        counter = newWebView();
        counter.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                if (isPickerUrl(u)) { openPicker(); return true; }
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                counterReady = true;
            }
        });
        counter.addJavascriptInterface(new Bridge(), "Android");
        root.addView(counter);
        setContentView(root);
        counter.loadUrl("file:///android_asset/index.html");
        CzNotify.reopen(getApplicationContext());
        askNotificationPermission();
    }

    /** Android 13 以降は通知を出すのに許可が要る。初回だけ尋ねる（断られたら通知なしで動く）。 */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (CzNotify.prefs(this).getBoolean("asked", false)) return;
        CzNotify.prefs(this).edit().putBoolean("asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            CzNotify.show(getApplicationContext());
        }
    }

    /** 通知のボタン操作をカウンターに渡す。 */
    void notifyOp(final String op, final String t, final String type) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (counter == null) return;
                counter.evaluateJavascript("window.__kbNotify&&window.__kbNotify(" + JSONObject.quote(op) + "," + JSONObject.quote(t == null ? "" : t) + ","
                        + JSONObject.quote(type == null ? "" : type) + ")", null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        // 「戻る」でアプリを終えたときは通知も片付ける。裏で止められた場合は通知を残し、押された分は次回反映する
        if (isFinishing()) CzNotify.cancel(getApplicationContext());
        if (LIVE.get() == this) LIVE = new WeakReference<MainActivity>(null);
        counterReady = false;
        super.onDestroy();
    }

    private WebView newWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // 記録の保存（localStorage）に必要
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setWebChromeClient(new WebChromeClient());
        return w;
    }

    /* ---------- 台選び（asabu-kabaneri） ---------- */

    private static boolean isPickerUrl(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath();
        return "https".equals(u.getScheme()) && PICKER_HOST.equals(u.getHost()) && (p.equals(PICKER_PATH) || p.startsWith(PICKER_PATH + "/"));
    }

    private static boolean isCacheable(Uri u) {
        String h = u.getHost();
        return isPickerUrl(u) || "fonts.googleapis.com".equals(h) || "fonts.gstatic.com".equals(h);
    }

    private void ensurePicker() {
        if (picker != null) return;
        fetcher = new Fetcher(new File(getCacheDir(), "picker"), WebSettings.getDefaultUserAgent(this));
        shellJs = readAsset("picker-shell.js");
        picker = newWebView();
        picker.setVisibility(View.GONE);
        picker.addJavascriptInterface(new PickerBridge(), "KabaneriShell");
        picker.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isPickerUrl(request.getUrl())) return false;
                openExternal(request.getUrl());
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"GET".equalsIgnoreCase(request.getMethod()) || !isCacheable(u)) return null;
                Fetcher.Result r = fetcher.get(u.toString(), request.getRequestHeaders());
                if (request.isForMainFrame()) {
                    pickerFromCache = r == null || r.fromCache;
                    pickerSavedAt = r == null ? 0 : r.savedAt;
                    if (r == null) return offlinePage();
                }
                if (r == null) return null;
                WebResourceResponse res = new WebResourceResponse(r.mime, r.charset, new ByteArrayInputStream(r.body));
                res.setStatusCodeAndReasonPhrase(r.status, r.reason);
                res.setResponseHeaders(r.headers);
                return res;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isPickerUrl(Uri.parse(url)) || shellJs == null) return;
                String info = "{\"offline\":" + pickerFromCache + ",\"savedAt\":" + pickerSavedAt + "}";
                view.evaluateJavascript(shellJs.replace("__INFO__", info), null);
            }
        });
        root.addView(picker);
    }

    private WebResourceResponse offlinePage() {
        String html = "<!doctype html><html lang=ja><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;margin:0;padding:24px;background:#f9f9ff;color:#191c20;line-height:1.6}"
                + "@media(prefers-color-scheme:dark){body{background:#111318;color:#e2e2e9}}"
                + "button{height:40px;border:0;border-radius:20px;padding:0 20px;margin:8px 8px 0 0;font-size:14px;font-weight:500;background:#415f91;color:#fff}"
                + "button.t{background:transparent;color:#415f91}@media(prefers-color-scheme:dark){button{background:#aac7ff;color:#0a305f}button.t{background:transparent;color:#aac7ff}}</style>"
                + "<h1 style='font-size:20px;font-weight:500'>台選びを表示できません</h1>"
                + "<p>通信できないため、台選びのデータを取得できませんでした。一度でも通信できる状態で開くと、以後は通信できないときも最後に取得した内容を表示します。</p>"
                + "<button onclick='location.reload()'>再読み込み</button><button class=t onclick='KabaneriShell.back()'>カウンターに戻る</button></html>";
        WebResourceResponse r = new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html.getBytes(java.nio.charset.Charset.forName("UTF-8"))));
        return r;
    }

    private void openPicker() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ensurePicker();
                long now = System.currentTimeMillis();
                if (pickerLoadedAt == 0) {
                    picker.loadUrl(PICKER_URL);
                    pickerLoadedAt = now;
                } else if (pickerFromCache || now - pickerLoadedAt > STALE_MS) {
                    picker.reload();
                    pickerLoadedAt = now;
                }
                picker.setVisibility(View.VISIBLE);
                counter.setVisibility(View.INVISIBLE);
                picker.requestFocus();
                picker.evaluateJavascript("window.__kbShellSend&&window.__kbShellSend()", null);
            }
        });
    }

    private boolean pickerShown() {
        return picker != null && picker.getVisibility() == View.VISIBLE;
    }

    private void showCounter() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                counter.setVisibility(View.VISIBLE);
                if (picker != null) picker.setVisibility(View.GONE);
                counter.requestFocus();
                applyBars(counterStatusColor, counterStatusColor, counterDark);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pickerShown() && (pickerFromCache || System.currentTimeMillis() - pickerLoadedAt > STALE_MS)) {
            picker.reload();
            pickerLoadedAt = System.currentTimeMillis();
        }
    }

    @Override
    public void onBackPressed() {
        if (pickerShown()) {
            // 台選び側でシートが開いていれば閉じる。開いていなければカウンターへ戻る
            picker.evaluateJavascript("(function(){var s=document.getElementById('sheet'),c=document.getElementById('scrim');"
                    + "if(s&&s.classList.contains('open')&&c){c.click();return '1'}return '0'})()", new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    if (v == null || v.indexOf('1') < 0) showCounter();
                }
            });
            return;
        }
        counter.evaluateJavascript("(window.__kabaneriBack&&window.__kabaneriBack())?'1':'0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || v.indexOf('1') < 0) finish();
            }
        });
    }

    /* ---------- 共通 ---------- */

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            // 開けるアプリがない
        }
    }

    private String readAsset(String name) {
        try {
            return new String(Fetcher.readAll(getAssets().open(name)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isDark(int c) {
        double l = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
        return l < 140;
    }

    private void applyBars(final int status, final int nav, final boolean dark) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Window w = getWindow();
                w.setStatusBarColor(status);
                w.setNavigationBarColor(nav);
                View d = w.getDecorView();
                int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                int f = d.getSystemUiVisibility();
                d.setSystemUiVisibility(dark ? (f & ~light) : (f | light));
            }
        });
    }

    private Uri insertImage(String base64, String name) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Kabaneri");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) return null;
            try { out.write(data); } finally { out.close(); }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** カウンター（同梱ページ）から呼ぶ機能。 */
    public final class Bridge {
        @JavascriptInterface
        public boolean saveImage(String base64, String name) {
            return insertImage(base64, name) != null;
        }

        @JavascriptInterface
        public void share(String text, String base64, String name) {
            Uri uri = base64 != null && base64.length() > 0 ? insertImage(base64, name) : null;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_TEXT, text);
            if (uri != null) {
                i.setType("image/png");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i.setType("text/plain");
            }
            startActivity(Intent.createChooser(i, null));
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("kabaneri", text));
                }
            });
        }

        @JavascriptInterface
        public void setBars(String hex, boolean dark) {
            try {
                counterStatusColor = Color.parseColor(hex);
                counterDark = dark;
                if (!pickerShown()) applyBars(counterStatusColor, counterStatusColor, dark);
            } catch (Exception e) {
                // 色の指定が不正なら何もしない
            }
        }

        @JavascriptInterface
        public void openPicker() {
            MainActivity.this.openPicker();
        }

        /** カウンターのCZポイントが変わるたびに呼ばれ、通知の表示を更新する。 */
        @JavascriptInterface
        public void czState(String json) {
            CzNotify.update(getApplicationContext(), json);
        }

        /** アプリが動いていない間に通知で押された操作（JSON配列）を取り出す。 */
        @JavascriptInterface
        public String takePending() {
            return CzNotify.takePending(getApplicationContext());
        }
    }

    /** 台選び（公開サイト）に渡す機能。戻ることと色合わせだけに限る。 */
    public final class PickerBridge {
        @JavascriptInterface
        public void back() {
            showCounter();
        }

        @JavascriptInterface
        public void bars(String top, String bottom) {
            try {
                int t = Color.parseColor(top), b = Color.parseColor(bottom);
                if (pickerShown()) applyBars(t, b, isDark(t));
            } catch (Exception e) {
                // 色の指定が不正なら何もしない
            }
        }

        @JavascriptInterface
        public String savedAt() {
            return pickerSavedAt == 0 ? "" : new SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(new Date(pickerSavedAt));
        }
    }
}
