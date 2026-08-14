package com.jairosalinas.vlcremote;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "vlc_remote_settings";
    private static final long STATUS_REFRESH_MS = 2500;
    private static final int REQUEST_OPEN_PLAYLIST = 1001;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText editHost;
    private EditText editPort;
    private EditText editPassword;
    private EditText editMediaUrl;
    private TextView txtConnection;
    private TextView txtNowPlaying;
    private TextView txtTime;
    private SeekBar seekPosition;
    private SeekBar seekVolume;
    private ListView listPlaylist;
    private ArrayAdapter<VlcHttpClient.PlaylistItem> playlistAdapter;
    private final List<VlcHttpClient.PlaylistItem> playlist = new ArrayList<>();

    private volatile VlcHttpClient client;
    private boolean userChangingPosition;
    private boolean userChangingVolume;
    private boolean periodicRefreshEnabled;

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            if (!periodicRefreshEnabled) return;
            refreshStatus(false);
            main.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        restoreSettings();
        wireActions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        periodicRefreshEnabled = true;
        main.removeCallbacks(periodicRefresh);
        main.postDelayed(periodicRefresh, STATUS_REFRESH_MS);
    }

    @Override
    protected void onStop() {
        periodicRefreshEnabled = false;
        main.removeCallbacks(periodicRefresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_PLAYLIST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) loadLocalPlaylist(uri);
    }

    private void bindViews() {
        editHost = findViewById(R.id.editHost);
        editPort = findViewById(R.id.editPort);
        editPassword = findViewById(R.id.editPassword);
        editMediaUrl = findViewById(R.id.editMediaUrl);
        txtConnection = findViewById(R.id.txtConnection);
        txtNowPlaying = findViewById(R.id.txtNowPlaying);
        txtTime = findViewById(R.id.txtTime);
        seekPosition = findViewById(R.id.seekPosition);
        seekVolume = findViewById(R.id.seekVolume);
        listPlaylist = findViewById(R.id.listPlaylist);
        playlistAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, playlist);
        listPlaylist.setAdapter(playlistAdapter);
    }

    private void restoreSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        editHost.setText(prefs.getString("host", ""));
        editPort.setText(Integer.toString(prefs.getInt("port", 8080)));
        editPassword.setText(prefs.getString("password", ""));
        if (!editHost.getText().toString().trim().isEmpty()) createClientFromFields(false);
    }

    private void wireActions() {
        findViewById(R.id.btnConnect).setOnClickListener(v -> {
            if (createClientFromFields(true)) {
                refreshStatus(true);
                refreshPlaylist();
            }
        });

        commandButton(R.id.btnPlayPause, c -> c.togglePlay());
        commandButton(R.id.btnStop, c -> c.stop());
        commandButton(R.id.btnPrev, c -> c.previous());
        commandButton(R.id.btnNext, c -> c.next());
        commandButton(R.id.btnBack, c -> c.seekSeconds(-10));
        commandButton(R.id.btnForward, c -> c.seekSeconds(10));
        commandButton(R.id.btnFullscreen, c -> c.toggleFullscreen());
        commandButton(R.id.btnClearPlaylist, c -> {
            c.clearPlaylist();
            main.postDelayed(this::refreshPlaylist, 250);
        });
        findViewById(R.id.btnRefreshPlaylist).setOnClickListener(v -> refreshPlaylist());

        findViewById(R.id.btnPlayUrl).setOnClickListener(v -> playMediaInput(true));
        findViewById(R.id.btnEnqueueUrl).setOnClickListener(v -> playMediaInput(false));
        findViewById(R.id.btnOpenLocalPlaylist).setOnClickListener(v -> openLocalPlaylistPicker());

        seekPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userChangingPosition = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userChangingPosition = false;
                double percent = seekBar.getProgress() / 10.0;
                runCommand(c -> c.seekPercent(percent), false);
            }
        });

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userChangingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userChangingVolume = false;
                runCommand(c -> c.setVolume(seekBar.getProgress()), false);
            }
        });

        listPlaylist.setOnItemClickListener((parent, view, position, id) -> {
            VlcHttpClient.PlaylistItem item = playlist.get(position);
            runCommand(c -> c.playItem(item.id), true);
        });
    }

    private void openLocalPlaylistPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN_PLAYLIST);
    }

    private void playMediaInput(boolean startPlaying) {
        String input = editMediaUrl.getText().toString().trim();
        if (input.isEmpty()) {
            toast("Escribe una URL, M3U/M3U8 o ruta accesible por el VLC remoto");
            return;
        }

        runCommand(c -> {
            if (startPlaying) c.playInput(input);
            else c.enqueueInput(input);
        }, true, () -> {
            toast(startPlaying ? "Contenido enviado a VLC" : "Añadido a la playlist");
            main.postDelayed(this::refreshPlaylist, 800);
        });
    }

    private void loadLocalPlaylist(Uri uri) {
        if (uri == null) return;

        VlcHttpClient active = client;
        if (active == null && !createClientFromFields(true)) return;
        active = client;
        VlcHttpClient finalClient = active;

        io.execute(() -> {
            try {
                List<String> entries = readPlaylistEntries(uri);
                if (entries.isEmpty()) {
                    main.post(() -> toast("La lista no contiene elementos reproducibles"));
                    return;
                }

                finalClient.playInput(entries.get(0));
                for (int i = 1; i < entries.size(); i++) {
                    finalClient.enqueueInput(entries.get(i));
                }

                main.post(() -> {
                    toast("Lista cargada: " + entries.size() + " elementos");
                    refreshStatus(false);
                    main.postDelayed(this::refreshPlaylist, 800);
                });
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private List<String> readPlaylistEntries(Uri uri) throws Exception {
        List<String> entries = new ArrayList<>();
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IllegalStateException("No se pudo abrir la lista seleccionada");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;

                int equals = value.indexOf('=');
                if (equals > 0 && value.substring(0, equals).matches("(?i)File\\d+")) {
                    value = value.substring(equals + 1).trim();
                }

                if (!value.isEmpty()) entries.add(value);
            }
        }
        return entries;
    }

    private interface ClientCommand { void run(VlcHttpClient client) throws Exception; }

    private void commandButton(int id, ClientCommand command) {
        findViewById(id).setOnClickListener(v -> runCommand(command, true));
    }

    private boolean createClientFromFields(boolean persist) {
        String host = editHost.getText().toString().trim();
        String portText = editPort.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Escribe la IP o hostname del equipo que ejecuta VLC");
            return false;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            toast("Puerto inválido");
            return false;
        }

        String password = editPassword.getText().toString();
        client = new VlcHttpClient(host, port, password);
        txtConnection.setText("Configurado: " + host + ":" + port);

        if (persist) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .putString("password", password)
                    .apply();
        }
        return true;
    }

    private void runCommand(ClientCommand command, boolean refreshAfter) {
        runCommand(command, refreshAfter, null);
    }

    private void runCommand(ClientCommand command, boolean refreshAfter, Runnable onSuccess) {
        VlcHttpClient active = client;
        if (active == null && !createClientFromFields(true)) return;
        active = client;
        VlcHttpClient finalClient = active;
        io.execute(() -> {
            try {
                command.run(finalClient);
                if (onSuccess != null) main.post(onSuccess);
                if (refreshAfter) main.postDelayed(() -> refreshStatus(false), 200);
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private void refreshStatus(boolean announceSuccess) {
        VlcHttpClient active = client;
        if (active == null) return;
        io.execute(() -> {
            try {
                VlcHttpClient.Status status = active.getStatus();
                main.post(() -> {
                    txtConnection.setText("Conectado • estado: " + status.state);
                    txtNowPlaying.setText(status.title);
                    txtTime.setText(formatTime(status.timeSeconds) + " / " + formatTime(status.lengthSeconds));
                    if (!userChangingPosition) {
                        seekPosition.setProgress((int) Math.round(status.position * 1000.0));
                    }
                    if (!userChangingVolume) {
                        seekVolume.setProgress(Math.max(0, Math.min(512, status.volume)));
                    }
                    if (announceSuccess) toast("Conectado con VLC");
                });
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private void refreshPlaylist() {
        VlcHttpClient active = client;
        if (active == null) return;
        io.execute(() -> {
            try {
                List<VlcHttpClient.PlaylistItem> received = active.getPlaylist();
                main.post(() -> {
                    playlist.clear();
                    playlist.addAll(received);
                    playlistAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private void showError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        main.post(() -> {
            txtConnection.setText("Error: " + msg);
            toast(msg);
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String formatTime(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int seconds = safe % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
