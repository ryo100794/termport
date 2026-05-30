#!/data/data/com.termux/files/usr/bin/python
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
PORT = int(os.environ.get('IME_CONSOLE_PORT', '8765'))
CTRL_PORT = PORT + 1000

client = None
client_addr = None
replay_buffer = deque()
replay_size = 0
REPLAY_LIMIT = int(os.environ.get('IME_CONSOLE_REPLAY_LIMIT', str(512 * 1024)))


def set_winsize(fd, rows=None, cols=None):
    if rows is None:
        rows = int(os.environ.get('IME_CONSOLE_ROWS', '32'))
    if cols is None:
        cols = int(os.environ.get('IME_CONSOLE_COLS', '100'))
    try:
        rows = max(2, int(rows))
        cols = max(2, int(cols))
        packed = struct.pack('HHHH', rows, cols, 0, 0)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, packed)
    except Exception:
        pass


def spawn_shell():
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
    set_winsize(fd)
    os.set_blocking(fd, False)
    return pid, fd


def close_client():
    global client, client_addr
    if client:
        try:
            client.close()
        except Exception:
            pass
    client = None
    client_addr = None


def remember_output(data):
    global replay_size
    if not data or REPLAY_LIMIT <= 0:
        return
    replay_buffer.append(data)
    replay_size += len(data)
    while replay_size > REPLAY_LIMIT and replay_buffer:
        replay_size -= len(replay_buffer.popleft())


def replay_to(conn):
    if not replay_buffer:
        return
    try:
        for chunk in replay_buffer:
            conn.sendall(chunk)
    except Exception:
        close_client()


def listen(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, port))
    srv.listen(4)
    srv.setblocking(False)
    return srv


def handle_control(ctrl_srv, pty_fd, shell_pid):
    conn, _ = ctrl_srv.accept()
    try:
        data = conn.recv(128).decode('ascii', 'ignore').strip().split()
        if len(data) == 3 and data[0] == 'RESIZE':
            set_winsize(pty_fd, data[1], data[2])
            try:
                os.kill(shell_pid, signal.SIGWINCH)
            except Exception:
                pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    global client, client_addr, replay_size
    signal.signal(signal.SIGCHLD, signal.SIG_IGN)
    shell_pid, pty_fd = spawn_shell()
    srv = listen(PORT)
    ctrl_srv = listen(CTRL_PORT)

    while True:
        readers = [srv, ctrl_srv, pty_fd]
        if client:
            readers.append(client)
        ready, _, _ = select.select(readers, [], [], 0.5)
        for item in ready:
            if item is srv:
                new_client, addr = srv.accept()
                new_client.setblocking(False)
                close_client()
                client = new_client
                client_addr = addr
                replay_to(client)
                replay_buffer.clear()
                replay_size = 0
            elif item is ctrl_srv:
                handle_control(ctrl_srv, pty_fd, shell_pid)
            elif item == pty_fd:
                try:
                    data = os.read(pty_fd, 4096)
                except BlockingIOError:
                    continue
                except OSError:
                    return
                if not data:
                    return
                if client:
                    try:
                        client.sendall(data)
                    except Exception:
                        close_client()
                        remember_output(data)
                else:
                    remember_output(data)
            elif item is client:
                try:
                    data = client.recv(4096)
                except Exception:
                    close_client()
                    continue
                if not data:
                    close_client()
                    continue
                try:
                    os.write(pty_fd, data)
                except OSError:
                    return


if __name__ == '__main__':
    try:
        main()
    except OSError as e:
        if getattr(e, 'errno', None) == 98:
            sys.exit(0)
        raise
