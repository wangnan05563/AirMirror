#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""AirMirror 线协议向量自检（跨语言基线，T05760 M0）。

用途：不依赖 Kotlin/JVM，按规范独立实现帧头、HELLO、HKDF、投屏码锚，
与 protocol-core 产出的 JSON 向量逐字节比对；PC/鸿蒙实现接入 CI 时可复用同一组向量。

用法：python scripts/verify-protocol-vectors.py
退出码：0 = 全部通过；1 = 有不一致。
"""
import hashlib
import hmac
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VECTORS = ROOT / "protocol-core" / "src" / "test" / "resources" / "vectors"


def load(name):
    with (VECTORS / name).open(encoding="utf-8") as fh:
        return json.load(fh)


def hx(value):
    return value.hex()


def check(name, actual, expected):
    if actual != expected:
        print(f"FAIL {name}\n  actual  = {actual}\n  expected= {expected}")
        return False
    print(f"OK   {name}")
    return True


def check_frame():
    v = load("frame-video.json")
    f = v["frame"]
    header = struct.pack(
        ">IBBBBqI",
        int(f["magic_hex"], 16),
        f["version"],
        f["type_code"],
        int(f["flags_hex"], 16),
        0,
        f["pts_us"],
        f["payload_len"],
    )
    actual = hx(header + bytes.fromhex(v["payload_hex"]))
    return check("frame-video", actual, v["frame_hex"])


def check_hello():
    v = load("hello.json")
    p = v["payload"]
    name = p["device_name"].encode("utf-8")
    payload = struct.pack(">HHIH", p["min_version"], p["max_version"], p["capabilities"], len(name)) + name
    return check("hello", hx(payload), v["payload_hex"])


def hkdf_sha256(ikm, salt, info, length):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def check_hkdf():
    v = load("hkdf-rfc5869-case1.json")
    okm = hkdf_sha256(
        bytes.fromhex(v["ikm_hex"]),
        bytes.fromhex(v["salt_hex"]),
        bytes.fromhex(v["info_hex"]),
        v["length"],
    )
    return check("hkdf-rfc5869-case1", hx(okm), v["okm_hex"])


def check_anchor():
    v = load("code-anchor.json")
    credential = v["credential"]
    client = bytes.fromhex(v["client_key_share_hex"])
    server = bytes.fromhex(v["server_key_share_hex"])
    anchor = hashlib.sha256((v["prefix"] + credential).encode("utf-8")).digest()
    client_proof = hmac.new(anchor, b"client|" + client, hashlib.sha256).digest()
    server_proof = hmac.new(anchor, b"server|" + client + server, hashlib.sha256).digest()
    ok = check("code-anchor/anchor", hx(anchor), v["anchor_tag_hex"])
    ok &= check("code-anchor/client", hx(client_proof), v["client_proof_hex"])
    ok &= check("code-anchor/server", hx(server_proof), v["server_proof_hex"])
    return ok


def check_aes_shape():
    """Python 标准库没有 AES-GCM；这里只校验向量结构，密文由 protocol-core 的 JVM 测试覆盖。"""
    v = load("aes-gcm-frame.json")
    payload = bytes.fromhex(v["wire_payload_hex"])
    ok = (
        payload[:12] == bytes.fromhex(v["iv_hex"])
        and len(payload) == 12 + 16 + len(bytes.fromhex(v["plaintext_hex"]))
        and v["wire_payload_hex"] == v["iv_hex"] + v["ciphertext_and_tag_hex"]
    )
    return check("aes-gcm-frame/shape", "ok" if ok else "mismatch", "ok")


def main():
    results = [check_frame(), check_hello(), check_hkdf(), check_anchor(), check_aes_shape()]
    if all(results):
        print("\nALL VECTORS PASS")
        return 0
    print("\nVECTOR CHECK FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
