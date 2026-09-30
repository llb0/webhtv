package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.http.SslError;
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
     * JS逻辑：只处理video标签，不依赖页面class，触发video原生play + 原生全屏
     * 使用MutationObserver监听新增video，不使用废弃DOMNodeInserted
     */
    private static final String FULLSCREEN_VIDEO_JS = "(function(){"
            + "document.body.style.cssText='background:#000 !important;margin:0 !important;padding:0 !important;overflow:hidden !important;';"
            + "document.documentElement.style.cssText='background:#000 !important;overflow:hidden !important;';"
            + "function handleVideo(v){"
            + "if(!v || v._marked) return;"
            + "v._marked = true;"
            + "v.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;"
            + "max-width:100%!important;max-height:100%!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important;';"
            + "try{v.setAttribute('playsinline','');v.play();}catch(e){console.log('video play err',e);}"
            + "// 调用video原生全屏API"
            + "var enterFull = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen || v.msRequestFullscreen;"
            + "if(enterFull) { try { enterFull.call(v); } catch(e) { console.log('video fullscreen err',e); } }"
            + "}"
            + "// 扫描页面所有video"
            + "function scanAllVideo(){var list = document.querySelectorAll('video');for(var i=0;i<list.length;i++) handleVideo(list[i]);return list.length>0;}"
            + "// 首次扫描，没找到就轮询"
            + "if(!scanAllVideo()){"
            + "let count=0;"
            + "let timer=setInterval(function(){"
            + "if(scanAllVideo() || ++count>40) clearInterval(timer);"
            + "},300);"
            + "}"
            + "// 监听DOM新增video标签"
            + "var observer = new MutationObserver(function(mutations){"
            + "mutations.forEach(function(m){"
            + "if(m.addedNodes.length) scanAllVideo();"
            + "});"
            + "});"
            + "observer.observe(document.body,{childList:true,subtree:true});"
            + "})()";

    private WebView webView;
    private ViewGroup container;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Activity activity;
    private View.OnTouchListener touchListener;

    public void attach(Activity activity, ViewGroup container, String url) {
        attach(activity, container, url, null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void attach(Activity activity, ViewGroup container, String url, View.OnTouchListener touchListener) {
        detach();
        this.activity = activity;
        this.container = container;
        this.touchListener = touchListener;

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
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 页面加载初期黑屏，避免加载时白屏/封面
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
                // Java层兜底点击触发页面交互
                view.performClick();
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
        touchListener = null;
    }
}
