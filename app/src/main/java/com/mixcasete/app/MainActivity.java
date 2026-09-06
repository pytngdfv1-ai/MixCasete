package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView wv;         // la app del cassette
    private WebView playerWv;   // reproductor YouTube de fondo

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
        s.setMediaPlaybackRequiresUserGesture(false);   // autoplay permitido
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    /* ---------- Puente hacia la web ---------- */
    public class Bridge {
        @JavascriptInterface
        public void playYT(final String id) {
            lastId = id;
            noVideoCount = 0;
            triedAlt = false;
            runOnUiThread(() -> {
                playerWv.loadUrl("https://www.youtube.com/embed/" + id +
                        "?autoplay=1&controls=0&playsinline=1&rel=0&mute=0");
            });
        }
        @JavascriptInterface public void resumeYT() { runOnUiThread(MainActivity.this::enforce); }
        @JavascriptInterface public void pauseYT() { js("(function(){var v=document.querySelector('video');if(v)v.pause();})();"); }
        @JavascriptInterface public void stopYT()  { runOnUiThread(() -> { polling = false; playerWv.loadUrl("about:blank"); }); }
        @JavascriptInterface public void seekYT(final int sec) { js("(function(){var v=document.querySelector('video');if(v)v.currentTime=" + sec + ";})();"); }
        @JavascriptInterface public void unmuteYT() { runOnUiThread(MainActivity.this::enforce); }
    }

    /* ---------- Cliente del reproductor: al cargar → toque + unmute ---------- */
    private class PlayerClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            if (url.contains("/embed/") || url.contains("youtube-nocookie")) {
                tap();                      // gesto sintético = activación real
                enforce();                  // unmute + play inmediato
                view.postDelayed(() -> { tap(); enforce(); }, 700);
                view.postDelayed(MainActivity.this::enforce, 1800);
                view.postDelayed(MainActivity.this::enforce, 3500);
                startPoll();
            }
        }
    }

    /* Toque sintético: pasa por el pipeline de input → cuenta como gesto del usuario */
    private void tap() {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        MotionEvent up   = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 1f, 1f, 0);
        playerWv.dispatchTouchEvent(down);
        playerWv.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /* Fuerza unmute + play; reporta estado al debug de la app */
    private void enforce() {
        playerWv.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
            "if(!v)return 'novideo';" +
            "v.muted=false;v.volume=1;if(v.paused){v.play();}" +
            "return 'ok m='+v.muted+' p='+v.paused;})()",
            value -> wv.evaluateJavascript("debug('bridge " + value + "')", null)
        );
    }

    /* Sondeo 1/s: reloj, fin de tema, y AUTOREPARACIÓN (si mutea/pausa → enforce) */
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
                        // autoreparación: si está muteado o pausado sin haber terminado → forzar
                        if (value.contains("\"m\":true") || value.contains("\"p\":true")) {
                            if (!value.contains("\"e\":true")) enforce();
                        }
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
