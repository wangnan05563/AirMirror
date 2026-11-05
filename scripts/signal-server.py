#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""AirMirror 跨网参考信令服务（PRD FS-04 第 3 步）。

职责只有一件事：按投屏码哈希把发送端与接收端配到一起，并在会话内转发两端的候选地址。
媒体不经过这里——这是 Q-4「不自建中继」的边界：服务端只看到 8 字节码哈希与候选地址，
看不到投屏码原文、画面、音频或文件名。

部署：默认监听 0.0.0.0:8787，Android 端在「跨网信令」设置里填 http://<主机>:8787。
生产使用必须放在 TLS 反向代理之后，并对公网做限流；本文件是参考实现，不内置鉴权。

用法：python scripts/signal-server.py --host 0.0.0.0 --port 8787
协议：与 app/src/main/java/.../crossnet/CrossNetProtocol.kt 保持一致。
"""
import argparse
import hashlib
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAGIC = b"AMCX"
VERSION = 1
SESSION_BYTES = 16
TYPE_MATCH = 1
TYPE_MATCHED = 2
TYPE_CANDIDATES = 3
TYPE_ABORT = 5
ROLE_SENDER = 1
ROLE_RECEIVER = 2
SESSION_TTL_SECONDS = 90


def now():
    return time.monotonic()


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.waiting = {}      # code_hash(hex) -> (session_id(bytes), created)
        self.sessions = {}     # session_id(hex) -> created
        self.candidates = {}   # (session_id(hex), role) -> raw body

    def prune(self):
        cutoff = now() - SESSION_TTL_SECONDS
        with self.lock:
            for key in [k for k, (_, created) in self.waiting.items() if created < cutoff]:
                self.waiting.pop(key, None)
            for key in [k for k, created in self.sessions.items() if created < cutoff]:
                self.sessions.pop(key, None)
                for role in (ROLE_SENDER, ROLE_RECEIVER):
                    self.candidates.pop((key, role), None)


STORE = Store()


def parse_message(body):
    """只解析信令头与码哈希；候选载荷保持不透明，服务端不解释 IP/端口语义。"""
    if len(body) < 25 or body[:4] != MAGIC or body[4] != VERSION:
        return None
    type_code = body[5] & 0xFF
    role = body[6] & 0xFF
    session = body[8:8 + SESSION_BYTES]
    hash_length = body[24] & 0xFF
    if hash_length not in (0, 8) or len(body) < 25 + hash_length:
        return None
    return {
        "type": type_code,
        "role": role,
        "session": session,
        "hash": body[25:25 + hash_length],
    }


def matched_message(session):
    body = bytearray()
    body += MAGIC
    body += bytes([VERSION, TYPE_MATCHED, ROLE_RECEIVER, 0])
    body += session
    body += bytes([0, 0, 0])  # 无码哈希、无候选、无原因
    return bytes(body)


def new_session_id():
    return os.urandom(SESSION_BYTES)


class Handler(BaseHTTPRequestHandler):
    server_version = "AirMirrorSignal/1"

    def log_message(self, fmt, *args):
        # 只记路径与方法，不记请求体；码哈希与候选地址也不进日志
        print("[%s] %s" % (self.command, self.path), flush=True)

    def reply(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def read_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > 4096:
            return b""
        return self.rfile.read(length)

    def do_POST(self):
        STORE.prune()
        path = self.path.split("?", 1)[0]
        body = self.read_body()
        message = parse_message(body)
        if path == "/v1/match":
            if message is None:
                return self.reply(400)
            return self.handle_match(message)
        if path == "/v1/candidates":
            if message is None or message["type"] != TYPE_CANDIDATES:
                return self.reply(400)
            session = message["session"].hex()
            with STORE.lock:
                if session not in STORE.sessions:
                    return self.reply(404)
                STORE.candidates[(session, message["role"])] = body
            return self.reply(200)
        if path == "/v1/abort":
            if message is not None:
                session = message["session"].hex()
                with STORE.lock:
                    STORE.sessions.pop(session, None)
                    for role in (ROLE_SENDER, ROLE_RECEIVER):
                        STORE.candidates.pop((session, role), None)
            return self.reply(200)
        return self.reply(404)

    def handle_match(self, message):
        code_hash = message["hash"]
        if message["role"] == ROLE_RECEIVER:
            if not code_hash:
                return self.reply(400)
            session = new_session_id()
            key = code_hash.hex()
            with STORE.lock:
                STORE.waiting[key] = (session, now())
                STORE.sessions[session.hex()] = now()
            return self.reply(200, matched_message(session))
        if message["role"] == ROLE_SENDER:
            key = code_hash.hex()
            with STORE.lock:
                # 一次配对：取走即失效，避免两个发送端复用同一会话抢同一条隧道
                found = STORE.waiting.pop(key, None)
            if not found:
                # 202 表示「大屏还没登记这个码」，客户端按正常等待继续轮询
                return self.reply(202)
            return self.reply(200, matched_message(found[0]))
        return self.reply(400)

    def do_GET(self):
        STORE.prune()
        if not self.path.startswith("/v1/candidates"):
            return self.reply(404)
        from urllib.parse import parse_qs, urlparse
        query = parse_qs(urlparse(self.path).query)
        session = (query.get("session") or [""])[0]
        try:
            role = int((query.get("role") or ["0"])[0])
        except ValueError:
            return self.reply(400)
        with STORE.lock:
            found = STORE.candidates.get((session, role))
        if not found:
            return self.reply(204)
        return self.reply(200, found)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print("AirMirror signaling on http://%s:%d (reference only)" % (args.host, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
