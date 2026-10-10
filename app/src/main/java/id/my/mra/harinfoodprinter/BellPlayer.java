package id.my.mra.harinfoodprinter;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Memutar musik pembuka (musik yang sama dengan saat aplikasi dibuka) ketika tombol iTag ditekan.
 * Berkas MP3 diunduh sekali dari URL musik pembuka lalu disimpan di cache; kalau belum ada, dipakai nada notifikasi bawaan HP.
 * Menekan tombol lagi saat musik masih berbunyi = menghentikan musik.
 */
public class BellPlayer {
    private static final Object LOCK = new Object();
    private static MediaPlayer player;

    public static void play(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> playBlocking(app)).start();
    }

    public static void stop() {
        synchronized (LOCK) { releaseLocked(); }
    }

    public static boolean isPlaying() {
        synchronized (LOCK) { return player != null; }
    }

    /** Hapus cache musik (dipanggil saat URL musik pembuka berganti). */
    public static void invalidate(Context ctx) {
        try { musicFile(ctx).delete(); } catch (Exception ignored) {}
    }

    private static File musicFile(Context c) { return new File(c.getCacheDir(), "bell_music.mp3"); }

    private static void releaseLocked() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private static boolean ensureDownloaded(Context c) {
        File f = musicFile(c);
        if (f.exists() && f.length() > 1000) return true;
        String url = c.getSharedPreferences("harin", Context.MODE_PRIVATE).getString("bell_music_url", "");
        if (url == null || url.isEmpty()) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            if (conn.getResponseCode() != 200) return false;
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return f.length() > 1000;
        } catch (Exception e) {
            try { f.delete(); } catch (Exception ignored) {}
            return false;
        }
    }

    private static void playBlocking(Context c) {
        synchronized (LOCK) {
            if (player != null) { releaseLocked(); return; } // ditekan lagi saat berbunyi -> berhenti
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
                mp.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK); // tetap berbunyi walau layar mati
                if (ensureDownloaded(c)) mp.setDataSource(musicFile(c).getAbsolutePath());
                else mp.setDataSource(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                mp.setOnCompletionListener(m -> { synchronized (LOCK) { if (player == m) releaseLocked(); } });
                mp.setOnErrorListener((m, what, extra) -> { synchronized (LOCK) { if (player == m) releaseLocked(); } return true; });
                mp.prepare();
                mp.start();
                player = mp;
            } catch (Exception e) {
                releaseLocked();
            }
        }
    }
}
