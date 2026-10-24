package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/** Waktu solat (JAKIM e-Solat): one slim glass strip under the clock row. */
public class PrayerStrip extends LinearLayout {
    private static final String ZONE = "KDH01"; // Kota Setar, Pokok Sena, Kubang Pasu
    private static final String API = "https://www.e-solat.gov.my/index.php"
            + "?r=esolatApi/takwimsolat&period=today&zone=" + ZONE;
    private static final String[] LABELS = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    private static final String[] HIJRI = {"Muharram", "Safar", "Rabiulawal", "Rabiulakhir",
            "Jamadilawal", "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal",
            "Zulkaedah", "Zulhijjah"};
    private static final int ACCENT = Color.parseColor("#5EEAD4");

    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextView hijriView;
    private final TextView[] labelViews = new TextView[6];
    private final TextView[] timeViews = new TextView[6];
    private final int[] mins = new int[6];
    private boolean loaded;
    private volatile boolean fetching;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                if (!today().equals(prefs().getString("day", ""))) fetch();
                highlight();
            } catch (Exception e) { /* never crash Home */ }
            h.postDelayed(this, 30000L);
        }
    };

    public PrayerStrip(Context c) { this(c, null); }

    public PrayerStrip(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setPadding(dp(20), dp(8), dp(20), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59000000);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        setBackground(bg);

        hijriView = text(13, 0xCCFFFFFF);
        hijriView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        addView(hijriView, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.7f));
        for (int i = 0; i < 6; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER);
            labelViews[i] = text(12, 0xB3FFFFFF);
            labelViews[i].setText(LABELS[i]);
            timeViews[i] = text(20, Color.WHITE);
            timeViews[i].setText("--:--");
            cell.addView(labelViews[i]);
            cell.addView(timeViews[i]);
            addView(cell, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        try {
            String cached = prefs().getString("times", null);
            if (cached != null) apply(cached, prefs().getString("hijri", ""));
        } catch (Exception e) { /* ignore bad cache */ }
    }

    private void apply(String times, String hijri) {
        try {
            String[] t = times.split(",");
            if (t.length != 6) return;
            int[] m = new int[6];
            for (int i = 0; i < 6; i++) {
                m[i] = Integer.parseInt(t[i].substring(0, 2)) * 60
                        + Integer.parseInt(t[i].substring(3, 5));
            }
            for (int i = 0; i < 6; i++) {
                mins[i] = m[i];
                timeViews[i].setText(t[i]);
            }
            loaded = true;
            try {
                String[] p = hijri.split("-");
                hijriView.setText(Integer.parseInt(p[2]) + " "
                        + HIJRI[Integer.parseInt(p[1]) - 1].toUpperCase(Locale.ROOT) + " " + p[0]);
            } catch (Exception e) {
                hijriView.setText("");
            }
            highlight();
        } catch (Exception e) { /* keep placeholders */ }
    }

    private void highlight() {
        if (!loaded) return;
        Calendar n = Calendar.getInstance();
        int now = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE);
        int next = 0; // after Isyak the next one is tomorrow's Subuh
        for (int i = 0; i < 6; i++) {
            if (mins[i] > now) { next = i; break; }
        }
        for (int i = 0; i < 6; i++) {
            boolean on = i == next;
            timeViews[i].setTextColor(on ? ACCENT : Color.WHITE);
            labelViews[i].setTextColor(on ? ACCENT : 0xB3FFFFFF);
        }
    }

    private void fetch() {
        if (fetching) return;
        fetching = true;
        final String day = today();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                try {
                    if (c.getResponseCode() != 200) {
                        throw new IllegalStateException("HTTP " + c.getResponseCode());
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                            if (out.size() > 65536) throw new IllegalStateException("too large");
                        }
                    }
                    JSONObject p = new JSONObject(out.toString("UTF-8"))
                            .getJSONArray("prayerTime").getJSONObject(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 6; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(p.getString(KEYS[i]), 0, 5);
                    }
                    final String times = sb.toString();
                    final String hijri = p.optString("hijri", "");
                    prefs().edit().putString("times", times).putString("hijri", hijri)
                            .putString("day", day).apply();
                    h.post(() -> apply(times, hijri));
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) { /* keep showing the last saved times */ }
            fetching = false;
        }, "prayer-fetch").start();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        h.removeCallbacks(tick);
        if (visibility == View.VISIBLE) tick.run();
    }

    @Override protected void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private static String today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-"
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("prayer_strip", Context.MODE_PRIVATE);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }
}
