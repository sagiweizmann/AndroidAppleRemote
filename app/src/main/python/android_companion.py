"""Android TV bridge for the iOS Control Center Apple TV Remote.

The actual Companion Link protocol implementation is vendored at build time from
vb3/atvr4samsung (MIT), whose server was validated against a real iPhone on iOS 26.
This module only adapts that protocol to Android: it exposes a fixed enrollment PIN,
starts the Companion TCP server, and forwards decoded remote events into Kotlin.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
from types import SimpleNamespace

from java import jclass

from atvr4samsung.companion.protocol.appletv import (
    FakeCompanionService,
    FakeCompanionState,
)
from atvr4samsung.companion.protocol.enums import TouchAction

Bridge = jclass("com.sagi.appleremotebridge.CompanionPythonBridge")
_LOG = logging.getLogger("AndroidCompanion")

_LOOP = None
_SERVER = None


class _PairingWindow:
    """Minimal always-open enrollment window for the current prototype.

    The PIN is deliberately visible in the app UI. Once pairing is proven on the
    target TV, this should become a short-lived random PIN persisted in Android.
    """

    def __init__(self, pin: int = 1337):
        self.pin = pin

    def active(self):
        return SimpleNamespace(pin=self.pin, expires_at=None, generation=None)


class AndroidCompanionService(FakeCompanionService):
    def __init__(self, state, *, identifier: str, private_key: bytes, pairing_window):
        super().__init__(
            state,
            device_name="Android TV",
            unique_id=identifier,
            private_key=private_key,
            paired_clients=None,
            require_paired=False,
            pairing_window=pairing_window,
        )
        self._android_touch_start = None

    def _dispatch(self, command: str):
        try:
            Bridge.dispatch(command)
        except Exception as exc:
            _LOG.exception("Android dispatch failed for %s: %s", command, exc)

    def handle__hidc(self, message):
        before = self.session.latest_button
        super().handle__hidc(message)
        after = self.session.latest_button
        # The base server marks a completed button when the UP frame arrives.
        if after and after != before:
            mapping = {
                "up": "UP",
                "down": "DOWN",
                "left": "LEFT",
                "right": "RIGHT",
                "select": "OK",
                "menu": "BACK",
                "home": "HOME",
                "play_pause": "PLAY_PAUSE",
                "volume_up": "VOLUME_UP",
                "volume_down": "VOLUME_DOWN",
                "mute": "MUTE",
            }
            command = mapping.get(after)
            if command:
                self._dispatch(command)

    def handle__mcc(self, message):
        before = self.session.latest_button
        super().handle__mcc(message)
        after = self.session.latest_button
        if after and after != before:
            mapping = {
                "play": "PLAY_PAUSE",
                "pause": "PLAY_PAUSE",
                "next": "MEDIA_NEXT",
                "previous": "MEDIA_PREVIOUS",
            }
            command = mapping.get(after)
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
                dx = x - start[0]
                dy = y - start[1]
                # iOS touchpad coordinates are normally a few hundred units wide/high.
                # Ignore tiny motion so a tap doesn't accidentally navigate.
                threshold = 28
                if abs(dx) >= threshold or abs(dy) >= threshold:
                    if abs(dx) > abs(dy):
                        self._dispatch("RIGHT" if dx > 0 else "LEFT")
                    else:
                        self._dispatch("DOWN" if dy > 0 else "UP")

        super().handle__hidt(message)


def run_server(identifier: str):
    """Blocking entry point called on a dedicated Kotlin thread."""
    global _LOOP, _SERVER

    if _LOOP is not None:
        return

    private_key = hashlib.sha256(
        ("AndroidAppleRemote:identity:" + identifier).encode("utf-8")
    ).digest()
    pairing_window = _PairingWindow(1337)
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
    Bridge.onStatus("Companion server ready. Pairing PIN: 1337")

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
