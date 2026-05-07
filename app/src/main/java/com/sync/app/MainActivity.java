package com.sync.app;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SYNC";
    private WebView webView;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen edge-to-edge
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);

        // Enable hardware acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(Color.parseColor("#08080D"));

        // JS Bridge
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {}
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Allow all requests to pass through (YouTube iframe needs network)
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Only allow navigation within our app
                if (url.startsWith("file://") || url.contains("youtube.com") ||
                    url.contains("ytimg.com") || url.contains("fonts.googleapis.com")) {
                    return false;
                }
                return false;
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    // ══════════════════════════════════════════════════
    //  JavaScript Bridge — Android ↔ JS
    // ══════════════════════════════════════════════════
    public class AndroidBridge {

        @JavascriptInterface
        public void postMessage(String json) {
            try {
                JSONObject msg = new JSONObject(json);
                String type = msg.optString("type");
                switch (type) {
                    case "search":
                        executor.submit(() -> doSearch(msg));
                        break;
                    case "suggest":
                        executor.submit(() -> doSuggest(msg));
                        break;
                    case "fetchLyrics":
                        executor.submit(() -> doFetchLyrics(msg));
                        break;
                    case "orientation":
                        String orient = msg.optString("value", "sensor");
                        runOnUiThread(() -> setOrientation(orient));
                        break;
                    default:
                        break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "postMessage parse error", e);
            }
        }
    }

    private void sendToJs(JSONObject payload) {
        String json = payload.toString();
        String b64 = Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        runOnUiThread(() ->
            webView.evaluateJavascript(
                "window.__sync && window.__sync(atob('" + b64 + "'))", null
            )
        );
    }

    // ══════════════════════════════════════════════════
    //  Orientation control (landscape = fullscreen NP)
    // ══════════════════════════════════════════════════
    private void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    // ══════════════════════════════════════════════════
    //  YouTube Search via InnerTube API
    // ══════════════════════════════════════════════════
    private void doSearch(JSONObject msg) {
        String query = msg.optString("query");
        String id = msg.optString("id", "0");
        try {
            String KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
            String URL = "https://www.youtube.com/youtubei/v1/search?key=" + KEY + "&prettyPrint=false";
            JSONObject body = new JSONObject();
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240101.00.00");
            client.put("hl", "ko");
            client.put("gl", "KR");
            context.put("client", client);
            body.put("context", context);
            body.put("query", query);
            body.put("params", "EgIQAQ%3D%3D");

            Request req = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) throw new IOException("HTTP " + resp.code());
                String json = resp.body().string();
                JSONArray tracks = parseSearchResults(json);
                JSONObject result = new JSONObject();
                result.put("type", "searchResult");
                result.put("id", id);
                result.put("success", true);
                result.put("tracks", tracks);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "searchResult");
                err.put("id", id);
                err.put("success", false);
                err.put("error", e.getMessage());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private JSONArray parseSearchResults(String json) throws JSONException {
        JSONArray list = new JSONArray();
        JSONObject doc = new JSONObject(json);
        JSONArray sections = doc
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents");

        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer").getJSONArray("contents");
            for (int k = 0; k < items.length() && list.length() < 20; k++) {
                JSONObject item = items.getJSONObject(k);
                if (!item.has("videoRenderer")) continue;
                JSONObject vr = item.getJSONObject("videoRenderer");
                if (!vr.has("videoId")) continue;
                String vid = vr.getString("videoId");
                if (vid.isEmpty()) continue;

                String title = "";
                if (vr.has("title") && vr.getJSONObject("title").has("runs")) {
                    title = vr.getJSONObject("title").getJSONArray("runs").getJSONObject(0).optString("text", "");
                }
                String ch = "";
                if (vr.has("ownerText") && vr.getJSONObject("ownerText").has("runs")) {
                    ch = vr.getJSONObject("ownerText").getJSONArray("runs").getJSONObject(0).optString("text", "");
                } else if (vr.has("shortBylineText") && vr.getJSONObject("shortBylineText").has("runs")) {
                    ch = vr.getJSONObject("shortBylineText").getJSONArray("runs").getJSONObject(0).optString("text", "");
                }
                String durStr = "";
                if (vr.has("lengthText")) durStr = vr.getJSONObject("lengthText").optString("simpleText", "");
                int dur = parseDur(durStr);

                if (!isMusicVideo(title, ch, vr)) continue;

                JSONObject t = new JSONObject();
                t.put("id", vid);
                t.put("title", title);
                t.put("channel", ch);
                t.put("dur", dur);
                t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private boolean isMusicVideo(String title, String channel, JSONObject vr) {
        String tl = title.toLowerCase();
        String cl = channel.toLowerCase();
        if (cl.contains("vevo") || cl.contains("topic") || cl.contains("music") ||
                cl.contains("records") || cl.contains("entertainment") ||
                cl.contains("sound") || cl.contains("audio") || cl.contains("official")) return true;
        if (tl.contains("official") || tl.contains("mv") || tl.contains("m/v") ||
                tl.contains("music video") || tl.contains("audio") || tl.contains("lyrics") ||
                tl.contains("lyric") || tl.contains("visualizer") || tl.contains("live") ||
                tl.contains("performance") || tl.contains("concert")) return true;
        return false;
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    // ══════════════════════════════════════════════════
    //  Autocomplete
    // ══════════════════════════════════════════════════
    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" +
                    java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = resp.body().string();
                // Response is a JSON array: [query, [suggestions...]]
                // Strip JSONP if present
                if (json.startsWith("window.")) {
                    json = json.replaceFirst("^[^(]+\\(", "").replaceFirst("\\)\\s*$", "");
                }
                JSONArray arr = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String s = (o instanceof JSONArray) ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!s.isEmpty()) sugs.put(s);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult");
                result.put("id", id);
                result.put("success", true);
                result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult");
                err.put("id", id);
                err.put("success", false);
                err.put("suggestions", new JSONArray());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    // ══════════════════════════════════════════════════
    //  Lyrics — lrclib → NetEase fallback
    // ══════════════════════════════════════════════════
    private void doFetchLyrics(JSONObject msg) {
        String rawTitle = msg.optString("title");
        String channel = msg.optString("channel");
        double dur = msg.optDouble("duration", 0);
        String id = msg.optString("id", "0");

        JSONArray lines = tryLrclib(rawTitle, channel, dur);
        if (lines == null) lines = tryNetEase(rawTitle, channel, dur);

        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (lines != null) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    private JSONArray tryLrclib(String rawTitle, String channel, double ytDuration) {
        try {
            String cleanTitle = cleanTitle(rawTitle);
            String cleanArtist = cleanArtist(channel);

            JSONArray results = searchLrclib(cleanTitle + " " + cleanArtist);
            if (!hasSyncedResults(results)) results = searchLrclib(cleanTitle);
            if (!hasSyncedResults(results)) {
                String stripped = stripBrackets(cleanTitle);
                if (!stripped.equals(cleanTitle)) results = searchLrclib(stripped);
            }
            if (!hasSyncedResults(results) && !cleanArtist.isEmpty())
                results = searchLrclib(cleanArtist + " " + cleanTitle);

            if (results == null || results.length() == 0) return null;

            List<CandidateLrc> candidates = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                if (!item.has("syncedLyrics")) continue;
                String lrcText = item.optString("syncedLyrics", "");
                if (lrcText.isEmpty()) continue;

                double lrcDur = getLrcLastTimestamp(lrcText);
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0);

                double score = 0;
                if (ytDuration > 0 && lrcDur > 0) {
                    double diff = Math.abs(lrcDur - ytDuration);
                    if (diff <= 3) score += 50;
                    else if (diff <= 10) score += 35;
                    else if (diff <= 30) score += 15;
                    else if (diff <= 60) score += 5;
                    else score -= 25;
                }
                score += titleSimilarity(cleanTitle, item.optString("trackName", "")) * 30;
                score += titleSimilarity(cleanArtist, item.optString("artistName", "")) * 20;
                candidates.add(new CandidateLrc(lrcText, score));
            }
            if (candidates.isEmpty()) return null;
            candidates.sort((a, b) -> Double.compare(b.score, a.score));
            return parseLrc(candidates.get(0).lrc);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray searchLrclib(String query) throws IOException, JSONException {
        String url = "https://lrclib.net/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
        Request req = new Request.Builder().url(url)
                .addHeader("User-Agent", "SYNCApp/1.0")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.body() == null) return new JSONArray();
            String json = resp.body().string();
            Object parsed = new org.json.JSONTokener(json).nextValue();
            return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
        }
    }

    private boolean hasSyncedResults(JSONArray arr) throws JSONException {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            String sl = arr.getJSONObject(i).optString("syncedLyrics", "");
            if (!sl.isEmpty()) return true;
        }
        return false;
    }

    private JSONArray tryNetEase(String rawTitle, String channel, double ytDuration) {
        try {
            String cleanTitle = cleanTitle(rawTitle);
            String cleanArtist = cleanArtist(channel);
            String[] queries = {cleanTitle + " " + cleanArtist, cleanTitle, stripBrackets(cleanTitle)};
            List<CandidateNE> candidates = null;
            for (String q : queries) {
                candidates = searchNetEase(q, cleanTitle, cleanArtist, ytDuration);
                if (candidates != null && !candidates.isEmpty()) break;
            }
            if (candidates == null || candidates.isEmpty()) return null;
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                if (candidates.get(i).score < 40) break;
                JSONArray lines = fetchNetEaseLrc(candidates.get(i).id);
                if (lines != null && lines.length() > 0) return lines;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<CandidateNE> searchNetEase(String query, String cleanTitle, String cleanArtist, double ytDuration) {
        try {
            String url = "https://music.163.com/api/search/get?s=" +
                    java.net.URLEncoder.encode(query, "UTF-8") + "&type=1&limit=10";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie", "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(resp.body().string());
                if (!doc.has("result")) return null;
                JSONArray songs = doc.getJSONObject("result").optJSONArray("songs");
                if (songs == null) return null;
                List<CandidateNE> list = new ArrayList<>();
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    long songId = song.optLong("id");
                    String songTitle = song.optString("name", "");
                    String artists = "";
                    JSONArray art = song.optJSONArray("artists");
                    if (art != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < art.length(); j++) sb.append(art.getJSONObject(j).optString("name", "")).append(" ");
                        artists = sb.toString().trim();
                    }
                    double duration = song.optDouble("duration", 0) / 1000.0;
                    double score = titleSimilarity(cleanTitle, songTitle) * 40 + titleSimilarity(cleanArtist, artists) * 25;
                    if (ytDuration > 0 && duration > 0) {
                        double diff = Math.abs(duration - ytDuration);
                        if (diff <= 3) score += 30;
                        else if (diff <= 10) score += 18;
                        else if (diff <= 30) score += 8;
                        else score -= 15;
                    }
                    list.add(new CandidateNE(songId, score));
                }
                list.sort((a, b) -> Double.compare(b.score, a.score));
                return list;
            }
        } catch (Exception e) { return null; }
    }

    private JSONArray fetchNetEaseLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie", "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(resp.body().string());
                String lrcText = null;
                if (doc.has("klyric")) lrcText = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrcText == null || lrcText.isEmpty()) {
                    if (doc.has("lrc")) lrcText = doc.getJSONObject("lrc").optString("lyric", null);
                }
                if (lrcText == null || lrcText.isEmpty()) return null;
                return parseLrc(lrcText);
            }
        } catch (Exception e) { return null; }
    }

    // ── Helpers ───────────────────────────────────────
    private double titleSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = a.toLowerCase(); b = b.toLowerCase();
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.85;
        String[] wa = a.split("[^\\w]+"); String[] wb = b.split("[^\\w]+");
        java.util.Set<String> sa = new java.util.HashSet<>(), sb = new java.util.HashSet<>();
        for (String w : wa) if (w.length() > 1) sa.add(w);
        for (String w : wb) if (w.length() > 1) sb.add(w);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        long inter = sa.stream().filter(sb::contains).count();
        return (double) inter / Math.max(sa.size(), sb.size());
    }

    private String stripBrackets(String t) {
        t = t.replaceAll("\\([^)]*\\)", "").trim();
        t = t.replaceAll("\\[[^\\]]*\\]", "").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        Pattern p = Pattern.compile("^\\[(\\d+):(\\d+)\\.(\\d+)\\]");
        for (String line : lrc.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;
            String ms = (m.group(3) + "000").substring(0, 3);
            double t = Integer.parseInt(m.group(1)) * 60.0 + Integer.parseInt(m.group(2)) + Integer.parseInt(ms) / 1000.0;
            if (t > last) last = t;
        }
        return last;
    }

    private static final Pattern CREDIT_RX = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手)\\s*[：:].{0,80}$");
    private static final Pattern LRC_RX = Pattern.compile("^\\[(\\d+):(\\d+)\\.(\\d+)\\](.*)");

    private JSONArray parseLrc(String lrc) throws JSONException {
        List<double[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            Matcher m = LRC_RX.matcher(line.trim());
            if (!m.matches()) continue;
            String text = m.group(4).trim();
            if (text.isEmpty() || CREDIT_RX.matcher(text).matches()) continue;
            String ms = (m.group(3) + "000").substring(0, 3);
            double t = Integer.parseInt(m.group(1)) * 60.0 + Integer.parseInt(m.group(2)) + Integer.parseInt(ms) / 1000.0;
            times.add(new double[]{t});
            texts.add(text);
        }
        // Sort by time
        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(times.get(a)[0], times.get(b)[0]));
        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int ci = idx[i];
            double start = times.get(ci)[0];
            double end = (i + 1 < idx.length) ? times.get(idx[i + 1])[0] : start + 5.0;
            JSONObject obj = new JSONObject();
            obj.put("start", start);
            obj.put("end", end);
            obj.put("text", texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    private String cleanTitle(String t) {
        String TAG_RX = "(?i)official\\s*(?:music\\s*)?(?:video|audio|mv|lyric|visualizer)?" +
                "|m/?v|music\\s*video|audio(?:\\s*only)?" +
                "|lyrics?\\s*(?:video|ver(?:sion)?)?|lyric\\s*video|visualizer" +
                "|live(?:\\s+(?:performance|version|session))?" +
                "|performance(?:\\s+video)?|hd|4k|1080p|720p" +
                "|remaster(?:ed)?(?:\\s+version)?|re-?upload" +
                "|eng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?" +
                "|kor(?:ean)?\\s*(?:ver\\.?|version)?|jp(?:n)?\\s*(?:ver\\.?|version)?" +
                "|공식|뮤직\\s*비디오|음원|영상|티저|안무" +
                "|feat\\.?\\s*.+?|ft\\.?\\s*.+?";
        t = t.replaceAll("\\(\\s*(?:" + TAG_RX + ")[^)]*\\)", "").trim();
        t = t.replaceAll("\\[\\s*(?:" + TAG_RX + ")[^\\]]*\\]", "").trim();
        t = t.replaceAll("\\s*[-|]\\s*(?:" + TAG_RX + ")\\s*$", "").trim();
        t = t.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.+$", "").trim();
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll("(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$", "").trim();
        return c.replaceAll("\\s{2,}", " ").trim();
    }

    private static class CandidateLrc {
        String lrc; double score;
        CandidateLrc(String l, double s) { lrc = l; score = s; }
    }
    private static class CandidateNE {
        long id; double score;
        CandidateNE(long i, double s) { id = i; score = s; }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        webView.destroy();
    }

    @Override
    public void onBackPressed() {
        // Let JS handle back (e.g. close NP panel)
        webView.evaluateJavascript("window.__onAndroidBack && window.__onAndroidBack()", null);
    }
}
