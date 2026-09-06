package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    private WebView wv;        // la app del cassette
    private WebView playerWv;  // reproductor YouTube de fondo (2px, invisible)

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
        playerWv.setWebViewClient(new WebViewClient());
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

    private void js(final String code) {
        runOnUiThread(() -> playerWv.evaluateJavascript(code, null));
    }

    public class Bridge {
        @JavascriptInterface
        public void playYT(final String id) {
            runOnUiThread(() -> {
                playerWv.loadUrl("https://www.youtube.com/embed/" + id +
                        "?autoplay=1&controls=0&playsinline=1&rel=0");
                startPoll();
            });
        }
        @JavascriptInterface
        public void resumeYT() { js("(function(){var v=document.querySelector('video');if(v){v.muted=false;v.volume=1;v.play();}})();"); }
        @JavascriptInterface
        public void pauseYT()  { js("(function(){var v=document.querySelector('video');if(v)v.pause();})();"); }
        @JavascriptInterface
        public void stopYT()   { runOnUiThread(() -> playerWv.loadUrl("about:blank")); }
        @JavascriptInterface
        public void seekYT(final int sec) { js("(function(){var v=document.querySelector('video');if(v)v.currentTime=" + sec + ";})();"); }
        @JavascriptInterface
        public void unmuteYT() { js("(function(){var v=document.querySelector('video');if(v){v.muted=false;v.volume=1;}})();"); }
    }

    private boolean polling = false;
    private void startPoll() {
        if (polling) return;
        polling = true;
        final Runnable[] r = new Runnable[1];
        r[0] = () -> {
            playerWv.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return null;" +
                "return JSON.stringify({t:v.currentTime||0,p:v.paused,e:v.ended});})()",
                value -> wv.evaluateJavascript(
                        "window.onBridgeState && window.onBridgeState(" + value + ");", null)
            );
            wv.postDelayed(r[0], 1000);
        };
        wv.postDelayed(r[0], 1500);
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
