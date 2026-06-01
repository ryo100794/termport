package io.github.ryo100794.termport;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOST = "127.0.0.1";
    private static final int BASE_PORT = 8765;
    private static final String DEFAULT_DOCKER_API_HOST = "127.0.0.1";
    private static final int DEFAULT_DOCKER_API_PORT = 2375;
    private static final String PREF_DOCKER_ENDPOINT = "docker_endpoint";
    private static final int MAX_SESSIONS = 8;
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_SETUP_COMMAND = "mkdir -p ~/.termux && "
            + "grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null "
            + "|| printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties; "
            + "termux-reload-settings; pkg install -y python";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Socket[] sockets = new Socket[MAX_SESSIONS];
    private final OutputStream[] socketOuts = new OutputStream[MAX_SESSIONS];
    private final String[] backends = new String[]{"termux", "termux", "termux", "termux", "termux", "termux", "termux", "termux"};
    private final String[] dockerExecIds = new String[MAX_SESSIONS];
    private final String[] dockerContainerIds = new String[MAX_SESSIONS];
    private final int[] rows = new int[]{32, 32, 32, 32, 32, 32, 32, 32};
    private final int[] cols = new int[]{100, 100, 100, 100, 100, 100, 100, 100};
    private WebView webView;
    private String bridgeAssetBase64;
    private String dockerApiHost = DEFAULT_DOCKER_API_HOST;
    private int dockerApiPort = DEFAULT_DOCKER_API_PORT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadDockerEndpoint();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                startInitialSessions();
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/xterm/index.html");
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

    private boolean ensureRunCommandPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{RUN_COMMAND_PERMISSION}, 7);
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

    private void startInitialSessions() {
        if (!isTermuxInstalled()) {
            showSetupHelp("Termux is not installed");
            return;
        }
        if (!ensureRunCommandPermission()) {
            setStatus("Allow Termux connection permission");
            return;
        }
        connectSession(0);
        connectSession(1);
    }

    private void showSetupHelp(String reason) {
        setStatus(reason);
        writeTerminal(0, "TermPort setup\r\n"
                + reason + "\r\n\r\n"
                + "In Termux, run this once:\r\n"
                + TERMUX_SETUP_COMMAND + "\r\n\r\n"
                + "If auto-start cannot continue, tap Setup, paste in Termux, then restart TermPort.\r\n");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startInitialSessions();
        } else if (requestCode == 7) {
            showSetupHelp("RUN_COMMAND permission was denied");
        }
    }

    private int clampSession(int session) {
        return Math.max(0, Math.min(session, MAX_SESSIONS - 1));
    }

    private int portFor(int session) {
        return BASE_PORT + clampSession(session);
    }

    private void startTermuxBridge(int session) {
        int s = clampSession(session);
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setComponent(new ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService"));
            String prefix = "/data/data/com.termux/files/usr";
            String home = "/data/data/com.termux/files/home";
            intent.putExtra("com.termux.RUN_COMMAND_PATH", prefix + "/bin/sh");
            String bootstrap = "PREFIX=" + prefix + " HOME=" + home
                    + " PATH=" + prefix + "/bin:/system/bin:/system/xbin"
                    + " IME_CONSOLE_PORT=" + portFor(s)
                    + " IME_CONSOLE_ROWS=" + rows[s]
                    + " IME_CONSOLE_COLS=" + cols[s]
                    + " sh -lc 'mkdir -p \"$HOME/.ime-console\""
                    + " && if [ ! -x \"$PREFIX/bin/python\" ] && command -v pkg >/dev/null 2>&1; then pkg install -y python; fi"
                    + " && if [ ! -x \"$PREFIX/bin/python\" ]; then echo python missing; exit 127; fi"
                    + " && \"$PREFIX/bin/python\" -c \"import base64,pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(base64.b64decode('" + bridgeAssetBase64() + "'))\" \"$HOME/.ime-console/bridge.py\""
                    + " && chmod 700 \"$HOME/.ime-console/bridge.py\""
                    + " && exec \"$PREFIX/bin/python\" \"$HOME/.ime-console/bridge.py\"'";
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", bootstrap});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(intent);
            setStatus("Session " + (s + 1) + ": starting");
        } catch (SecurityException e) {
            showSetupHelp("Termux external command access is not enabled");
        } catch (Exception e) {
            setStatus("Termux auto-start failed");
            writeTerminal(s, "Termux auto-start failed: " + e + "\r\n");
            showSetupHelp("Termux auto-start failed");
        }
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
        if (!forceReconnect && isSocketOpen(s)) {
            setStatus("Session " + (s + 1) + ": connected");
            return;
        }
        closeSocket(s);
        backends[s] = "termux";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
        startTermuxBridge(s);
        setStatus("Session " + (s + 1) + ": connecting " + HOST + ":" + portFor(s));
        io.execute(() -> {
            try {
                Socket sock = new Socket();
                sock.connect(new InetSocketAddress(HOST, portFor(s)), 1500);
                sockets[s] = sock;
                socketOuts[s] = sock.getOutputStream();
                setStatus("Session " + (s + 1) + ": connected");
                readLoop(s, sock, sock.getInputStream());
            } catch (Exception e) {
                setStatus("Session " + (s + 1) + ": disconnected");
                closeSocket(s);
            }
        });
    }

    private void readLoop(int session, Socket sock, InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) writeTerminal(session, buf, n);
            }
        } finally {
            closeSocketIfCurrent(session, sock);
            setStatus(statusPrefix(session) + ": disconnected");
        }
    }

    private void closeSocket(int session) {
        int s = clampSession(session);
        try {
            if (sockets[s] != null) sockets[s].close();
        } catch (Exception ignored) {
        }
        sockets[s] = null;
        socketOuts[s] = null;
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
    }

    private void closeSocketIfCurrent(int session, Socket sock) {
        int s = clampSession(session);
        if (sockets[s] != sock) return;
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
        return "docker".equals(backends[s]) ? "Docker " + (s + 1) : "Session " + (s + 1);
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

    private EngineResponse dockerRequest(String method, String path, byte[] body, int timeoutMs) throws Exception {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(dockerApiHost, dockerApiPort), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            OutputStream out = sock.getOutputStream();
            byte[] requestBody = body == null ? new byte[0] : body;
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: docker\r\n");
            head.append("Connection: close\r\n");
            if (requestBody.length > 0) {
                head.append("Content-Type: application/json\r\n");
                head.append("Content-Length: ").append(requestBody.length).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (requestBody.length > 0) out.write(requestBody);
            out.flush();
            InputStream in = sock.getInputStream();
            String responseHead = readHttpHead(in);
            return new EngineResponse(httpStatus(responseHead), readRemaining(in));
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

    private Socket startDockerExecStream(String execId) throws Exception {
        JSONObject payload = new JSONObject().put("Detach", false).put("Tty", true);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(dockerApiHost, dockerApiPort), 5000);
        sock.setSoTimeout(5000);
        String head = "POST /exec/" + encodePath(execId) + "/start HTTP/1.1\r\n"
                + "Host: docker\r\n"
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
        return dockerApiHost + ":" + dockerApiPort;
    }

    private float webViewCssHeight() {
        if (webView == null) return 0f;
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        return webView.getHeight() / density;
    }

    private void loadDockerEndpoint() {
        String saved = getPreferences(MODE_PRIVATE).getString(PREF_DOCKER_ENDPOINT, DEFAULT_DOCKER_API_HOST + ":" + DEFAULT_DOCKER_API_PORT);
        setDockerEndpointInternal(saved, false);
    }

    private boolean setDockerEndpointInternal(String endpoint, boolean persist) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) value = DEFAULT_DOCKER_API_HOST + ":" + DEFAULT_DOCKER_API_PORT;
        value = value.replaceFirst("^tcp://", "").replaceFirst("^http://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        String host = value;
        int port = DEFAULT_DOCKER_API_PORT;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            host = value.substring(0, colon);
            try {
                port = Integer.parseInt(value.substring(colon + 1));
            } catch (Exception ignored) {
                return false;
            }
        }
        if (host.isEmpty() || port <= 0 || port > 65535) return false;
        dockerApiHost = host;
        dockerApiPort = port;
        if (persist) getPreferences(MODE_PRIVATE).edit().putString(PREF_DOCKER_ENDPOINT, dockerEndpoint()).apply();
        return true;
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
        EngineResponse list = dockerRequest("GET", "/containers/json?all=0", null, 4000);
        if (list.status < 200 || list.status > 299) {
            String detail = list.text();
            throw new Exception(detail.isEmpty() ? "/containers/json HTTP " + list.status : detail);
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
        setStatus("Docker: listing " + dockerEndpoint());
        io.execute(() -> {
            try {
                JSONArray containers = listDockerContainers();
                publishDockerContainers(containers, null);
                setStatus("Docker: " + containers.length() + " running containers");
            } catch (Exception e) {
                publishDockerContainers(new JSONArray(), e.getMessage());
                setStatus("Docker: unavailable " + dockerEndpoint());
            }
        });
    }

    private void connectDockerContainer(int session, String containerId) {
        int s = clampSession(session);
        String cid = containerId == null ? "" : containerId.trim();
        if (cid.isEmpty()) return;
        closeSocket(s);
        backends[s] = "docker";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = cid;
        setStatus("Docker " + (s + 1) + ": connecting " + dockerEndpoint());
        writeTerminal(s, "\r\n[TermPort] Docker API exec " + cid + " via " + dockerEndpoint() + "...\r\n");
        io.execute(() -> {
            try {
                String execId = createDockerExec(cid);
                dockerExecIds[s] = execId;
                Socket sock = startDockerExecStream(execId);
                sockets[s] = sock;
                socketOuts[s] = sock.getOutputStream();
                sendDockerResizeControl(s, rows[s], cols[s]);
                setStatus("Docker " + (s + 1) + ": connected");
                readLoop(s, sock, sock.getInputStream());
            } catch (Exception e) {
                setStatus("Docker " + (s + 1) + ": unavailable");
                writeTerminal(s, "[TermPort] Docker exec failed: " + e.getMessage() + "\r\n");
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
        closeSocket(s);
        backends[s] = "docker";
        dockerExecIds[s] = null;
        dockerContainerIds[s] = null;
        setStatus("Docker " + (s + 1) + ": connecting " + dockerEndpoint());
        writeTerminal(s, "\r\n[TermPort] Connecting to Docker Engine API " + dockerEndpoint() + "...\r\n");
        io.execute(() -> {
            try {
                EngineResponse ping = dockerRequest("GET", "/_ping", null, 1500);
                if (ping.status < 200 || ping.status > 299) throw new Exception("/_ping HTTP " + ping.status);
                EngineResponse list = dockerRequest("GET", "/containers/json?all=0", null, 4000);
                if (list.status < 200 || list.status > 299) {
                    String detail = list.text();
                    throw new Exception(detail.isEmpty() ? "/containers/json HTTP " + list.status : detail);
                }
                JSONArray containers = new JSONArray(list.text());
                if (containers.length() == 0) {
                    setStatus("Docker " + (s + 1) + ": no running containers");
                    writeTerminal(s, "[TermPort] Docker API is reachable, but no running containers were found.\r\n");
                    return;
                }
                JSONObject container = containers.getJSONObject(0);
                String containerId = container.getString("Id");
                JSONArray names = container.optJSONArray("Names");
                String name = names != null && names.length() > 0 ? names.optString(0, "").replaceFirst("^/", "") : "";
                if (name.isEmpty()) name = containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
                String execId = createDockerExec(containerId);
                dockerExecIds[s] = execId;
                dockerContainerIds[s] = containerId;
                Socket sock = startDockerExecStream(execId);
                sockets[s] = sock;
                socketOuts[s] = sock.getOutputStream();
                setStatus("Docker " + (s + 1) + ": connected " + name);
                writeTerminal(s, "[TermPort] Connected to Docker container " + name + "\r\n");
                readLoop(s, sock, sock.getInputStream());
            } catch (Exception e) {
                setStatus("Docker " + (s + 1) + ": unavailable");
                writeTerminal(s, "[TermPort] Docker API unavailable: " + e.getMessage() + "\r\n");
            }
        });
    }

    private void sendDockerResizeControl(int session, int newRows, int newCols) {
        int s = clampSession(session);
        String execId = dockerExecIds[s];
        if (execId == null || execId.isEmpty()) return;
        io.execute(() -> {
            try {
                dockerRequest("POST", "/exec/" + encodePath(execId) + "/resize?h=" + Math.max(2, newRows) + "&w=" + Math.max(2, newCols), null, 1200);
            } catch (Exception ignored) {
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
        String b64 = Base64.encodeToString(copy, Base64.NO_WRAP);
        runOnUiThread(() -> webView.evaluateJavascript("window.terminalWriteBase64 && window.terminalWriteBase64(" + s + ", '" + b64 + "')", null));
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
        public void copyTermuxSetup() {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("TermPort setup", TERMUX_SETUP_COMMAND));
            setStatus("Setup command copied");
        }

        @JavascriptInterface
        public void openTermux() {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } else {
                    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TERMUX_PACKAGE));
                    startActivity(view);
                }
            } catch (Exception e) {
                setStatus("Could not open Termux");
            }
        }

        @JavascriptInterface
        public String termuxSetupCommand() {
            return TERMUX_SETUP_COMMAND;
        }

        @JavascriptInterface
        public void connectSkydnir(int session) {
            MainActivity.this.connectDockerFirstContainer(session);
        }

        @JavascriptInterface
        public void openSkydnirTerminal() {
            MainActivity.this.connectDockerFirstContainer(0);
        }

        @JavascriptInterface
        public void refreshDockerContainers() {
            MainActivity.this.refreshDockerContainers();
        }

        @JavascriptInterface
        public void connectDockerContainer(int session, String containerId) {
            MainActivity.this.connectDockerContainer(session, containerId);
        }

        @JavascriptInterface
        public void setDockerEndpoint(String endpoint) {
            if (setDockerEndpointInternal(endpoint, true)) {
                setStatus("Docker endpoint " + dockerEndpoint());
            } else {
                setStatus("Invalid Docker endpoint");
            }
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
