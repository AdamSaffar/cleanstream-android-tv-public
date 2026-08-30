package com.cleanstream.engine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * FLAG_SECURE probe: request one screen capture via MediaProjection, grab a
 * single frame, and report whether it came back as real pixels or a black
 * (FLAG_SECURE) frame. This is the exact API our title-OCR path would use, so
 * the verdict is authoritative — unlike `adb screencap`, which runs as system
 * and can bypass FLAG_SECURE.
 *
 * Usage: console command CAPTURE launches this; navigate Netflix to a title
 * DETAIL page (or the player) first. Result via CAPTURERESULT / logcat, and a
 * PNG saved to the app's external files dir.
 */
public class CaptureActivity extends Activity {

    private static final String TAG = "CleanStreamEngine";
    private static final int REQ = 4242;
    public static volatile String lastResult = "no capture yet";

    private MediaProjectionManager mpm;
    private HandlerThread ht;
    private Handler bg;
    private int w, h, dpi;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        w = dm.widthPixels; h = dm.heightPixels; dpi = dm.densityDpi;
        ht = new HandlerThread("cap-bg"); ht.start(); bg = new Handler(ht.getLooper());
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        } catch (Exception e) {
            report("ERROR launching capture intent: " + e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) { finish(); return; }
        if (res != RESULT_OK || data == null) {
            report("DENIED: user did not grant screen capture");
            finish();
            return;
        }
        final Intent token = data;
        final int code = res;
        // small delay so the projection consent UI is gone and Netflix is on top
        bg.postDelayed(new Runnable() { public void run() { doCapture(code, token); } }, 700);
    }

    private void doCapture(int code, Intent token) {
        MediaProjection mp = null;
        ImageReader reader = null;
        VirtualDisplay vd = null;
        try {
            mp = mpm.getMediaProjection(code, token);
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            vd = mp.createVirtualDisplay("cs-cap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, bg);
            Thread.sleep(600);   // let a frame arrive

            Image img = null;
            for (int i = 0; i < 10 && img == null; i++) {
                img = reader.acquireLatestImage();
                if (img == null) Thread.sleep(120);
            }
            if (img == null) { report("NO FRAME captured (reader returned null)"); return; }

            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();

            long lumSum = 0; long nonBlack = 0; long n = 0;
            int step = 16; // sample grid
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int off = y * rowStride + x * pixelStride;
                    if (off + 2 >= buf.limit()) continue;
                    int r = buf.get(off) & 0xff;
                    int g = buf.get(off + 1) & 0xff;
                    int bl = buf.get(off + 2) & 0xff;
                    int lum = (r * 299 + g * 587 + bl * 114) / 1000;
                    lumSum += lum;
                    if (lum > 12) nonBlack++;
                    n++;
                }
            }
            double avgLum = n == 0 ? 0 : (double) lumSum / n;
            double nonBlackFrac = n == 0 ? 0 : (double) nonBlack / n;

            // save PNG for eyeballing
            String pngPath = "(png save failed)";
            try {
                Bitmap bmp = Bitmap.createBitmap(
                    rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
                File out = new File(getExternalFilesDir(null), "cs_capture.png");
                FileOutputStream fos = new FileOutputStream(out);
                cropped.compress(Bitmap.CompressFormat.PNG, 90, fos);
                fos.close();
                pngPath = out.getAbsolutePath();
            } catch (Throwable t) {
                pngPath = "(png save failed: " + t.getClass().getSimpleName() + ")";
            }

            img.close();

            boolean secure = avgLum < 6 && nonBlackFrac < 0.01;
            String verdict = secure
                ? "BLACK / FLAG_SECURE — capture blocked (OCR path NOT viable on this screen)"
                : "CAPTURABLE — real pixels (OCR path VIABLE on this screen)";
            report(verdict + " | " + w + "x" + h
                + " avgLum=" + String.format("%.1f", avgLum)
                + " nonBlackFrac=" + String.format("%.4f", nonBlackFrac)
                + " png=" + pngPath);
        } catch (Throwable t) {
            report("ERROR during capture: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            try { if (vd != null) vd.release(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (mp != null) mp.stop(); } catch (Exception ignored) {}
            runOnUiThread(new Runnable() { public void run() { finish(); } });
        }
    }

    private void report(String msg) {
        lastResult = msg;
        Log.i(TAG, "CAPTURE: " + msg);
        EngineService svc = EngineService.get();
        if (svc != null) svc.log("CAPTURE: " + msg);
    }

    @Override
    protected void onDestroy() {
        try { if (ht != null) ht.quitSafely(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
