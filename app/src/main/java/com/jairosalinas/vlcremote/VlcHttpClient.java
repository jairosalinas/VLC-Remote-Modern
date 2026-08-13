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

final class VlcHttpClient {
    static final class Status {
        final String state;
        final String title;
        final int timeSeconds;
        final int lengthSeconds;
        final int volume;
        final double position;

        Status(String state, String title, int timeSeconds, int lengthSeconds, int volume, double position) {
            this.state = state;
            this.title = title;
            this.timeSeconds = timeSeconds;
            this.lengthSeconds = lengthSeconds;
            this.volume = volume;
            this.position = position;
        }
    }

    static final class PlaylistItem {
        final int id;
        final String name;

        PlaylistItem(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String toString() { return name; }
    }

    private final String baseUrl;
    private final String authorization;

    VlcHttpClient(String host, int port, String password) {
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.startsWith("http://")) cleanHost = cleanHost.substring(7);
        if (cleanHost.startsWith("https://")) cleanHost = cleanHost.substring(8);
        while (cleanHost.endsWith("/")) cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
        this.baseUrl = "http://" + cleanHost + ":" + port;
        String rawAuth = ":" + (password == null ? "" : password);
        this.authorization = "Basic " + Base64.encodeToString(
                rawAuth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    Status getStatus() throws IOException, JSONException {
        JSONObject root = new JSONObject(get("/requests/status.json"));
        String state = root.optString("state", "unknown");
        int time = root.optInt("time", 0);
        int length = root.optInt("length", 0);
        int volume = root.optInt("volume", 0);
        double position = root.optDouble("position", 0.0);

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
        return new Status(state, title, time, length, volume, position);
    }

    List<PlaylistItem> getPlaylist() throws IOException, JSONException {
        JSONObject root = new JSONObject(get("/requests/playlist.json"));
        List<PlaylistItem> items = new ArrayList<>();
        collectPlaylistItems(root.optJSONArray("children"), items);
        return items;
    }

    void togglePlay() throws IOException { command("pl_pause", null, null); }
    void stop() throws IOException { command("pl_stop", null, null); }
    void previous() throws IOException { command("pl_previous", null, null); }
    void next() throws IOException { command("pl_next", null, null); }
    void toggleFullscreen() throws IOException { command("fullscreen", null, null); }
    void clearPlaylist() throws IOException { command("pl_empty", null, null); }

    void playItem(int id) throws IOException {
        command("pl_play", "id", Integer.toString(id));
    }

    void seekSeconds(int seconds) throws IOException {
        String value = (seconds >= 0 ? "+" : "") + seconds;
        command("seek", "val", value);
    }

    void seekPercent(double percent) throws IOException {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        command("seek", "val", String.format(java.util.Locale.US, "%.2f%%", clamped));
    }

    void setVolume(int volume) throws IOException {
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
            String name = firstNonBlank(node.optString("name", ""), node.optString("uri", ""), "Elemento " + id);
            out.add(new PlaylistItem(id, name));
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
