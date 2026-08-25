"""Android TV bridge for the iOS Control Center Apple TV Remote."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import secrets
import time
from dataclasses import dataclass

from java import jclass
from atvr4samsung.companion.protocol.appletv import FakeCompanionService, FakeCompanionState
from atvr4samsung.companion.protocol.enums import FrameType, TouchAction

Bridge = jclass("com.sagi.appleremotebridge.CompanionPythonBridge")
_LOG = logging.getLogger("AndroidCompanion")
_LOOP = None
_SERVER = None

@dataclass(frozen=True)
class _WindowRecord:
    pin: str
    expires_at: float
    generation: str

class _PairingWindow:
    """Stable in-memory enrollment window matching the protocol's expected shape."""
    def __init__(self, pin: str = "1337", duration_seconds: float = 3600.0):
        self._window = _WindowRecord(
            pin=str(pin).zfill(4),
            expires_at=time.time() + duration_seconds,
            generation=secrets.token_hex(16),
        )

    def active(self):
        if time.time() >= self._window.expires_at:
            return None
        return self._window

class AndroidCompanionService(FakeCompanionService):
    def __init__(self, state, *, identifier: str, private_key: bytes, pairing_window):
        super().__init__(
            state,
            device_name="Xiaomi TV",
            unique_id=identifier,
            private_key=private_key,
            paired_clients=None,
            require_paired=False,
            pairing_window=pairing_window,
        )
        self._android_touch_start = None

    def handle_auth_frame(self, frame_type, data):
        labels = {
            FrameType.PS_Start: "Pair Setup M1",
            FrameType.PS_Next: "Pair Setup next",
            FrameType.PV_Start: "Pair Verify M1",
            FrameType.PV_Next: "Pair Verify next",
        }
        try:
            Bridge.onStatus(labels.get(frame_type, f"Auth {frame_type}"))
            ok = super().handle_auth_frame(frame_type, data)
            Bridge.onStatus(
                f"{labels.get(frame_type, 'Auth')} -> {'OK' if ok else 'FAILED'} • phase {self.authentication_phase.name}"
            )
            return ok
        except Exception as exc:
            Bridge.onStatus(f"PAIRING EXCEPTION: {type(exc).__name__}: {exc}")
            raise

    def _dispatch(self, command: str):
        try:
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

    private_key = hashlib.sha256(
        ("AndroidAppleRemote:Xiaomi:v2:" + identifier).encode("utf-8")
    ).digest()
    pairing_window = _PairingWindow("1337", 3600.0)
    state = FakeCompanionState()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _LOOP = loop

    def factory():
        return AndroidCompanionService(
            state,
            identifier=identifier,
            private_key=private_key,
            pairing_window=pairing_window,
        )

    server = loop.run_until_complete(loop.create_server(factory, "0.0.0.0", 0))
    _SERVER = server
    port = int(server.sockets[0].getsockname()[1])
    Bridge.onServerReady(port)
    Bridge.onStatus("Xiaomi TV ready • Pairing PIN 1337")

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
