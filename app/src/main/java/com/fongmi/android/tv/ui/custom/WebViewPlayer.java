package com.fongmi.android.tv.ui.custom;
 
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.net.http.SslError;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
 
import com.github.catvod.crawler.SpiderDebug;
 
/**
 * Fullscreen WebView playback helper for {@code webview://} live channels.
 *
 * <p>Used for sites whose stream cannot be played by native players (e.g.
 * yangshipin.cn, which uses CMG encryption). The page's own player handles
 * decryption via WebAssembly inside the WebView.</p>
 *
 * <p>Usage: call {@link #attach(Activity, ViewGroup, String)} to overlay a
 * WebView on top of the video container, and {@link #detach()} when the channel
 * changes or the activity is destroyed.</p>
 */
public class WebViewPlayer {
 
    private static final String TAG = "WebViewPlayer";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
 
    private WebView webView;
    private ViewGroup container;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Activity activity;
 
    @SuppressLint("SetJavaScriptEnabled")
    public void attach(Activity activity, ViewGroup container, String url) {
        detach();
        this.activity = activity;
        this.container = container;
 
        webView = new WebView(activity);
        webView.setBackgroundColor(0xFF000000);
 
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
 
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
 
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
 
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                SpiderDebug.log(TAG, "resource error main=%s code=%s desc=%s url=%s",
                        request.isForMainFrame(), error.getErrorCode(), error.getDescription(), request.getUrl());
            }
        });
 
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                SpiderDebug.log(TAG, "console: %s", cm == null ? "" : cm.message());
                return super.onConsoleMessage(cm);
            }
 
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
                decor.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (webView != null) webView.setVisibility(View.GONE);
            }
 
            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
                decor.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
                if (webView != null) webView.setVisibility(View.VISIBLE);
            }
 
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
 
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(webView, lp);
 
        webView.onResume();
        webView.loadUrl(url);
        SpiderDebug.log(TAG, "loading %s", url);
    }
 
    public void onResume() {
        if (webView != null) webView.onResume();
    }
 
    public void onPause() {
        if (webView != null) webView.onPause();
    }
 
    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }
 
    public void goBack() {
        if (webView != null) webView.goBack();
    }
 
    public void detach() {
        if (customView != null) {
            FrameLayout decor = activity != null ? (FrameLayout) activity.getWindow().getDecorView() : null;
            if (decor != null) decor.removeView(customView);
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.onPause();
                webView.removeAllViews();
                if (container != null) container.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        container = null;
        activity = null;
    }
}
