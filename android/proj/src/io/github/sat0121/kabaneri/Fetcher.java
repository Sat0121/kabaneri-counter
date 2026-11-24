package io.github.sat0121.kabaneri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * ネットワーク優先・キャッシュ予備の取得。
 * 通信できれば毎回最新を取りに行き（サイトの更新がそのまま反映される）、取れた内容を保存しておく。
 * 通信できないときは最後に保存した内容を返す。Android の API に依存しないので PC 上でも試験できる。
 */
final class Fetcher {
    static final class Result {
        int status;
        String reason;
        String mime;
        String charset;
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body;
        boolean fromCache;
        long savedAt;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] FORWARD = {"Accept", "Accept-Language", "Origin", "Referer"};
    private final File dir;
    private final String userAgent;
    int connectTimeoutMs = 6000;
    int readTimeoutMs = 12000;

    Fetcher(File dir, String userAgent) {
        this.dir = dir;
        this.userAgent = userAgent;
        dir.mkdirs();
    }

    /** 取得結果。通信も保存済みの内容もなければ null。 */
    Result get(String url, Map<String, String> requestHeaders) {
        url = stripFragment(url);
        try {
            return fromNetwork(url, requestHeaders);
        } catch (IOException e) {
            return fromCache(url);
        }
    }

    private Result fromNetwork(String url, Map<String, String> requestHeaders) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (requestHeaders != null) {
                for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                    for (String f : FORWARD) {
                        if (f.equalsIgnoreCase(h.getKey())) c.setRequestProperty(f, h.getValue());
                    }
                }
            }
            int code = c.getResponseCode();
            if (code >= 500) throw new IOException("server error " + code); // 一時的な障害は保存済みの内容で代用する
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            String type = c.getContentType();
            String acao = c.getHeaderField("Access-Control-Allow-Origin");
            Result r = build(code, c.getResponseMessage(), type, acao, body);
            r.savedAt = System.currentTimeMillis();
            if (code == 200) save(url, r.savedAt, type, acao, body);
            return r;
        } finally {
            c.disconnect();
        }
    }

    Result fromCache(String url) {
        url = stripFragment(url);
        File b = file(url, ".body"), m = file(url, ".meta");
        if (!b.isFile() || !m.isFile()) return null;
        try {
            String[] meta = new String(readAll(new FileInputStream(m)), UTF8).split("\n", -1);
            Result r = build(200, "OK", meta.length > 1 ? meta[1] : null, meta.length > 2 && meta[2].length() > 0 ? meta[2] : null, readAll(new FileInputStream(b)));
            r.fromCache = true;
            r.savedAt = Long.parseLong(meta[0].trim());
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static Result build(int code, String reason, String type, String acao, byte[] body) {
        Result r = new Result();
        r.status = code;
        r.reason = reason == null || reason.length() == 0 ? (code < 400 ? "OK" : "Error") : reason;
        String mime = "application/octet-stream", charset = null;
        if (type != null) {
            String[] parts = type.split(";");
            mime = parts[0].trim().toLowerCase();
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.toLowerCase().startsWith("charset=")) charset = p.substring(8).replace("\"", "").trim();
            }
        }
        if (charset == null && (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json"))) charset = "utf-8";
        r.mime = mime;
        r.charset = charset;
        if (acao != null) r.headers.put("Access-Control-Allow-Origin", acao);
        r.headers.put("Cache-Control", "no-cache");
        r.body = body;
        return r;
    }

    private void save(String url, long at, String type, String acao, byte[] body) {
        try {
            write(file(url, ".body"), body);
            write(file(url, ".meta"), (at + "\n" + (type == null ? "" : type) + "\n" + (acao == null ? "" : acao)).getBytes(UTF8));
        } catch (IOException e) {
            // 保存できなくても表示は続ける
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        }
    }

    private File file(String url, String ext) {
        return new File(dir, sha1(url) + ext);
    }

    static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }
}
