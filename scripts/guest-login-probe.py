#!/usr/bin/env python3
import socket
import struct
import sys
import uuid
import zlib

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 25577
PROTOCOL = int(sys.argv[3]) if len(sys.argv) > 3 else 769
CLAIMED_NAME = sys.argv[4] if len(sys.argv) > 4 else "ClientClaim"
CLAIMED_UUID = uuid.UUID("00000000-0000-4000-8000-000000000001")


def write_varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varint_from_bytes(data: bytes, pos: int = 0):
    result = 0
    shift = 0
    while True:
        if pos >= len(data):
            raise EOFError("truncated VarInt")
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7
        if shift >= 35:
            raise ValueError("VarInt too long")


def read_varint(sock: socket.socket) -> int:
    result = 0
    shift = 0
    while True:
        raw = sock.recv(1)
        if not raw:
            raise EOFError("socket closed reading VarInt")
        b = raw[0]
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result
        shift += 7
        if shift >= 35:
            raise ValueError("VarInt too long")


def mc_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return write_varint(len(encoded)) + encoded


def frame(packet_id: int, payload: bytes, compression_threshold=None) -> bytes:
    body = write_varint(packet_id) + payload
    if compression_threshold is None:
        return write_varint(len(body)) + body
    if len(body) >= compression_threshold:
        compressed = zlib.compress(body)
        framed = write_varint(len(body)) + compressed
    else:
        framed = b"\x00" + body
    return write_varint(len(framed)) + framed


def recv_exact(sock: socket.socket, length: int) -> bytes:
    out = bytearray()
    while len(out) < length:
        chunk = sock.recv(length - len(out))
        if not chunk:
            raise EOFError("socket closed")
        out.extend(chunk)
    return bytes(out)


def read_packet(sock: socket.socket, compression_threshold=None):
    length = read_varint(sock)
    framed = recv_exact(sock, length)
    if compression_threshold is not None:
        data_length, pos = read_varint_from_bytes(framed)
        payload = framed[pos:]
        if data_length:
            payload = zlib.decompress(payload)
            if len(payload) != data_length:
                raise ValueError("bad decompressed packet length")
    else:
        payload = framed
    packet_id, pos = read_varint_from_bytes(payload)
    return packet_id, payload[pos:]


with socket.create_connection((HOST, PORT), timeout=10) as sock:
    sock.settimeout(10)

    handshake = (
        write_varint(PROTOCOL)
        + mc_string(HOST)
        + struct.pack(">H", PORT)
        + write_varint(2)
    )
    sock.sendall(frame(0x00, handshake))

    # 1.21.4 Login Start: username + client-provided UUID.
    sock.sendall(frame(0x00, mc_string(CLAIMED_NAME) + CLAIMED_UUID.bytes))

    compression = None
    while True:
        packet_id, payload = read_packet(sock, compression)

        if packet_id == 0x00:
            try:
                length, pos = read_varint_from_bytes(payload)
                reason = payload[pos:pos + length].decode("utf-8", errors="replace")
            except Exception:
                reason = payload.hex()
            raise SystemExit(f"server disconnected before guest login success: {reason}")

        if packet_id == 0x01:
            raise SystemExit("received encryption request on explicitly guest/offline admission")

        if packet_id == 0x03:
            threshold, _ = read_varint_from_bytes(payload)
            compression = threshold
            continue

        if packet_id == 0x04:
            message_id, _ = read_varint_from_bytes(payload)
            response = write_varint(message_id) + b"\x00"
            sock.sendall(frame(0x02, response, compression))
            continue

        if packet_id == 0x02:
            if len(payload) < 16:
                raise SystemExit("truncated login success")
            assigned_uuid = uuid.UUID(bytes=payload[:16])
            name_len, pos = read_varint_from_bytes(payload, 16)
            assigned_name = payload[pos:pos + name_len].decode("utf-8")

            if assigned_uuid == CLAIMED_UUID:
                raise SystemExit("guest UUID was not replaced by proxy authority")
            if assigned_name == CLAIMED_NAME:
                raise SystemExit("guest username was not replaced by proxy authority")
            if not assigned_name.startswith("Guest_") or len(assigned_name) != 16:
                raise SystemExit(f"unexpected guest game name: {assigned_name!r}")

            print("guest_login=PASS")
            print(f"assigned_uuid={assigned_uuid}")
            print(f"assigned_name={assigned_name}")
            break
