import os
import runpy
import socket
import socketserver
import sys
import threading


class _ConnectProxyHandler(socketserver.BaseRequestHandler):
    timeout = 30

    def handle(self):
        client = self.request
        try:
            header = b""
            while b"\r\n\r\n" not in header:
                chunk = client.recv(4096)
                if not chunk:
                    return
                header += chunk
                if len(header) > 16384:
                    return
            line = header.split(b"\r\n", 1)[0].decode("latin-1", "replace")
            parts = line.split()
            if len(parts) < 2 or parts[0].upper() != "CONNECT":
                client.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                return
            host, _, port = parts[1].rpartition(":")
            if not host:
                client.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
                return
            try:
                upstream = _connect_upstream(host, int(port), timeout=10)
            except OSError as exc:
                client.sendall(("HTTP/1.1 502 Bad Gateway\r\n\r\n" + str(exc)).encode())
                return
            client.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            _pipe(client, upstream)
        finally:
            try:
                client.close()
            except OSError:
                pass


def _connect_upstream(host, port, timeout=10):
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    infos.sort(key=lambda item: 0 if item[0] == socket.AF_INET else 1)
    errors = []
    for family, socktype, proto, _, sockaddr in infos:
        sock = socket.socket(family, socktype, proto)
        try:
            sock.settimeout(timeout)
            sock.connect(sockaddr)
            return sock
        except OSError as exc:
            errors.append(f"{sockaddr}: {exc}")
            sock.close()
    raise OSError("; ".join(errors) or f"could not connect to {host}:{port}")


def _pipe(a, b):
    def forward(src, dst):
        try:
            while True:
                data = src.recv(16384)
                if not data:
                    break
                dst.sendall(data)
        except OSError:
            pass
        finally:
            for item in (src, dst):
                try:
                    item.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    t1 = threading.Thread(target=forward, args=(a, b), daemon=True)
    t2 = threading.Thread(target=forward, args=(b, a), daemon=True)
    t1.start(); t2.start()
    t1.join(); t2.join()


class _ProxyServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def _start_connect_proxy():
    server = _ProxyServer(("127.0.0.1", 0), _ConnectProxyHandler)
    threading.Thread(target=server.serve_forever, name="skydnir-proxy", daemon=True).start()
    return server.server_address[1]


def run_engine(sock_path, home, runtime_dir, platform=None):
    os.environ["SKYDNIR_HOME"] = home
    os.environ["PDOCKER_HOME"] = home
    tmp_dir = os.path.join(runtime_dir, "tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    os.environ["PDOCKER_TMP_DIR"] = tmp_dir
    os.environ["TMPDIR"] = tmp_dir
    os.environ["PROOT_TMP_DIR"] = tmp_dir
    os.environ["PDOCKER_RUNTIME_PREFLIGHT"] = "1"
    os.environ.setdefault("PDOCKER_AUTO_PRUNE_UNREFERENCED_LAYERS", "1")
    os.environ.setdefault("PDOCKER_AUTO_PRUNE_BUILD_ARTIFACTS", "1")
    os.environ.setdefault("PDOCKER_RUNTIME_BACKEND", "direct")
    os.environ.setdefault("PDOCKER_DIRECT_EXPERIMENTAL_PROCESS_EXEC", "1")
    os.environ.setdefault("PDOCKER_DIRECT_TRACE_SYSCALLS", "0")
    os.environ.setdefault("PDOCKER_DIRECT_TRACE_MODE", "seccomp")
    os.environ.setdefault("PDOCKER_USE_COW_BIND", "1")
    os.environ.setdefault("PDOCKER_SUPPRESS_DEPRECATION_WARNING", "1")
    os.environ.setdefault("SKYDNIR_DAEMON_NAME", "skydnird")
    if platform:
        os.environ["PDOCKER_PLATFORM"] = platform

    direct_executor = os.path.join(runtime_dir, "docker-bin", "skydnir-direct")
    if os.path.exists(direct_executor):
        os.environ["PDOCKER_DIRECT_EXECUTOR"] = direct_executor
    gpu_executor = os.path.join(runtime_dir, "gpu", "skydnir-gpu-executor")
    if os.path.exists(gpu_executor):
        os.environ["PDOCKER_GPU_EXECUTOR"] = gpu_executor
        os.environ["PDOCKER_GPU_EXECUTOR_AVAILABLE"] = "1"
        os.environ["PDOCKER_GPU_HOST_DIR"] = os.path.join(runtime_dir, "gpu")
    media_dir = os.path.join(runtime_dir, "media")
    os.makedirs(media_dir, exist_ok=True)
    media_executor = os.path.join(media_dir, "skydnir-media-executor")
    if os.path.exists(media_executor):
        os.environ["PDOCKER_MEDIA_EXECUTOR"] = media_executor
        os.environ["PDOCKER_MEDIA_EXECUTOR_AVAILABLE"] = "1"
    os.environ["PDOCKER_MEDIA_HOST_DIR"] = media_dir

    bin_dir = os.path.join(runtime_dir, "docker-bin")
    os.environ["PATH"] = bin_dir + os.pathsep + os.environ.get("PATH", "")
    lib_dir = os.path.join(runtime_dir, "lib")
    existing = os.environ.get("LD_LIBRARY_PATH", "")
    os.environ["LD_LIBRARY_PATH"] = lib_dir + (os.pathsep + existing if existing else "")

    proxy_port = _start_connect_proxy()
    proxy_url = f"http://127.0.0.1:{proxy_port}"
    os.environ["HTTP_PROXY"] = proxy_url
    os.environ["HTTPS_PROXY"] = proxy_url
    os.environ["NO_PROXY"] = "localhost,127.0.0.1,::1"
    os.environ.setdefault("SSL_CERT_DIR", "/system/etc/security/cacerts")
    os.environ.setdefault("PDOCKER_LINK_MODE", "symlink")

    daemon = os.path.join(runtime_dir, "bin", "skydnird")
    sys.argv = ["skydnird", "--socket", sock_path]
    runpy.run_path(daemon, run_name="__main__")
