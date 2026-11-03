package com.aminaqatan.officedesk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Locale;

/**
 * «شاشة المكتب» on Android TV / Google TV (Sony and others): the web app full screen, driven by the remote.
 * The page itself knows it is on a TV from "OfficeDeskTV" in the user agent: the arrows move the focus,
 * OK presses, and Back asks the page first (close what is open, go to the first page) before leaving the app.
 */
public class MainActivity extends Activity {
    static final String HOME = "https://aminaqatan.github.io/Office-Desk-App/";
    static final String HOST = "aminaqatan.github.io";
    static final String PATH = "/office-desk-app";
    static final int BG = 0xFFFDF6EC;
    static final int PICK_FILE = 7;

    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean offline;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // a desk screen stays on
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        makeWeb();
        web.loadUrl(HOME);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void makeWeb() {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // tasks, notes and settings stay on the TV
        s.setMediaPlaybackRequiresUserGesture(false);    // scene videos and sounds can start by themselves
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " OfficeDeskTV/1.0");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestFocus(View.FOCUS_DOWN);
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private void toast(int id) {
        Toast.makeText(this, getString(id), Toast.LENGTH_LONG).show();
    }

    /** Back: the page closes what is open first; when it has nothing to close, the app closes. */
    @Override
    public void onBackPressed() {
        if (web == null || offline) {
            finish();
            return;
        }
        web.evaluateJavascript("(function(){try{return !!(window.tvBack&&window.tvBack())}catch(e){return false}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (!"true".equals(v)) finish();
                    }
                });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        hideBars();
        if (web != null && !web.hasFocus()) web.requestFocus(View.FOCUS_DOWN);   // the remote keeps talking to the page
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /* Home or another app: the sound stops and the page rests; back again: it carries on. */
    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.resumeTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        web.resumeTimers();
        js("window.tvResume&&tvResume()");
    }

    @Override
    protected void onPause() {
        if (web != null) {
            js("window.tvPause&&tvPause()");
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (web != null) web.pauseTimers();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (fileCb != null) {
            fileCb.onReceiveValue(null);
            fileCb = null;
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /** true when a link leaves the app: it opens in another app on the TV when there is one (YouTube, a browser). */
    private boolean leave(Uri u) {
        if (u == null) return false;
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "/" : u.getPath().toLowerCase(Locale.ROOT);
        if ((sc.equals("https") || sc.equals("http")) && host.equals(HOST) && (path.equals(PATH) || path.startsWith(PATH + "/"))) {
            return false;   // the app itself
        }
        if (sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript") || sc.equals("file")) {
            return true;    // never replace the app with these
        }
        try {
            Intent i;
            if (sc.equals("intent")) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            startActivity(i);
        } catch (Exception e) {
            toast(R.string.no_app);
        }
        return true;
    }

    private void showOffline() {
        offline = true;
        String html = "<!doctype html><html dir='rtl' lang='ar'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>"
                + "<body style='margin:0;height:100vh;display:grid;place-items:center;background:#FDF6EC;color:#2B2D42;font-family:sans-serif;text-align:center'>"
                + "<div><p style='font-size:30px;font-weight:bold;margin:0 0 10px'>" + getString(R.string.offline_title) + "</p>"
                + "<p style='font-size:19px;color:#6B6D80;margin:0 0 26px'>" + getString(R.string.offline_hint) + "</p>"
                + "<button id='r' autofocus style='font:inherit;font-size:22px;padding:14px 40px;border:0;border-radius:14px;background:#E07A5F;color:#fff;outline:4px solid #2B2D42;outline-offset:4px'>"
                + getString(R.string.retry) + "</button></div>"
                + "<script>var go=function(){location.href='" + HOME + "'};document.getElementById('r').onclick=go;"
                + "document.onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();go()}};document.getElementById('r').focus()</script></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return r.isForMainFrame() && leave(r.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {   // Android 6 and older
            return leave(Uri.parse(url));
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (url != null && url.toLowerCase(Locale.ROOT).startsWith("https://" + HOST)) offline = false;
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) showOffline();
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView v, int code, String desc, String url) {   // Android 5
            if (Build.VERSION.SDK_INT < 23) showOffline();
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            // the page's process ended (the TV ran low on memory): start the page again instead of closing the app
            if (web != null) {
                root.removeView(web);
                web.destroy();
                web = null;
            }
            makeWeb();
            web.loadUrl(HOME);
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        /** «صورتي» and «صورة أو ملف»: open the TV's file picker if it has one. */
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                startActivityForResult(p.createIntent(), PICK_FILE);
            } catch (Exception e) {
                fileCb = null;
                cb.onReceiveValue(null);
                toast(R.string.no_files);
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            r.deny();   // no camera or microphone on the TV app
        }

        /** no grey play-button picture over the scene videos while they load */
        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
