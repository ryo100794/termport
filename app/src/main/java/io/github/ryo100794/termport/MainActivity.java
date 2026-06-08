package io.github.ryo100794.termport;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
public class MainActivity extends Activity {
    private static final String TAG = "TermPort";
    private static final String HOST = "127.0.0.1";
    private static final int BASE_PORT = 8765;
    private static final int SKYDNIR_ENGINE_SOCKET_ID = 12375;
    private static final String PREF_BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested";
    private static final String PREF_TERMUX_CONNECTED_MASK = "termux_connected_mask";
    private static final int REQUEST_TERMUX_RUN_COMMAND = 7;
    private static final int REQUEST_POST_NOTIFICATIONS = 8;
    private static final int SKYDNIR_IMAGE_SCAN_LIMIT = 2000;
    private static final int MAX_SESSIONS = 16;
    private static final int RAW_CAPTURE_LIMIT = 512 * 1024;
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_SETUP_COMMAND = "mkdir -p ~/.termux && "
            + "grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null "
            + "|| printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties; "
            + "termux-reload-settings";
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Socket[] sockets = new Socket[MAX_SESSIONS];
    private final LocalSocket[] engineSockets = new LocalSocket[MAX_SESSIONS];
    private final OutputStream[] socketOuts = new OutputStream[MAX_SESSIONS];
    private final String[] backends = filledStrings(MAX_SESSIONS, "termux");
    private final String[] dockerExecIds = new String[MAX_SESSIONS];
    private final String[] dockerContainerIds = new String[MAX_SESSIONS];
    private final int[] connectGenerations = new int[MAX_SESSIONS];
    private final int[] rows = filledInts(MAX_SESSIONS, 32);
    private final int[] cols = filledInts(MAX_SESSIONS, 100);
    private static String[] filledStrings(int count, String value) {
        String[] out = new String[count];
        Arrays.fill(out, value);
        return out;
    }
    private static int[] filledInts(int count, int value) {
        int[] out = new int[count];
        Arrays.fill(out, value);
        return out;
    }
    private WebView webView;
    private String bridgeAssetBase64;
    private final boolean[] termuxSingleBridgeStarted = new boolean[MAX_SESSIONS];
    private boolean initialSessionsStarted = false;
    private int pendingTermuxSession = -1;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setTextZoom(100);
        settings.setMinimumFontSize(1);
        settings.setMinimumLogicalFontSize(1);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                restoreTermuxSessions();
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        requestPostNotificationsIfNeeded();
        startSkydnirService();
        webView.loadUrl("file:///android_asset/xterm/index.html");
        webView.postDelayed(this::requestIgnoreBatteryOptimizationsIfNeeded, 1000);
    }
    @Override
    protected void onResume() {
        super.onResume();
        startSkydnirService();
        if (webView != null) {
            webView.postDelayed(this::restoreTermuxSessions, 700);
            webView.postDelayed(this::restoreTermuxSessions, 2000);
        }
    }
    @Override
    protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.flushScrollback && window.flushScrollback()", null);
        }
        super.onPause();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < MAX_SESSIONS; i++) closeSocket(i);
        io.shutdownNow();
    }
    private void requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_POST_NOTIFICATIONS);
    }
    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (getPreferences(MODE_PRIVATE).getBoolean(PREF_BATTERY_OPTIMIZATION_REQUESTED, false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_BATTERY_OPTIMIZATION_REQUESTED, true).apply();
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            setStatus("Allow battery optimization exemption");
        } catch (Exception e) {
            Log.w(TAG, "Battery optimization exemption request failed", e);
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                setStatus("Disable battery optimization for TermPort");
            } catch (Exception ignored) {
            }
        }
    }
    private boolean ensureRunCommandPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{RUN_COMMAND_PERMISSION}, REQUEST_TERMUX_RUN_COMMAND);
            return false;
        }
        return true;
    }
    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
    private synchronized void restoreTermuxSessions() {
        if (initialSessionsStarted) return;
        int mask = getPreferences(MODE_PRIVATE).getInt(PREF_TERMUX_CONNECTED_MASK, 0);
        if (mask == 0) {
            initialSessionsStarted = true;
            return;
        }
        if (!isTermuxInstalled()) {
            Log.w(TAG, "Termux is not installed");
            showSetupHelp("Termux is not installed");
            return;
        }
        if (!ensureRunCommandPermission()) {
            pendingTermuxSession = -2;
            Log.w(TAG, "RUN_COMMAND permission is not granted");
            setStatus("Allow Termux connection permission");
            return;
        }
        initialSessionsStarted = true;
        Log.i(TAG, "Restoring previously connected Termux sessions mask=" + mask);
        io.execute(() -> {
            for (int i = 0; i < MAX_SESSIONS; i++) {
                if ((mask & (1 << i)) == 0) continue;
                final int session = i;
                runOnUiThread(() -> connectSession(session));
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }
    private void showSetupHelp(String reason) {
        setStatus(reason);
        writeTerminal(0, "TermPort setup\r\n"
                + reason + "\r\n\r\n"
                + "In Termux, run this once:\r\n"
                + TERMUX_SETUP_COMMAND + "\r\n\r\n"
                + "If auto-start cannot continue, run the command in Termux, then restart TermPort.\r\n");
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TERMUX_RUN_COMMAND && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            int pending = pendingTermuxSession;
            pendingTermuxSession = -1;
            if (pending >= 0) connectSession(pending);
            else {
                initialSessionsStarted = false;
                restoreTermuxSessions();
            }
        } else if (requestCode == REQUEST_TERMUX_RUN_COMMAND) {
            pendingTermuxSession = -1;
            showSetupHelp("RUN_COMMAND permission was denied");
        }
    }
    private int clampSession(int session) {
        return Math.max(0, Math.min(session, MAX_SESSIONS - 1));
    }
    private int portFor(int session) {
        return BASE_PORT + clampSession(session);
    }
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
    private String termuxBootstrap(String prefix, String home, int basePort, int count, int firstSession) throws Exception {
        String bridgeB64 = bridgeAssetBase64();
        int s = clampSession(firstSession);
        return "export PREFIX=" + shellQuote(prefix)
                + " HOME=" + shellQuote(home)
                + " PATH=" + shellQuote(prefix + "/bin:/system/bin:/system/xbin")
                + " IME_CONSOLE_BASE_PORT=" + basePort
                + " IME_CONSOLE_SESSION_COUNT=" + count
                + " IME_CONSOLE_ROWS=" + rows[s]
                + " IME_CONSOLE_COLS=" + cols[s]
                + "; mkdir -p \"$HOME/.ime-console\""
                + "; PYTHON=\"$PREFIX/bin/python3\"; if [ ! -x \"$PYTHON\" ]; then PYTHON=\"$PREFIX/bin/python\"; fi"
                + "; if [ ! -x \"$PYTHON\" ]; then echo 'TermPort Termux bridge needs python already installed in Termux'; exit 127; fi"
                + "; if command -v base64 >/dev/null 2>&1; then printf %s " + shellQuote(bridgeB64) + " | base64 -d > \"$HOME/.ime-console/bridge.py\";"
                + " else \"$PYTHON\" -c \"import base64,pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(base64.b64decode(sys.stdin.read()))\" \"$HOME/.ime-console/bridge.py\" <<'IME_BRIDGE_B64'\n"
                + bridgeB64
                + "\nIME_BRIDGE_B64\n"
                + " fi"
                + "; chmod 700 \"$HOME/.ime-console/bridge.py\""
                + "; exec \"$PYTHON\" \"$HOME/.ime-console/bridge.py\"";
    }
    private synchronized void startTermuxSingleBridge(int session) {
        int s = clampSession(session);
        if (termuxSingleBridgeStarted[s]) return;
        termuxSingleBridgeStarted[s] = true;
        Log.i(TAG, "Starting single Termux bridge for session " + (s + 1));
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setComponent(new ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService"));
            String prefix = "/data/data/com.termux/files/usr";
            String home = "/data/data/com.termux/files/home";
            intent.putExtra("com.termux.RUN_COMMAND_PATH", prefix + "/bin/sh");
            String bootstrap = termuxBootstrap(prefix, home, portFor(s), 1, s);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", bootstrap});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(intent);
        } catch (Exception e) {
            termuxSingleBridgeStarted[s] = false;
            Log.e(TAG, "Single Termux bridge start failed", e);
        }
    }
    private void rememberTermuxConnected(int session, boolean connected) {
        int s = clampSession(session);
        int mask = getPreferences(MODE_PRIVATE).getInt(PREF_TERMUX_CONNECTED_MASK, 0);
        if (connected) mask |= (1 << s);
        else mask &= ~(1 << s);
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_TERMUX_CONNECTED_MASK, mask).apply();
    }
    private boolean ensureTermuxReadyForConnect(int session) {
        int s = clampSession(session);
        if (!isTermuxInstalled()) {
            Log.w(TAG, "Termux is not installed");
            showSetupHelp("Termux is not installed");
            return false;
        }
        if (!ensureRunCommandPermission()) {
            pendingTermuxSession = s;
            Log.w(TAG, "RUN_COMMAND permission is not granted");
            setStatus("Allow Termux connection permission");
            return false;
        }
        return true;
    }
    private void connectSession(int session) {
        connectSession(session, false);
    }
    private void reconnectSession(int session) {
        connectSession(session, true);
    }
    private boolean isSocketOpen(int session) {
        int s = clampSession(session);
        Socket sock = sockets[s];
        return sock != null
                && socketOuts[s] != null
                && sock.isConnected()
                && !sock.isClosed()
                && !sock.isInputShutdown()
                && !sock.isOutputShutdown();
    }
    private void connectSession(int session, boolean forceReconnect) {
        int s = clampSession(session);
        if (!ensureTermuxReadyForConnect(s)) return;
        if (!forceReconnect && isSocketOpen(s)) {
            setStatus("Session " + (s + 1) + ": connected");
            return;
        }
        closeSocket(s);
        int generation = ++connectGenerations[s];
        backends[s] = "termux";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
        setStatus("Session " + (s + 1) + ": connecting " + HOST + ":" + portFor(s));
        io.execute(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < 18; attempt++) {
                if (generation != connectGenerations[s]) return;
                try {
                    Socket sock = new Socket();
                    sock.setTcpNoDelay(true);
                    sock.connect(new InetSocketAddress(HOST, portFor(s)), 900);
                    if (generation != connectGenerations[s]) {
                        try { sock.close(); } catch (Exception ignored) {}
                        return;
                    }
                    sockets[s] = sock;
                    socketOuts[s] = sock.getOutputStream();
                    setStatus("Session " + (s + 1) + ": connected");
                    rememberTermuxConnected(s, true);
                    readLoop(s, sock, sock.getInputStream());
                    return;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt == 2) runOnUiThread(() -> startTermuxSingleBridge(s));
                    try {
                        Thread.sleep(Math.min(1000L, 180L * (attempt + 1)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (generation == connectGenerations[s]) {
                setStatus("Session " + (s + 1) + ": disconnected");
                writeTerminal(s, "[TermPort] connect failed: " + (lastError == null ? "timeout" : lastError.getMessage()) + "\r\n");
                termuxSingleBridgeStarted[s] = false;
                closeSocket(s);
            }
        });
    }
    private void readLoop(int session, Closeable owner, InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) writeTerminal(session, buf, n);
            }
        } finally {
            closeSocketIfCurrent(session, owner);
            setStatus(statusPrefix(session) + ": disconnected");
        }
    }
    private void closeSocket(int session) {
        int s = clampSession(session);
        try {
            if (sockets[s] != null) sockets[s].close();
        } catch (Exception ignored) {
        }
        try {
            if (engineSockets[s] != null) engineSockets[s].close();
        } catch (Exception ignored) {
        }
        sockets[s] = null;
        engineSockets[s] = null;
        socketOuts[s] = null;
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
    }
    private void closeSocketIfCurrent(int session, Closeable owner) {
        int s = clampSession(session);
        if (sockets[s] != owner && engineSockets[s] != owner) return;
        closeSocket(s);
    }
    private static class EngineResponse {
        final int status;
        final byte[] body;
        EngineResponse(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
    private String statusPrefix(int session) {
        int s = clampSession(session);
        return "docker".equals(backends[s]) ? "Skydnir " + (s + 1) : "Session " + (s + 1);
    }
    private String encodePath(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }
    private String readHttpHead(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] marker = new byte[]{'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (true) {
            int b = input.read();
            if (b < 0) break;
            out.write(b);
            matched = ((byte) b == marker[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == marker.length) break;
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
    private byte[] readRemaining(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = input.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
    private int httpContentLength(String head) {
        if (head == null) return -1;
        String[] lines = head.split("\r?\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (!"content-length".equalsIgnoreCase(line.substring(0, colon).trim())) continue;
            try { return Integer.parseInt(line.substring(colon + 1).trim()); } catch (Exception ignored) { return -1; }
        }
        return -1;
    }
    private byte[] readHttpBody(String head, InputStream input) throws Exception {
        int length = httpContentLength(head);
        if (length < 0) return readRemaining(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, length));
        byte[] buf = new byte[Math.min(8192, Math.max(1, length))];
        int remaining = length;
        while (remaining > 0) {
            int n = input.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) throw new Exception("HTTP body ended early");
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }
    private int httpStatus(String head) {
        if (head == null) return 0;
        String[] parts = head.split("\\s+", 3);
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
            return 0;
        }
    }
    private File skydnirHomeDir() {
        File dir = new File(getFilesDir(), "skydnir");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
    private String skydnirEngineSocketPath() {
        return new File(skydnirHomeDir(), "engine-" + SKYDNIR_ENGINE_SOCKET_ID + ".sock").getAbsolutePath();
    }
    private File skydnirImagesDir() {
        return new File(skydnirHomeDir(), "images");
    }
    private LocalSocket openEngineSocket(int timeoutMs) throws Exception {
        LocalSocket sock = new LocalSocket();
        sock.connect(new LocalSocketAddress(skydnirEngineSocketPath(), LocalSocketAddress.Namespace.FILESYSTEM));
        sock.setSoTimeout(timeoutMs);
        return sock;
    }
    private EngineResponse dockerRequest(String method, String path, byte[] body, int timeoutMs) throws Exception {
        try (LocalSocket sock = openEngineSocket(timeoutMs)) {
            OutputStream out = sock.getOutputStream();
            byte[] requestBody = body == null ? new byte[0] : body;
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.0\r\n");
            head.append("Host: skydnir\r\n");
            head.append("Connection: close\r\n");
            if (requestBody.length > 0) {
                head.append("Content-Type: application/json\r\n");
                head.append("Content-Length: ").append(requestBody.length).append("\r\n");
            } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                head.append("Content-Length: 0\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (requestBody.length > 0) out.write(requestBody);
            out.flush();
            InputStream in = sock.getInputStream();
            String responseHead = readHttpHead(in);
            return new EngineResponse(httpStatus(responseHead), readHttpBody(responseHead, in));
        }
    }
    private String createDockerExec(String containerId) throws Exception {
        JSONObject payload = new JSONObject()
                .put("AttachStdin", true)
                .put("AttachStdout", true)
                .put("AttachStderr", true)
                .put("Tty", true)
                .put("Env", new JSONArray(Arrays.asList("TERM=xterm-256color", "COLORTERM=truecolor", "ENV=", "BASH_ENV=")))
                .put("Cmd", new JSONArray(Arrays.asList("/bin/sh", "-lc", "if command -v /bin/bash >/dev/null 2>&1; then exec /bin/bash -i; else exec /bin/sh -i; fi")));
        EngineResponse response = dockerRequest("POST", "/containers/" + encodePath(containerId) + "/exec", payload.toString().getBytes(StandardCharsets.UTF_8), 5000);
        if (response.status < 200 || response.status > 299) {
            String detail = response.text();
            throw new Exception(detail.isEmpty() ? "HTTP " + response.status : detail);
        }
        return new JSONObject(response.text()).getString("Id");
    }
    private LocalSocket startDockerExecStream(String execId) throws Exception {
        JSONObject payload = new JSONObject().put("Detach", false).put("Tty", true);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        LocalSocket sock = openEngineSocket(5000);
        String head = "POST /exec/" + encodePath(execId) + "/start HTTP/1.1\r\n"
                + "Host: skydnir\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: tcp\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n";
        OutputStream out = sock.getOutputStream();
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        String responseHead = readHttpHead(sock.getInputStream());
        if (!responseHead.startsWith("HTTP/1.1 101") && !responseHead.startsWith("HTTP/1.0 101")) {
            int status = httpStatus(responseHead);
            byte[] rest = readRemaining(sock.getInputStream());
            sock.close();
            String detail = new String(rest, StandardCharsets.UTF_8);
            throw new Exception(detail.isEmpty() ? "HTTP " + status : detail);
        }
        sock.setSoTimeout(0);
        return sock;
    }
    private String dockerEndpoint() {
        return "unix://" + skydnirEngineSocketPath();
    }
    private float webViewCssHeight() {
        if (webView == null) return 0f;
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        return webView.getHeight() / density;
    }
    private float visibleDisplayCssHeight() {
        if (webView == null) return 0f;
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        Rect rect = new Rect();
        webView.getWindowVisibleDisplayFrame(rect);
        int[] location = new int[2];
        webView.getLocationOnScreen(location);
        int top = Math.max(rect.top, location[1]);
        int visiblePx = Math.max(0, rect.bottom - top);
        return visiblePx / density;
    }
    private String containerDisplayName(JSONObject container) {
        JSONArray names = container.optJSONArray("Names");
        String name = names != null && names.length() > 0 ? names.optString(0, "").replaceFirst("^/", "") : "";
        if (name.isEmpty()) name = container.optString("Id", "");
        return name.length() > 24 ? name.substring(0, 24) : name;
    }
    private JSONArray compactContainerList(JSONArray containers) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < containers.length(); i++) {
            JSONObject c = containers.optJSONObject(i);
            if (c == null) continue;
            try {
                out.put(new JSONObject()
                        .put("id", c.optString("Id"))
                        .put("name", containerDisplayName(c))
                        .put("image", c.optString("Image"))
                        .put("imageId", c.optString("ImageID"))
                        .put("state", c.optString("State"))
                        .put("status", c.optString("Status")));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }
    private JSONArray listDockerContainers() throws Exception {
        EngineResponse ping = dockerRequest("GET", "/_ping", null, 1500);
        if (ping.status < 200 || ping.status > 299) throw new Exception("/_ping HTTP " + ping.status);
        EngineResponse list = dockerRequest("GET", "/containers/json?all=1", null, 4000);
        if (list.status < 200 || list.status > 299) {
            String detail = list.text();
            throw new Exception(detail.isEmpty() ? "/containers/json?all=1 HTTP " + list.status : detail);
        }
        return new JSONArray(list.text());
    }
    private void publishDockerContainers(JSONArray containers, String error) {
        try {
            JSONObject payload = new JSONObject()
                    .put("endpoint", dockerEndpoint())
                    .put("containers", containers == null ? new JSONArray() : compactContainerList(containers));
            if (error != null && !error.isEmpty()) payload.put("error", error);
            String script = "window.setDockerContainers && window.setDockerContainers(" + payload + ")";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
        }
    }
    private void refreshDockerContainers() {
        setStatus("Skydnir: listing " + dockerEndpoint());
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                JSONArray containers = listDockerContainers();
                publishDockerContainers(containers, null);
                setStatus("Skydnir: " + containers.length() + " containers");
            } catch (Exception e) {
                publishDockerContainers(new JSONArray(), e.getMessage());
                setStatus("Skydnir: unavailable " + dockerEndpoint());
            }
        });
    }
    private String displayDockerImageRef(String ref) {
        String out = ref == null ? "" : ref.trim();
        if (out.startsWith("docker.io/library/")) return out.substring("docker.io/library/".length());
        if (out.startsWith("docker.io/")) return out.substring("docker.io/".length());
        return out;
    }
    private String safeImageFileName(String ref) {
        String out = displayDockerImageRef(ref).replaceAll("[^A-Za-z0-9._-]+", "_");
        return out.isEmpty() ? "rootfs" : out;
    }
    private boolean imageRefMatches(String wanted, String actual, String dirName) {
        String w = wanted == null ? "" : wanted.trim();
        String a = actual == null ? "" : actual.trim();
        if (w.isEmpty()) return false;
        if (w.equals(a) || w.equals(dirName)) return true;
        String wd = displayDockerImageRef(w);
        String ad = displayDockerImageRef(a);
        return w.equals(ad) || wd.equals(a) || wd.equals(ad) || wd.equals(displayDockerImageRef(dirName));
    }
    private File findDockerImageDir(String imageRef) throws Exception {
        File root = skydnirImagesDir();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String ref = readTextFile(new File(dir, "image_ref")).trim();
                if (imageRefMatches(imageRef, ref, dir.getName())) return dir;
            }
        }
        throw new Exception("Image not found: " + displayDockerImageRef(imageRef));
    }
    private File findDockerImageDirOrNull(String imageRef) {
        try {
            return findDockerImageDir(imageRef);
        } catch (Exception ignored) {
            return null;
        }
    }
    private String imageRootfsRelativePath(File rootfs, File file) throws Exception {
        String rootPath = rootfs.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) return "/";
        if (!filePath.startsWith(rootPath + File.separator)) throw new Exception("Path escapes rootfs");
        return "/" + filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }
    private String imageRootfsListedPath(File rootfs, File file) throws Exception {
        String rootPath = rootfs.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.equals(rootPath)) return "/";
        if (!filePath.startsWith(rootPath + File.separator)) throw new Exception("Path escapes rootfs");
        return "/" + filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }
    private File resolveDockerImageRootfsFile(String imageRef, String path) throws Exception {
        File imageDir = findDockerImageDir(imageRef);
        File rootfs = new File(imageDir, "rootfs").getCanonicalFile();
        if (!rootfs.isDirectory()) throw new Exception("Image rootfs is not available: " + displayDockerImageRef(imageRef));
        String rel = path == null || path.trim().isEmpty() || "/".equals(path.trim()) ? "" : path.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        File target = new File(rootfs, rel).getCanonicalFile();
        String rootPath = rootfs.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new Exception("Path escapes rootfs");
        }
        return target;
    }
    private String imageFileDetail(File file) {
        String kind;
        try {
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                return "symlink -> " + java.nio.file.Files.readSymbolicLink(file.toPath()).toString();
            }
        } catch (Exception ignored) {
        }
        if (file.isDirectory()) kind = "directory";
        else if (file.isFile()) kind = "file " + file.length() + " bytes";
        else kind = "special";
        return kind + " / modified " + file.lastModified();
    }
    private int imageRootfsTopLevelCount(File rootfs) {
        String[] names = rootfs.list();
        return names == null ? 0 : names.length;
    }
    private JSONObject imageFileEntryJson(File rootfs, File file) throws Exception {
        String type = file.isDirectory() ? "dir" : file.isFile() ? "file" : "special";
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) type = "symlink";
        return new JSONObject()
                .put("name", file.getName())
                .put("path", imageRootfsListedPath(rootfs, file))
                .put("type", type)
                .put("size", file.isFile() ? file.length() : 0)
                .put("modified", file.lastModified())
                .put("detail", imageFileDetail(file))
                .put("previewable", file.isFile());
    }
    private JSONObject dockerImageLocalMetadata(String imageRef) {
        JSONObject out = new JSONObject();
        try {
            File dir = findDockerImageDir(imageRef);
            File rootfs = new File(dir, "rootfs");
            out.put("rootfsEntries", imageRootfsTopLevelCount(rootfs));
            out.put("imageDir", dir.getName());
            String configText = readTextFile(new File(dir, "config.json"));
            if (!configText.isEmpty()) {
                JSONObject config = new JSONObject(configText);
                JSONObject rootfsConfig = config.optJSONObject("rootfs");
                JSONArray diffIds = rootfsConfig == null ? null : rootfsConfig.optJSONArray("diff_ids");
                if (diffIds != null) out.put("layers", diffIds.length());
            }
        } catch (Exception ignored) {
        }
        return out;
    }
    private String listDockerImageFilesJson(String imageRef, String path) {
        try {
            File target = resolveDockerImageRootfsFile(imageRef, path);
            File imageDir = findDockerImageDir(imageRef);
            File rootfs = new File(imageDir, "rootfs").getCanonicalFile();
            if (!target.isDirectory()) target = target.getParentFile();
            if (target == null) target = rootfs;
            target = target.getCanonicalFile();
            JSONArray entries = new JSONArray();
            File[] files = target.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File file : files) entries.put(imageFileEntryJson(rootfs, file));
            }
            JSONObject out = new JSONObject()
                    .put("image", displayDockerImageRef(imageRef))
                    .put("path", imageRootfsRelativePath(rootfs, target))
                    .put("root", imageDir.getName())
                    .put("entries", entries)
                    .put("summary", "rootfs entries " + imageRootfsTopLevelCount(rootfs));
            File parent = target.getParentFile();
            if (parent != null) {
                try {
                    if (!parent.getCanonicalPath().equals(target.getCanonicalPath()) && parent.getCanonicalPath().startsWith(rootfs.getPath())) {
                        out.put("parent", imageRootfsRelativePath(rootfs, parent));
                    }
                } catch (Exception ignored) {
                }
            }
            return out.toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", skydnirUserText(e.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"Image file browser failed\"}";
            }
        }
    }
    private String readDockerImageFileJson(String imageRef, String path) {
        try {
            File file = resolveDockerImageRootfsFile(imageRef, path);
            File imageDir = findDockerImageDir(imageRef);
            File rootfs = new File(imageDir, "rootfs").getCanonicalFile();
            JSONObject out = new JSONObject()
                    .put("image", displayDockerImageRef(imageRef))
                    .put("path", imageRootfsRelativePath(rootfs, file))
                    .put("name", file.getName())
                    .put("detail", imageFileDetail(file));
            if (!file.isFile()) return out.put("error", "Not a regular file").toString();
            long length = file.length();
            out.put("size", length);
            if (length > 64 * 1024) return out.put("tooLarge", true).toString();
            byte[] data;
            try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) bytes.write(buf, 0, n);
                data = bytes.toByteArray();
            }
            for (byte b : data) {
                if (b == 0) return out.put("binary", true).toString();
            }
            return out.put("text", new String(data, StandardCharsets.UTF_8)).toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", skydnirUserText(e.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"Image file preview failed\"}";
            }
        }
    }
    private String copyDockerImageFileToProjectJson(String imageRef, String path) {
        try {
            File file = resolveDockerImageRootfsFile(imageRef, path);
            File imageDir = findDockerImageDir(imageRef);
            File rootfs = new File(imageDir, "rootfs").getCanonicalFile();
            if (!file.isFile()) throw new Exception("Not a regular file");
            String rel = imageRootfsRelativePath(rootfs, file).replaceFirst("^/", "");
            File target = new File(new File(skydnirProjectDir(), "imports/" + safeImageFileName(imageRef)), rel);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(target, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                out.getFD().sync();
            }
            return new JSONObject()
                    .put("copied", true)
                    .put("target", target.getAbsolutePath())
                    .put("projectRelative", "imports/" + safeImageFileName(imageRef) + "/" + rel.replace(File.separatorChar, '/'))
                    .toString();
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", skydnirUserText(e.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"Copy failed\"}";
            }
        }
    }
    private JSONArray compactImageList(JSONArray images) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            JSONArray tags = image.optJSONArray("RepoTags");
            if (tags == null || tags.length() == 0) tags = new JSONArray().put("");
            for (int j = 0; j < tags.length(); j++) {
                String tag = tags.optString(j, "");
                String id = image.optString("Id", "");
                if (tag == null || tag.isEmpty() || "<none>:<none>".equals(tag)) {
                    tag = id.length() > 19 ? id.substring(7, 19) : id;
                }
                try {
                    JSONObject row = new JSONObject()
                            .put("id", id)
                            .put("repoTag", tag)
                            .put("size", image.optLong("Size", 0))
                            .put("uniqueSize", image.optLong("UniqueSize", -1))
                            .put("sharedSize", image.optLong("SharedSize", -1))
                            .put("virtualSize", image.optLong("VirtualSize", image.optLong("Size", 0)))
                            .put("containers", image.optLong("Containers", -1))
                            .put("created", image.optLong("Created", 0));
                    JSONObject local = dockerImageLocalMetadata(tag);
                    if (local.has("rootfsEntries")) row.put("rootfsEntries", local.optInt("rootfsEntries", 0));
                    if (local.has("layers")) row.put("layers", local.optInt("layers", 0));
                    if (local.has("imageDir")) row.put("imageDir", local.optString("imageDir"));
                    out.put(row);
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }
    private JSONArray listDockerImages() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                EngineResponse ping = dockerRequest("GET", "/_ping", null, 1500);
                if (ping.status < 200 || ping.status > 299) throw new Exception("/_ping HTTP " + ping.status);
                EngineResponse list = dockerRequest("GET", "/images/json", null, 8000);
                if (list.status < 200 || list.status > 299) {
                    String detail = list.text();
                    throw new Exception(detail.isEmpty() ? "/images/json HTTP " + list.status : detail);
                }
                return new JSONArray(list.text());
            } catch (Exception e) {
                last = e;
                if (attempt < 2) Thread.sleep(250L * (attempt + 1));
            }
        }
        throw last == null ? new Exception("/images/json unavailable") : last;
    }
    private JSONObject listDockerStorageSummary() {
        try {
            EngineResponse response = dockerRequest("GET", "/system/df", null, 8000);
            if (response.status >= 200 && response.status <= 299) return new JSONObject(response.text());
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }
    private void publishDockerImages(JSONArray images, JSONArray containers, JSONObject storage, String error) {
        publishDockerImages(images, containers, storage, error, null);
    }
    private void publishDockerImages(JSONArray images, JSONArray containers, JSONObject storage, String error, String warning) {
        try {
            JSONObject payload = new JSONObject()
                    .put("endpoint", dockerEndpoint())
                    .put("images", images == null ? new JSONArray() : compactImageList(images))
                    .put("containers", containers == null ? new JSONArray() : compactContainerList(containers))
                    .put("storage", storage == null ? new JSONObject() : storage);
            if (error != null && !error.isEmpty()) payload.put("error", error);
            if (warning != null && !warning.isEmpty()) payload.put("warnings", new JSONArray().put(warning));
            String script = "window.setDockerImages && window.setDockerImages(" + payload + ")";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        } catch (Exception ignored) {
        }
    }
    private void publishDockerImagesWithOptionalRefs(JSONArray images) {
        JSONArray containers = new JSONArray();
        String warning = null;
        try {
            containers = listDockerContainers();
        } catch (Exception containerError) {
            warning = "container refs unavailable: " + containerError.getMessage();
            Log.w(TAG, "Skydnir image container refs unavailable", containerError);
        }
        publishDockerImages(images, containers, listDockerStorageSummary(), null, warning);
    }
    private void refreshDockerImages() {
        setStatus("Skydnir: listing images " + dockerEndpoint());
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                JSONArray images = listDockerImages();
                JSONArray containers = new JSONArray();
                String warning = null;
                try {
                    containers = listDockerContainers();
                } catch (Exception containerError) {
                    warning = "container refs unavailable: " + containerError.getMessage();
                    Log.w(TAG, "Skydnir image container refs unavailable", containerError);
                }
                publishDockerImages(images, containers, listDockerStorageSummary(), null, warning);
                setStatus("Skydnir: " + compactImageList(images).length() + " images");
            } catch (Exception e) {
                Log.w(TAG, "Skydnir image list unavailable", e);
                publishDockerImages(new JSONArray(), new JSONArray(), new JSONObject(), e.getMessage());
                setStatus("Skydnir: image list unavailable");
            }
        });
    }
    private String debugDockerRequestJson(String path) {
        try {
            String p = path == null || path.trim().isEmpty() ? "/_ping" : path.trim();
            EngineResponse response = dockerRequest("GET", p, null, 5000);
            return new JSONObject()
                    .put("endpoint", dockerEndpoint())
                    .put("path", p)
                    .put("status", response.status)
                    .put("body", response.text())
                    .toString();
        } catch (Exception e) {
            try {
                return new JSONObject()
                        .put("endpoint", dockerEndpoint())
                        .put("path", path == null ? "" : path)
                        .put("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                        .toString();
            } catch (Exception ignored) {
                return "{\"error\":\"debug failed\"}";
            }
        }
    }

    private String abiDefaultImagePlatform() {
        return SkydnirRuntime.imagePlatform();
    }
    private String currentImagePlatform() {
        try {
            EngineResponse response = dockerRequest("GET", "/system/host", null, 2500);
            if (response.status >= 200 && response.status <= 299) {
                JSONObject runtime = new JSONObject(response.text()).optJSONObject("Runtime");
                String platform = runtime == null ? "" : runtime.optString("Platform", "").trim();
                if (!platform.isEmpty()) return platform;
            }
        } catch (Exception ignored) {
        }
        return abiDefaultImagePlatform();
    }
    private void addImageSuggestion(LinkedHashSet<String> refs, String ref) {
        if (ref == null) return;
        String r = ref.trim();
        if (r.isEmpty() || r.startsWith("#")) return;
        refs.add(r);
    }
    private String dockerImagePullSuggestionsJson() {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        addImageSuggestion(refs, "ubuntu:22.04");
        addImageSuggestion(refs, "ubuntu:24.04");
        addImageSuggestion(refs, "debian:bookworm");
        addImageSuggestion(refs, "alpine:3.20");
        addImageSuggestion(refs, "busybox:latest");
        File[] imageDirs = skydnirImagesDir().listFiles(File::isDirectory);
        if (imageDirs != null) {
            for (File dir : imageDirs) {
                String ref = readTextFile(new File(dir, "image_ref")).trim();
                addImageSuggestion(refs, displayDockerImageRef(ref.isEmpty() ? dir.getName() : ref));
            }
        }
        File project = skydnirProjectDir();
        Pattern imageLine = Pattern.compile("^\\s*image\\s*:\\s*['" + '"' + "]?([^'" + '"' + "\\s#]+)", Pattern.CASE_INSENSITIVE);
        Pattern fromLine = Pattern.compile("^\\s*FROM\\s+(?:--platform=\\S+\\s+)?([^@\\s]+(?:@[^\\s]+|:[^\\s]+)?)", Pattern.CASE_INSENSITIVE);
        ArrayList<File> composeFiles = new ArrayList<>();
        ArrayList<File> dockerfiles = new ArrayList<>();
        collectImageRefFiles(project, composeFiles, dockerfiles, new int[1]);
        Collections.sort(composeFiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        Collections.sort(dockerfiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        for (File file : composeFiles) scanImageRefs(file, imageLine, refs);
        for (File file : dockerfiles) scanImageRefs(file, fromLine, refs);
        ArrayList<String> sorted = new ArrayList<>(refs);
        Collections.sort(sorted, (a, b) -> {
            int c = a.split(":", 2)[0].compareToIgnoreCase(b.split(":", 2)[0]);
            return c != 0 ? c : a.compareToIgnoreCase(b);
        });
        return new JSONArray(sorted).toString();
    }
    private void scanImageRefs(File file, Pattern pattern, LinkedHashSet<String> refs) {
        String text = readTextFile(file);
        if (text.isEmpty()) return;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            Matcher m = pattern.matcher(line);
            if (m.find()) addImageSuggestion(refs, m.group(1));
        }
    }
    private void collectImageRefFiles(File dir, ArrayList<File> composeFiles, ArrayList<File> dockerfiles, int[] visited) {
        if (dir == null || !dir.isDirectory() || visited[0] >= SKYDNIR_IMAGE_SCAN_LIMIT) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (visited[0]++ >= SKYDNIR_IMAGE_SCAN_LIMIT) return;
            if (file.isDirectory()) {
                collectImageRefFiles(file, composeFiles, dockerfiles, visited);
                continue;
            }
            String name = file.getName();
            if ("compose.yaml".equals(name) || "compose.yml".equals(name) || "docker-compose.yaml".equals(name) || "docker-compose.yml".equals(name)) composeFiles.add(file);
            if ("Dockerfile".equals(name)) dockerfiles.add(file);
        }
    }
    private String fetchDockerHubImageRefsJson(String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || q.contains("/") || q.contains(":") || q.contains("@")) return "[]";
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://hub.docker.com/v2/search/repositories/?query=" + encodePath(q) + "&page_size=25");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            InputStream stream = conn.getResponseCode() >= 200 && conn.getResponseCode() <= 299 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return "[]";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) >= 0) out.write(buf, 0, n);
            JSONArray results = new JSONObject(out.toString(StandardCharsets.UTF_8.name())).optJSONArray("results");
            JSONArray refs = new JSONArray();
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    String ref = results.optJSONObject(i) == null ? "" : results.optJSONObject(i).optString("repo_name", "");
                    if (!ref.trim().isEmpty()) refs.put(ref.trim());
                }
            }
            return refs.toString();
        } catch (Exception e) {
            return "[]";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private int dockerImageCreateStreaming(int session, String imageRef, String platform) throws Exception {
        try (LocalSocket sock = openEngineSocket(600000)) {
            OutputStream out = sock.getOutputStream();
            String platformQuery = platform == null || platform.trim().isEmpty() ? "" : "&platform=" + encodePath(platform.trim());
            String head = "POST /images/create?fromImage=" + encodePath(imageRef) + platformQuery + " HTTP/1.0\r\n"
                    + "Host: skydnir\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = sock.getInputStream();
            String responseHead = readHttpHead(in);
            int status = httpStatus(responseHead);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            String[] streamError = new String[1];
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        String item = line.toString(StandardCharsets.UTF_8.name());
                        String error = dockerStreamError(item);
                        if (streamError[0] == null && error != null && !error.isEmpty()) streamError[0] = error;
                        writeBuildStreamLine(session, item);
                        line.reset();
                    } else if (b != '\r') {
                        line.write(b);
                    }
                }
            }
            if (line.size() > 0) {
                String item = line.toString(StandardCharsets.UTF_8.name());
                String error = dockerStreamError(item);
                if (streamError[0] == null && error != null && !error.isEmpty()) streamError[0] = error;
                writeBuildStreamLine(session, item);
            }
            if (streamError[0] != null && !streamError[0].isEmpty()) throw new Exception(streamError[0]);
            return status;
        }
    }
    private void pullDockerImage(int session, String imageRef) {
        pullDockerImage(session, imageRef, currentImagePlatform());
    }
    private void pullDockerImage(int session, String imageRef, String platform) {
        int s = clampSession(session);
        String ref = imageRef == null ? "" : imageRef.trim();
        if (ref.isEmpty()) return;
        String pullPlatform = platform == null || platform.trim().isEmpty() ? currentImagePlatform() : platform.trim();
        setStatus("Skydnir: pulling image " + ref);
        writeTerminal(s, "\r\n[TermPort] Skydnir image pull: " + ref + "\r\n[TermPort] platform: " + pullPlatform + "\r\n");
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                int status = dockerImageCreateStreaming(s, ref, pullPlatform);
                if (status < 200 || status > 299) throw new Exception("HTTP " + status);
                JSONArray images = listDockerImages();
                publishDockerImagesWithOptionalRefs(images);
                setStatus("Skydnir: pulled image " + ref + " (" + pullPlatform + ")");
            } catch (Exception e) {
                publishDockerImages(null, null, new JSONObject(), e.getMessage());
                setStatus("Skydnir: image pull failed");
                writeTerminal(s, "[TermPort] Skydnir image pull failed: " + skydnirUserText(e.getMessage()) + "\r\n");
            }
        });
    }
    private void deleteDockerImage(String imageRef) {
        deleteDockerImageInternal(imageRef, false);
    }
    private void cleanDockerImage(String imageRef) {
        deleteDockerImageInternal(imageRef, true);
    }
    private void deleteDockerImageInternal(String imageRef, boolean cleanCache) {
        String ref = imageRef == null ? "" : imageRef.trim();
        if (ref.isEmpty()) return;
        setStatus("Skydnir: deleting image " + ref);
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                EngineResponse response = dockerRequest("DELETE", "/images/" + encodePath(ref) + "?force=1", null, 120000);
                if (response.status < 200 || response.status > 299) {
                    String detail = response.text();
                    throw new Exception(detail.isEmpty() ? "HTTP " + response.status : detail);
                }
                if (cleanCache) pruneSkydnirBuildState();
                setStatus("Skydnir: deleted image " + ref);
                JSONArray images = listDockerImages();
                publishDockerImagesWithOptionalRefs(images);
            } catch (Exception e) {
                publishDockerImages(null, null, new JSONObject(), e.getMessage());
                setStatus("Skydnir: image delete failed");
            }
        });
    }
    private void pruneDockerBuildCache() {
        setStatus("Skydnir: pruning build cache");
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                pruneSkydnirBuildState();
                JSONArray images = listDockerImages();
                publishDockerImagesWithOptionalRefs(images);
                setStatus("Skydnir: build cache pruned");
            } catch (Exception e) {
                publishDockerImages(null, null, new JSONObject(), e.getMessage());
                setStatus("Skydnir: prune failed");
            }
        });
    }
    private void connectDockerContainer(int session, String containerId) {
        int s = clampSession(session);
        String cid = containerId == null ? "" : containerId.trim();
        if (cid.isEmpty()) return;
        connectGenerations[s]++;
        closeSocket(s);
        rememberTermuxConnected(s, false);
        backends[s] = "docker";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = cid;
        setStatus("Skydnir " + (s + 1) + ": connecting " + dockerEndpoint());
        writeTerminal(s, "\r\n[TermPort] Skydnir Engine exec " + cid + " via " + dockerEndpoint() + "...\r\n");
        io.execute(() -> {
            try {
                String execId = createDockerExec(cid);
                dockerExecIds[s] = execId;
                LocalSocket sock = startDockerExecStream(execId);
                engineSockets[s] = sock;
                socketOuts[s] = sock.getOutputStream();
                sendDockerResizeControl(s, rows[s], cols[s]);
                setStatus("Skydnir " + (s + 1) + ": connected");
                readLoop(s, sock, sock.getInputStream());
            } catch (Exception e) {
                setStatus("Skydnir " + (s + 1) + ": unavailable");
                writeTerminal(s, "[TermPort] Skydnir exec failed: " + e.getMessage() + "\r\n");
            }
        });
    }
    private void reconnectDockerSession(int session) {
        int s = clampSession(session);
        String cid = dockerContainerIds[s];
        if (cid != null && !cid.isEmpty()) connectDockerContainer(s, cid);
        else connectDockerFirstContainer(s);
    }
    private void connectDockerFirstContainer(int session) {
        int s = clampSession(session);
        connectGenerations[s]++;
        closeSocket(s);
        rememberTermuxConnected(s, false);
        backends[s] = "docker";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
        setStatus("Skydnir " + (s + 1) + ": connecting " + dockerEndpoint());
        writeTerminal(s, "\r\n[TermPort] Connecting to Skydnir Engine " + dockerEndpoint() + "...\r\n");
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                EngineResponse ping = dockerRequest("GET", "/_ping", null, 1500);
                if (ping.status < 200 || ping.status > 299) throw new Exception("/_ping HTTP " + ping.status);
                EngineResponse list = dockerRequest("GET", "/containers/json?all=1", null, 4000);
                if (list.status < 200 || list.status > 299) {
                    String detail = list.text();
                    throw new Exception(detail.isEmpty() ? "/containers/json HTTP " + list.status : detail);
                }
                JSONArray containers = new JSONArray(list.text());
                JSONObject container = null;
                for (int i = 0; i < containers.length(); i++) {
                    JSONObject candidate = containers.optJSONObject(i);
                    if (candidate != null && "running".equalsIgnoreCase(candidate.optString("State", ""))) {
                        container = candidate;
                        break;
                    }
                }
                if (container == null) {
                    setStatus("Skydnir " + (s + 1) + ": no running containers");
                    writeTerminal(s, "[TermPort] Skydnir Engine is reachable, but no running containers are available. Open Skydnir list to see stopped containers.\r\n");
                    return;
                }
                String containerId = container.getString("Id");
                JSONArray names = container.optJSONArray("Names");
                String name = names != null && names.length() > 0 ? names.optString(0, "").replaceFirst("^/", "") : "";
                if (name.isEmpty()) name = containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
                String execId = createDockerExec(containerId);
                dockerExecIds[s] = execId;
                dockerContainerIds[s] = containerId;
                LocalSocket sock = startDockerExecStream(execId);
                engineSockets[s] = sock;
                socketOuts[s] = sock.getOutputStream();
                setStatus("Skydnir " + (s + 1) + ": connected " + name);
                writeTerminal(s, "[TermPort] Connected to Skydnir container " + name + "\r\n");
                readLoop(s, sock, sock.getInputStream());
            } catch (Exception e) {
                setStatus("Skydnir " + (s + 1) + ": unavailable");
                writeTerminal(s, "[TermPort] Skydnir Engine unavailable: " + e.getMessage() + "\r\n");
            }
        });
    }
    private void sendDockerResizeControl(int session, int newRows, int newCols) {
        int s = clampSession(session);
        String execId = dockerExecIds[s];
        if (execId == null || execId.isEmpty()) return;
        int targetRows = Math.max(2, newRows);
        int targetCols = Math.max(2, newCols);
        io.execute(() -> {
            for (int attempt = 0; attempt < 6; attempt++) {
                try {
                    String path = "/exec/" + encodePath(execId) + "/resize?h=" + targetRows + "&w=" + targetCols;
                    dockerRequest("POST", path, null, 1200);
                    return;
                } catch (Exception ignored) {
                    try {
                        Thread.sleep(120L * (attempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
    private void sendResizeControl(int session, int newRows, int newCols) {
        int s = clampSession(session);
        int targetRows = Math.max(2, newRows);
        int targetCols = Math.max(2, newCols);
        io.execute(() -> {
            String command = "RESIZE " + targetRows + " " + targetCols + "\n";
            for (int attempt = 0; attempt < 6; attempt++) {
                try (Socket ctrl = new Socket()) {
                    ctrl.connect(new InetSocketAddress(HOST, portFor(s) + 1000), 500);
                    OutputStream out = ctrl.getOutputStream();
                    out.write(command.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                } catch (Exception ignored) {
                    try {
                        Thread.sleep(120L * (attempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
    private void writeSocket(int session, byte[] data) {
        int s = clampSession(session);
        io.execute(() -> {
            try {
                OutputStream out = socketOuts[s];
                if (out != null) {
                    out.write(data);
                    out.flush();
                } else {
                    writeTerminal(s, "not connected\r\n");
                    if ("docker".equals(backends[s])) reconnectDockerSession(s); else connectSession(s);
                }
            } catch (Exception e) {
                setStatus(statusPrefix(s) + ": disconnected");
            }
        });
    }
    private void setStatus(String text) {
        runOnUiThread(() -> webView.evaluateJavascript("window.setBridgeStatus && window.setBridgeStatus(" + jsQuote(text) + ")", null));
    }
    private void writeTerminal(int session, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writeTerminal(session, data, data.length);
    }
    private void writeTerminal(int session, byte[] data, int len) {
        int s = clampSession(session);
        byte[] copy = new byte[len];
        System.arraycopy(data, 0, copy, 0, len);
        appendRawCapture(s, copy);
        String b64 = Base64.encodeToString(copy, Base64.NO_WRAP);
        runOnUiThread(() -> webView.evaluateJavascript("window.terminalWriteBase64 && window.terminalWriteBase64(" + s + ", '" + b64 + "')", null));
    }
    private File rawCaptureFile(int session) {
        int s = clampSession(session);
        File dir = new File(getFilesDir(), "raw");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "session-" + s + ".bin");
    }
    private synchronized void appendRawCapture(int session, byte[] data) {
        if (data == null || data.length == 0) return;
        File target = rawCaptureFile(session);
        try (FileOutputStream out = new FileOutputStream(target, true)) {
            out.write(data);
        } catch (Exception ignored) {
            return;
        }
        if (target.length() <= RAW_CAPTURE_LIMIT) return;
        try (FileInputStream in = new FileInputStream(target); ByteArrayOutputStream all = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) all.write(buf, 0, n);
            byte[] bytes = all.toByteArray();
            int start = Math.max(0, bytes.length - RAW_CAPTURE_LIMIT);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes, start, bytes.length - start);
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream out = new FileOutputStream(target, false)) {
                    out.write(bytes, start, bytes.length - start);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
    private String jsQuote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }
    private File scrollbackFile(int session) {
        int s = clampSession(session);
        File dir = new File(getFilesDir(), "scrollback");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "session-" + s + ".json");
    }
    private synchronized void saveScrollbackFile(int session, String json) {
        int s = clampSession(session);
        String data = json == null ? "" : json;
        File target = scrollbackFile(s);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
    private String loadScrollbackFile(int session) {
        File file = scrollbackFile(session);
        if (!file.exists() || file.length() <= 0) return "";
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
    private String bridgeAssetBase64() throws Exception {
        if (bridgeAssetBase64 != null) return bridgeAssetBase64;
        try (InputStream in = getAssets().open("bridge.py"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            bridgeAssetBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return bridgeAssetBase64;
        }
    }
    private void startSkydnirService() {
        Intent intent = new Intent(this, SkydnirService.class).setAction(SkydnirService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }
    private void waitForSkydnirEngine(long timeoutMs) throws Exception {
        startSkydnirService();
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                EngineResponse ping = dockerRequest("GET", "/_ping", null, 1200);
                if (ping.status >= 200 && ping.status <= 299) return;
                last = new Exception("/_ping HTTP " + ping.status);
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(250);
        }
        throw last == null ? new Exception("Skydnir startup timeout") : last;
    }
    private File skydnirProjectDir() {
        try { SkydnirRuntime.prepare(this); } catch (Exception e) { Log.w(TAG, "prepare project failed", e); }
        return SkydnirRuntime.projectDir(this);
    }
    private String readTextFile(File file) {
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
    private boolean writeTextFile(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write failed " + file, e);
            return false;
        }
    }
    private String loadSkydnirProjectFiles() {
        File dir = skydnirProjectDir();
        try {
            return new JSONObject()
                    .put("dockerfile", readTextFile(new File(dir, "Dockerfile")))
                    .put("compose", readTextFile(new File(dir, "compose.yaml")))
                    .put("path", dir.getAbsolutePath())
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }
    private boolean saveSkydnirProjectFiles(String dockerfile, String compose) {
        File dir = skydnirProjectDir();
        boolean a = writeTextFile(new File(dir, "Dockerfile"), dockerfile);
        boolean b = writeTextFile(new File(dir, "compose.yaml"), compose);
        SkydnirRuntime.projectHomeDir(this);
        return a && b;
    }
    private String skydnirUserText(String text) {
        if (text == null) return "";
        return text.replace("pdockerd", "skydnird")
                .replace("pdocker", "Skydnir")
                .replace("Pdocker", "Skydnir")
                .replace("PDOCKER", "SKYDNIR");
    }
    private void writeTarString(ByteArrayOutputStream tar, String name, String text) throws Exception {
        byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        writeTarHeader(tar, name, data.length, false);
        tar.write(data);
        int pad = (512 - (data.length % 512)) % 512;
        for (int i = 0; i < pad; i++) tar.write(0);
    }
    private void writeTarHeader(ByteArrayOutputStream tar, String name, long size, boolean dir) throws Exception {
        byte[] header = new byte[512];
        writeTarField(header, 0, 100, name);
        writeTarOctal(header, 100, 8, dir ? 0755 : 0644);
        writeTarOctal(header, 108, 8, 0);
        writeTarOctal(header, 116, 8, 0);
        writeTarOctal(header, 124, 12, dir ? 0 : size);
        writeTarOctal(header, 136, 12, System.currentTimeMillis() / 1000L);
        for (int i = 148; i < 156; i++) header[i] = 0x20;
        header[156] = (byte) (dir ? '5' : '0');
        writeTarField(header, 257, 6, "ustar");
        writeTarField(header, 263, 2, "00");
        long sum = 0;
        for (byte b : header) sum += (b & 0xff);
        String oct = Long.toOctalString(sum);
        String chk = "000000".substring(0, Math.max(0, 6 - oct.length())) + oct;
        writeTarField(header, 148, 6, chk);
        header[154] = 0;
        header[155] = 0x20;
        tar.write(header);
    }
    private void writeTarField(byte[] header, int offset, int length, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        int n = Math.min(length, bytes.length);
        System.arraycopy(bytes, 0, header, offset, n);
    }
    private void writeTarOctal(byte[] header, int offset, int length, long value) throws Exception {
        String oct = Long.toOctalString(value);
        String padded = "000000000000" + oct;
        byte[] bytes = padded.substring(padded.length() - (length - 1)).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + length - 1] = 0;
    }
    private byte[] buildSkydnirContextTar() throws Exception {
        File dir = skydnirProjectDir();
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeTarString(tar, "Dockerfile", readTextFile(new File(dir, "Dockerfile")));
        writeTarString(tar, "compose.yaml", readTextFile(new File(dir, "compose.yaml")));
        writeTarHeader(tar, "home/", 0, true);
        tar.write(new byte[1024]);
        return tar.toByteArray();
    }
    private String terminalStreamText(String text) {
        String value = text == null ? "" : text;
        StringBuilder out = new StringBuilder(value.length() + 16);
        char prev = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' && prev != '\r') out.append('\r');
            out.append(ch);
            prev = ch;
        }
        return out.toString();
    }
    private void writeBuildStreamLine(int session, String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        String text = null;
        boolean rawStream = false;
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("stream")) {
                text = obj.optString("stream", "");
                rawStream = true;
            }
            else if (obj.has("status")) {
                String id = obj.optString("id", "");
                String progress = obj.optString("progress", "");
                String status = obj.optString("status", "");
                text = (id == null || id.isEmpty()) ? status : id + ": " + status;
                if (progress != null && !progress.isEmpty()) text += " " + progress;
            }
            else if (obj.has("error")) text = "ERROR: " + obj.optString("error", "");
            else if (obj.has("errorDetail")) {
                JSONObject detail = obj.optJSONObject("errorDetail");
                text = "ERROR: " + (detail == null ? trimmed : detail.optString("message", trimmed));
            }
        } catch (Exception ignored) {
            text = trimmed;
        }
        text = skydnirUserText(text == null ? "" : text);
        if (text.isEmpty()) return;
        if (rawStream) {
            writeTerminal(session, terminalStreamText(text));
            return;
        }
        text = text.replace("\r", "\n");
        if (text.trim().isEmpty()) return;
        writeTerminal(session, text.replace("\n", "\r\n"));
        if (!text.endsWith("\n")) writeTerminal(session, "\r\n");
    }
    private String dockerStreamError(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(trimmed);
            String error = obj.optString("error", "");
            if (!error.isEmpty()) return error;
            JSONObject detail = obj.optJSONObject("errorDetail");
            if (detail != null) {
                String message = detail.optString("message", "");
                if (!message.isEmpty()) return message;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int dockerBuildRequestStreaming(int session, byte[] tar) throws Exception {
        try (LocalSocket sock = openEngineSocket(600000)) {
            OutputStream out = sock.getOutputStream();
            byte[] requestBody = tar == null ? new byte[0] : tar;
            String head = "POST /build?t=termport-local:latest&dockerfile=Dockerfile HTTP/1.0\r\n"
                    + "Host: skydnir\r\n"
                    + "Connection: close\r\n"
                    + "Content-Type: application/x-tar\r\n"
                    + "Content-Length: " + requestBody.length + "\r\n"
                    + "\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(requestBody);
            out.flush();
            InputStream in = sock.getInputStream();
            String responseHead = readHttpHead(in);
            int status = httpStatus(responseHead);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        writeBuildStreamLine(session, line.toString(StandardCharsets.UTF_8.name()));
                        line.reset();
                    } else if (b != '\r') {
                        line.write(b);
                    }
                }
            }
            if (line.size() > 0) writeBuildStreamLine(session, line.toString(StandardCharsets.UTF_8.name()));
            return status;
        }
    }

    private void deleteSkydnirProjectContainer() {
        try { dockerRequest("DELETE", "/containers/termport-local?force=1", null, 3000); } catch (Exception ignored) {}
    }
    private JSONObject findSkydnirProjectContainer() throws Exception {
        JSONArray containers = listDockerContainers();
        for (int i = 0; i < containers.length(); i++) {
            JSONObject c = containers.optJSONObject(i);
            if (c == null) continue;
            if ("termport-local".equals(containerDisplayName(c))) return c;
        }
        return null;
    }
    private String skydnirContainerFailureDetail(JSONObject container) {
        if (container == null) return "container state is unavailable";
        String state = container.optString("State", "unknown");
        String status = container.optString("Status", "");
        String id = container.optString("Id", "");
        StringBuilder detail = new StringBuilder("container ").append(state);
        if (!status.isEmpty()) detail.append(" (").append(status).append(")");
        if (!id.isEmpty()) {
            try {
                EngineResponse logs = dockerRequest("GET", "/containers/" + encodePath(id) + "/logs?stdout=1&stderr=1&tail=20", null, 3000);
                String text = skydnirUserText(logs.text()).trim();
                if (!text.isEmpty()) detail.append(": ").append(text.replace('\r', '\n').replaceAll("\n+", " | "));
            } catch (Exception ignored) {}
        }
        return detail.toString();
    }
    private void verifySkydnirProjectContainerRunning(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 2500L;
        JSONObject last = null;
        while (System.currentTimeMillis() < deadline) {
            try { last = findSkydnirProjectContainer(); } catch (Exception ignored) {}
            if (last != null) {
                String state = last.optString("State", "");
                if ("running".equalsIgnoreCase(state)) return;
                if ("exited".equalsIgnoreCase(state) || "dead".equalsIgnoreCase(state)) {
                    throw new Exception(skydnirContainerFailureDetail(last));
                }
            }
            try { Thread.sleep(250L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        last = findSkydnirProjectContainer();
        if (last != null && "running".equalsIgnoreCase(last.optString("State", ""))) return;
        throw new Exception(skydnirContainerFailureDetail(last));
    }
    private void deleteSkydnirProjectImage() {
        try { dockerRequest("DELETE", "/images/termport-local:latest?force=1", null, 8000); } catch (Exception ignored) {}
    }
    private void pruneSkydnirBuildState() {
        try { dockerRequest("POST", "/build/prune", null, 10000); } catch (Exception ignored) {}
        try { dockerRequest("POST", "/system/prune", null, 20000); } catch (Exception ignored) {}
    }
    private void buildSkydnirProject(int session) {
        int s = clampSession(session);
        setStatus("Skydnir: starting");
        writeTerminal(s, "\r\n[TermPort] Skydnir build: preparing engine...\r\n");
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                writeTerminal(s, "[TermPort] Skydnir build: removing previous image/layers...\r\n");
                deleteSkydnirProjectImage();
                pruneSkydnirBuildState();
                byte[] tar = buildSkydnirContextTar();
                int status = dockerBuildRequestStreaming(s, tar);
                if (status >= 200 && status <= 299) setStatus("Skydnir: build complete");
                else setStatus("Skydnir: build failed HTTP " + status);
            } catch (Exception e) {
                setStatus("Skydnir: build failed");
                writeTerminal(s, "[TermPort] Skydnir build failed: " + skydnirUserText(e.getMessage()) + "\r\n");
            }
        });
    }
    private String createSkydnirProjectContainer() throws Exception {
        File project = SkydnirRuntime.projectDir(this);
        File home = SkydnirRuntime.projectHomeDir(this);
        JSONArray binds = new JSONArray()
                .put(project.getAbsolutePath() + ":/workspace")
                .put(home.getAbsolutePath() + ":/root");
        JSONObject payload = new JSONObject()
                .put("Image", "termport-local:latest")
                .put("Tty", true)
                .put("OpenStdin", true)
                .put("AttachStdin", true)
                .put("AttachStdout", true)
                .put("AttachStderr", true)
                .put("WorkingDir", "/workspace")
                .put("Env", new JSONArray(Arrays.asList(
                        "HOME=/root",
                        "TERM=xterm-256color",
                        "JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64",
                        "ANDROID_HOME=/opt/android-sdk",
                        "ANDROID_SDK_ROOT=/opt/android-sdk",
                        "ANDROID_NDK_HOME=/opt/android-sdk/ndk/26.3.11579264",
                        "PATH=/usr/lib/jvm/java-21-openjdk-arm64/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                        "LD_LIBRARY_PATH=/usr/lib/jvm/java-21-openjdk-arm64/lib:/usr/lib/jvm/java-21-openjdk-arm64/lib/server",
                        "JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
                )))
                .put("Cmd", new JSONArray(Arrays.asList("/bin/sh", "-lc", "while :; do sleep 3600; done")))
                .put("Labels", new JSONObject().put("io.github.ryo100794.termport.project", "termport-local"))
                .put("HostConfig", new JSONObject().put("Binds", binds));
        EngineResponse response = dockerRequest("POST", "/containers/create?name=termport-local", payload.toString().getBytes(StandardCharsets.UTF_8), 15000);
        if (response.status < 200 || response.status > 299) throw new Exception(response.text());
        return new JSONObject(response.text()).getString("Id");
    }
    private void runSkydnirProject(int session, boolean connectAfterStart) {
        int s = clampSession(session);
        setStatus("Skydnir: run");
        writeTerminal(s, "\r\n[TermPort] Skydnir run: preparing engine...\r\n");
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                JSONObject existing = findSkydnirProjectContainer();
                String id;
                if (existing != null) {
                    id = existing.optString("Id", "");
                    String state = existing.optString("State", "");
                    if (!"running".equalsIgnoreCase(state)) {
                        EngineResponse start = dockerRequest("POST", "/containers/" + encodePath(id) + "/start", null, 20000);
                        if (start.status < 200 || start.status > 299) throw new Exception(start.text());
                        verifySkydnirProjectContainerRunning(id);
                    }
                } else {
                    id = createSkydnirProjectContainer();
                    EngineResponse start = dockerRequest("POST", "/containers/" + encodePath(id) + "/start", null, 20000);
                    if (start.status < 200 || start.status > 299) throw new Exception(start.text());
                    verifySkydnirProjectContainerRunning(id);
                }
                setStatus("Skydnir: container running");
                writeTerminal(s, "[TermPort] Skydnir container ready: " + id.substring(0, Math.min(12, id.length())) + "\r\n");
                if (connectAfterStart) runOnUiThread(() -> connectDockerContainer(s, id));
            } catch (Exception e) {
                setStatus("Skydnir: run failed");
                writeTerminal(s, "[TermPort] Skydnir run failed: " + skydnirUserText(e.getMessage()) + "\r\n");
            }
        });
    }
    private void connectSkydnirProject(int session) {
        int s = clampSession(session);
        io.execute(() -> {
            try {
                waitForSkydnirEngine(30000);
                JSONObject c = findSkydnirProjectContainer();
                if (c != null && "running".equalsIgnoreCase(c.optString("State", ""))) {
                    runOnUiThread(() -> connectDockerContainer(s, c.optString("Id")));
                    return;
                }
                runOnUiThread(() -> runSkydnirProject(s, true));
            } catch (Exception e) {
                setStatus("Skydnir: unavailable");
                writeTerminal(s, "[TermPort] Skydnir connect failed: " + skydnirUserText(e.getMessage()) + "\r\n");
            }
        });
    }
    public class Bridge {
        @JavascriptInterface
        public void writeBase64(int session, String b64) {
            writeSocket(session, Base64.decode(b64, Base64.DEFAULT));
        }
        @JavascriptInterface
        public void connectSession(int session) {
            MainActivity.this.connectSession(session);
        }
        @JavascriptInterface
        public void connectSkydnir(int session) {
            MainActivity.this.connectSkydnirProject(session);
        }
        @JavascriptInterface
        public void openSkydnirTerminal() {
            MainActivity.this.connectSkydnirProject(0);
        }
        @JavascriptInterface
        public void refreshDockerContainers() {
            MainActivity.this.refreshDockerContainers();
        }
        @JavascriptInterface
        public void refreshDockerImages() {
            MainActivity.this.refreshDockerImages();
        }
        @JavascriptInterface
        public String debugDockerRequest(String path) {
            return MainActivity.this.debugDockerRequestJson(path);
        }
        @JavascriptInterface
        public void deleteDockerImage(String imageRef) {
            MainActivity.this.deleteDockerImage(imageRef);
        }
        @JavascriptInterface
        public void pullDockerImage(int session, String imageRef) {
            MainActivity.this.pullDockerImage(session, imageRef);
        }
        @JavascriptInterface
        public void pullDockerImageWithPlatform(int session, String imageRef, String platform) {
            MainActivity.this.pullDockerImage(session, imageRef, platform);
        }
        @JavascriptInterface
        public String imagePullPlatform() {
            return MainActivity.this.currentImagePlatform();
        }
        @JavascriptInterface
        public String dockerImagePullSuggestions() {
            return MainActivity.this.dockerImagePullSuggestionsJson();
        }
        @JavascriptInterface
        public String fetchDockerHubImageRefs(String query) {
            return MainActivity.this.fetchDockerHubImageRefsJson(query);
        }
        @JavascriptInterface
        public void cleanDockerImage(String imageRef) {
            MainActivity.this.cleanDockerImage(imageRef);
        }
        @JavascriptInterface
        public void pruneDockerBuildCache() {
            MainActivity.this.pruneDockerBuildCache();
        }
        @JavascriptInterface
        public String listDockerImageFiles(String imageRef, String path) {
            return MainActivity.this.listDockerImageFilesJson(imageRef, path);
        }
        @JavascriptInterface
        public String readDockerImageFile(String imageRef, String path) {
            return MainActivity.this.readDockerImageFileJson(imageRef, path);
        }
        @JavascriptInterface
        public String copyDockerImageFileToProject(String imageRef, String path) {
            return MainActivity.this.copyDockerImageFileToProjectJson(imageRef, path);
        }
        @JavascriptInterface
        public void connectDockerContainer(int session, String containerId) {
            MainActivity.this.connectDockerContainer(session, containerId);
        }
        @JavascriptInterface
        public String loadSkydnirProjectFiles() {
            return MainActivity.this.loadSkydnirProjectFiles();
        }
        @JavascriptInterface
        public boolean saveSkydnirProjectFiles(String dockerfile, String compose) {
            return MainActivity.this.saveSkydnirProjectFiles(dockerfile, compose);
        }
        @JavascriptInterface
        public void buildSkydnirProject(int session) {
            MainActivity.this.buildSkydnirProject(session);
        }
        @JavascriptInterface
        public void runSkydnirProject(int session) {
            MainActivity.this.runSkydnirProject(session, false);
        }
        @JavascriptInterface
        public String dockerEndpoint() {
            return MainActivity.this.dockerEndpoint();
        }
        @JavascriptInterface
        public float webViewCssHeight() {
            return MainActivity.this.webViewCssHeight();
        }

        @JavascriptInterface
        public float visibleDisplayCssHeight() {
            return MainActivity.this.visibleDisplayCssHeight();
        }
        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("TermPort", text));
        }
        @JavascriptInterface
        public void resize(int session, int newRows, int newCols) {
            int s = clampSession(session);
            rows[s] = Math.max(2, newRows);
            cols[s] = Math.max(2, newCols);
            if ("docker".equals(backends[s])) sendDockerResizeControl(s, rows[s], cols[s]);
            else sendResizeControl(s, rows[s], cols[s]);
        }
        @JavascriptInterface
        public void saveScrollback(int session, String json) {
            saveScrollbackFile(session, json);
        }
        @JavascriptInterface
        public String loadScrollback(int session) {
            return loadScrollbackFile(session);
        }
    }
}
