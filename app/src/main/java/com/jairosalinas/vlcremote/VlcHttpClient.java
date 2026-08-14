package com.jairosalinas.vlcremote;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class VlcHttpClient {
    public static final class Status {
        public final String state;
        public final String title;
        public final int timeSeconds;
        public final int lengthSeconds;
        public final int volume;
        public final double position;
        public final int currentPlaylistId;

        Status(String state, String title, int timeSeconds, int lengthSeconds,
               int volume, double position, int currentPlaylistId) {
            this.state = state;
            this.title = title;
            this.timeSeconds = timeSeconds;
            this.lengthSeconds = lengthSeconds;
            this.volume = volume;
            this.position = position;
            this.currentPlaylistId = currentPlaylistId;
        }
    }

    public static final class PlaylistItem {
        public final int id;
        public final String name;
        public final boolean current;

        PlaylistItem(int id, String name, boolean current) {
            this.id = id;
            this.name = name;
            this.current = current;
        }

        @Override public String toString() { return name; }
    }

    public static final class BrowserEntry {
        public final boolean directory;
        public final String name;
        public final String uri;
        public final String path;

        BrowserEntry(boolean directory, String name, String uri, String path) {
            this.directory = directory;
            this.name = name;
            this.uri = uri;
            this.path = path;
        }
    }

    private final String baseUrl;
    private final String authorization;

    public VlcHttpClient(String host, int port, String password) {
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7);
        if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8);
        while (cleanHost.endsWith("/")) cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
        this.baseUrl = "http://" + cleanHost + ":" + port;
        String rawAuth = ":" + (password == null ? "" : password);
        this.authorization = "Basic " + Base64.encodeToString(
                rawAuth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public Status getStatus() throws IOException, JSONException {
        JSONObject root = new JSONObject(get("/requests/status.json"));
        String state = root.optString("state", "unknown");
        int time = root.optInt("time", 0);
        int length = root.optInt("length", 0);
        int volume = root.optInt("volume", 0);
        double position = root.optDouble("position", 0.0);
        int currentPlaylistId = root.optInt("currentplid", -1);

        String title = "Nada reproduciéndose";
        JSONObject information = root.optJSONObject("information");
        if (information != null) {
            JSONObject category = information.optJSONObject("category");
            if (category != null) {
                JSONObject meta = category.optJSONObject("meta");
                if (meta != null) {
                    title = firstNonBlank(
                            meta.optString("title", ""),
                            meta.optString("filename", ""),
                            meta.optString("now_playing", ""),
                            title);
                }
            }
        }
        return new Status(state, title, time, length, volume, position, currentPlaylistId);
    }

    public List<PlaylistItem> getPlaylist() throws IOException, JSONException {
        JSONObject root = new JSONObject(get("/requests/playlist.json"));
        List<PlaylistItem> items = new ArrayList<>();
        collectPlaylistItems(root.optJSONArray("children"), items);
        return items;
    }

    public List<BrowserEntry> browse(String uri) throws IOException, JSONException {
        String requested = uri == null || uri.trim().isEmpty() ? "file://~" : uri.trim();
        JSONObject root = new JSONObject(get("/requests/browse.json?uri=" + urlEncode(requested)));
        JSONArray elements = root.optJSONArray("element");
        List<BrowserEntry> out = new ArrayList<>();
        if (elements == null) return out;

        for (int i = 0; i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "unknown");
            String name = firstNonBlank(item.optString("name", ""), item.optString("path", ""));
            String itemUri = item.optString("uri", "");
            String path = item.optString("path", "");
            if (name.isEmpty()) continue;
            out.add(new BrowserEntry("dir".equals(type), name, itemUri, path));
        }
        return out;
    }

    public void togglePlay() throws IOException { command("pl_pause", null, null); }
    public void stop() throws IOException { command("pl_stop", null, null); }
    public void previous() throws IOException { command("pl_previous", null, null); }
    public void next() throws IOException { command("pl_next", null, null); }
    public void toggleFullscreen() throws IOException { command("fullscreen", null, null); }
    public void clearPlaylist() throws IOException { command("pl_empty", null, null); }

    public void playItem(int id) throws IOException {
        command("pl_play", "id", Integer.toString(id));
    }

    public void playInput(String input) throws IOException {
        command("in_play", "input", requireInput(input));
    }

    public void enqueueInput(String input) throws IOException {
        command("in_enqueue", "input", requireInput(input));
    }

    public void seekSeconds(int seconds) throws IOException {
        String value = (seconds >= 0 ? "+" : "") + seconds;
        command("seek", "val", value);
    }

    public void seekPercent(double percent) throws IOException {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        command("seek", "val", String.format(java.util.Locale.US, "%.2f%%", clamped));
    }

    public void setVolume(int volume) throws IOException {
        int clamped = Math.max(0, Math.min(512, volume));
        command("volume", "val", Integer.toString(clamped));
    }

    private void command(String command, String key, String value) throws IOException {
        StringBuilder path = new StringBuilder("/requests/status.json?command=")
                .append(urlEncode(command));
        if (key != null && value != null) {
            path.append('&').append(urlEncode(key)).append('=').append(urlEncode(value));
        }
        get(path.toString());
    }

    private String get(String path) throws IOException {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(baseUrl + path);
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", authorization);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Connection", "close");

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readFully(stream);
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new IOException("VLC rechazó la contraseña (HTTP 401)");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + (body.trim().isEmpty() ? "" : ": " + body));
            }
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void collectPlaylistItems(JSONArray nodes, List<PlaylistItem> out) throws JSONException {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            JSONArray children = node.optJSONArray("children");
            if (children != null && children.length() > 0) {
                collectPlaylistItems(children, out);
                continue;
            }
            int id = node.optInt("id", -1);
            if (id < 0) continue;
            String name = firstNonBlank(
                    node.optString("name", ""),
                    node.optString("uri", ""),
                    "Elemento " + id);
            boolean current = "current".equals(node.optString("current", ""));
            out.add(new PlaylistItem(id, name, current));
        }
    }

    private static String readFully(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }

    private static String requireInput(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IOException("La URL o ruta está vacía");
        return value;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) return candidate;
        }
        return "";
    }
}
