package id.my.mra.harinfoodprinter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.OutputStream;
import java.util.UUID;

/** Kirim data ESC/POS langsung ke printer Bluetooth (SPP) yang dipilih di aplikasi. Dipakai oleh layar & layanan latar belakang. */
public final class BtPrinter {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BtPrinter() {}

    /** Mengembalikan "OK" bila berhasil, selain itu pesan kesalahan. */
    public static synchronized String send(Context ctx, byte[] data) {
        String addr = ctx.getSharedPreferences("harin", Context.MODE_PRIVATE).getString("printer", "");
        if (addr.isEmpty()) return "Printer Bluetooth belum dipilih";
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return "Perangkat tidak punya Bluetooth";
        if (!ad.isEnabled()) return "Bluetooth HP mati. Nyalakan dulu";
        BluetoothSocket sock = null;
        try {
            BluetoothDevice dev = ad.getRemoteDevice(addr);
            try { ad.cancelDiscovery(); } catch (Exception ignored) {}
            try {
                sock = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
            } catch (Exception e1) {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                sock = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", new Class[]{int.class}).invoke(dev, 1);
                sock.connect();
            }
            OutputStream os = sock.getOutputStream();
            int off = 0;
            while (off < data.length) {
                int n = Math.min(1024, data.length - off);
                os.write(data, off, n);
                os.flush();
                off += n;
                Thread.sleep(12);
            }
            Thread.sleep(700); // beri waktu printer menyelesaikan cetak sebelum koneksi ditutup
            return "OK";
        } catch (SecurityException se) {
            return "Izin Bluetooth belum diberikan";
        } catch (Exception e) {
            String m = e.getMessage();
            return "Gagal tersambung ke printer" + (m == null ? "" : " (" + m + ")") + ". Pastikan printer menyala & berdekatan";
        } finally {
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        }
    }
}
