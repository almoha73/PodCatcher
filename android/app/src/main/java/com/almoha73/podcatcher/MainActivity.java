package com.almoha73.podcatcher;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class MainActivity extends AppCompatActivity {

    private static final String VIRTUAL_HOST = "appassets.androidplatform.net";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_BYTES = 25 * 1024 * 1024;

    private static class CacheEntry {
        final long timestamp;
        final byte[] body;
        CacheEntry(long timestamp, byte[] body) {
            this.timestamp = timestamp;
            this.body = body;
        }
    }

    private static final Map<String, CacheEntry> feedCache = new ConcurrentHashMap<>();

    private WebView webView;

    public class NativeBridge {
        @JavascriptInterface
        public void play(String url, String title, String author, String artworkUrl) {
            runOnUiThread(() ->
                PlaybackService.start(MainActivity.this, url, title, author, artworkUrl)
            );
        }

        @JavascriptInterface
        public void togglePlay() {
            runOnUiThread(() -> {
                PlaybackService s = PlaybackService.getInstance();
                if (s != null) s.togglePlay();
            });
        }

        @JavascriptInterface
        public void seekBy(int deltaSeconds) {
            runOnUiThread(() -> {
                PlaybackService s = PlaybackService.getInstance();
                if (s != null) s.seekBy(deltaSeconds);
            });
        }

        @JavascriptInterface
        public void seekTo(double seconds) {
            runOnUiThread(() -> {
                PlaybackService s = PlaybackService.getInstance();
                if (s != null) s.seekToSeconds(seconds);
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.parseColor("#0d1117"));
        getWindow().setNavigationBarColor(Color.parseColor("#0d1117"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0d1117"));
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !VIRTUAL_HOST.equalsIgnoreCase(uri.getHost())) {
                    return null;
                }
                String path = uri.getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) {
                    path = "/index.html";
                }
                if ("/proxy".equals(path)) {
                    return handleProxyRequest(uri);
                }
                String assetPath = path.startsWith("/") ? path.substring(1) : path;
                try {
                    InputStream in = getAssets().open(assetPath);
                    String mime = guessMimeType(assetPath);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, in);
                } catch (Exception e) {
                    return new WebResourceResponse(
                        "text/plain", "UTF-8", 404, "Not Found", null,
                        new ByteArrayInputStream("Not Found".getBytes(StandardCharsets.UTF_8))
                    );
                }
            }
        });

        PlaybackService.setListener((isPlaying, currentSec, durationSec, ended) -> runOnUiThread(() -> {
            if (webView != null) {
                String js = String.format(
                    Locale.US,
                    "window.app && window.app.onNativeAudioUpdate && window.app.onNativeAudioUpdate(%b, %.3f, %.3f, %b);",
                    isPlaying, currentSec, durationSec, ended
                );
                webView.evaluateJavascript(js, null);
            }
        }));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });

        requestNotificationPermissionIfNeeded();
        webView.loadUrl("https://" + VIRTUAL_HOST + "/index.html");
    }

    private WebResourceResponse handleProxyRequest(Uri uri) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");

        String target = uri.getQueryParameter("url");
        if (target == null || target.trim().isEmpty()) {
            return new WebResourceResponse(
                "text/plain", "UTF-8", 400, "Bad Request", headers,
                new ByteArrayInputStream("Missing url".getBytes(StandardCharsets.UTF_8))
            );
        }
        target = target.trim();

        CacheEntry cached = feedCache.get(target);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return new WebResourceResponse(
                "application/xml", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(cached.body)
            );
        }

        try {
            byte[] raw = downloadUrlFollowingRedirects(target);
            if (feedCache.size() > 30) {
                feedCache.clear();
            }
            feedCache.put(target, new CacheEntry(now, raw));
            return new WebResourceResponse(
                "application/xml", "UTF-8", 200, "OK", headers,
                new ByteArrayInputStream(raw)
            );
        } catch (Exception e) {
            String msg = "Proxy error: " + e.getMessage();
            return new WebResourceResponse(
                "text/plain", "UTF-8", 502, "Bad Gateway", headers,
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8))
            );
        }
    }

    private byte[] downloadUrlFollowingRedirects(String urlStr) throws Exception {
        String current = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) PodcatcherLocal/1.0");
            conn.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new Exception("Redirect without Location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }

            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new Exception("HTTP " + code);
            }

            String encoding = conn.getContentEncoding();
            InputStream rawStream = conn.getInputStream();
            if (encoding != null) {
                String enc = encoding.toLowerCase(Locale.ROOT);
                if (enc.contains("gzip")) {
                    rawStream = new GZIPInputStream(rawStream);
                } else if (enc.contains("deflate")) {
                    rawStream = new InflaterInputStream(rawStream);
                }
            }

            try (InputStream in = rawStream;
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new Exception("Feed too large");
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("Too many redirects");
    }

    private String guessMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlaybackService s = PlaybackService.getInstance();
        if (s != null) {
            s.pushProgress(false);
        }
    }
}
