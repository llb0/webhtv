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
 * <p>Used for sites whose stream cannot be played by native players (e.g.
 * yangshipin.cn, which uses CMG encryption). The page's own player handles
 * decryption via WebAssembly inside the WebView.</p>
 * <p>Usage: call {@link #attach(Activity, ViewGroup, String)} to overlay a
 * WebView on top of the video container, and {@link #detach()} when the channel
 * changes or the activity is destroyed.</p>
 * <p>内置双WebView轮换，onPageStarted清理JS、JS回调视频就绪再触发切换+注入全屏/静音脚本、
 * 旧WebView延时销毁；系统WebView，无X5</p>
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
    // 全屏、自动播放、取消静音，视频ready后调用androidBridge通知Java切换
    private static final String UNMUTE_VIDEO_JS = """
            var videoEl = null;
            function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
            function setscale(scaletype) {
                if (!videoEl) return;
                let objectFitValue = 'contain', aspectratioValue = 'auto', widthValue = '100%', heightValue = '100%';
                switch (scaletype) {
                    case 0: objectFitValue = 'contain'; aspectratioValue = 'auto'; widthValue = '100%'; break;
                    case 1: objectFitValue = 'contain'; aspectratioValue = '16/9'; widthValue = '100%'; break;
                    case 2: aspectratioValue = '4/3'; objectFitValue = 'fill'; widthValue = 'auto'; break;
                    case 3: objectFitValue = 'fill'; aspectratioValue = 'none'; widthValue = '100%'; break;
                    case 4: objectFitValue = 'contain'; aspectratioValue = 'auto'; widthValue = '100%'; break;
                    case 5: objectFitValue = 'cover'; aspectratioValue = 'none'; widthValue = '100%'; break;
                    case 6:
                        objectFitValue = 'fill'; aspectratioValue = 'auto'; widthValue = '100%';
                        const screenWidth = window.innerWidth, screenHeight = window.innerHeight;
                        let videoHeight = screenWidth / 2.35;
                        if (videoHeight > screenHeight) videoHeight = screenHeight;
                        heightValue = (videoHeight / screenHeight) * 100 + '%';
                        break;
                }
                videoEl.style.cssText = 'width: ' + widthValue + ' !important; height: ' + heightValue + ' !important; object-fit: ' + objectFitValue + ' !important; aspect-ratio: ' + aspectratioValue + ' !important; position: absolute !important; top: 50% !important; left: 50% !important; transform: translate(-50%, -50%) !important; outline: none !important;';
            }
            function play() { if (videoEl && videoEl.paused) videoEl.play().catch(e => console.warn('play failed:', e)); }
            function pause() { if (videoEl && !videoEl.paused) videoEl.pause(); }
            function setposition(position) { if (videoEl) videoEl.currentTime = position; }
            function setspeed(speed) { if (videoEl) videoEl.playbackRate = speed; }
            async function ensureVideoVolume() {
                if (!videoEl) return;
                const maxRetries = 5;
                let retryCount = 0;
                while (retryCount < maxRetries) {
                    try {
                        videoEl.muted = false;
                        videoEl.volume = 1;
                        if (videoEl.paused) await videoEl.play().catch(() => {});
                        if (!videoEl.muted && videoEl.volume === 1) { console.log('volume set ok'); break; }
                    } catch (e) { console.warn('volume set retry:', e); }
                    retryCount++;
                    await delay(200);
                }
                if (retryCount >= maxRetries) console.error('volume set failed after retries');
            }
            (async function() {
                while (true) {
                    videoEl = document.querySelector('video');
                    if (videoEl && videoEl.readyState >= 1) break;
                    await delay(50);
                }
                // 视频元数据就绪，通知Java执行切换
                androidBridge.onVideoReady();
                document.body.style.cssText = 'width: 100vw; height: 100vh; margin: 0; min-width: 0; background: #000000; overflow: hidden;';
                document.documentElement.style.overflow = 'hidden';
                let fullscreenContainer = document.createElement('div');
                fullscreenContainer.style.cssText = 'position: fixed !important; top: 0 !important; left: 0 !important; width: 100% !important; height: 100% !important; z-index: 999999 !important; background: black !important; overflow: hidden !important;';
                document.body.appendChild(fullscreenContainer);
                if (videoEl.parentNode !== fullscreenContainer) fullscreenContainer.appendChild(videoEl);
                videoEl.controls = false;
                videoEl.removeAttribute('controls');
                // 强制 video 适配容器，防止原始尺寸溢出屏幕（含旋转后）
                function fitVideo(){
                    videoEl.style.cssText = 'width:100%!important;height:100%!important;object-fit:contain!important;max-width:100%!important;max-height:100%!important;outline:none!important;border:none!important;';
                }
                fitVideo();
                window.addEventListener('resize', fitVideo);
                window.addEventListener('orientationchange', () => setTimeout(fitVideo, 300));
                if (videoEl.readyState < 1) {
                    await new Promise(resolve => { videoEl.addEventListener('loadedmetadata', resolve, { once: true }); });
                }
                await ensureVideoVolume();
                setTimeout(async () => {
                    if (videoEl) {
                        await ensureVideoVolume();
                        if (videoEl.paused) videoEl.play().catch(e => console.warn('play failed:', e));
                    }
                }, 500);
                videoEl.addEventListener('volumechange', () => {
                    if (videoEl.muted || videoEl.volume === 0) ensureVideoVolume();
                }, { passive: true });
                videoEl.addEventListener('play', () => { ensureVideoVolume(); }, { passive: true });
                if (typeof ku9 !== 'undefined' && ku9.getscale) setscale(ku9.getscale());
                if (typeof ku9 !== 'undefined' && ku9.setduration) {
                    if (videoEl.duration > 0) ku9.setduration(videoEl.duration);
                    else videoEl.addEventListener('loadedmetadata', () => { if (videoEl.duration > 0) ku9.setduration(videoEl.duration); });
                }
                if (typeof ku9 !== 'undefined' && ku9.setvideo) {
                    if (videoEl.videoWidth && videoEl.videoHeight) ku9.setvideo(videoEl.videoWidth, videoEl.videoHeight);
                    else videoEl.addEventListener('loadedmetadata', () => { ku9.setvideo(videoEl.videoWidth, videoEl.videoHeight); });
                }
                if (typeof ku9 !== 'undefined' && ku9.setposition) {
                    videoEl.addEventListener('timeupdate', () => { ku9.setposition(videoEl.currentTime); });
                }
                videoEl.addEventListener('resize', () => {
                    if (typeof ku9 !== 'undefined' && ku9.setvideo) ku9.setvideo(videoEl.videoWidth, videoEl.videoHeight);
                    if (typeof ku9 !== 'undefined' && ku9.getscale) setscale(ku9.getscale());
                });
            })();
            """;

    private WebView activeWebView;
    private WebView idleWebView;

    private ViewGroup container;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Activity activity;
    private View.OnTouchListener touchListener;

    //==== 新增：双全屏承载容器 + 离屏预加载容器 ====
    private FrameLayout fullContainerA;
    private FrameLayout fullContainerB;
    private FrameLayout offscreenContainer;
    //标记当前active绑定哪个全屏容器
    private boolean activeUseA = true;

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

            //初始化双全屏容器 + 离屏容器，一次性创建加到DecorView
            initDualFullContainers();
            initOffscreenContainer();

            activeWebView = createWebViewInstance(activity, true);
            if (touchListener != null) activeWebView.setOnTouchListener(touchListener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(activeWebView, lp);

            activeWebView.onResume();
            activeWebView.loadUrl(url);
            SpiderDebug.log(TAG, "first load: %s", url);
        } else {
            if (isChanging || activity == null || container == null) return;
            isChanging = true;
            SpiderDebug.log(TAG, "preload next url: %s", url);
            if (idleWebView != null) {
                destroyWebView(idleWebView);
                idleWebView = null;
            }
            idleWebView = createWebViewInstance(activity, false);
            if (touchListener != null) idleWebView.setOnTouchListener(touchListener);

            //【核心改动】idle不再加入主container，放到1x1离屏容器
            FrameLayout.LayoutParams offLp = new FrameLayout.LayoutParams(1,1);
            offscreenContainer.addView(idleWebView, offLp);
            idleWebView.setVisibility(View.VISIBLE);
            idleWebView.onResume();
            idleWebView.loadUrl(url);

            // 兜底超时：若JS迟迟不回调视频就绪，5s后强制切换，避免卡死
            mainHandler.postDelayed(() -> {
                if (isChanging && idleWebView != null) {
                    SpiderDebug.log(TAG, "preload timeout fallback, force swap");
                    swapWebView();
                }
            }, 5000);
        }
    }

    //初始化A/B两套全屏容器，挂载DecorView
    private void initDualFullContainers() {
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        fullContainerA = new FrameLayout(activity);
        fullContainerA.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(fullContainerA);

        fullContainerB = new FrameLayout(activity);
        fullContainerB.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(fullContainerB);

        fullContainerB.setVisibility(View.GONE);
        activeUseA = true;
    }

    //初始化1x1 INVISIBLE离屏容器，预加载idle专用
    private void initOffscreenContainer() {
        offscreenContainer = new FrameLayout(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1,1);
        offscreenContainer.setLayoutParams(lp);
        offscreenContainer.setVisibility(View.INVISIBLE);
        ((FrameLayout) activity.getWindow().getDecorView()).addView(offscreenContainer);
    }

    /**
     * JS回调视频就绪后执行：新页面视频元数据就绪才显示新 WebView，旧 WebView 延时销毁。
     * 这样新视频画面就绪才切走旧画面，避免看到网页封面/UI，旧WebView保留一段时间做平滑过渡。
     */
    private void swapWebView() {
        if (!isChanging || idleWebView == null || activeWebView == null) {
            isChanging = false;
            return;
        }
        SpiderDebug.log(TAG, "swap webview: show new, schedule old destroy");
        WebView oldWeb = activeWebView;
        activeWebView = idleWebView;
        idleWebView = null;

        //1. idle从离屏容器剥离，加入主container
        if(offscreenContainer != null && offscreenContainer.indexOfChild(activeWebView) >=0){
            offscreenContainer.removeView(activeWebView);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(activeWebView, 0, lp);
        activeWebView.setVisibility(View.VISIBLE);

        //2. 切换全屏A/B容器显隐
        if(activeUseA){
            fullContainerA.setVisibility(View.GONE);
            fullContainerB.setVisibility(View.VISIBLE);
        }else{
            fullContainerB.setVisibility(View.GONE);
            fullContainerA.setVisibility(View.VISIBLE);
        }
        activeUseA = !activeUseA;

        oldWeb.setVisibility(View.GONE);

        // 旧 WebView 延时销毁：给新 WebView 留出首帧渲染时间，避免切换瞬间空白
        mainHandler.postDelayed(() -> destroyWebView(oldWeb), 1500);
        isChanging = false;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private WebView createWebViewInstance(Activity ctx, boolean isActive) {
        WebView webView = new WebView(ctx);
        webView.setBackgroundColor(0xFF000000);
        webView.setFocusable(false);

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
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadsImagesAutomatically(false);
        s.setBlockNetworkImage(true);

        //注入JS桥
        webView.addJavascriptInterface(new JsBridge(), "androidBridge");

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
                view.evaluateJavascript(UNMUTE_VIDEO_JS, null);
                //移除原来这里的swap延时！不再onPageFinished触发切换
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
            private final boolean webIsActive = isActive;
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
                //根据当前web实例，选择A/B全屏容器
                FrameLayout targetFullContainer;
                if(webIsActive){
                    targetFullContainer = activeUseA ? fullContainerA : fullContainerB;
                }else{
                    targetFullContainer = activeUseA ? fullContainerB : fullContainerA;
                }
                targetFullContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (webView != null) webView.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                if(customView.getParent() != null){
                    ((ViewGroup)customView.getParent()).removeView(customView);
                }
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

    //JS桥，视频就绪回调
    private class JsBridge {
        @JavascriptInterface
        public void onVideoReady() {
            mainHandler.post(() -> {
                if (isChanging && idleWebView != null) {
                    SpiderDebug.log(TAG, "JS notify video ready, start swap");
                    swapWebView();
                }
            });
        }
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
            //额外兜底：如果wv在离屏容器，也移除
            if(offscreenContainer != null && offscreenContainer.indexOfChild(wv)>=0){
                offscreenContainer.removeView(wv);
            }
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
            if(customView.getParent() != null){
                ((ViewGroup)customView.getParent()).removeView(customView);
            }
            customView = null;
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        }
        destroyWebView(activeWebView);
        destroyWebView(idleWebView);

        //清理双全屏容器
        if(fullContainerA != null && fullContainerA.getParent() != null){
            ((ViewGroup)fullContainerA.getParent()).removeView(fullContainerA);
        }
        if(fullContainerB != null && fullContainerB.getParent() != null){
            ((ViewGroup)fullContainerB.getParent()).removeView(fullContainerB);
        }
        //清理离屏容器
        if(offscreenContainer != null && offscreenContainer.getParent() != null){
            ((ViewGroup)offscreenContainer.getParent()).removeView(offscreenContainer);
        }

        activeWebView = null;
        idleWebView = null;
        container = null;
        activity = null;
        touchListener = null;
        fullContainerA = null;
        fullContainerB = null;
        offscreenContainer = null;
        isChanging = false;
    }

    public boolean isChanging() {
        return isChanging;
    }
}
