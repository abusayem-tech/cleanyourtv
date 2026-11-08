package com.gathin.tvhome;

import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daily Hikmah strip above the app grid: Quran, Hadith, 99 Names and Quote for Asia/Dhaka today,
 * rotating every thirty seconds. Fetches {@code /api/daily} in the background and caches the JSON.
 * A new day arrives on Home resume, on {@link android.content.Intent#ACTION_DATE_CHANGED}, and on
 * the minute tick when the Dhaka date no longer matches the loaded set.
 */
final class HikmahShelf {

    interface Host {
        void onLeaveToApps();
        void onLeaveToTopBar();
    }

    private static final String API = "https://hikmah-infotainment.vercel.app/api/daily";
    private static final String[] TYPES = {"quran", "hadith", "asma_ul_husna", "quote"};
    private static final long ROTATE_MS = 30_000L;
    private static final int DOT_DP = 7;
    private static final int DOT_GAP_DP = 8;

    private final View root;
    private final TextView kicker;
    private final TextView title;
    private final TextView body;
    private final LinearLayout dots;
    private final Handler handler;
    private final Host host;
    private final File cache;
    private final float density;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Card> cards = new ArrayList<>();
    private int index;
    private boolean running;
    private String loadedDate = "";

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (!running || cards.size() < 2 || root.hasFocus()) return;
            if (!dhakaToday().equals(loadedDate)) {
                io.execute(HikmahShelf.this::fetchToday);
                return;
            }
            index = (index + 1) % cards.size();
            bind(cards.get(index));
            handler.postDelayed(this, ROTATE_MS);
        }
    };

    HikmahShelf(View panel, Handler handler, Host host) {
        this.root = panel;
        this.handler = handler;
        this.host = host;
        kicker = panel.findViewById(R.id.hikmahKicker);
        title = panel.findViewById(R.id.hikmahTitle);
        body = panel.findViewById(R.id.hikmahBody);
        dots = panel.findViewById(R.id.hikmahDots);
        density = panel.getResources().getDisplayMetrics().density;
        cache = new File(panel.getContext().getFilesDir(), "hikmah-daily.json");
        panel.setOnKeyListener(this::onKey);
        panel.setOnFocusChangeListener((v, focused) -> {
            if (focused) handler.removeCallbacks(rotate);
            else scheduleRotate();
        });
        showCacheIfFresh();
    }

    View root() {
        return root;
    }

    boolean focus() {
        if (root.getVisibility() != View.VISIBLE || cards.isEmpty()) return false;
        return root.requestFocus();
    }

    /** Pull today's set if the Dhaka date has rolled since the last successful load. */
    void onMinute() {
        if (!running) return;
        if (!dhakaToday().equals(loadedDate)) io.execute(this::fetchToday);
    }

    void start() {
        running = true;
        if (!cards.isEmpty()) {
            bind(cards.get(index));
            scheduleRotate();
        }
        io.execute(this::fetchToday);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(rotate);
    }

    void shutdown() {
        stop();
        io.shutdownNow();
    }

    private void showCacheIfFresh() {
        String json = readCache();
        if (json.isEmpty()) return;
        List<Card> parsed = parse(json, dhakaToday());
        if (parsed.isEmpty()) return;
        cards.clear();
        cards.addAll(parsed);
        loadedDate = dhakaToday();
        index = 0;
        buildDots();
        root.setVisibility(View.VISIBLE);
        bind(cards.get(0));
    }

    private void fetchToday() {
        String today = dhakaToday();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "CleanYourTV-Hikmah/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return;
            }
            String json = readStream(conn);
            conn.disconnect();
            writeCache(json);
            List<Card> parsed = parse(json, today);
            handler.post(() -> apply(parsed, today));
        } catch (Exception ignored) {
            // Cached cards, if any, stay on screen.
        }
    }

    private void apply(List<Card> parsed, String date) {
        if (parsed.isEmpty()) {
            if (cards.isEmpty()) root.setVisibility(View.GONE);
            return;
        }
        boolean same = date.equals(loadedDate) && parsed.equals(cards);
        if (!date.equals(loadedDate)) index = 0;
        cards.clear();
        cards.addAll(parsed);
        loadedDate = date;
        if (index >= cards.size()) index = 0;
        buildDots();
        root.setVisibility(View.VISIBLE);
        bind(cards.get(index));
        if (!same) scheduleRotate();
    }

    private void scheduleRotate() {
        handler.removeCallbacks(rotate);
        if (running && cards.size() > 1 && !root.hasFocus()) handler.postDelayed(rotate, ROTATE_MS);
    }

    private boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                step(-1);
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step(1);
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                host.onLeaveToApps();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                host.onLeaveToTopBar();
                return true;
            default:
                return false;
        }
    }

    private void step(int delta) {
        if (cards.size() < 2) return;
        index = (index + delta + cards.size()) % cards.size();
        bind(cards.get(index));
        scheduleRotate();
    }

    private void bind(Card card) {
        kicker.setText(card.kicker);
        title.setText(card.title);
        body.setText(card.body);
        int n = dots.getChildCount();
        for (int i = 0; i < n; i++) {
            View dot = dots.getChildAt(i);
            boolean on = i == index;
            dot.setBackgroundColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int size = Math.round((on ? DOT_DP + 1 : DOT_DP) * density);
            lp.width = size;
            lp.height = size;
            dot.setLayoutParams(lp);
        }
    }

    private void buildDots() {
        dots.removeAllViews();
        int size = Math.round(DOT_DP * density);
        int gap = Math.round(DOT_GAP_DP * density);
        for (int i = 0; i < cards.size(); i++) {
            View dot = new View(root.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            dot.setBackgroundColor(0x4DFFFFFF);
            dots.addView(dot);
        }
    }

    static List<Card> parse(String json, String expectedDate) {
        List<Card> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            String date = root.optString("date", "");
            if (!expectedDate.isEmpty() && !date.isEmpty() && !expectedDate.equals(date)) {
                return out;
            }
            JSONArray items = root.optJSONArray("items");
            if (items == null) return out;
            for (String type : TYPES) {
                JSONObject item = firstOfType(items, type);
                if (item == null) continue;
                Card card = fromItem(item);
                if (card != null) out.add(card);
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    private static JSONObject firstOfType(JSONArray items, String type) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && type.equals(item.optString("type"))) return item;
        }
        return null;
    }

    private static Card fromItem(JSONObject item) {
        String type = item.optString("type");
        JSONObject ref = item.optJSONObject("reference");
        if (ref == null) ref = new JSONObject();
        String titleEn = item.optString("titleEn", "").trim();
        String bodyEn = item.optString("bodyEn", "").trim();
        String kicker;
        String title;
        String body;
        switch (type) {
            case "quran":
                kicker = "Quran Verse";
                title = titleEn;
                body = stripLeadingArabic(bodyEn);
                break;
            case "hadith":
                kicker = "Hadith";
                title = titleEn;
                body = stripLeadingArabic(bodyEn);
                break;
            case "asma_ul_husna":
                kicker = "99 Names";
                String translit = ref.optString("transliteration", "").trim();
                String meaning = ref.optString("meaningEn", "").trim();
                title = join(" — ", translit.isEmpty() ? titleEn : translit, meaning);
                body = firstLine(ref.optString("detailEn", bodyEn));
                break;
            case "quote":
                kicker = "Quote";
                String author = ref.optString("author", titleEn).trim();
                title = author.isEmpty() ? titleEn : author;
                body = bodyEn;
                break;
            default:
                return null;
        }
        if (title.isEmpty() && body.isEmpty()) return null;
        return new Card(kicker, title, oneLine(body));
    }

    private static String join(String sep, String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + sep + b;
    }

    private static String firstLine(String text) {
        int n = text.indexOf('\n');
        return n < 0 ? text.trim() : text.substring(0, n).trim();
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replaceAll(" +", " ").trim();
    }

    private static String stripLeadingArabic(String text) {
        String trimmed = text.trim();
        int split = trimmed.indexOf("\n\n");
        if (split < 0) return trimmed;
        String head = trimmed.substring(0, split).trim();
        if (!looksArabic(head)) return trimmed;
        return trimmed.substring(split + 2).trim();
    }

    private static boolean looksArabic(String text) {
        int letters = 0;
        int arabic = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c >= 0x0600 && c <= 0x06FF) arabic++;
            }
        }
        return letters > 0 && arabic * 2 >= letters;
    }

    static String dhakaToday() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Dhaka"));
        return fmt.format(new Date());
    }

    private String readCache() {
        if (!cache.isFile()) return "";
        try (FileInputStream in = new FileInputStream(cache)) {
            byte[] buf = new byte[(int) cache.length()];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void writeCache(String json) {
        try (FileOutputStream out = new FileOutputStream(cache)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readStream(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static final class Card {
        final String kicker;
        final String title;
        final String body;

        Card(String kicker, String title, String body) {
            this.kicker = kicker;
            this.title = title;
            this.body = body;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Card)) return false;
            Card c = (Card) o;
            return kicker.equals(c.kicker) && title.equals(c.title) && body.equals(c.body);
        }
    }
}
