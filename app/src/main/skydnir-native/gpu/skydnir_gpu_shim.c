/*
 * Container-facing pdocker GPU shim probe.
 *
 * This binary is built for Linux/glibc and bind-mounted into GPU-requesting
 * containers as /usr/local/bin/pdocker-gpu-shim. It is deliberately
 * backend-neutral: Android GLES/Vulkan/OpenCL details stay behind the APK
 * executor. The next implementation step is replacing the current capability
 * probe with a shared-memory command queue.
 */
#include "skydnir_gpu_abi.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static int connect_queue(void);

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
}

static const char *env_or(const char *name, const char *fallback) {
    const char *v = getenv(name);
    return (v && v[0]) ? v : fallback;
}

static void print_capabilities(void) {
    const char *socket_path = getenv("PDOCKER_GPU_QUEUE_SOCKET");
    const int queue_ready = socket_path && socket_path[0];
    printf("{\"shim\":\"pdocker-gpu-shim\","
           "\"api\":\"%s\","
           "\"abi_version\":\"%s\","
           "\"llm_engine\":\"%s\","
           "\"device_independent\":true,"
           "\"container_contract\":\"%s\","
           "\"executor_available\":%s,"
           "\"executor_role\":\"%s\","
           "\"queue_socket_available\":%s,"
           "\"transport\":\"%s\","
           "\"fd_shared_buffer\":true,"
           "\"backend_impl_visible_to_container\":false}\n",
           PDOCKER_GPU_COMMAND_API,
           PDOCKER_GPU_ABI_VERSION,
           PDOCKER_GPU_LLM_ENGINE_LOCATION,
           PDOCKER_GPU_CONTAINER_CONTRACT,
           strcmp(env_or("PDOCKER_GPU_EXECUTOR_AVAILABLE", "0"), "1") == 0 ? "true" : "false",
           env_or("PDOCKER_GPU_EXECUTOR_ROLE", "apk-bionic-gpu-command-executor"),
           queue_ready ? "true" : "false",
           queue_ready ? "unix-socket-command-queue" : "command-queue-pending");
}

static void fill_inputs(float *a, float *b, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        a[i] = (float)i * 0.25f;
        b[i] = 1.0f - (float)i * 0.125f;
    }
}

static int create_shared_fd(size_t bytes) {
#ifdef __NR_memfd_create
    int memfd = (int)syscall(__NR_memfd_create, "pdocker-gpu-vector-add", MFD_CLOEXEC);
    if (memfd >= 0) {
        if (ftruncate(memfd, (off_t)bytes) == 0) return memfd;
        int err = errno;
        close(memfd);
        errno = err;
        return -1;
    }
#endif
    const char *dir = env_or("PDOCKER_GPU_SHARED_DIR", "/tmp");
    char path[512];
    snprintf(path, sizeof(path), "%s/pdocker-gpu-vector-add-XXXXXX", dir);
    int fd = mkstemp(path);
    if (fd < 0) return -1;
    unlink(path);
    if (ftruncate(fd, (off_t)bytes) != 0) {
        int err = errno;
        close(fd);
        errno = err;
        return -1;
    }
    return fd;
}

static int send_fd_command(int socket_fd, const char *command, int passed_fd) {
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr msg;
    memset(control, 0, sizeof(control));
    memset(&iov, 0, sizeof(iov));
    memset(&msg, 0, sizeof(msg));
    iov.iov_base = (void *)command;
    iov.iov_len = strlen(command);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &passed_fd, sizeof(int));
    msg.msg_controllen = cmsg->cmsg_len;
    return sendmsg(socket_fd, &msg, 0) < 0 ? -errno : 0;
}

static int send_fds_command(int socket_fd, const char *command, const int *passed_fds, size_t fd_count) {
    if (!passed_fds || fd_count == 0 || fd_count > 4) return -EINVAL;
    char control[CMSG_SPACE(sizeof(int) * 4)];
    struct iovec iov;
    struct msghdr msg;
    memset(control, 0, sizeof(control));
    memset(&iov, 0, sizeof(iov));
    memset(&msg, 0, sizeof(msg));
    iov.iov_base = (void *)command;
    iov.iov_len = strlen(command);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fd_count);
    memcpy(CMSG_DATA(cmsg), passed_fds, sizeof(int) * fd_count);
    msg.msg_controllen = cmsg->cmsg_len;
    return sendmsg(socket_fd, &msg, 0) < 0 ? -errno : 0;
}

static int read_response_line(int fd, char *line, size_t line_size) {
    if (!line || line_size == 0) return -EINVAL;
    size_t off = 0;
    while (off + 1 < line_size) {
        char ch;
        ssize_t n = read(fd, &ch, 1);
        if (n == 0) break;
        if (n < 0) return -errno;
        line[off++] = ch;
        if (ch == '\n') break;
    }
    line[off] = '\0';
    return off > 0 ? 0 : -EIO;
}

static int vector_add_3fd_on_socket(int fd, size_t n, const char *command_prefix) {
    if (n == 0 || n > PDOCKER_GPU_VECTOR_ADD_MAX_N) {
        fprintf(stderr, "pdocker-gpu-shim: invalid vector size: %zu\n", n);
        return 64;
    }
    const size_t bytes = n * sizeof(float);
    int shared_fds[3] = {-1, -1, -1};
    void *maps[3] = {MAP_FAILED, MAP_FAILED, MAP_FAILED};
    int rc = 70;
    for (int i = 0; i < 3; ++i) {
        shared_fds[i] = create_shared_fd(bytes);
        if (shared_fds[i] < 0) {
            fprintf(stderr, "pdocker-gpu-shim: shared fd allocation failed: %s\n", strerror(errno));
            goto cleanup;
        }
        maps[i] = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, shared_fds[i], 0);
        if (maps[i] == MAP_FAILED) {
            fprintf(stderr, "pdocker-gpu-shim: mmap shared fd failed: %s\n", strerror(errno));
            goto cleanup;
        }
    }
    float *a = (float *)maps[0];
    float *b = (float *)maps[1];
    float *out = (float *)maps[2];
    fill_inputs(a, b, n);
    memset(out, 0, bytes);

    char cmd[80];
    snprintf(cmd, sizeof(cmd), "%s %zu\n", command_prefix, n);
    rc = send_fds_command(fd, cmd, shared_fds, 3);
    for (int i = 0; i < 3; ++i) {
        close(shared_fds[i]);
        shared_fds[i] = -1;
    }
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: send 3fd command failed: %s\n", strerror(-rc));
        rc = 70;
        goto cleanup;
    }
    char line[4096];
    rc = read_response_line(fd, line, sizeof(line));
    double max_err = 0.0;
    for (size_t i = 0; i < n; ++i) {
        double err = fabs((double)out[i] - (double)(a[i] + b[i]));
        if (err > max_err) max_err = err;
    }
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: response read failed: %s\n", strerror(-rc));
        rc = 70;
        goto cleanup;
    }
    fputs(line, stdout);
    rc = max_err <= 0.0001 ? 0 : 6;

cleanup:
    for (int i = 0; i < 3; ++i) {
        if (maps[i] != MAP_FAILED) munmap(maps[i], bytes);
        if (shared_fds[i] >= 0) close(shared_fds[i]);
    }
    return rc;
}

static int vector_add_fd_on_socket(int fd, size_t n) {
    if (n == 0 || n > PDOCKER_GPU_VECTOR_ADD_MAX_N) {
        fprintf(stderr, "pdocker-gpu-shim: invalid vector size: %zu\n", n);
        return 64;
    }
    const size_t bytes = n * sizeof(float);
    const size_t total = bytes * 3;
    int shared_fd = create_shared_fd(total);
    if (shared_fd < 0) {
        fprintf(stderr, "pdocker-gpu-shim: shared fd allocation failed: %s\n", strerror(errno));
        return 70;
    }
    void *map = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_SHARED, shared_fd, 0);
    if (map == MAP_FAILED) {
        fprintf(stderr, "pdocker-gpu-shim: mmap shared fd failed: %s\n", strerror(errno));
        close(shared_fd);
        return 70;
    }
    float *a = (float *)map;
    float *b = a + n;
    float *out = b + n;
    fill_inputs(a, b, n);
    memset(out, 0, bytes);

    char cmd[64];
    snprintf(cmd, sizeof(cmd), "VECTOR_ADD_FD %zu\n", n);
    int rc = send_fd_command(fd, cmd, shared_fd);
    close(shared_fd);
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: send fd command failed: %s\n", strerror(-rc));
        munmap(map, total);
        return 70;
    }
    char line[4096];
    rc = read_response_line(fd, line, sizeof(line));
    double max_err = 0.0;
    for (size_t i = 0; i < n; ++i) {
        double err = fabs((double)out[i] - (double)(a[i] + b[i]));
        if (err > max_err) max_err = err;
    }
    munmap(map, total);
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: response read failed: %s\n", strerror(-rc));
        return 70;
    }
    fputs(line, stdout);
    return max_err <= 0.0001 ? 0 : 6;
}

static int vector_add_fd_once(size_t n) {
    int fd = connect_queue();
    if (fd < 0) {
        fprintf(stderr, "pdocker-gpu-shim: connect failed: %s\n", strerror(-fd));
        return 69;
    }
    int rc = vector_add_fd_on_socket(fd, n);
    close(fd);
    return rc;
}

static int bench_cpu_vector_add(int count) {
    if (count <= 0) count = 1;
    const size_t n = PDOCKER_GPU_VECTOR_ADD_DEFAULT_N;
    const size_t bytes = n * sizeof(float);
    float *a = (float *)malloc(bytes);
    float *b = (float *)malloc(bytes);
    float *out = (float *)calloc(n, sizeof(float));
    if (!a || !b || !out) {
        free(a);
        free(b);
        free(out);
        fprintf(stderr, "pdocker-gpu-shim: host allocation failed\n");
        return 70;
    }
    fill_inputs(a, b, n);
    int last = 0;
    for (int r = 0; r < count; ++r) {
        memset(out, 0, bytes);
        double start = now_ms();
        for (size_t i = 0; i < n; ++i) {
            out[i] = a[i] + b[i];
        }
        double total_ms = now_ms() - start;
        double max_err = 0.0;
        for (size_t i = 0; i < n; ++i) {
            double err = fabs((double)out[i] - (double)(a[i] + b[i]));
            if (err > max_err) max_err = err;
        }
        const int valid = max_err <= 0.0001;
        printf("{\"shim\":\"pdocker-gpu-shim\",\"api\":\"%s\",\"abi_version\":\"%s\","
               "\"llm_engine\":\"%s\",\"device_independent\":true,"
               "\"backend_impl\":\"cpu_scalar\",\"backend_affinity\":\"cpu\","
               "\"execution_scope\":\"container\",\"transport\":\"container-local-process-buffer\","
               "\"kernel\":\"vector_add\",\"problem_size\":\"n=%zu\","
               "\"init_ms\":0.0000,\"compile_ms\":0.0000,\"upload_ms\":0.0000,"
               "\"dispatch_ms\":%.4f,\"download_ms\":0.0000,\"total_ms\":%.4f,"
               "\"max_abs_error\":%.8f,\"valid\":%s}\n",
               PDOCKER_GPU_COMMAND_API, PDOCKER_GPU_ABI_VERSION,
               PDOCKER_GPU_LLM_ENGINE_LOCATION,
               n, total_ms, total_ms, max_err, valid ? "true" : "false");
        last = valid ? 0 : 6;
        if (last != 0) break;
    }
    free(a);
    free(b);
    free(out);
    return last;
}

static void print_env(void) {
    printf("PDOCKER_GPU_COMMAND_API=%s\n", env_or("PDOCKER_GPU_COMMAND_API", PDOCKER_GPU_COMMAND_API));
    printf("PDOCKER_GPU_ABI_VERSION=%s\n", env_or("PDOCKER_GPU_ABI_VERSION", PDOCKER_GPU_ABI_VERSION));
    printf("PDOCKER_GPU_LLM_ENGINE_LOCATION=%s\n", env_or("PDOCKER_GPU_LLM_ENGINE_LOCATION", PDOCKER_GPU_LLM_ENGINE_LOCATION));
    printf("PDOCKER_GPU_EXECUTOR_AVAILABLE=%s\n", env_or("PDOCKER_GPU_EXECUTOR_AVAILABLE", "0"));
    printf("PDOCKER_GPU_QUEUE_SOCKET=%s\n", env_or("PDOCKER_GPU_QUEUE_SOCKET", ""));
    printf("PDOCKER_GPU_MODES=%s\n", env_or("PDOCKER_GPU_MODES", ""));
}

static int send_command(const char *command) {
    const char *path = getenv("PDOCKER_GPU_QUEUE_SOCKET");
    if (!path || !path[0]) path = "/run/pdocker-gpu/pdocker-gpu.sock";
    if (access(path, F_OK) != 0) {
        printf("{\"shim\":\"pdocker-gpu-shim\","
               "\"api\":\"%s\","
               "\"valid\":false,"
               "\"reason\":\"GPU queue socket is not available\","
               "\"socket\":\"%s\"}\n",
               PDOCKER_GPU_COMMAND_API, path);
        return 2;
    }
    if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "pdocker-gpu-shim: queue socket path too long: %s\n", path);
        return 64;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("socket");
        return 70;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        fprintf(stderr, "pdocker-gpu-shim: connect %s failed: %s\n", path, strerror(errno));
        close(fd);
        return 69;
    }
    dprintf(fd, "%s\n", command);
    shutdown(fd, SHUT_WR);
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        if (fwrite(buf, 1, (size_t)n, stdout) != (size_t)n) {
            close(fd);
            return 74;
        }
    }
    close(fd);
    return 0;
}

static int connect_queue(void) {
    const char *path = getenv("PDOCKER_GPU_QUEUE_SOCKET");
    if (!path || !path[0]) path = "/run/pdocker-gpu/pdocker-gpu.sock";
    if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        return -ENAMETOOLONG;
    }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -errno;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

static int bench_vector_add(int count, int persistent) {
    if (count <= 0) count = 1;
    if (persistent) {
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: persistent connect failed: %s\n", strerror(-fd));
            return 69;
        }
        FILE *io = fdopen(fd, "r+");
        if (!io) {
            close(fd);
            return 70;
        }
        setvbuf(io, NULL, _IONBF, 0);
        char line[4096];
        for (int i = 0; i < count; ++i) {
            fprintf(io, "VECTOR_ADD\n");
            if (!fgets(line, sizeof(line), io)) {
                fclose(io);
                return 70;
            }
            fputs(line, stdout);
        }
        fclose(io);
        return 0;
    }
    int last = 0;
    for (int i = 0; i < count; ++i) {
        last = send_command("VECTOR_ADD");
    }
    return last;
}

static int bench_noop(int count, int persistent) {
    if (count <= 0) count = 1;
    if (persistent) {
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: persistent connect failed: %s\n", strerror(-fd));
            return 69;
        }
        FILE *io = fdopen(fd, "r+");
        if (!io) {
            close(fd);
            return 70;
        }
        setvbuf(io, NULL, _IONBF, 0);
        char line[4096];
        for (int i = 0; i < count; ++i) {
            double start = now_ms();
            fprintf(io, "NOOP\n");
            if (!fgets(line, sizeof(line), io)) {
                fclose(io);
                return 70;
            }
            double total_ms = now_ms() - start;
            printf("{\"shim\":\"pdocker-gpu-shim\",\"api\":\"%s\",\"abi_version\":\"%s\","
                   "\"llm_engine\":\"%s\",\"device_independent\":true,"
                   "\"backend_impl\":\"bridge_roundtrip\",\"backend_affinity\":\"transport\","
                   "\"execution_scope\":\"container-to-host-bridge\","
                   "\"transport\":\"unix-socket-command-queue-persistent\","
                   "\"kernel\":\"noop\",\"total_ms\":%.4f,\"valid\":true}\n",
                   PDOCKER_GPU_COMMAND_API, PDOCKER_GPU_ABI_VERSION,
                   PDOCKER_GPU_LLM_ENGINE_LOCATION, total_ms);
        }
        fclose(io);
        return 0;
    }
    int last = 0;
    for (int i = 0; i < count; ++i) {
        double start = now_ms();
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: connect failed: %s\n", strerror(-fd));
            return 69;
        }
        dprintf(fd, "NOOP\n");
        shutdown(fd, SHUT_WR);
        char line[4096];
        last = read_response_line(fd, line, sizeof(line));
        close(fd);
        if (last != 0) return 70;
        double total_ms = now_ms() - start;
        printf("{\"shim\":\"pdocker-gpu-shim\",\"api\":\"%s\",\"abi_version\":\"%s\","
               "\"llm_engine\":\"%s\",\"device_independent\":true,"
               "\"backend_impl\":\"bridge_roundtrip\",\"backend_affinity\":\"transport\","
               "\"execution_scope\":\"container-to-host-bridge\","
               "\"transport\":\"unix-socket-command-queue\","
               "\"kernel\":\"noop\",\"total_ms\":%.4f,\"valid\":true}\n",
               PDOCKER_GPU_COMMAND_API, PDOCKER_GPU_ABI_VERSION,
               PDOCKER_GPU_LLM_ENGINE_LOCATION, total_ms);
    }
    return last;
}

static int bench_vector_add_fd(int count, int persistent) {
    if (count <= 0) count = 1;
    if (persistent) {
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: persistent connect failed: %s\n", strerror(-fd));
            return 69;
        }
        int last = 0;
        for (int i = 0; i < count; ++i) {
            last = vector_add_fd_on_socket(fd, PDOCKER_GPU_VECTOR_ADD_DEFAULT_N);
            if (last != 0) break;
        }
        close(fd);
        return last;
    }
    int last = 0;
    for (int i = 0; i < count; ++i) {
        last = vector_add_fd_once(PDOCKER_GPU_VECTOR_ADD_DEFAULT_N);
    }
    return last;
}

static int bench_vector_add_3fd(int count, int persistent, const char *command_prefix) {
    if (count <= 0) count = 1;
    if (persistent) {
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: persistent connect failed: %s\n", strerror(-fd));
            return 69;
        }
        int last = 0;
        for (int i = 0; i < count; ++i) {
            last = vector_add_3fd_on_socket(fd, PDOCKER_GPU_VECTOR_ADD_DEFAULT_N, command_prefix);
            if (last != 0) break;
        }
        close(fd);
        return last;
    }
    int last = 0;
    for (int i = 0; i < count; ++i) {
        int fd = connect_queue();
        if (fd < 0) {
            fprintf(stderr, "pdocker-gpu-shim: connect failed: %s\n", strerror(-fd));
            return 69;
        }
        last = vector_add_3fd_on_socket(fd, PDOCKER_GPU_VECTOR_ADD_DEFAULT_N, command_prefix);
        close(fd);
        if (last != 0) break;
    }
    return last;
}

static int bench_vector_add_registered(int count) {
    if (count <= 0) count = 1;
    const size_t n = PDOCKER_GPU_VECTOR_ADD_DEFAULT_N;
    const size_t bytes = n * sizeof(float);
    const size_t total = bytes * 3;
    int shared_fd = create_shared_fd(total);
    if (shared_fd < 0) {
        fprintf(stderr, "pdocker-gpu-shim: shared fd allocation failed: %s\n", strerror(errno));
        return 70;
    }
    void *map = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_SHARED, shared_fd, 0);
    if (map == MAP_FAILED) {
        fprintf(stderr, "pdocker-gpu-shim: mmap shared fd failed: %s\n", strerror(errno));
        close(shared_fd);
        return 70;
    }
    float *a = (float *)map;
    float *b = a + n;
    float *out = b + n;
    fill_inputs(a, b, n);
    memset(out, 0, bytes);

    int fd = connect_queue();
    if (fd < 0) {
        fprintf(stderr, "pdocker-gpu-shim: persistent connect failed: %s\n", strerror(-fd));
        munmap(map, total);
        close(shared_fd);
        return 69;
    }
    char cmd[64];
    snprintf(cmd, sizeof(cmd), "REGISTER_VECTOR_FD %zu\n", n);
    int rc = send_fd_command(fd, cmd, shared_fd);
    close(shared_fd);
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: register fd command failed: %s\n", strerror(-rc));
        close(fd);
        munmap(map, total);
        return 70;
    }
    char line[4096];
    rc = read_response_line(fd, line, sizeof(line));
    if (rc != 0) {
        fprintf(stderr, "pdocker-gpu-shim: register response failed: %s\n", strerror(-rc));
        close(fd);
        munmap(map, total);
        return 70;
    }

    int last = 0;
    for (int i = 0; i < count; ++i) {
        if (dprintf(fd, "VECTOR_ADD_REGISTERED\n") < 0) {
            last = 70;
            break;
        }
        rc = read_response_line(fd, line, sizeof(line));
        if (rc != 0) {
            last = 70;
            break;
        }
        double max_err = 0.0;
        for (size_t j = 0; j < n; ++j) {
            double err = fabs((double)out[j] - (double)(a[j] + b[j]));
            if (err > max_err) max_err = err;
        }
        fputs(line, stdout);
        if (max_err > 0.0001) {
            last = 6;
            break;
        }
    }
    close(fd);
    munmap(map, total);
    return last;
}

static int parse_count(const char *s, int fallback) {
    if (!s || !s[0]) return fallback;
    char *end = NULL;
    long n = strtol(s, &end, 10);
    if (!end || *end || n <= 0 || n > 10000) return fallback;
    return (int)n;
}

int main(int argc, char **argv) {
    if (argc <= 1 || strcmp(argv[1], "--capabilities") == 0) {
        print_capabilities();
        return 0;
    }
    if (strcmp(argv[1], "--env") == 0) {
        print_env();
        return 0;
    }
    if (strcmp(argv[1], "--queue-probe") == 0) {
        return send_command("CAPABILITIES");
    }
    if (strcmp(argv[1], "--vector-add") == 0) {
        return send_command("VECTOR_ADD");
    }
    if (strcmp(argv[1], "--vector-add-fd") == 0) {
        return vector_add_fd_once(PDOCKER_GPU_VECTOR_ADD_DEFAULT_N);
    }
    if (strcmp(argv[1], "--bench-vector-add") == 0) {
        return bench_vector_add(parse_count(argc > 2 ? argv[2] : NULL, 5), 0);
    }
    if (strcmp(argv[1], "--bench-cpu-vector-add") == 0) {
        return bench_cpu_vector_add(parse_count(argc > 2 ? argv[2] : NULL, 5));
    }
    if (strcmp(argv[1], "--bench-vector-add-persistent") == 0) {
        return bench_vector_add(parse_count(argc > 2 ? argv[2] : NULL, 5), 1);
    }
    if (strcmp(argv[1], "--bench-vector-add-fd") == 0) {
        return bench_vector_add_fd(parse_count(argc > 2 ? argv[2] : NULL, 5), 0);
    }
    if (strcmp(argv[1], "--bench-vector-add-fd-persistent") == 0) {
        return bench_vector_add_fd(parse_count(argc > 2 ? argv[2] : NULL, 5), 1);
    }
    if (strcmp(argv[1], "--bench-vulkan-vector-add-3fd") == 0) {
        return bench_vector_add_3fd(parse_count(argc > 2 ? argv[2] : NULL, 5), 0, "VULKAN_VECTOR_ADD_3FD");
    }
    if (strcmp(argv[1], "--bench-vulkan-vector-add-3fd-persistent") == 0) {
        return bench_vector_add_3fd(parse_count(argc > 2 ? argv[2] : NULL, 5), 1, "VULKAN_VECTOR_ADD_3FD");
    }
    if (strcmp(argv[1], "--bench-vector-add-registered") == 0) {
        return bench_vector_add_registered(parse_count(argc > 2 ? argv[2] : NULL, 5));
    }
    if (strcmp(argv[1], "--bench-noop") == 0) {
        return bench_noop(parse_count(argc > 2 ? argv[2] : NULL, 5), 0);
    }
    if (strcmp(argv[1], "--bench-noop-persistent") == 0) {
        return bench_noop(parse_count(argc > 2 ? argv[2] : NULL, 5), 1);
    }
    fprintf(stderr, "usage: %s [--capabilities|--env|--queue-probe|--vector-add|--vector-add-fd|--bench-cpu-vector-add N|--bench-vector-add N|--bench-vector-add-persistent N|--bench-vector-add-fd N|--bench-vector-add-fd-persistent N|--bench-vulkan-vector-add-3fd N|--bench-vulkan-vector-add-3fd-persistent N|--bench-vector-add-registered N|--bench-noop N|--bench-noop-persistent N]\n", argv[0]);
    return 64;
}
