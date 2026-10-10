package id.my.mra.harinfoodprinter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Layanan latar depan: tetap memeriksa pesanan pelanggan baru & mencetaknya ke printer Bluetooth
 * walaupun layar HP terkunci atau aplikasi tidak sedang dibuka.
 */
public class PrintService extends Service {
    private static final String BASE = "https://harinfood.mra.my.id/";
    private static final String CHANNEL = "harin_print";
    private static final int NOTIF_ID = 4101;

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private String lastText = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("harin", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("bgservice", false)) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = buildNotification("Menunggu pesanan…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (!running) {
            running = true;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harin:print");
                wakeLock.acquire();
            } catch (Exception ignored) {}
            worker = new Thread(this::loop, "harin-print");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------- notifikasi ----------
    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Cetak otomatis", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Harinfood Printer aktif")
         .setContentText(text)
         .setSmallIcon(android.R.drawable.ic_menu_info_details)
         .setContentIntent(pi)
         .setOngoing(true);
        return b.build();
    }

    private void status(String text) {
        if (text.equals(lastText)) return;
        lastText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    // ---------- pemeriksaan pesanan ----------
    private void loop() {
        while (running) {
            long wait = 4000;
            try {
                SharedPreferences prefs = getSharedPreferences("harin", Context.MODE_PRIVATE);
                if (!prefs.getBoolean("bgservice", false)) { stopSelf(); return; }
                String cookie = CookieManager.getInstance().getCookie(BASE);
                if (cookie == null || cookie.isEmpty()) {
                    status("Belum login kasir — buka aplikasi & login");
                    wait = 10000;
                } else {
                    JSONObject q = http("GET", BASE + "api.php?action=auto_print_queue", null, cookie);
                    if (q == null || !"success".equals(q.optString("status"))) {
                        status("Sesi kasir berakhir — buka aplikasi & login ulang");
                        wait = 10000;
                    } else {
                        JSONArray ids = q.getJSONObject("data").getJSONArray("ids");
                        if (ids.length() == 0) {
                            status("Siaga • " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                        }
                        for (int i = 0; i < ids.length() && running; i++) {
                            if (!processOrder(ids.getInt(i), cookie)) { wait = 10000; break; }
                        }
                    }
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                // jaringan putus / server sibuk: coba lagi nanti
                wait = 8000;
            }
            try { Thread.sleep(wait); } catch (InterruptedException ie) { return; }
        }
    }

    private static Bitmap logoBmp = null;

    /** Unduh gambar header struk (receipt_header.png) sekali saja (dipakai bila pengaturan "Logo & Header di Struk" aktif). */
    private Bitmap logo(String cookie) {
        if (logoBmp != null) return logoBmp;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "receipt_header.png").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            logoBmp = b;
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mengembalikan false bila pencetakan gagal (supaya jeda lebih lama sebelum mencoba lagi). */
    private boolean processOrder(int id, String cookie) throws Exception {
        JSONObject res = http("POST", BASE + "api.php?action=claim_auto_print", "id=" + id, cookie);
        if (res == null || !"success".equals(res.optString("status"))) return true;
        JSONObject d = res.optJSONObject("data");
        if (d == null || d.optBoolean("skip", false)) return true; // sudah dicetak perangkat lain
        String invoice = d.optString("invoice");

        byte[] bytes;
        try {
            JSONObject rcp = d.getJSONObject("receipt");
            Bitmap lg = rcp.optBoolean("logo", false) ? logo(cookie) : null;
            bytes = ReceiptRenderer.render(rcp, lg);
        } catch (Exception e) {
            bytes = Base64.decode(d.getString("escpos"), Base64.DEFAULT); // cadangan: struk teks
        }
        String result = BtPrinter.send(this, bytes);
        if ("OK".equals(result)) {
            status("Tercetak #" + invoice + " • " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            Thread.sleep(800);
            return true;
        }
        // gagal: lepaskan klaim supaya dicoba lagi
        try { http("POST", BASE + "api.php?action=release_auto_print", "id=" + id, cookie); } catch (Exception ignored) {}
        status("Gagal cetak #" + invoice + ": " + result);
        return false;
    }

    private JSONObject http(String method, String url, String body, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            try { return new JSONObject(bo.toString("UTF-8")); } catch (Exception e) { return null; }
        } finally {
            c.disconnect();
        }
    }
}
