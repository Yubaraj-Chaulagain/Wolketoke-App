package com.yubaraj.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** Online LiveKit mode. Offline mode remains in MainActivity. */
public class OnlineActivity extends Activity {
    private WebView webView;
    private static final int REQ_AUDIO = 409;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 21) {
                        boolean audioRequested = false;
                        for (String resource : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioRequested = true;
                        }
                        if (audioRequested && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        } else {
                            request.deny();
                        }
                    }
                });
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/online.html");
    }

    @Override public void onBackPressed() {
        if (webView != null) webView.evaluateJavascript("if(window.disconnectLiveKit){window.disconnectLiveKit();}", null);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.evaluateJavascript("if(window.disconnectLiveKit){window.disconnectLiveKit();}", null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
