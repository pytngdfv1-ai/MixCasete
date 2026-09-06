        @JavascriptInterface
        public void loadVideo(final String id) {
            lastId = id;
            noVideoCount = 0;
            triedAlt = false;
            runOnUiThread(() -> playerWv.loadUrl("https://www.youtube.com/embed/" + id +
                    "?autoplay=1&playsinline=1&rel=0&mute=0"));
        }

        @JavascriptInterface
        public void placeVideo(float x, float y, float w, float h) {
            final float d = getResources().getDisplayMetrics().density;
            final int X = Math.round(x * d), Y = Math.round(y * d);
            final int W = Math.round(w * d), H = Math.round(h * d);
            runOnUiThread(() -> {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(W, H);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                lp.setMargins(X, Y, 0, 0);
                playerWv.setLayoutParams(lp);
                playerWv.bringToFront();
            });
        }

        @JavascriptInterface
        public void hideVideo() {
            runOnUiThread(() -> playerWv.setLayoutParams(new FrameLayout.LayoutParams(2, 2)));
        }
