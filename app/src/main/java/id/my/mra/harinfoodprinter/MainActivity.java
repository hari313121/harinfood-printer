package id.my.mra.harinfoodprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;

/**
 * Pembungkus (WebView) untuk HARINFOOD POS.
 * Menyediakan window.HarinPrint.print(base64) ke halaman web, sehingga struk bisa dikirim ke aplikasi RawBT
 * TANPA sentuhan layar (Chrome memblokir hal ini, WebView aplikasi tidak).
 */
public class MainActivity extends Activity {

    // Ganti kalau alamat aplikasi Anda berubah
    private static final String START_URL = "https://harinfood.mra.my.id/";
    private static final int FILE_CHOOSER_REQ = 4711;
    private static final int GPS_RESOLVE_REQ = 4712;
    private static final int LOCATION_PERM_REQ = 4713;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Izin lokasi untuk halaman web (navigator.geolocation) & permintaan "aktifkan GPS"
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private boolean pendingEnableGps = false;

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLocationPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERM_REQ);
    }

    /** Kabari halaman web hasil permintaan aktifkan GPS. */
    private void notifyGps(final boolean ok) {
        webView.post(() -> webView.evaluateJavascript("window.__harinGpsResult && window.__harinGpsResult(" + ok + ")", null));
    }

    /** Tampilkan dialog sistem Google "Aktifkan lokasi?" (satu ketukan OK menyalakan GPS). Cadangan: buka Pengaturan Lokasi. */
    private void checkAndEnableGps() {
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build();
            LocationSettingsRequest settingsReq = new LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build();
            LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq)
                .addOnSuccessListener(r -> notifyGps(true))
                .addOnFailureListener(e -> {
                    if (e instanceof ResolvableApiException) {
                        try {
                            ((ResolvableApiException) e).startResolutionForResult(MainActivity.this, GPS_RESOLVE_REQ);
                        } catch (Exception ex) { openLocationSettings(); }
                    } else { openLocationSettings(); }
                });
        } catch (Throwable t) { openLocationSettings(); }
    }

    private void openLocationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { /* abaikan */ }
        notifyGps(false);
    }

    /** Jembatan JavaScript -> Android untuk GPS. */
    private class GpsBridge {
        @JavascriptInterface
        public void enable() {
            runOnUiThread(() -> {
                if (!hasLocationPermission()) { pendingEnableGps = true; askLocationPermission(); }
                else checkAndEnableGps();
            });
        }
    }

    /** Jembatan JavaScript -> Android. */
    private class PrintBridge {
        @JavascriptInterface
        public boolean print(String base64Data) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("rawbt:base64," + base64Data));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Layar tetap menyala selama aplikasi terbuka (supaya pesanan bisa tercetak otomatis)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings st = webView.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        st.setAllowFileAccess(false);

        webView.addJavascriptInterface(new PrintBridge(), "HarinPrint");
        webView.addJavascriptInterface(new GpsBridge(), "HarinGps");
        st.setGeolocationEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) return false; // tetap di dalam aplikasi
                try { // tel:, wa.me, intent:, dsb. dibuka aplikasi lain
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // Dukungan pilih berkas/foto (mis. unggah gambar produk)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) { callback.invoke(origin, true, false); return; }
                geoCallback = callback; geoOrigin = origin;
                askLocationPermission();
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQ);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERM_REQ) return;
        boolean granted = false;
        for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
        if (geoCallback != null) { geoCallback.invoke(geoOrigin, granted, false); geoCallback = null; geoOrigin = null; }
        if (pendingEnableGps) {
            pendingEnableGps = false;
            if (granted) checkAndEnableGps(); else notifyGps(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == GPS_RESOLVE_REQ) { notifyGps(resultCode == RESULT_OK); return; }
        if (requestCode == FILE_CHOOSER_REQ && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
