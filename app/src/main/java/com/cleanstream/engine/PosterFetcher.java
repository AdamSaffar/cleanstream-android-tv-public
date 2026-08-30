package com.cleanstream.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads poster art off the UI thread with a bounded bitmap cache. */
public final class PosterFetcher {

    public interface Callback { void onPoster(Bitmap bmp); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Hundreds of full-size posters would exhaust a TV device. Limit cache to 24 MB.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private PosterFetcher() { }

    /** The catalog supplies this exact TMDB URL, avoiding a network search per card. */
    public static void fetchByUrl(final String posterUrl, final Callback cb) {
        if (posterUrl == null || posterUrl.isEmpty()) { cb.onPoster(null); return; }
        Bitmap hit = CACHE.get(posterUrl);
        if (hit != null) { cb.onPoster(hit); return; }
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap result = downloadBitmap(smallPosterUrl(posterUrl));
                if (result != null) CACHE.put(posterUrl, result);
                deliver(cb, result);
            }
        });
    }

    private static String smallPosterUrl(String posterUrl) {
        return posterUrl.replace("/t/p/w500/", "/t/p/w342/");
    }

    private static Bitmap downloadBitmap(String urlString) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            input = connection.getInputStream();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private static void deliver(final Callback callback, final Bitmap bitmap) {
        MAIN.post(new Runnable() {
            @Override public void run() { callback.onPoster(bitmap); }
        });
    }
}
