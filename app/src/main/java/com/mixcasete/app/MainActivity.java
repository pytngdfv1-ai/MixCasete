package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView wv;
    private WebView playerWv;

    private boolean polling = false;
    private int noVideoCount = 0;
    private boolean triedAlt = false;
    private String lastId = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        wv = new WebView(this);
        config(wv.getSettings());
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        wv.addJavascriptInterface(new Bridge(), "Android");
        root.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        playerWv = new WebView(this);
        config(playerWv.getSettings());
        playerWv.setWebViewClient(new PlayerClient());
        playerWv.setWebChromeClient(new WebChromeClient());
        root.addView(playerWv, new FrameLayout.LayoutParams(2, 2));

        setContentView(root);
        wv.loadUrl("file:///android_asset/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void config(WebSettings s) {
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    private void debugJs(final String m) {
        runOnUiThread(() -> wv.evaluateJavascript("debug('" + m + "')", null));
    }

    /* ================= PUENTE ================= */
    public class Bridge {
        @JavascriptInterface
        public void getStream(final String id) {
            new Thread(() -> {
                String json = null;
                try { json = nativePlayer(id); } catch (Exception e) {}
                final String out = json;
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.__streamCb && window.__streamCb(" + (out != null ? out : "null") + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void downloadYT(final String id, final String title) {
            new Thread(() -> {
                String path = null;
                try {
                    JSONObject j = new JSONObject(nativePlayer(id));
                    String url = j.getString("url");
                    File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    if (dir != null) {
                        File f = new File(dir, id + ".m4a");
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(60000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) Chrome/120 Mobile");
                        InputStream in = c.getInputStream();
                        FileOutputStream out = new FileOutputStream(f);
                        byte[] buf = new byte[16384];
                        int n;
                        long total = 0;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                        out.close();
                        in.close();
                        if (total > 10000) path = f.getAbsolutePath();
                        else f.delete();
                    }
                } catch (Exception e) {}
                final String p = path;
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.onDownloaded && window.onDownloaded('" + id + "'," +
                        (p != null ? "'" + p + "'" : "null") + ")", null));
            }).start();
        }

        @JavascriptInterface
        public void playYT(final String id) {
            lastId = id;
            noVideoCount = 0;
            triedAlt = false;
            runOnUiThread(() -> playerWv.loadUrl("https://www.youtube.com/embed/" + id +
                    "?autoplay=1&controls=0&playsinline=1&rel=0&mute=0"));
        }
        @JavascriptInterface public void resumeYT() { runOnUiThread(() -> { tap(); enforce(); }); }
        @JavascriptInterface public void pauseYT() { js("(function(){var v=document.querySelector('video');if(v)v.pause();})();"); }
        @JavascriptInterface public void stopYT()  { runOnUiThread(() -> { polling = false; playerWv.loadUrl("about:blank"); }); }
        @JavascriptInterface public void seekYT(final int sec) { js("(function(){var v=document.querySelector('video');if(v)v.currentTime=" + sec + ";})();"); }
        @JavascriptInterface public void unmuteYT() { runOnUiThread(() -> { tap(); enforce(); tap(); enforce(); }); }
    }

    /* ============ EXTRACCIÓN MULTI-FUENTE (Java, sin CORS) ============ */
    private String nativePlayer(String id) {
        debugJs("Java: probando TV client...");
        String r = innertube(id,
                "{\"clientName\":\"TVHTML5\",\"clientVersion\":\"7.20250120.19.00\"}",
                "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) 92.0.4515.43 TV Safari/537.36");
        if (r != null) { debugJs("Java: TV client OK ✔"); return r; }

        r = innertube(id,
                "{\"clientName\":\"ANDROID_MUSIC\",\"clientVersion\":\"7.16.50\",\"androidSdkVersion\":30}",
                "com.google.android.apps.youtube.music/7.16.50 (Linux; U; Android 11) gzip");
        if (r != null) { debugJs("Java: ANDROID_MUSIC OK ✔"); return r; }

        r = fromInstances(id);
        if (r != null) return r;

        debugJs("Java: ninguna fuente dio audio ✖");
        return null;
    }

    private String innertube(String id, String clientJson, String ua) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", ua);
            c.setDoOutput(true);
            String body = "{\"context\":{\"client\":" + clientJson + ",\"hl\":\"es\",\"gl\":\"US\"}," +
                    "\"videoId\":\"" + id + "\",\"params\":\"8AEB\"}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            if (c.getResponseCode() != 200) return null;
            JSONObject j = new JSONObject(readAll(c.getInputStream()));
            JSONObject sd = j.optJSONObject("streamingData");
            if (sd == null) return null;
            String url = null;
            int best = -1;
            JSONArray ad = sd.optJSONArray("adaptiveFormats");
            if (ad != null) for (int i = 0; i < ad.length(); i++) {
                JSONObject f = ad.optJSONObject(i);
                if (f == null) continue;
                if (f.has("url") && f.optString("mimeType", "").startsWith("audio/mp4")) {
                    int br = f.optInt("bitrate", 0);
                    if (br > best) { best = br; url = f.optString("url"); }
                }
            }
            if (url == null) {
                JSONArray fm = sd.optJSONArray("formats");
                if (fm != null) for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.optJSONObject(i);
                    if (f != null && f.has("url")) { url = f.optString("url"); break; }
                }
            }
            if (url == null) return null;
            JSONObject out = new JSONObject();
            out.put("url", url);
            JSONObject vd = j.optJSONObject("videoDetails");
            out.put("title", vd != null ? vd.optString("title", "") : "");
            out.put("author", vd != null ? vd.optString("author", "") : "");
            return out.toString();
        } catch (Exception e) { return null; }
    }

    private String fromInstances(String id) {
        try {
            JSONArray arr = new JSONArray(httpGet("https://api.invidious.io/instances.json?sort_by=health"));
            int tried = 0;
            for (int i = 0; i < arr.length() && tried < 6; i++) {
                JSONArray entry = arr.optJSONArray(i);
                if (entry == null) continue;
                String host = entry.optString(0, "");
                JSONObject meta = entry.optJSONObject(1);
                if (host.isEmpty() || meta == null || !meta.optBoolean("api", false)) continue;
                tried++;
                try {
                    debugJs("Java: Invidious " + host + "...");
                    JSONObject v = new JSONObject(httpGet("https://" + host + "/api/v1/videos/" + id));
                    String url = pickInvidious(v, host);
                    if (url != null) {
                        debugJs("Java: Invidious OK ✔ " + host);
                        return buildOut(url, v.optString("title", ""), v.optString("author", ""));
                    }
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        try {
            JSONArray arr = new JSONArray(httpGet("https://piped-instances.kavin.rocks/"));
            int tried = 0;
            for (int i = 0; i < arr.length() && tried < 6; i++) {
                JSONObject inst = arr.optJSONObject(i);
                if (inst == null) continue;
                String api = inst.optString("api_url", "");
                if (api.isEmpty()) continue;
                tried++;
                try {
                    debugJs("Java: Piped " + api.replace("https://", "") + "...");
                    JSONObject v = new JSONObject(httpGet(api + "/streams/" + id));
                    String url = pickPiped(v);
                    if (url != null) {
                        debugJs("Java: Piped OK ✔");
                        return buildOut(url, v.optString("title", ""), v.optString("uploader", ""));
                    }
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        return null;
    }

    private String pickInvidious(JSONObject v, String host) {
        String fallback = null;
        JSONArray ad = v.optJSONArray("adaptiveFormats");
        if (ad != null) {
            for (int i = 0; i < ad.length(); i++) {
                JSONObject f = ad.optJSONObject(i);
                if (f == null) continue;
                String t = f.optString("type", "");
                String u = f.optString("url", "");
                if (u.isEmpty()) continue;
                if (u.startsWith("/")) u = "https://" + host + u;
                if (t.startsWith("audio/mp4")) return u;
                if (fallback == null && t.startsWith("audio")) fallback = u;
            }
        }
        if (fallback != null) return fallback;
        JSONArray fs = v.optJSONArray("formatStreams");
        if (fs != null && fs.length() > 0) {
            JSONObject f0 = fs.optJSONObject(0);
            if (f0 != null) {
                String u = f0.optString("url", "");
                if (u.startsWith("/")) u = "https://" + host + u;
                if (!u.isEmpty()) return u;
            }
        }
        return null;
    }

    private String pickPiped(JSONObject v) {
        JSONArray as = v.optJSONArray("audioStreams");
        String best = null;
        int bestBr = -1;
        if (as != null) for (int i = 0; i < as.length(); i++) {
            JSONObject f = as.optJSONObject(i);
            if (f == null) continue;
            String u = f.optString("url", "");
            if (u.isEmpty()) continue;
            int br = f.optInt("bitrate", 0);
            if (br > bestBr) { bestBr = br; best = u; }
        }
        return best;
    }

    private String buildOut(String url, String title, String author) {
        try {
            JSONObject out = new JSONObject();
            out.put("url", url);
            out.put("title", title);
            out.put("author", author);
            return out.toString();
        } catch (Exception e) { return null; }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("http " + code);
        return readAll(c.getInputStream());
    }

    private String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    /* ============ WEBVIEW DE FONDO (último recurso) ============ */
    private class PlayerClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            if (url.contains("/embed/") || url.contains("youtube-nocookie")) {
                tap();
                enforce();
                view.postDelayed(() -> { tap(); enforce(); }, 700);
                view.postDelayed(() -> enforce(), 1800);
                view.postDelayed(() -> enforce(), 3500);
                startPoll();
            }
        }
    }

    private void tap() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        MotionEvent up   = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 1f, 1f, 0);
        playerWv.dispatchTouchEvent(down);
        playerWv.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private void enforce() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        } catch (Throwable t) {}
        playerWv.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
            "if(!v)return 'novideo';" +
            "v.removeAttribute('muted');v.defaultMuted=false;v.muted=false;v.volume=1;" +
            "if(v.paused){v.play();}" +
            "return 'ok m='+v.muted+' p='+v.paused;})()",
            value -> wv.evaluateJavascript("debug('bridge " + value + "')", null)
        );
    }

    private void startPoll() {
        if (polling) return;
        polling = true;
        final Runnable[] r = new Runnable[1];
        r[0] = () -> {
            playerWv.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return null;" +
                "return JSON.stringify({t:v.currentTime||0,p:v.paused,e:v.ended,m:v.muted});})()",
                value -> {
                    if (value == null || value.equals("null")) {
                        noVideoCount++;
                        if (noVideoCount > 4 && !triedAlt && lastId != null) {
                            triedAlt = true;
                            noVideoCount = 0;
                            runOnUiThread(() -> playerWv.loadUrl(
                                "https://www.youtube-nocookie.com/embed/" + lastId +
                                "?autoplay=1&playsinline=1&rel=0"));
                        }
                    } else {
                        noVideoCount = 0;
                        if ((value.contains("\"m\":true") || value.contains("\"p\":true"))
                                && !value.contains("\"e\":true")) enforce();
                    }
                    wv.evaluateJavascript(
                        "window.onBridgeState && window.onBridgeState(" + value + ");", null);
                }
            );
            wv.postDelayed(r[0], 1000);
        };
        wv.postDelayed(r[0], 1200);
    }

    private void js(final String code) {
        runOnUiThread(() -> playerWv.evaluateJavascript(code, null));
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
