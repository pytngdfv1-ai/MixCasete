package com.mixcasete.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView wv;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = params.createIntent();
                try { startActivityForResult(i, FILE_CHOOSER); }
                catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        wv.addJavascriptInterface(new Bridge(), "Android");

        FrameLayout root = new FrameLayout(this);
        root.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        wv.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String ds = data.getDataString();
                if (ds != null) results = new Uri[] { Uri.parse(ds) };
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
        }
    }

    /* ================= PUENTE LEGAL ================= */
    public class Bridge {
        /* Guarda un archivo subido por el usuario al almacenamiento privado de la app */
        @JavascriptInterface
        public void saveLocalFile(final String uriString, final String id) {
            new Thread(() -> {
                String path = null;
                try {
                    Uri src = Uri.parse(uriString);
                    File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    if (dir != null) {
                        String ext = "mp3";
                        String t = src.getLastPathSegment();
                        if (t != null) {
                            int dot = t.lastIndexOf('.');
                            if (dot > 0) ext = t.substring(dot + 1);
                        }
                        File f = new File(dir, id + "." + ext);
                        InputStream in = getContentResolver().openInputStream(src);
                        OutputStream out = new FileOutputStream(f);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                        in.close();
                        path = f.getAbsolutePath();
                    }
                } catch (Exception e) {}
                final String p = path;
                runOnUiThread(() -> wv.evaluateJavascript(
                        "window.onLocalSaved && window.onLocalSaved('" + id + "'," +
                        (p != null ? "'" + p + "'" : "null") + ")", null));
            }).start();
        }

        /* Lee un archivo ya guardado (para reproducirlo desde file://) */
        @JavascriptInterface
        public String readLocalPath(final String id) {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (dir == null) return null;
            File[] files = dir.listFiles();
            if (files == null) return null;
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith(id + ".")) return f.getAbsolutePath();
            }
            return null;
        }

        /* Borra un archivo local */
        @JavascriptInterface
        public void deleteLocal(final String id) {
            new Thread(() -> {
                File dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if (dir == null) return;
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) if (f.getName().startsWith(id + ".")) f.delete();
            }).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (wv.canGoBack()) wv.goBack();
        else super.onBackPressed();
    }
}
