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

        String provider = "";
        try {
            android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi != null) provider = pi.packageName;
        } catch (Throwable t) {}
        final String prov = provider;
        wv.postDelayed(() -> wv.evaluateJavascript("debug('WebView: " + prov + "')", null), 1500);
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

    /* ================= PUENTE ================= */
    public class Bridge {
        /* Extrae URL de audio desde JAVA (sin CORS) y la devuelve a la web */
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

        /* Descarga el tema al almacenamiento local de la app */
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
                        c.setReadTimeout(30000);
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

    /* ============ EXTRACCIÓN NATIVA (Java, sin CORS) ============ */
    private String nativePlayer(String id) {
        String[] clients = {
            "{\"clientName\":\"ANDROID\",\"clientVersion\":\"19.09.37\",\"androidSdkVersion\":30," +
            "\"userAgent\":\"com.google.android.youtube/19.09.37 (Linux; U; Android 11; en_US) gzip\"}",
            "{\"clientName\":\"ANDROID_MUSIC\",\"clientVersion\":\"7.16.50\",\"androidSdkVersion\":30," +
            "\"userAgent\":\"com.google.android.apps.youtube.music/7.16.50 (Linux; U; Android 11; en_US) gzip\"}"
        };
        for (String client : clients) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                String body = "{\"context\":{\"client\":" + client + ",\"hl\":\"es\",\"gl\":\"US\"}," +
                        "\"videoId\":\"" + id + "\",\"params\":\"8AEB\"}";
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
                if (c.getResponseCode() != 200) continue;
                JSONObject j = new JSONObject(readAll(c.getInputStream()));
                JSONObject sd = j.optJSONObject("streamingData");
                if (sd == null) continue;
                String url = null;
                int best = -1;
                JSONArray ad = sd.optJSONArray("adaptiveFormats");
                if (ad != null) {
                    for (int i = 0; i < ad.length(); i++) {
                        JSONObject f = ad.getJSONObject(i);
                        if (f.optString("mimeType", "").startsWith("audio/mp4") && f.has("url")) {
                            int br = f.optInt("bitrate", 0);
                            if (br > best) { best = br; url = f.getString("url"); }
                        }
                    }
                }
                if (url == null) {
                    JSONArray fm = sd.optJSONArray("formats");
                    if (fm != null) for (int i = 0; i < fm.length(); i++) {
                        JSONObject f = fm.getJSONObject(i);
                        if (f.has("url")) { url = f.getString("url"); break; }
                    }
                }
                if (url != null) {
                    JSONObject out = new JSONObject();
                    out.put("url", url);
                    JSONObject vd = j.optJSONObject("videoDetails");
                    out.put("title", vd != null ? vd.optString("title", "") : "");
                    out.put("author", vd != null ? vd.optString("author", "") : "");
                    return out.toString();
                }
            } catch (Exception e) { /* siguiente client */ }
        }
        return null;
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
