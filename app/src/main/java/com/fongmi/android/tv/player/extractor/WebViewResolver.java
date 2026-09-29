package com.fongmi.android.tv.player.extractor;
 
import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.net.http.SslError;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
 
import androidx.annotation.NonNull;
 
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
 
import com.google.common.net.HttpHeaders;
 
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
 
/**
 * Resolves {@code webview://<url>} links by loading the target page inside a
 * hidden WebView and sniffing the real media URL out of the network traffic.
 *
 * <p>This deliberately avoids any native signing/decryption libraries: it only
 * relies on the platform WebView's network stack. For sites like yangshipin.cn
 * the HLS manifest ({@code .m3u8}) is requested by the page's own player script
 * and can be captured transparently via {@link WebViewClient#shouldInterceptRequest}.</p>
 *
 * <p>The resolver also captures the request headers (Referer, Cookie, etc.) that
 * the WebView sends with the media request, and writes them onto the {@link Result}
 * so that the native player (ExoPlayer / MPV / IJK) can replay them when fetching
 * the stream. Without these headers CDNs like ysp.cctv.cn reject or degrade the
 * connection.</p>
 */
public class WebViewResolver implements Source.Extractor {
 
    private static final String TAG = "WebViewResolver";
    private static final String SCHEME = "webview";
    private static final String PREFIX = SCHEME + "://";
    private static final long TIMEOUT_MS = 30_000L;
    private static final long POLL_INTERVAL_MS = 1_000L;
    private static final long COOKIE_SETTLE_MS = 1_000L;
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
 
    private final AtomicReference<WebView> webViewRef = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    private volatile String targetUrl = "";
 
    @Override
    public boolean match(Uri uri) {
        return SCHEME.equals(UrlUtil.scheme(uri));
    }
 
    @Override
    public String fetch(String url) throws Exception {
        if (url == null || !url.startsWith(PREFIX)) return url;
        String target = url.substring(PREFIX.length());
        if (target.isEmpty()) throw new Exception("webview url is empty");
        targetUrl = target;
        return resolve(target);
    }
 
    @Override
    public String fetch(Result result) throws Exception {
        String resolved = fetch(result.getUrl().v());
        applyHeaders(result, resolved);
        return resolved;
    }
 
    /** Set Referer / User-Agent / Cookie / Origin on the Result so the native player can access the stream. */
    private void applyHeaders(Result result, String resolvedUrl) {
        Map<String, String> headers = new HashMap<>(result.getHeader());
        // 1. Use captured WebView request headers as the base (includes Cookie/Referer the page sent)
        Map<String, String> captured = capturedHeaders.get();
        if (captured != null) {
            for (Map.Entry<String, String> e : captured.entrySet()) {
                String key = e.getKey();
                // Skip content-type / range / host — these are request mechanics, not playback headers
                if (key.equalsIgnoreCase("content-type") || key.equalsIgnoreCase("content-length")
                        || key.equalsIgnoreCase("host") || key.equalsIgnoreCase("connection")
                        || key.equalsIgnoreCase("accept-encoding") || key.equalsIgnoreCase("accept")
                        || key.equalsIgnoreCase("range")) continue;
                headers.putIfAbsent(UrlUtil.fixHeader(key), e.getValue());
            }
        }
        // 2. Fallback: ensure Referer and User-Agent are always set
        if (!hasKey(headers, HttpHeaders.REFERER) && !targetUrl.isEmpty()) {
            headers.put(HttpHeaders.REFERER, targetUrl);
        }
        if (!hasKey(headers, HttpHeaders.USER_AGENT)) {
            headers.put(HttpHeaders.USER_AGENT, DESKTOP_UA);
        }
        // 3. Try to get cookies from CookieManager for the resolved URL domain
        if (!hasKey(headers, HttpHeaders.COOKIE)) {
            try {
                String cookie = CookieManager.getInstance().getCookie(resolvedUrl);
                if (TextUtils.isEmpty(cookie)) cookie = CookieManager.getInstance().getCookie(targetUrl);
                if (!TextUtils.isEmpty(cookie)) headers.put(HttpHeaders.COOKIE, cookie);
            } catch (Throwable ignored) {
            }
        }
        // 4. Set Origin for good measure
        if (!hasKey(headers, HttpHeaders.ORIGIN) && !targetUrl.isEmpty()) {
            try {
                Uri uri = Uri.parse(targetUrl);
                String origin = uri.getScheme() + "://" + uri.getHost();
                headers.put(HttpHeaders.ORIGIN, origin);
            } catch (Throwable ignored) {
            }
        }
        // Write back: directly modify the stored map if it exists, otherwise set it
        if (result.getHeader().isEmpty()) {
            result.setHeader(headers);
        } else {
            result.getHeader().putAll(headers);
        }
        SpiderDebug.log(TAG, "applied headers for %s: %s", resolvedUrl, headers.keySet());
    }
 
    private static boolean hasKey(Map<String, String> map, String key) {
        return map.keySet().stream().anyMatch(key::equalsIgnoreCase);
    }
 
    private String resolve(String target) throws Exception {
        Activity activity = App.activity();
        if (activity == null) throw new Exception("no activity for webview resolver");
 
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>("");
        AtomicReference<String> errorRef = new AtomicReference<>("");
 
        App.post(() -> startWebView(activity, target, latch, resultRef, errorRef));
 
        boolean done = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (done) {
            // Give the m3u8 response a moment to arrive so cookies are set in CookieManager
            try { Thread.sleep(COOKIE_SETTLE_MS); } catch (InterruptedException ignored) {}
        }
        cleanup();
        if (!done) throw new Exception("webview resolve timeout: " + target);
        String error = errorRef.get();
        if (!error.isEmpty()) throw new Exception(error);
        String resolved = resultRef.get();
        if (TextUtils.isEmpty(resolved)) throw new Exception("webview resolve empty: " + target);
        SpiderDebug.log(TAG, "resolved %s -> %s", target, resolved);
        return resolved;
    }
 
    @SuppressLint("SetJavaScriptEnabled")
    private void startWebView(@NonNull Activity activity, @NonNull String target,
                              @NonNull CountDownLatch latch,
                              @NonNull AtomicReference<String> resultRef,
                              @NonNull AtomicReference<String> errorRef) {
        try {
            WebView webView = new WebView(activity.getApplicationContext());
            webViewRef.set(webView);
 
            webView.setBackgroundColor(0);
            webView.setAlpha(0f);
            webView.setVisibility(View.VISIBLE);
            webView.setClickable(false);
            webView.setFocusable(false);
            webView.setFocusableInTouchMode(false);
            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(1, 1);
            root.addView(webView, lp);
 
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
 
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString(DESKTOP_UA);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            s.setLoadWithOverviewMode(false);
            s.setUseWideViewPort(false);
 
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }
 
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if (maybeResolve(url, resultRef, latch)) {
                        // Capture the request headers the WebView is sending (includes Referer, Cookie, etc.)
                        try {
                            capturedHeaders.set(new HashMap<>(request.getRequestHeaders()));
                        } catch (Throwable ignored) {
                        }
                        // Do NOT intercept — let the request pass through so the CDN sees a real
                        // browser request and response cookies are set in CookieManager.
                    }
                    return super.shouldInterceptRequest(view, request);
                }
 
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    schedulePoll(view, target, resultRef, latch);
                }
 
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    SpiderDebug.log(TAG, "resource error main=%s code=%s desc=%s url=%s",
                            request.isForMainFrame(), error.getErrorCode(),
                            error.getDescription(), request.getUrl());
                }
 
                @Override
                public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    errorRef.set("webview render process gone");
                    countDown(latch);
                    return true;
                }
            });
 
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage cm) {
                    if (cm != null) {
                        maybeResolve(cm.message(), resultRef, latch);
                    }
                    return super.onConsoleMessage(cm);
                }
            });
 
            webView.onResume();
            webView.loadUrl(target);
        } catch (Throwable t) {
            errorRef.set("webview init error: " + t.getMessage());
            countDown(latch);
        }
    }
 
    /** Poll the page DOM for media URLs that were not intercepted on the network layer. */
    private void schedulePoll(WebView webView, String target,
                              AtomicReference<String> resultRef, CountDownLatch latch) {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (latch.getCount() == 0 || webViewRef.get() != webView) return;
                try {
                    webView.evaluateJavascript(buildPollScript(), value -> {
                        String url = decodeJsString(value);
                        if (!TextUtils.isEmpty(url)) maybeResolve(url, resultRef, latch);
                        if (latch.getCount() > 0 && webViewRef.get() == webView) {
                            webView.postDelayed(this, POLL_INTERVAL_MS);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        };
        webView.postDelayed(poll, POLL_INTERVAL_MS);
    }
 
    private static String buildPollScript() {
        return "(function(){"
                + "var out=[];"
                + "function add(u){if(u&&u.indexOf('http')===0)out.push(u);}"
                + "document.querySelectorAll('video,source,audio').forEach(function(e){add(e.src);add(e.currentSrc);});"
                + "var html=document.documentElement.outerHTML||'';"
                + "var m=html.match(/https?:\\\\/\\\\/[^\\\\s'\\\"<>]+\\\\.(?:m3u8|mp4|mkv|flv|mpd)(?:\\\\?[^\\\\s'\\\"<>]*)?/gi);"
                + "if(m)m.forEach(add);"
                + "return out.length?out[0]:'';"
                + "})()";
    }
 
    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        try {
            return new org.json.JSONArray("[" + value + "]").optString(0, "");
        } catch (Exception e) {
            return "";
        }
    }
 
    private static boolean maybeResolve(String candidate,
                                        AtomicReference<String> resultRef, CountDownLatch latch) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (resultRef.get().length() > 0) return false;
        if (!Sniffer.isVideoFormat(candidate)) return false;
        resultRef.set(candidate);
        countDown(latch);
        return true;
    }
 
    private static void countDown(CountDownLatch latch) {
        while (latch.getCount() > 0) latch.countDown();
    }
 
    private void cleanup() {
        WebView webView = webViewRef.getAndSet(null);
        if (webView == null) return;
        App.post(() -> {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.onPause();
                ViewParent p = webView.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
        });
    }
 
    @Override
    public void stop() {
        cleanup();
    }
 
    @Override
    public void exit() {
        cleanup();
    }
}
