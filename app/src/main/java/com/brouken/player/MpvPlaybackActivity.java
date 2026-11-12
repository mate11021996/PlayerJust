package com.brouken.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import dev.jdtech.mpv.MPVLib;

/**
 * Secondary in-process playback engine. Media3 remains the default player;
 * this activity is only entered when the user chooses MPV or Media3 fails.
 */
public final class MpvPlaybackActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_URI = "com.brouken.player.extra.MPV_URI";
    public static final String EXTRA_HEADERS = "com.brouken.player.extra.MPV_HEADERS";

    private MPVLib mpv;
    private SurfaceView surfaceView;
    private TextView status;
    private boolean surfaceReady;
    private boolean initialized;
    private boolean released;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        String media = getIntent().getStringExtra(EXTRA_URI);
        if (media == null || media.trim().isEmpty()) {
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            status = new TextView(this);
            status.setTextColor(Color.WHITE);
            status.setText("MPV zahtijeva Android 8.0 ili noviji.");
            status.setGravity(17);
            setContentView(status);
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setText("Pokretanje MPV playera…");
        status.setGravity(17);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        surfaceView.getHolder().addCallback(this);

        try {
            mpv = MPVLib.create(getApplicationContext());
            if (mpv == null) throw new IllegalStateException("MPV nije mogao stvoriti instancu");
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("gpu-context", "android");
            mpv.setOptionString("hwdec", "mediacodec-copy");
            mpv.setOptionString("keep-open", "yes");
            mpv.setOptionString("force-window", "yes");
            mpv.setOptionString("osc", "yes");
            mpv.setOptionString("input-default-bindings", "yes");
            mpv.setOptionString("input-vo-keyboard", "yes");

            String headers = getIntent().getStringExtra(EXTRA_HEADERS);
            if (headers != null && !headers.trim().isEmpty()) {
                mpv.setOptionString("http-header-fields", headers);
            }
            mpv.init();
            initialized = true;
            mpv.command(new String[]{"loadfile", media, "replace"});
            status.setVisibility(View.GONE);
            if (surfaceReady) mpv.attachSurface(surfaceView.getHolder().getSurface());
        } catch (Throwable error) {
            if (status != null) status.setText("MPV greška: " + error.getMessage());
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (mpv != null && initialized && !released) {
            try { mpv.attachSurface(holder.getSurface()); }
            catch (Throwable error) { if (status != null) { status.setVisibility(View.VISIBLE); status.setText("MPV prikaz: " + error.getMessage()); } }
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (mpv != null && initialized && !released) {
            try { mpv.detachSurface(); } catch (Throwable ignored) { }
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mpv != null && initialized && !released) {
            try {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    case KeyEvent.KEYCODE_SPACE:
                        mpv.command(new String[]{"cycle", "pause"}); return true;
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        mpv.setPropertyBoolean("pause", false); return true;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        mpv.setPropertyBoolean("pause", true); return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        mpv.command(new String[]{"seek", "-10", "relative"}); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        mpv.command(new String[]{"seek", "10", "relative"}); return true;
                    case KeyEvent.KEYCODE_BACK:
                        finish(); return true;
                }
            } catch (Throwable ignored) { }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onStop() {
        super.onStop();
        releaseMpv();
    }

    @Override protected void onDestroy() {
        releaseMpv();
        super.onDestroy();
    }

    private void releaseMpv() {
        if (released) return;
        released = true;
        if (mpv != null) {
            try { if (initialized && surfaceReady) mpv.detachSurface(); } catch (Throwable ignored) { }
            try { mpv.destroy(); } catch (Throwable ignored) { }
            mpv = null;
        }
    }
}
