package io.github.ryo100794.termport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;

final class SkydnirRuntime {
    private static final String TAG = "SkydnirRuntime";
    private static final String LEGACY_DEFAULT_DOCKERFILE = "FROM alpine:3.20\n"
            + "RUN adduser -D -h /home/termport termport\n"
            + "WORKDIR /workspace\n"
            + "ENV HOME=/home/termport TERM=xterm-256color\n"
            + "CMD [\"/bin/sh\", \"-l\"]\n";
    static final int ENGINE_SOCKET_ID = 12375;
    private static final String FALLBACK_RESOLV_CONF = "nameserver 8.8.8.8\n"
            + "nameserver 1.1.1.1\n";

    private SkydnirRuntime() {}

    static File homeDir(Context context) {
        File dir = new File(context.getFilesDir(), "skydnir");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File projectDir(Context context) {
        File dir = new File(homeDir(context), "project");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File projectHomeDir(Context context) {
        File dir = new File(projectDir(context), "home");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File socketFile(Context context) {
        return new File(homeDir(context), "engine-" + ENGINE_SOCKET_ID + ".sock");
    }

    static File prepare(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "skydnir-runtime");
        File bin = mkdir(root, "bin");
        File dockerBin = mkdir(root, "docker-bin");
        File gpu = mkdir(root, "gpu");
        File media = mkdir(root, "media");
        File lib = mkdir(root, "lib");
        mkdir(root, "tmp");
        File etc = mkdir(root, "etc");

        extractAsset(context, "skydnir/skydnird", new File(bin, "skydnird"), true);
        extractAsset(context, "skydnir/llama-gpu-env-manifest.json", new File(bin, "llama-gpu-env-manifest.json"), true);
        ensureProjectFile(context, "Dockerfile");
        ensureProjectFile(context, "compose.yaml");
        projectHomeDir(context);

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        optionalLink(new File(nativeDir, "libcrane.so"), new File(dockerBin, "crane"));
        optionalLink(new File(nativeDir, "libskydnirdirect.so"), new File(dockerBin, "skydnir-direct"));
        Files.deleteIfExists(new File(dockerBin, "skydnir-ld-musl-aarch64").toPath());
        Files.deleteIfExists(new File(dockerBin, "libskydnirldmusl.so").toPath());
        Files.deleteIfExists(new File(dockerBin, "libskydnir-ld-musl-aarch64.so").toPath());
        optionalLink(new File(nativeDir, "libpdocker-ld-linux-aarch64.so"), new File(dockerBin, "pdocker-ld-linux-aarch64"));
        Files.deleteIfExists(new File(dockerBin, "libpdocker-ld-linux-aarch64.so").toPath());
        Files.deleteIfExists(new File(dockerBin, "pdocker-direct").toPath());
        optionalLink(new File(nativeDir, "libskydnirgpuexecutor.so"), new File(gpu, "skydnir-gpu-executor"));
        optionalLink(new File(nativeDir, "libskydnirmediaexecutor.so"), new File(media, "skydnir-media-executor"));
        optionalLink(new File(nativeDir, "libcow.so"), new File(lib, "libcow.so"));
        optionalLink(new File(nativeDir, "libskydnirgpushim.so"), new File(lib, "skydnir-gpu-shim"));
        optionalLink(new File(nativeDir, "libskydnirvulkanicd.so"), new File(lib, "skydnir-vulkan-icd.so"));
        optionalLink(new File(nativeDir, "libskydniropenclicd.so"), new File(lib, "skydnir-opencl-icd.so"));
        optionalLink(new File(nativeDir, "libskydniropenclicd.so"), new File(lib, "libOpenCL.so"));
        optionalLink(new File(nativeDir, "libskydniropenclicd.so"), new File(lib, "libOpenCL.so.1"));
        writeIfChanged(new File(etc, "resolv.conf"), androidResolvConf(context));
        return root;
    }

    private static File mkdir(File root, String name) {
        File dir = new File(root, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void ensureProjectFile(Context context, String name) throws Exception {
        File dst = new File(projectDir(context), name);
        if (dst.exists() && dst.length() > 0) {
            if ("Dockerfile".equals(name)) {
                String current = new String(Files.readAllBytes(dst.toPath()), StandardCharsets.UTF_8);
                if (LEGACY_DEFAULT_DOCKERFILE.equals(current)) {
                    extractAsset(context, "skydnir/project/" + name, dst, true);
                }
            }
            return;
        }
        extractAsset(context, "skydnir/project/" + name, dst, false);
    }

    private static void writeIfChanged(File dst, String content) throws Exception {
        if (!dst.exists() || !new String(Files.readAllBytes(dst.toPath()), StandardCharsets.UTF_8).equals(content)) {
            try (FileOutputStream out = new FileOutputStream(dst, false)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "wrote " + content.length() + " bytes to " + dst);
        }
    }

    private static String androidResolvConf(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return FALLBACK_RESOLV_CONF;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return FALLBACK_RESOLV_CONF;
        LinkedHashSet<String> servers = new LinkedHashSet<>();
        Network active = manager.getActiveNetwork();
        if (active != null) appendDnsServers(manager.getLinkProperties(active), servers);
        for (Network network : manager.getAllNetworks()) {
            if (active != null && active.equals(network)) continue;
            appendDnsServers(manager.getLinkProperties(network), servers);
        }
        if (servers.isEmpty()) return FALLBACK_RESOLV_CONF;
        StringBuilder out = new StringBuilder();
        for (String server : servers) out.append("nameserver ").append(server).append('\n');
        return out.toString();
    }

    private static void appendDnsServers(LinkProperties props, LinkedHashSet<String> servers) {
        if (props == null) return;
        for (java.net.InetAddress addr : props.getDnsServers()) {
            String host = addr.getHostAddress();
            if (host == null) continue;
            int zone = host.indexOf('%');
            if (zone >= 0) host = host.substring(0, zone);
            if (host.length() == 0 || "::1".equals(host) || "127.0.0.1".equals(host)) continue;
            servers.add(host);
        }
    }

    private static void extractAsset(Context context, String asset, File dst, boolean force) throws Exception {
        if (!force && dst.exists()) return;
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        dst.setReadable(true, false);
        dst.setExecutable(true, false);
    }

    private static void optionalLink(File target, File link) throws Exception {
        if (!target.exists()) {
            Files.deleteIfExists(link.toPath());
            return;
        }
        Files.deleteIfExists(link.toPath());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.createSymbolicLink(link.toPath(), target.toPath());
                return;
            }
        } catch (Exception e) {
            Log.i(TAG, "symlink fallback for " + link + ": " + e.getMessage());
        }
        try (InputStream in = Files.newInputStream(target.toPath()); FileOutputStream out = new FileOutputStream(link, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        link.setReadable(true, false);
        link.setExecutable(true, false);
    }
}
