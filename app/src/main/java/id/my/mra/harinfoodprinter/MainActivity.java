package id.my.mra.harinfoodprinter;

import android.Manifest;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.graphics.Color;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.PowerManager;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
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
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;


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
    private static final int BT_PERM_REQ = 4714;
    private static final int CAMERA_PERM_REQ = 4715;
    private static final int BLE_PERM_REQ = 4716;
    private static final String HOST = "harinfood.mra.my.id";
    private static final int KIND_EXTERNAL = 0, KIND_AUTOPRINT = 1, KIND_VISIBLE = 2, KIND_DOWNLOAD = 3;

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

    // ================= Tombol bell iTag (BLE) =================
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner leScanner;
    private ScanCallback scanCb;
    private boolean pendingScan = false;

    private boolean hasBlePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBellService() {
        try {
            Intent i = new Intent(this, BellService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            BellService.status = "Gagal menjalankan layanan: " + e.getMessage();
        }
    }

    private void stopBellService() {
        try { stopService(new Intent(this, BellService.class)); } catch (Exception ignored) {}
        BellService.status = "Tombol bell nonaktif";
    }

    private void notifyScan(String json, String message) {
        final String js = "window.__harinBellScan && window.__harinBellScan(" + JSONObject.quote(json) + "," + JSONObject.quote(message) + ")";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void stopBleScan() {
        try { if (leScanner != null && scanCb != null) leScanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanCb = null;
    }

    /** Cari perangkat BLE selama 8 detik lalu kirim daftarnya ke halaman web. */
    private void doBleScan() {
        if (!hasBlePerms()) {
            pendingScan = true;
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, BLE_PERM_REQ);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERM_REQ);
            return;
        }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) { notifyScan("[]", "Bluetooth HP mati. Nyalakan Bluetooth lalu cari lagi."); return; }
        try {
            stopBleScan();
            leScanner = ad.getBluetoothLeScanner();
            if (leScanner == null) { notifyScan("[]", "Pemindai Bluetooth tidak tersedia."); return; }
            final Map<String, JSONObject> found = new LinkedHashMap<String, JSONObject>();
            final ParcelUuid ffe0 = ParcelUuid.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
            scanCb = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult r) {
                    try {
                        BluetoothDevice d = r.getDevice();
                        String name = d.getName();
                        boolean tag = false;
                        ScanRecord rec = r.getScanRecord();
                        if (rec != null && rec.getServiceUuids() != null) tag = rec.getServiceUuids().contains(ffe0);
                        if (name == null || name.isEmpty()) {
                            if (!tag) return; // lewati perangkat tanpa nama kecuali bertipe iTag
                            String a = d.getAddress();
                            name = "iTag (" + a.substring(Math.max(0, a.length() - 5)) + ")";
                        }
                        JSONObject o = new JSONObject();
                        o.put("name", name);
                        o.put("address", d.getAddress());
                        o.put("rssi", r.getRssi());
                        o.put("tag", tag || name.toLowerCase().contains("itag"));
                        found.put(d.getAddress(), o);
                    } catch (Exception ignored) {}
                }
            };
            leScanner.startScan(scanCb);
            uiHandler.postDelayed(() -> {
                stopBleScan();
                try {
                    ArrayList<JSONObject> list = new ArrayList<JSONObject>(found.values());
                    Collections.sort(list, new Comparator<JSONObject>() {
                        @Override
                        public int compare(JSONObject a, JSONObject b) {
                            int ta = a.optBoolean("tag") ? 0 : 1, tb = b.optBoolean("tag") ? 0 : 1;
                            if (ta != tb) return ta - tb;
                            return b.optInt("rssi") - a.optInt("rssi");
                        }
                    });
                    JSONArray arr = new JSONArray();
                    for (JSONObject o : list) arr.put(o);
                    notifyScan(arr.toString(), arr.length() == 0 ? "Tidak ada perangkat ditemukan. Tekan tombol iTag sekali lalu cari lagi, dan pastikan iTag tidak sedang tersambung ke HP lain." : "");
                } catch (Exception e) {
                    notifyScan("[]", "Gagal membaca hasil pencarian");
                }
            }, 8000);
        } catch (SecurityException se) {
            notifyScan("[]", "Izin Bluetooth belum diberikan");
        } catch (Exception e) {
            notifyScan("[]", "Gagal mencari: " + e.getMessage());
        }
    }

    /** Jembatan JavaScript -> Android untuk pengaturan tombol bell iTag. */
    private class BellBridge {
        @JavascriptInterface
        public String getState() {
            try {
                SharedPreferences p = prefs();
                JSONObject o = new JSONObject();
                o.put("enabled", p.getBoolean("bell_on", false));
                o.put("addr", p.getString("bell_addr", ""));
                o.put("name", p.getString("bell_name", ""));
                o.put("status", BellService.status);
                o.put("running", BellService.running);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void startScan() { runOnUiThread(() -> doBleScan()); }

        @JavascriptInterface
        public void select(String address, String name) {
            prefs().edit().putString("bell_addr", address == null ? "" : address).putString("bell_name", name == null ? "" : name).apply();
            if (prefs().getBoolean("bell_on", false)) runOnUiThread(() -> startBellService()); // mulai ulang dengan iTag baru
        }

        @JavascriptInterface
        public void setEnabled(final boolean on) {
            prefs().edit().putBoolean("bell_on", on).apply();
            runOnUiThread(() -> { if (on) startBellService(); else stopBellService(); });
        }

        @JavascriptInterface
        public void setMusicUrl(String url) {
            String old = prefs().getString("bell_music_url", "");
            String nu = url == null ? "" : url;
            if (!old.equals(nu)) {
                prefs().edit().putString("bell_music_url", nu).apply();
                BellPlayer.invalidate(MainActivity.this);
            }
        }

        @JavascriptInterface
        public void test() { BellPlayer.play(getApplicationContext()); }
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

    // ---- Layanan latar belakang ----
    private boolean hasBtConnect() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBgService() {
        if (!hasBtConnect()) {
            Toast.makeText(this, "Izinkan akses Bluetooth agar cetak otomatis bisa jalan di latar belakang", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(this, PrintService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Layanan latar belakang gagal dimulai", Toast.LENGTH_LONG).show();
        }
    }

    private void stopBgService() {
        try { stopService(new Intent(this, PrintService.class)); } catch (Exception ignored) {}
    }

    // ---- Cetak LANGSUNG ke printer Bluetooth (tanpa aplikasi RawBT) ----
    private SharedPreferences prefs() { return getSharedPreferences("harin", MODE_PRIVATE); }

    private String sendBytesToPrinter(byte[] data) { return BtPrinter.send(this, data); }

    private void notifyPrintDone(final String jobId, final String result) {
        webView.post(() -> webView.evaluateJavascript(
            "window.__harinPrintDone && window.__harinPrintDone(" + JSONObject.quote(jobId) + "," + JSONObject.quote(result) + ")", null));
    }

    /** Jembatan JavaScript -> Android: kirim data ESC/POS (base64) ke aplikasi RawBT tanpa sentuhan layar. */
    private class PrintBridge {
        /** Daftar printer Bluetooth yang sudah dipasangkan (paired) sebagai JSON, atau "ERR:...". */
        @JavascriptInterface
        public String listPrinters() {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                JSONArray arr = new JSONArray();
                if (ad != null && ad.getBondedDevices() != null) {
                    for (BluetoothDevice d : ad.getBondedDevices()) {
                        JSONObject o = new JSONObject();
                        String name = d.getName();
                        o.put("name", name == null ? d.getAddress() : name);
                        o.put("address", d.getAddress());
                        arr.put(o);
                    }
                }
                return arr.toString();
            } catch (SecurityException se) {
                return "ERR:Izin Bluetooth belum diberikan";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** Nyalakan/matikan layanan latar belakang (cetak otomatis saat HP terkunci). */
        @JavascriptInterface
        public void setBackgroundPrint(final boolean on) {
            prefs().edit().putBoolean("bgservice", on).apply();
            runOnUiThread(() -> { if (on) startBgService(); else stopBgService(); });
        }

        @JavascriptInterface
        public boolean isBackgroundPrint() { return prefs().getBoolean("bgservice", false); }

        @JavascriptInterface
        public boolean isBatteryExempt() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                return pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void requestBatteryExempt() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getPrinter() { return prefs().getString("printer", ""); }

        @JavascriptInterface
        public void setPrinter(String address) { prefs().edit().putString("printer", address == null ? "" : address).apply(); }

        /** Cetak langsung ke printer Bluetooth di latar belakang; hasilnya dikabarkan ke JavaScript. */
        @JavascriptInterface
        public void printDirect(final String jobId, final String base64Data) {
            new Thread(() -> {
                String r;
                try { r = sendBytesToPrinter(Base64.decode(base64Data, Base64.DEFAULT)); }
                catch (Exception e) { r = "Data cetak tidak valid"; }
                notifyPrintDone(jobId, r);
            }).start();
        }

        @JavascriptInterface
        public boolean print(String base64Data) {
            Uri data = Uri.parse("rawbt:base64," + base64Data);
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, data);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e1) {
                try {
                    Intent i2 = new Intent(Intent.ACTION_VIEW, data);
                    i2.setPackage("ru.a402d.rawbtprinter");
                    i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i2);
                    return true;
                } catch (Exception e2) {
                    final String msg = e2.getMessage() == null ? e2.toString() : e2.getMessage();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "RawBT gagal dibuka: " + msg, Toast.LENGTH_LONG).show());
                    return false;
                }
            }
        }
    }

    // ================= Jendela tambahan (window.open), cetak, & unduhan =================
    // WebView tidak mendukung window.open()/window.print()/unduhan blob seperti Chrome, jadi semuanya dijembatani di sini:
    //  - halaman struk (print_order, print_note, ...)      : dimuat tersembunyi lalu langsung dikirim ke dialog cetak Android
    //  - halaman interaktif (kartu nama, kartu member, laporan): ditampilkan penuh dengan tombol "Tutup"; window.print() & unduh PDF dijembatani
    //  - export_*.php (CSV/Excel)                            : diunduh lewat DownloadManager dengan sesi login kasir
    //  - alamat lain (WhatsApp, dll)                         : dibuka di aplikasi lain
    private FrameLayout root;
    private WebView printView;
    private LinearLayout popupContainer;
    private PermissionRequest pendingPermRequest;

    private static final String HOOK_JS = "(function(){if(window.__harinHook)return;window.__harinHook=true;"
        + "window.print=function(){HarinNative.printPage();};"
        + "function send(u,n){var x=new XMLHttpRequest();x.open('GET',u);x.responseType='blob';"
        + "x.onload=function(){var r=new FileReader();r.onloadend=function(){HarinNative.saveDataUrl(String(r.result),n||'berkas');};r.readAsDataURL(x.response);};"
        + "x.onerror=function(){HarinNative.toast('Gagal membaca berkas');};x.send();}"
        + "function grab(a){try{if(a&&a.tagName==='A'&&a.download&&a.href&&a.href.indexOf('blob:')===0){send(a.href,a.download);return true;}}catch(e){}return false;}"
        + "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(grab(this))return;return oc.apply(this,arguments);};"
        + "var od=EventTarget.prototype.dispatchEvent;EventTarget.prototype.dispatchEvent=function(e){if(e&&e.type==='click'&&grab(this))return true;return od.apply(this,arguments);};"
        + "})();";

    private int classifyUrl(String url) {
        if (url == null) return KIND_EXTERNAL;
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        String path = u.getPath() == null ? "" : u.getPath();
        if (!host.equals(HOST)) return KIND_EXTERNAL;
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.startsWith("export_")) return KIND_DOWNLOAD;
        if (file.equals("print_order.php") || file.equals("print_note.php")
            || file.equals("print_game_proof.php") || file.equals("print_member_card.php")) return KIND_AUTOPRINT;
        return KIND_VISIBLE; // kartu nama kedai, semua kartu member, laporan harian, gambar QRIS, dll
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void doSystemPrint(WebView v, String jobName) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            PrintDocumentAdapter adapter = v.createPrintDocumentAdapter(jobName);
            pm.print(jobName, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka dialog cetak", Toast.LENGTH_SHORT).show();
        }
    }

    /** Tampilkan jendela tambahan memenuhi layar dengan tombol Tutup di atas. */
    private void showPopupOverlay(WebView popup) {
        if (popupContainer != null || root == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#F97316"));
        bar.setPadding(24, 8, 16, 8);
        TextView title = new TextView(this);
        title.setText("Harinfood");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        Button close = new Button(this);
        close.setText("✕ Tutup");
        close.setOnClickListener(v -> closePopupOverlay());
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(popup, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        popupContainer = box;
    }

    private void closePopupOverlay() {
        if (popupContainer != null) {
            root.removeView(popupContainer);
            popupContainer = null;
        }
        if (printView != null) {
            try { printView.destroy(); } catch (Exception ignored) {}
            printView = null;
        }
    }

    /** Klien untuk jendela tambahan: menentukan apakah dicetak otomatis, ditampilkan, diunduh, atau dibuka di aplikasi lain. */
    private class PopupClient extends WebViewClient {
        private boolean routed = false;
        private boolean printed = false;
        private int kind = KIND_AUTOPRINT;

        private boolean route(WebView v, String url) {
            if (url == null || url.equals("about:blank") || routed) return false;
            routed = true;
            kind = classifyUrl(url);
            if (kind == KIND_EXTERNAL) { openExternal(url); return true; }
            if (kind == KIND_VISIBLE) showPopupOverlay(v);
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
            return route(v, request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            if (route(v, url)) v.stopLoading();
        }

        @Override
        public void onPageFinished(final WebView v, String url) {
            if (kind == KIND_VISIBLE) { v.evaluateJavascript(HOOK_JS, null); return; }
            if (printed || kind == KIND_DOWNLOAD || kind == KIND_EXTERNAL) return;
            // AUTOPRINT atau about:blank (jendela kosong yang isinya ditulis lewat JavaScript, mis. struk offline)
            printed = true;
            v.postDelayed(() -> doSystemPrint(v, "Harinfood"), 900);
        }
    }

    private WebView buildPopup() {
        final WebView p = new WebView(this);
        WebSettings ps = p.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setUseWideViewPort(true);
        ps.setLoadWithOverviewMode(true);
        ps.setBuiltInZoomControls(true);
        ps.setDisplayZoomControls(false);
        ps.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        p.addJavascriptInterface(new NativeBridge(p), "HarinNative");
        p.setDownloadListener(downloadListener(p));
        p.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView w) { closePopupOverlay(); }
        });
        p.setWebViewClient(new PopupClient());
        return p;
    }

    // ---- Unduhan ----
    private DownloadListener downloadListener(final WebView src) {
        return (url, userAgent, contentDisposition, mimetype, contentLength) ->
            handleDownload(src, url, userAgent, contentDisposition, mimetype);
    }

    private void handleDownload(final WebView src, String url, String ua, String cd, String mime) {
        final String name = URLUtil.guessFileName(url, cd, mime);
        if (url.startsWith("blob:")) {
            final String js = "(function(){var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url) + ");x.responseType='blob';"
                + "x.onload=function(){var r=new FileReader();r.onloadend=function(){HarinNative.saveDataUrl(String(r.result)," + JSONObject.quote(name) + ");};r.readAsDataURL(x.response);};"
                + "x.onerror=function(){HarinNative.toast('Gagal membaca berkas');};x.send();})();";
            runOnUiThread(() -> src.evaluateJavascript(js, null));
            return;
        }
        if (url.startsWith("data:")) { writeDownload(url, name); return; }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setMimeType(mime);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Harinfood/" + name);
            else r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Mengunduh " + name + " … (lihat notifikasi)", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            final String m = String.valueOf(e.getMessage());
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Gagal mengunduh: " + m, Toast.LENGTH_LONG).show());
        }
    }

    /** Simpan data base64 (hasil PDF dsb.) ke folder Download/Harinfood lalu buka. */
    private void writeDownload(String dataUrl, String name) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, comma);
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            final String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Harinfood");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("Tidak bisa membuat berkas di Download");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(bytes);
                os.close();
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                FileOutputStream fo = new FileOutputStream(new File(dir, safe));
                fo.write(bytes);
                fo.close();
            }
            final Uri fu = uri;
            final String fm = mime;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Tersimpan di Download/Harinfood: " + safe, Toast.LENGTH_LONG).show();
                if (fu != null) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(fu, fm);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) { /* tidak ada aplikasi pembuka PDF: berkas tetap tersimpan */ }
                }
            });
        } catch (final Exception e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Gagal menyimpan berkas: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    /** Jembatan JavaScript -> Android untuk halaman cetak/unduh (window.print & unduh blob). */
    private class NativeBridge {
        private final WebView owner;
        NativeBridge(WebView owner) { this.owner = owner; }

        @JavascriptInterface
        public void printPage() { runOnUiThread(() -> doSystemPrint(owner, "Harinfood")); }

        @JavascriptInterface
        public void saveDataUrl(final String dataUrl, final String name) {
            new Thread(() -> writeDownload(dataUrl, name == null || name.isEmpty() ? "berkas" : name)).start();
        }

        @JavascriptInterface
        public void toast(final String msg) { runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show()); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Layar tetap menyala selama aplikasi terbuka (supaya pesanan bisa tercetak otomatis)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), BT_PERM_REQ);

        webView = new WebView(this);
        root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings st = webView.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        st.setAllowFileAccess(false);

        webView.addJavascriptInterface(new PrintBridge(), "HarinPrint");
        webView.addJavascriptInterface(new GpsBridge(), "HarinGps");
        webView.addJavascriptInterface(new BellBridge(), "HarinBell");
        if (prefs().getBoolean("bell_on", false)) startBellService();
        webView.addJavascriptInterface(new NativeBridge(webView), "HarinNative");
        webView.setDownloadListener(downloadListener(webView));
        st.setGeolocationEnabled(true);
        st.setSupportMultipleWindows(true);
        st.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(HOOK_JS, null); // unduhan blob (mis. QRIS) & window.print di halaman utama
            }

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
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (popupContainer != null) closePopupOverlay();
                if (printView != null) { try { printView.destroy(); } catch (Exception ignored) {} printView = null; }
                final WebView popup = buildPopup();
                printView = popup;
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needCam = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) needCam = true;
                    if (!needCam) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingPermRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERM_REQ);
                    }
                });
            }

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

        if (prefs().getBoolean("bgservice", false)) startBgService();

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERM_REQ) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (pendingScan) {
                pendingScan = false;
                if (ok) doBleScan(); else notifyScan("[]", "Izin Bluetooth ditolak. Izinkan 'Perangkat sekitar' di pengaturan aplikasi.");
            }
            return;
        }
        if (requestCode == CAMERA_PERM_REQ) {
            boolean ok = false;
            for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            if (pendingPermRequest != null) {
                if (ok) pendingPermRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); else pendingPermRequest.deny();
                pendingPermRequest = null;
            }
            return;
        }
        if (requestCode == BT_PERM_REQ) {
            if (prefs().getBoolean("bgservice", false)) startBgService();
            return;
        }
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
        if (keyCode == KeyEvent.KEYCODE_BACK && popupContainer != null) {
            closePopupOverlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
