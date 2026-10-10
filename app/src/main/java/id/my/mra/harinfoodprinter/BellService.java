package id.my.mra.harinfoodprinter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.LinkedList;
import java.util.UUID;

/**
 * Layanan latar depan yang menjaga sambungan BLE ke iTag (tombol bell).
 * Saat tombol iTag ditekan, iTag mengirim notifikasi -> BellPlayer memutar musik pembuka.
 */
public class BellService extends Service {
    public static volatile String status = "Belum berjalan";
    public static volatile boolean running = false;

    private static final String CHANNEL = "harin_bell";
    private static final int NOTIF_ID = 7102;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID SVC_FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private String addr = "";
    private boolean stopping = false;
    private long lastPress = 0;
    private final LinkedList<BluetoothGattCharacteristic> pendingEnable = new LinkedList<BluetoothGattCharacteristic>();

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        stopping = false;
        try {
            Notification n = buildNotification("Menyiapkan tombol bell…");
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            status = "Gagal menjalankan layanan: " + e.getMessage();
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacksAndMessages(null);
        closeGatt();
        addr = getSharedPreferences("harin", MODE_PRIVATE).getString("bell_addr", "");
        if (addr.isEmpty()) setStatus("Belum ada iTag yang dipilih");
        else connect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        closeGatt();
        status = "Tombol bell nonaktif";
        super.onDestroy();
    }

    // ---------------- notifikasi ----------------
    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Tombol bell iTag", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Tombol Bell iTag")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void setStatus(String text) {
        status = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    // ---------------- sambungan BLE ----------------
    private void scheduleReconnect(long ms) {
        handler.postDelayed(() -> { if (!stopping && running) connect(); }, ms);
    }

    private void closeGatt() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {}
        gatt = null;
        pendingEnable.clear();
    }

    private void connect() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) {
                setStatus("Bluetooth HP mati. Nyalakan Bluetooth.");
                scheduleReconnect(8000);
                return;
            }
            BluetoothDevice dev = ad.getRemoteDevice(addr);
            setStatus("Menyambung ke iTag…");
            if (Build.VERSION.SDK_INT >= 23) gatt = dev.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE);
            else gatt = dev.connectGatt(this, true, callback);
        } catch (SecurityException se) {
            setStatus("Izin Bluetooth belum diberikan");
        } catch (Exception e) {
            setStatus("Gagal menyambung: " + e.getMessage());
            scheduleReconnect(8000);
        }
    }

    private boolean ignored(BluetoothGattService s, BluetoothGattCharacteristic c) {
        String su = s.getUuid().toString().toLowerCase();
        String cu = c.getUuid().toString().toLowerCase();
        if (su.startsWith("00001800") || su.startsWith("00001801")) return true; // Generic Access / Generic Attribute
        if (cu.startsWith("00002a19")) return true;                              // tingkat baterai
        return false;
    }

    private void enableNext(BluetoothGatt g) {
        try {
            BluetoothGattCharacteristic c = pendingEnable.poll();
            if (c == null) {
                setStatus("Siap: tekan tombol iTag untuk membunyikan musik");
                return;
            }
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(d)) enableNext(g);
            } else {
                enableNext(g);
            }
        } catch (SecurityException se) {
            setStatus("Izin Bluetooth belum diberikan");
        } catch (Exception e) {
            setStatus("Gagal mengaktifkan tombol: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Tersambung, menyiapkan tombol…");
                try { g.discoverServices(); } catch (SecurityException ignoredEx) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("Terputus dari iTag, menyambung lagi…");
                handler.post(() -> {
                    closeGatt();
                    if (!stopping && running) scheduleReconnect(3000);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            pendingEnable.clear();
            BluetoothGattService ffe0 = g.getService(SVC_FFE0); // iTag umumnya memakai layanan 0xFFE0 (karakteristik 0xFFE1)
            for (BluetoothGattService s : g.getServices()) {
                if (ffe0 != null && s != ffe0) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && !ignored(s, c)) pendingEnable.add(c);
                }
            }
            if (pendingEnable.isEmpty()) {
                setStatus("Tersambung, tetapi perangkat ini tidak punya tombol yang bisa dibaca");
                return;
            }
            enableNext(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            enableNext(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPress < 1200) return; // abaikan pantulan tombol
            lastPress = now;
            handler.post(() -> {
                setStatus("Tombol ditekan → memutar musik");
                BellPlayer.play(getApplicationContext());
            });
        }
    };
}
