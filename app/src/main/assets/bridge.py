#!/data/data/com.termux/files/usr/bin/python
import errno
import os
import pty
import select
import signal
import socket
import sys
import termios
import fcntl
import struct
from collections import deque

HOST = '127.0.0.1'
BASE_PORT = int(os.environ.get('IME_CONSOLE_BASE_PORT', os.environ.get('IME_CONSOLE_PORT', '8765')))
SESSION_COUNT = max(1, int(os.environ.get('IME_CONSOLE_SESSION_COUNT', '8')))
REPLAY_LIMIT = int(os.environ.get('IME_CONSOLE_REPLAY_LIMIT', str(512 * 1024)))


def env_list(name, default, count):
    raw = os.environ.get(name, '')
    values = []
    for part in raw.split(','):
        part = part.strip()
        if not part:
            continue
        try:
            values.append(int(part))
        except Exception:
            pass
    while len(values) < count:
        values.append(default)
    return values[:count]


DEFAULT_ROWS = int(os.environ.get('IME_CONSOLE_ROWS', '32'))
DEFAULT_COLS = int(os.environ.get('IME_CONSOLE_COLS', '100'))
ROWS = env_list('IME_CONSOLE_ROWS_LIST', DEFAULT_ROWS, SESSION_COUNT)
COLS = env_list('IME_CONSOLE_COLS_LIST', DEFAULT_COLS, SESSION_COUNT)


def set_winsize(fd, rows, cols):
    try:
        rows = max(2, int(rows))
        cols = max(2, int(cols))
        packed = struct.pack('HHHH', rows, cols, 0, 0)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, packed)
    except Exception:
        pass


def listen(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, port))
    srv.listen(4)
    srv.setblocking(False)
    return srv


class Session:
    def __init__(self, index):
        self.index = index
        self.port = BASE_PORT + index
        self.ctrl_port = self.port + 1000
        self.rows = ROWS[index]
        self.cols = COLS[index]
        self.clients = []
        self.replay_buffer = deque()
        self.replay_size = 0
        self.shell_pid, self.pty_fd = self.spawn_shell()
        self.srv = listen(self.port)
        self.ctrl_srv = listen(self.ctrl_port)

    def spawn_shell(self):
        pid, fd = pty.fork()
        if pid == 0:
            os.environ.setdefault('TERM', 'xterm-256color')
            os.environ.setdefault('LANG', 'ja_JP.UTF-8')
            home = os.environ.get('HOME', '/data/data/com.termux/files/home')
            try:
                os.chdir(home)
            except Exception:
                pass
            shell = os.environ.get('SHELL', '/data/data/com.termux/files/usr/bin/bash')
            os.execl(shell, shell, '-l')
        set_winsize(fd, self.rows, self.cols)
        os.set_blocking(fd, False)
        return pid, fd

    def close_client(self, conn=None):
        targets = list(self.clients) if conn is None else [conn]
        for c in targets:
            try:
                c.close()
            except Exception:
                pass
            try:
                self.clients.remove(c)
            except ValueError:
                pass

    def remember_output(self, data):
        if not data or REPLAY_LIMIT <= 0:
            return
        self.replay_buffer.append(data)
        self.replay_size += len(data)
        while self.replay_size > REPLAY_LIMIT and self.replay_buffer:
            self.replay_size -= len(self.replay_buffer.popleft())

    def replay_to(self, conn):
        if not self.replay_buffer:
            return
        try:
            for chunk in self.replay_buffer:
                conn.sendall(chunk)
        except Exception:
            self.close_client(conn)

    def accept_client(self):
        new_client, _ = self.srv.accept()
        try:
            new_client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except Exception:
            pass
        new_client.setblocking(False)
        self.clients.append(new_client)
        self.replay_to(new_client)

    def broadcast_output(self, data):
        for conn in list(self.clients):
            try:
                conn.sendall(data)
            except Exception:
                self.close_client(conn)

    def handle_control(self):
        conn, _ = self.ctrl_srv.accept()
        try:
            data = conn.recv(128).decode('ascii', 'ignore').strip().split()
            if len(data) == 3 and data[0] == 'RESIZE':
                self.rows = max(2, int(data[1]))
                self.cols = max(2, int(data[2]))
                set_winsize(self.pty_fd, self.rows, self.cols)
                try:
                    os.kill(self.shell_pid, signal.SIGWINCH)
                except Exception:
                    pass
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def read_pty(self):
        try:
            data = os.read(self.pty_fd, 4096)
        except BlockingIOError:
            return True
        except OSError:
            return False
        if not data:
            return False
        self.remember_output(data)
        self.broadcast_output(data)
        return True

    def read_client(self, conn):
        try:
            data = conn.recv(4096)
        except Exception:
            self.close_client(conn)
            return True
        if not data:
            self.close_client(conn)
            return True
        try:
            os.write(self.pty_fd, data)
        except OSError:
            return False
        return True


def main():
    signal.signal(signal.SIGCHLD, signal.SIG_IGN)
    sessions = [Session(i) for i in range(SESSION_COUNT)]
    while True:
        readers = []
        for s in sessions:
            readers.extend([s.srv, s.ctrl_srv, s.pty_fd])
            readers.extend(s.clients)
        ready, _, _ = select.select(readers, [], [], 0.5)
        for item in ready:
            for s in sessions:
                if item is s.srv:
                    s.accept_client()
                    break
                if item is s.ctrl_srv:
                    s.handle_control()
                    break
                if item == s.pty_fd:
                    if not s.read_pty():
                        return
                    break
                if item in s.clients:
                    if not s.read_client(item):
                        return
                    break


if __name__ == '__main__':
    try:
        main()
    except OSError as e:
        if getattr(e, 'errno', None) == errno.EADDRINUSE:
            sys.exit(0)
        raise
