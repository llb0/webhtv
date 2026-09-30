package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * <p>Used for sites whose stream cannot be played by native players (e.g.
 * yangshipin.cn, which uses CMG encryption). The page's own player handles
 * decryption via WebAssembly inside the WebView.</p>
 * <p>Usage: call {@link #attach(Activity, ViewGroup, String)} to overlay a
 * WebView on top of the video container, and {@link #detach()} when the channel
 * changes or the activity is destroyed.</p>
 * <p>内置双WebView轮换，onPageStarted清理JS、onPageFinished AutoFullscreen、硬编码延时；系统WebView，无X5</p>
 */
public class WebViewPlayer {

    private static final String TAG = "WebViewPlayer";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    // onPageStarted FastLoading 清理脚本
    private static final String FAST_LOADING_JS = """
            function FastLoading() {
                const fullscreenBtn = document.querySelector('#player_pagefullscreen_yes_player') || document.querySelector('.videoFull');
                if (fullscreenBtn) return;
                // 清空所有图片的 src 属性，阻止图片加载
                Array.from(document.getElementsByTagName('img')).forEach(img => {
                    img.src = '';
                });
                // 清空特定的脚本 src 属性
                const scriptKeywords = ['login', 'index', 'daohang', 'grey', 'jquery'];
                Array.from(document.getElementsByTagName('script')).forEach(script => {
                    if (scriptKeywords.some(keyword => script.src.includes(keyword))) {
                        script.src = '';
                    }
                });
                // 清空具有特定 class 的 div 内容
                const classNames = ['newmap', 'newtopbz', 'newtopbzTV', 'column_wrapper'];
                classNames.forEach(className => {
                    Array.from(document.getElementsByClassName(className)).forEach(div => {
                        div.innerHTML = '';
                    });
                });
                // 递归调用 FastLoading，每 4ms 触发一次
                setTimeout(FastLoading, 4);
            }
            FastLoading();
            """;

    // onPageFinished AutoFullscreen 自动点击全屏
    private static final String AUTO_FULLSCREEN_JS = """
            function AutoFullscreen(){
                var fullscreenBtn = document.querySelector('#player_pagefullscreen_yes_player')||document.querySelector('.videoFull');
                if(fullscreenBtn!=null){
                    fullscreenBtn.click();
                    document.querySelector('video').volume=1;
                }else{
                    setTimeout(()=>{ AutoFullscreen();},16);
                }
            }
            AutoFullscreen();
            """;

    private static final String FULLSCREEN_VIDEO_JS = "(function(){"
            + "function f(v){if(!v||v._wvfs)return;v._wvfs=1;"
            + "v.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;"
            + "max-width:100%!important;max-height:100%!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important;';"
            + "try{v.setAttribute('playsinline','');v.play()}catch(e){}"
            + "var r=v.requestFullscreen||v.webkitRequestFullscreen||v.webkitEnterFullscreen||v.msRequestFullscreen;"
            + "if(r){try{r.call(v)}catch(e){}}"
            + "}"
            + "function s(){var a=document.querySelectorAll('video');for(var i=0;i<a.length;i++)f(a[i]);return a.length>0}"
            + "if(!s()){var n=0,t=setInterval(function(){if(s()||++n>40)clearInterval(t)},300)}"
            + "document.addEventListener('DOMNodeInserted',function(e){"
            + "if(e.target&&e.target.tagName==='VIDEO')f(e.target);"
            + "else if(e.target&&e.target.querySelectorAll){var a=e.target.querySelectorAll('video');for(var i=0;i<a.length;i++)f(a[i])}"
            + "});"
            + "})()";

    // 硬编码延时：央视频500ms，其他1000ms
    private static final int DELAY_CCTV = 1500;
    private static final int DELAY_OTHER = 2000;

    private WebView activeWebView;
    private WebView idleWebView;

    private ViewGroup container;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Activity activity;
    private View.OnTouchListener touchListener;

    private boolean isChanging = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void attach(Activity activity, ViewGroup container, String url) {
        attach(activity, container, url, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void attach(Activity activity, ViewGroup container, String url, View.OnTouchListener touchListener) {
        if (activeWebView == null) {
            // 首次加载，直接创建active
            detach();
            this.activity = activity;
            this.container = container;
            this.touchListener = touchListener;

            activeWebView = createWebViewInstance(activity);
            if (touchListener != null) activeWebView.setOnTouchListener(touchListener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(activeWebView, lp);

            activeWebView.onResume();
            activeWebView.loadUrl(url);
            SpiderDebug.log(TAG, "first load: %s", url);
        } else {
            // 上层再次调用attach，触发后台idle预加载轮换，完全不改动上层调用
            if (isChanging || activity == null || container == null) return;
            isChanging = true;
            SpiderDebug.log(TAG, "preload next url: %s", url);

            idleWebView = createWebViewInstance(activity);
            if (touchListener != null) idleWebView.setOnTouchListener(touchListener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(idleWebView, lp);
            idleWebView.setVisibility(View.INVISIBLE);
            idleWebView.onResume();
            idleWebView.loadUrl(url);

            // 判断是否央视频地址，使用参考版对应硬编码延时
            int delay = url.contains("tv.cctv.com") ? DELAY_CCTV : DELAY_OTHER;
            mainHandler.postDelayed(() -> {
                if (!isChanging || idleWebView == null || activeWebView == null) {
                    isChanging = false;
                    return;
                }
                SpiderDebug.log(TAG, "preload delay(%d) reached, swap webview", delay);
                WebView oldWeb = activeWebView;
                activeWebView = idleWebView;
                idleWebView = null;

                activeWebView.setVisibility(View.VISIBLE);
                destroyWebView(oldWeb);
                isChanging = false;
            }, delay);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebViewInstance(Activity ctx) {
        WebView webView = new WebView(ctx);
        webView.setBackgroundColor(0xFF000000);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(FAST_LOADING_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if ("about:blank".equals(url)) return;
                if (url.contains("tv.cctv.com")) {
                    view.evaluateJavascript(AUTO_FULLSCREEN_JS, null);
                } else {
                    view.evaluateJavascript(FULLSCREEN_VIDEO_JS, null);
                    if (url.contains("miguvideo.com")) webView.postDelayed(() -> simulateClick(webView), 3000);
                }
                SpiderDebug.log(TAG, "onPageFinished %s", url);
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
        return webView;
    }

    private void simulateClick(WebView webView) {
        View targetView;
        if (customView != null) {
            targetView = customView;
        } else {
            targetView = webView;
        }

        if (targetView == null || targetView.getWidth() <= 0 || targetView.getHeight() <= 0) {
            SpiderDebug.log(TAG, "simulate click skip, view size invalid");
            return;
        }

        int x = targetView.getWidth() / 2;
        int y = (int) (targetView.getHeight() * 0.75f);

        long downTime = System.currentTimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_UP, x, y, 0);
        targetView.dispatchTouchEvent(down);
        targetView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        SpiderDebug.log(TAG, "simulate click target=%s x=%d y=%d", customView != null ? "customView" : "webView", x, y);
    }

    private void destroyWebView(WebView wv) {
        if (wv == null) return;
        try {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.onPause();
            wv.removeAllViews();
            if (container != null) container.removeView(wv);
            wv.destroy();
        } catch (Throwable ignored) {}
    }

    public void onResume() {
        if (activeWebView != null) activeWebView.onResume();
    }

    public void onPause() {
        if (activeWebView != null) activeWebView.onPause();
    }

    public boolean canGoBack() {
        return activeWebView != null && activeWebView.canGoBack();
    }

    public void goBack() {
        if (activeWebView != null) activeWebView.goBack();
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
        destroyWebView(activeWebView);
        destroyWebView(idleWebView);

        activeWebView = null;
        idleWebView = null;
        container = null;
        activity = null;
        touchListener = null;
        isChanging = false;
    }

    public boolean isChanging() {
        return isChanging;
    }
}
