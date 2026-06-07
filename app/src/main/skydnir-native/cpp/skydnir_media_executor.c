#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define PDOCKER_MEDIA_COMMAND_API "pdocker-media-command-v1"
#define PDOCKER_MEDIA_ABI_VERSION "0.1"

static volatile sig_atomic_t keep_running = 1;

static void on_signal(int signo) {
    (void)signo;
    keep_running = 0;
}

static void json_write(int fd, const char *fmt, ...) {
    char buf[8192];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if ((size_t)n >= sizeof(buf)) n = (int)sizeof(buf) - 1;
    (void)write(fd, buf, (size_t)n);
    (void)write(fd, "\n", 1);
}

static bool contains(const char *haystack, const char *needle) {
    return haystack && needle && strstr(haystack, needle) != NULL;
}

static bool file_exists(const char *path) {
    return path && path[0] && access(path, R_OK) == 0;
}

static const char *command_name(const char *request) {
    if (contains(request, "capabilities")) return "capabilities";
    if (contains(request, "probe")) return "probe";
    if (contains(request, "open-camera")) return "open-camera";
    if (contains(request, "open-audio-capture")) return "open-audio-capture";
    if (contains(request, "open-audio-playback")) return "open-audio-playback";
    if (contains(request, "start") || contains(request, "stop")) return "stream-control";
    return "hello";
}

static void reply_hello(int fd, const char *descriptor_path) {
    json_write(
        fd,
        "{\"executor\":\"pdocker-media-executor\","
        "\"api\":\"%s\",\"abi_version\":\"%s\","
        "\"role\":\"android-media-command-executor\","
        "\"contract\":\"linux-like-socket-env-v1\","
        "\"raw_device_passthrough\":false,"
        "\"descriptor_path\":\"%s\","
        "\"descriptor_available\":%s,"
        "\"capture_ready\":false,\"camera_ready\":false,\"audio_ready\":false,"
        "\"commands\":[\"hello\",\"capabilities\",\"probe\","
        "\"open-camera\",\"open-audio-capture\",\"open-audio-playback\"]}",
        PDOCKER_MEDIA_COMMAND_API,
        PDOCKER_MEDIA_ABI_VERSION,
        descriptor_path ? descriptor_path : "",
        file_exists(descriptor_path) ? "true" : "false");
}

static void reply_capabilities(int fd, const char *descriptor_path) {
    json_write(
        fd,
        "{\"executor\":\"pdocker-media-executor\","
        "\"api\":\"%s\",\"abi_version\":\"%s\","
        "\"valid\":true,"
        "\"descriptor_path\":\"%s\","
        "\"descriptor_available\":%s,"
        "\"raw_device_passthrough\":false,"
        "\"video_api\":\"android-camera2\","
        "\"audio_api\":\"android-audiorecord-audiotrack\","
        "\"audio_device_api\":\"android-audiomanager\","
        "\"targets\":[\"video.camera2\",\"camera.front\",\"camera.rear\","
        "\"camera.external\",\"audio.capture\",\"audio.playback\","
        "\"audio.usb.multichannel\"]}",
        PDOCKER_MEDIA_COMMAND_API,
        PDOCKER_MEDIA_ABI_VERSION,
        descriptor_path ? descriptor_path : "",
        file_exists(descriptor_path) ? "true" : "false");
}

static void reply_not_ready(int fd, const char *cmd) {
    json_write(
        fd,
        "{\"executor\":\"pdocker-media-executor\","
        "\"api\":\"%s\",\"abi_version\":\"%s\","
        "\"valid\":false,"
        "\"command\":\"%s\","
        "\"stage\":\"android-framework-broker\","
        "\"error\":\"capture-playback-not-implemented\","
        "\"raw_device_passthrough\":false,"
        "\"capture_ready\":false,\"camera_ready\":false,\"audio_ready\":false}",
        PDOCKER_MEDIA_COMMAND_API,
        PDOCKER_MEDIA_ABI_VERSION,
        cmd ? cmd : "unknown");
}

static void handle_client(int fd, const char *descriptor_path) {
    char request[4096];
    ssize_t n = read(fd, request, sizeof(request) - 1);
    if (n < 0) {
        reply_not_ready(fd, "read-error");
        return;
    }
    request[n > 0 ? n : 0] = '\0';
    const char *cmd = command_name(request);
    if (strcmp(cmd, "hello") == 0) {
        reply_hello(fd, descriptor_path);
    } else if (strcmp(cmd, "capabilities") == 0 || strcmp(cmd, "probe") == 0) {
        reply_capabilities(fd, descriptor_path);
    } else {
        reply_not_ready(fd, cmd);
    }
}

static int serve_socket(const char *socket_path, const char *descriptor_path) {
    if (!socket_path || !socket_path[0]) {
        fprintf(stderr, "pdocker-media-executor: missing socket path\n");
        return 2;
    }
    if (strlen(socket_path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "pdocker-media-executor: socket path too long: %s\n", socket_path);
        return 2;
    }

    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("pdocker-media-executor: socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", socket_path);
    unlink(socket_path);
    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        perror("pdocker-media-executor: bind");
        close(server_fd);
        return 1;
    }
    chmod(socket_path, 0660);
    if (listen(server_fd, 8) != 0) {
        perror("pdocker-media-executor: listen");
        close(server_fd);
        unlink(socket_path);
        return 1;
    }

    fprintf(stderr,
            "pdocker-media-executor: serving %s api=%s descriptor=%s raw-dev=0\n",
            socket_path,
            PDOCKER_MEDIA_COMMAND_API,
            descriptor_path ? descriptor_path : "");

    while (keep_running) {
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            perror("pdocker-media-executor: accept");
            break;
        }
        handle_client(client_fd, descriptor_path);
        close(client_fd);
    }

    close(server_fd);
    unlink(socket_path);
    return 0;
}

int main(int argc, char **argv) {
    const char *socket_path = NULL;
    const char *descriptor_path = NULL;
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--serve-socket") == 0 && i + 1 < argc) {
            socket_path = argv[++i];
        } else if (strcmp(argv[i], "--descriptor") == 0 && i + 1 < argc) {
            descriptor_path = argv[++i];
        } else if (strcmp(argv[i], "--hello") == 0) {
            reply_hello(STDOUT_FILENO, descriptor_path);
            return 0;
        } else if (strcmp(argv[i], "--capabilities") == 0) {
            reply_capabilities(STDOUT_FILENO, descriptor_path);
            return 0;
        } else {
            fprintf(stderr, "usage: %s --serve-socket PATH [--descriptor PATH]\n", argv[0]);
            return 2;
        }
    }

    return serve_socket(socket_path, descriptor_path);
}
