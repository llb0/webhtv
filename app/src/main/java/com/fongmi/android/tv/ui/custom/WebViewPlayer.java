package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.http.SslError;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
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

    /**
     * 原版JS保留；增加逻辑：video准备就绪后调用Android接口通知Java执行点击
     */
    private static final String FULLSCREEN_VIDEO_JS = "(function(){"
            + "function f(v){if(!v||v._wvfs)return;v._wvfs=1;"
            + "v.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;"
            + "max-width:100%!important;max-height:100%!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important;';"
            + "try{v.setAttribute('playsinline','');v.play()}catch(e){}"
            + "var r=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen||v.msRequestFullscreen;"
            + "if(r){try{r.call(v)}catch(e){}}"
            + "// video加载就绪回调，通知Java执行点击"
            + "v.addEventListener('canplay',function(){"
            + "AndroidBridge.onVideoReady();"
            + "},{once:true});"
            + "}"
            + "function s(){var a=document.querySelectorAll('video');for(var i=0;i<a.length;i++)f(a[i]);return a.length>0}"
            + "if(!s()){var n=0,t=setInterval(function(){if(s()||++n>40)clearInterval(t)},300)}"
            + "document.addEventListener('DOMNodeInserted',function(e){"
            + "if(e.target&&e.target.tagName==='VIDEO')f(e.target);"
            + "else if(e.target&&e.target.querySelectorAll){var a=e.target.querySelectorAll('video');for(var i=0;i<a.length;i++)f(a[i])}"
            + "});"
            + "})()";

    /**
     * 央视专用：精准选择页面顶部导航，不再模糊匹配top/header，避免把播放器一起隐藏
     * tv.cctv.com 顶部导航实际id/class：#header、.header-banner，不匹配播放器内部元素
     */
    private static final String CCTV_HIDE_HEADER_JS = "(function(){"
            + "var topBanner = document.querySelector('#header'); if(topBanner) topBanner.style.display='none';"
            + "var topBanner2 = document.querySelector('.header-banner'); if(topBanner2) topBanner2.style.display='none';"
            + "})()";

    private WebView webView;
    private ViewGroup container;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Activity activity;
    private View.OnTouchListener touchListener;
    private boolean alreadyTriggerClick;

    public void attach(Activity activity, ViewGroup container, String url) {
        attach(activity, container, url, null);
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    public void attach(Activity activity, ViewGroup container, String url, View.OnTouchListener touchListener) {
        detach();
        this.activity = activity;
        this.container = container;
        this.touchListener = touchListener;
        alreadyTriggerClick = false;

        webView = new WebView(activity);
        webView.setBackgroundColor(0xFF000000);
        if (touchListener != null) webView.setOnTouchListener(touchListener);

        // 注入JS桥，JS回调Java
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

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
            private String currentUrl;

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentUrl = url;
                alreadyTriggerClick = false;
                // 加载阶段黑屏
                view.evaluateJavascript("(function(){document.body.style.background='#000';document.documentElement.style.background='#000';})()", null);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(FULLSCREEN_VIDEO_JS, null);
                // 仅央视执行顶部导航隐藏
                if (url.contains("tv.cctv.com") || url.contains("cctv.com")) {
                    view.evaluateJavascript(CCTV_HIDE_HEADER_JS, null);
                }
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

    /**
     * JS桥：JS在video canplay时回调这里，再执行模拟点击
     */
    public class JsBridge {
        @JavascriptInterface
        public void onVideoReady() {
            if (alreadyTriggerClick) return;
            alreadyTriggerClick = true;
            // 切回UI线程，增加短暂延迟留给播放按钮渲染
            if (webView != null) {
                webView.postDelayed(() -> simulateClickOnWebView(webView), 400);
            }
        }
    }

    /**
     * 模拟点击：
     * ✅ TV场景：customView原生全屏**依然执行模拟点击**，保证能触发播放按钮
     * ✅ 只发送一次DOWN+UP手势，发送结束后不会影响后续遥控器真实点击
     */
    private void simulateClickOnWebView(WebView wv) {
        if (wv == null || wv.getWidth() <= 0 || wv.getHeight() <= 0) return;

        int x = wv.getWidth() / 2;
        int y = (int) (wv.getHeight() * 0.75f);

        long downTime = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_UP, x, y, 0);
        wv.dispatchTouchEvent(down);
        wv.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        SpiderDebug.log(TAG, "simulate click x=%d y=%d", x, y);
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
                // 移除JS桥，防止内存泄漏
                webView.removeJavascriptInterface("AndroidBridge");
                if (container != null) container.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        container = null;
        activity = null;
        touchListener = null;
    }
}
