package id.my.mra.harinfoodprinter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Menggambar struk 58mm (384 titik) sama seperti tampilan di halaman web, lalu mengubahnya ke raster ESC/POS. */
public final class ReceiptRenderer {
    private static final int W = 384;
    private static final float S = 2.1f;
    private static final int PAD = 8;
    private static final int CW = W - PAD * 2;

    private interface Op { void draw(Canvas c); }

    private final List<Op> ops = new ArrayList<>();
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float y = 6;

    public static byte[] render(JSONObject r) throws Exception {
        return new ReceiptRenderer().build(r, null);
    }

    /** logo = gambar logo kedai (boleh null). Dipakai hanya kalau pengaturan "Logo & Header di Struk" aktif. */
    public static byte[] render(JSONObject r, Bitmap logo) throws Exception {
        return new ReceiptRenderer().build(r, logo);
    }

    /** Header struk berupa gambar siap cetak (logo hitam pekat + HARINFOOD + POS + tagline), lebar 368 titik. */
    private void logoHeader(JSONObject r, Bitmap logo) {
        final float y0 = y;
        int cwi = (int) CW;
        int hw = Math.min(cwi, logo.getWidth());
        int hh = Math.round(logo.getHeight() * (float) hw / logo.getWidth());
        final Bitmap scaled = (hw == logo.getWidth()) ? logo : Bitmap.createScaledBitmap(logo, hw, hh, false);
        final float hx = PAD + Math.round((cwi - hw) / 2f);
        ops.add(c -> c.drawBitmap(scaled, hx, y0, null));
        y = y0 + hh + 6;
    }

    private void setFont(Paint p, float px, int wt, boolean italic) {
        int w = Math.min(800, Math.max(500, wt + 100));
        Typeface base;
        if (w >= 700) base = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        else base = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (italic) base = Typeface.create(base, base.isBold() ? Typeface.BOLD_ITALIC : Typeface.ITALIC);
        p.setTypeface(base);
        p.setTextSize(px * S);
    }

    private Paint paint(float px, int wt, boolean italic, int color, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        setFont(p, px, wt, italic);
        p.setColor(color);
        p.setTextAlign(align);
        return p;
    }

    private int lh(float px) { return Math.round(px * S * 1.1f); }

    private List<String> wrap(Paint p, String text, float maxW) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w0 : text.trim().split("\\s+")) {
            if (w0.isEmpty()) continue;
            String w = w0;
            String t = line.isEmpty() ? w : line + " " + w;
            if (p.measureText(t) <= maxW) { line = t; continue; }
            if (!line.isEmpty()) out.add(line);
            while (p.measureText(w) > maxW && w.length() > 1) {
                int k = w.length();
                while (k > 1 && p.measureText(w.substring(0, k)) > maxW) k--;
                out.add(w.substring(0, k));
                w = w.substring(k);
            }
            line = w;
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    private void text(Canvas c, Paint p, String t, float x, float top) {
        c.drawText(t, x, top - p.ascent(), p);
    }

    // ---------- elemen struk ----------
    private void centerText(String text, float px, int wt, int color) {
        setFont(measure, px, wt, false);
        for (String t : wrap(measure, text, CW)) {
            final float yy = y;
            final String line = t;
            final Paint p = paint(px, wt, false, color, Paint.Align.CENTER);
            ops.add(c -> text(c, p, line, W / 2f, yy));
            y += lh(px);
        }
    }

    private void blockText(String text, float px, int wt, boolean italic, boolean center) {
        setFont(measure, px, wt, italic);
        // Tiap baris (Enter) dipertahankan sebagai baris baru; baris panjang tetap dibungkus otomatis
        List<String> lines = new ArrayList<String>();
        for (String par : text.split("\\r?\\n")) lines.addAll(wrap(measure, par, CW));
        for (String t : lines) {
            final float yy = y;
            final String line = t;
            final Paint p = paint(px, wt, italic, Color.BLACK, center ? Paint.Align.CENTER : Paint.Align.LEFT);
            ops.add(c -> text(c, p, line, center ? W / 2f : PAD, yy));
            y += lh(px);
        }
    }

    private void dashed() {
        final float yy = y + Math.round(4 * S);
        final Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, Math.round(1.5f * S)));
        p.setPathEffect(new DashPathEffect(new float[]{Math.round(4 * S), Math.round(3 * S)}, 0));
        ops.add(c -> c.drawLine(PAD, yy, W - PAD, yy, p));
        y += Math.round(9 * S);
    }

    private void rowLR(String left, String right, float px, int wtL, int wtR, int gapAfter, boolean fitOneLine) {
        if (fitOneLine) {
            float p = px;
            while (p > px * 0.6f) {
                setFont(measure, p, wtL, false); float a = measure.measureText(left);
                setFont(measure, p, wtR, false); float b = measure.measureText(right);
                if (a + b + 6 <= CW) break;
                p -= 0.25f;
            }
            px = p;
        }
        setFont(measure, px, wtL, false);
        float lw = measure.measureText(left);
        setFont(measure, px, wtR, false);
        final List<String> rl = fitOneLine ? new ArrayList<String>() : wrap(measure, right, CW - lw - 8 * S);
        if (fitOneLine) rl.add(right);
        final float yy = y;
        final Paint pl = paint(px, wtL, false, Color.BLACK, Paint.Align.LEFT);
        final Paint pr = paint(px, wtR, false, Color.BLACK, Paint.Align.RIGHT);
        final int lineH = lh(px);
        final String leftText = left;
        ops.add(c -> {
            text(c, pl, leftText, PAD, yy);
            for (int i = 0; i < rl.size(); i++) text(c, pr, rl.get(i), W - PAD, yy + i * lineH);
        });
        y += Math.max(1, rl.size()) * lineH + gapAfter;
    }

    // ---------- susun & ubah ke ESC/POS ----------
    private byte[] build(JSONObject r, Bitmap logo) throws Exception {
        if (r.optBoolean("logo", false) && logo != null) logoHeader(r, logo);
        else centerText(r.optString("store"), 19, 800, Color.BLACK);

        // alamat toko: satu baris, huruf mengecil sampai muat
        String addr = r.optString("address").replaceAll("\\s+", " ").trim();
        float ap = 11;
        while (ap > 6) {
            setFont(measure, ap, 400, false);
            if (measure.measureText(addr) <= CW) break;
            ap -= 0.25f;
        }
        final float addrPx = ap;
        final String addrText = addr;
        final float ay = y;
        final Paint addrPaint = paint(addrPx, 400, false, Color.parseColor("#222222"), Paint.Align.CENTER);
        ops.add(c -> text(c, addrPaint, addrText, W / 2f, ay));
        y += lh(addrPx);

        centerText(r.optString("wa"), 11, 400, Color.parseColor("#222222"));
        dashed();

        JSONArray info = r.optJSONArray("info");
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONArray p = info.getJSONArray(i);
            rowLR(p.getString(0), p.getString(1), 12.5f, 600, 700, 1, "Invoice".equals(p.getString(0)));
        }
        dashed();

        JSONArray items = r.optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject x = items.getJSONObject(i);
            blockText(x.optString("nama"), 13.5f, 700, false, false);
            rowLR(x.optString("qty"), x.optString("subtotal"), 12.5f, 400, 700, 3, false);
        }
        dashed();
        rowLR("TOTAL", r.optString("total"), 16, 800, 800, 0, false);
        dashed();

        JSONArray pay = r.optJSONArray("pay");
        if (pay != null) for (int i = 0; i < pay.length(); i++) {
            JSONArray p = pay.getJSONArray(i);
            rowLR(p.getString(0), p.getString(1), 12.5f, 600, 600, 0, false);
        }

        String note = r.optString("note");
        if (!note.isEmpty()) {
            dashed();
            blockText("Catatan:", 12, 700, false, false);
            blockText("\"" + note + "\"", 12, 400, true, false);
        }
        String reward = r.optString("reward");
        if (!reward.isEmpty()) {
            dashed();
            blockText("\uD83C\uDF89 SELAMAT! HADIAH GAME \uD83C\uDF81", 12.5f, 800, false, true);
            blockText(reward, 12.5f, 700, false, true);
        } else if (r.optBoolean("played", false)) {
            dashed();
            blockText("\uD83C\uDFAE TERIMA KASIH SUDAH BERMAIN!", 12.5f, 800, false, true);
            blockText("Belum beruntung kali ini, yuk main lagi & jadilah juara! \uD83C\uDFC6", 11.5f, 700, false, true);
        }
        dashed();
        centerText("\uD83C\uDF10 " + r.optString("website"), 11, 600, Color.parseColor("#333333"));
        y += Math.round(5 * S);
        centerText("Terima Kasih atas Kunjungan Anda! \uD83D\uDE4F", 12.5f, 700, Color.BLACK);

        int h = (int) y + 4;
        Bitmap bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        for (Op op : ops) op.draw(canvas);
        int[] px = new int[W * h];
        bmp.getPixels(px, 0, W, 0, 0, W, h);
        bmp.recycle();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B); out.write(0x40); // reset printer
        int bpr = W / 8;
        for (int y0 = 0; y0 < h; y0 += 128) {
            int rows = Math.min(128, h - y0);
            out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0x00);
            out.write(bpr & 255); out.write(bpr >> 8);
            out.write(rows & 255); out.write(rows >> 8);
            for (int yy = y0; yy < y0 + rows; yy++) {
                for (int bx = 0; bx < bpr; bx++) {
                    int b = 0;
                    for (int k = 0; k < 8; k++) {
                        int c = px[yy * W + bx * 8 + k];
                        double lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
                        if (lum < 165) b |= (0x80 >> k);
                    }
                    out.write(b);
                }
            }
        }
        out.write(0x1B); out.write(0x4A); out.write(88); // dorong kertas ~11 mm agar baris terakhir tidak terpotong
        return out.toByteArray();
    }
}
