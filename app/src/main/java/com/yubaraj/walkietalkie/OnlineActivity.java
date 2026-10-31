package com.yubaraj.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import androidx.webkit.WebViewAssetLoader;

/** Online LiveKit mode. Assets are served from a secure HTTPS-like origin so
 * ES modules and microphone APIs work correctly in Android WebView. */
public class OnlineActivity extends Activity {
    private WebView webView;
    private PermissionRequest pendingWebPermission;
    private static final int REQ_AUDIO = 409;
    private WebViewAssetLoader assetLoader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioRequested = true;
                    }
                    if (!audioRequested) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                    }
                });
            }
        });
        setContentView(webView);
        webView.loadUrl("https://appassets.androidplatform.net/assets/online.html");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && pendingWebPermission != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null) webView.evaluateJavascript("if(window.disconnectLiveKit){window.disconnectLiveKit();}", null);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (pendingWebPermission != null) { pendingWebPermission.deny(); pendingWebPermission = null; }
        if (webView != null) {
            webView.evaluateJavascript("if(window.disconnectLiveKit){window.disconnectLiveKit();}", null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
