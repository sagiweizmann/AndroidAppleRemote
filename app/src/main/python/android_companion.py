"""Android TV bridge for the iOS Control Center Apple TV Remote.

Uses the real atvr4samsung Companion auth implementation with Android-private
persistence for the enrollment window and paired iPhone long-term public keys.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from java import jclass
from atvr4samsung.companion.protocol.appletv import FakeCompanionService, FakeCompanionState
from atvr4samsung.companion.protocol.enums import FrameType, TouchAction

Bridge = jclass("com.sagi.appleremotebridge.CompanionPythonBridge")
_LOG = logging.getLogger("AndroidCompanion")
_LOOP = None
_SERVER = None

PIN = "1337"
DEVICE_NAME = "Xiaomi TV"


def _atomic_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(value, f, separators=(",", ":"))
        f.flush()
        try:
            os.fsync(f.fileno())
        except OSError:
            pass
    os.replace(tmp, path)


@dataclass(frozen=True)
class _WindowRecord:
    pin: str
    expires_at: float
    generation: str
    server_identifier: str
    server_generation: str


class AndroidPairingWindow:
    """Protocol-compatible pairing window, bound to this server identity."""
    def __init__(self, path: Path, server_identifier: str, server_generation: str):
        self.path = path
        self.server_identifier = server_identifier
        self.server_generation = server_generation
        self._record = _WindowRecord(
            pin=PIN,
            expires_at=time.time() + 24 * 60 * 60,
            generation=secrets.token_hex(16),
            server_identifier=server_identifier,
            server_generation=server_generation,
        )
        self._save()

    def _save(self):
        _atomic_json(self.path, {
            "pin": self._record.pin,
            "expires_at": self._record.expires_at,
            "generation": self._record.generation,
            "server_identifier": self._record.server_identifier,
            "server_generation": self._record.server_generation,
        })

    @contextmanager
    def transaction(self):
        yield

    def active(self):
        r = self._record
        return r if time.time() < r.expires_at else None

    def active_for_server(self, server_identifier: str, server_generation: str):
        r = self.active()
        if r is None:
            return None
        if r.server_identifier != server_identifier or r.server_generation != server_generation:
            return None
        return r

    def mutate_if_current(self, generation, mutation, *, server_identifier=None, server_generation=None):
        r = self.active()
        if r is None or r.generation != generation:
            return False, None
        if server_identifier is not None and r.server_identifier != server_identifier:
            return False, None
        if server_generation is not None and r.server_generation != server_generation:
            return False, None
        return True, mutation()


class AndroidPairedClients:
    """Small persistent identifier -> LTPK store with the interface auth.py requires."""
    def __init__(self, path: Path):
        self.path = path
        self._clients = {}
        self._load()

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if isinstance(raw, dict):
                self._clients = {
                    str(k): str(v) for k, v in raw.items()
                    if isinstance(k, str) and isinstance(v, str) and len(v) == 64
                }
        except (FileNotFoundError, OSError, ValueError, TypeError):
            self._clients = {}

    def _save(self):
        _atomic_json(self.path, self._clients)

    def reset_in_progress(self):
        return False

    def add(self, identifier: str, ltpk: bytes):
        self.add_locked(identifier, ltpk)

    def add_locked(self, identifier: str, ltpk: bytes):
        if not identifier or not isinstance(ltpk, (bytes, bytearray)) or len(ltpk) != 32:
            raise ValueError("invalid paired client")
        self._clients[str(identifier)] = bytes(ltpk).hex()
        self._save()
        Bridge.onStatus("Pair Setup M5 • iPhone credential saved")

    def ltpk(self, identifier: str):
        self._load()
        value = self._clients.get(identifier)
        if not value:
            return None
        try:
            return bytes.fromhex(value)
        except ValueError:
            return None

    def authorizes(self, identifier: str, ltpk: bytes):
        expected = self.ltpk(identifier)
        return expected is not None and hmac.compare_digest(expected, bytes(ltpk))

    def count(self):
        self._load()
        return len(self._clients)

    def empty(self):
        return self.count() == 0


class AndroidCompanionService(FakeCompanionService):
    def __init__(self, state, *, identifier, private_key, server_generation, pairing_window, paired_clients):
        super().__init__(
            state,
            device_name=DEVICE_NAME,
            unique_id=identifier,
            private_key=private_key,
            server_identity_generation=server_generation,
            paired_clients=paired_clients,
            require_paired=True,
            pairing_window=pairing_window,
        )
        self._android_touch_start = None

    def handle_auth_frame(self, frame_type, data):
        label = {
            FrameType.PS_Start: "PS M1",
            FrameType.PS_Next: "PS NEXT",
            FrameType.PV_Start: "PV M1",
            FrameType.PV_Next: "PV NEXT",
        }.get(frame_type, f"AUTH {frame_type}")
        try:
            Bridge.onStatus(f"Pairing • {label} • phase {self.authentication_phase.name}")
            ok = super().handle_auth_frame(frame_type, data)
            Bridge.onStatus(f"Pairing • {label} -> {'OK' if ok else 'FAILED'} • {self.authentication_phase.name}")
            if ok and self.authentication_phase.name == "ENCRYPTED":
                Bridge.onStatus("PAIRING COMPLETE • encrypted remote connected")
            return ok
        except Exception as exc:
            Bridge.onStatus(f"PAIRING EXCEPTION • {type(exc).__name__}: {exc}")
            raise

    def _dispatch(self, command: str):
        try:
            Bridge.onStatus(f"REMOTE • {command}")
            Bridge.dispatch(command)
        except Exception as exc:
            _LOG.exception("Android dispatch failed for %s: %s", command, exc)

    def handle__hidc(self, message):
        before = self.session.latest_button
        super().handle__hidc(message)
        after = self.session.latest_button
        if after and after != before:
            command = {
                "up":"UP", "down":"DOWN", "left":"LEFT", "right":"RIGHT",
                "select":"OK", "menu":"BACK", "home":"HOME",
                "play_pause":"PLAY_PAUSE", "volume_up":"VOLUME_UP",
                "volume_down":"VOLUME_DOWN", "mute":"MUTE",
            }.get(after)
            if command:
                self._dispatch(command)

    def handle__mcc(self, message):
        before = self.session.latest_button
        super().handle__mcc(message)
        after = self.session.latest_button
        if after and after != before:
            command = {
                "play":"PLAY_PAUSE", "pause":"PLAY_PAUSE",
                "next":"MEDIA_NEXT", "previous":"MEDIA_PREVIOUS",
            }.get(after)
            if command:
                self._dispatch(command)

    def handle__touchstart(self, message):
        self._android_touch_start = None
        super().handle__touchstart(message)

    def handle__touchstop(self, message):
        self._android_touch_start = None
        super().handle__touchstop(message)

    def handle__hidt(self, message):
        content = message.get("_c", {})
        phase = int(content.get("_tPh", -1))
        x = int(content.get("_cx", 0))
        y = int(content.get("_cy", 0))
        try:
            action = TouchAction(phase)
        except Exception:
            action = None
        if action == TouchAction.Press:
            self._android_touch_start = (x, y)
        elif action == TouchAction.Click:
            self._dispatch("OK")
            self._android_touch_start = None
        elif action == TouchAction.Release:
            start = self._android_touch_start
            self._android_touch_start = None
            if start is not None:
                dx, dy = x - start[0], y - start[1]
                if abs(dx) >= 28 or abs(dy) >= 28:
                    if abs(dx) > abs(dy):
                        self._dispatch("RIGHT" if dx > 0 else "LEFT")
                    else:
                        self._dispatch("DOWN" if dy > 0 else "UP")
        super().handle__hidt(message)


def run_server(identifier: str):
    global _LOOP, _SERVER
    if _LOOP is not None:
        return

    state_dir = Path(str(Bridge.getStateDir())) / "companion-v3"
    state_dir.mkdir(parents=True, exist_ok=True)

    private_key = hashlib.sha256(
        ("AndroidAppleRemote:Xiaomi:v3:" + identifier).encode("utf-8")
    ).digest()
    server_generation = hashlib.sha256(
        ("generation:v3:" + identifier).encode("utf-8")
    ).hexdigest()[:32]

    pairing_window = AndroidPairingWindow(
        state_dir / "pairing-window.json", identifier, server_generation
    )
    paired_clients = AndroidPairedClients(state_dir / "paired-clients.json")
    state = FakeCompanionState()

    Bridge.onStatus(
        f"Pairing state ready • PIN {PIN} • paired clients {paired_clients.count()}"
    )

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _LOOP = loop

    def factory():
        return AndroidCompanionService(
            state,
            identifier=identifier,
            private_key=private_key,
            server_generation=server_generation,
            pairing_window=pairing_window,
            paired_clients=paired_clients,
        )

    server = loop.run_until_complete(loop.create_server(factory, "0.0.0.0", 0))
    _SERVER = server
    port = int(server.sockets[0].getsockname()[1])
    Bridge.onServerReady(port)
    Bridge.onStatus(f"{DEVICE_NAME} ready • PIN {PIN} • waiting for iPhone")

    try:
        loop.run_forever()
    finally:
        server.close()
        loop.run_until_complete(server.wait_closed())
        loop.close()
        _SERVER = None
        _LOOP = None


def stop_server():
    loop = _LOOP
    if loop is not None:
        loop.call_soon_threadsafe(loop.stop)
