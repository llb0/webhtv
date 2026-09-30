package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.http.SslError;
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
 * 回退稳定版本，移除JS桥；央视导航使用实测DOM选择器，延时执行隐藏
 */
public class WebViewPlayer {

    private static final String TAG = "WebViewPlayer";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 原版全屏JS，不动
     */
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

    /**
     * 实测抓取 tv.cctv.com 直播页面DOM，精准隐藏顶部导航，避开.player-box播放器
     */
    private static final String CCTV_HIDE_HEADER_JS = "(function(){"
            + "var selectors=[\".header\",\".header-top\",\".header-nav\",\".banner-box\",\".top-fixed\"];"
            + "selectors.forEach(function(sel){"
            + "var el=document.querySelector(sel);if(el)el.style.display='none';"
            + "});"
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

    @SuppressLint({"SetJavaScriptEnabled"})
    public void attach(Activity activity, ViewGroup container, String url, View.OnTouchListener touchListener) {
        detach();
        this.activity = activity;
        this.container = container;
        this.touchListener = touchListener;
        alreadyTriggerClick = false;

        webView = new WebView(activity);
        webView.setBackgroundColor(0xFF000000);
        if (touchListener != null) webView.setOnTouchListener(touchListener);

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
                //加载黑屏
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

                // ==========重点改动==========
                // 央视页面：延迟1200ms再执行隐藏脚本，等待JS动态渲染导航DOM
                if (url.contains("tv.cctv.com") || url.contains("cctv.com")) {
                    webView.postDelayed(() -> view.evaluateJavascript(CCTV_HIDE_HEADER_JS, null),1200);
                }

                // 模拟点击：2000ms，留给播放器控件渲染
                webView.postDelayed(() -> simulateClick(), 2000);
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

    private void simulateClick() {
        if (alreadyTriggerClick) return;
        alreadyTriggerClick = true;

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
        touchListener = null;
    }
}
