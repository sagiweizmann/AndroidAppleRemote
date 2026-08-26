"""Android TV bridge for the iOS Control Center Apple TV Remote."""
from __future__ import annotations
import asyncio, hashlib, hmac, json, os, secrets, time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from java import jclass
from atvr4samsung.companion.protocol.appletv import FakeCompanionService, FakeCompanionState
from atvr4samsung.companion.protocol.enums import FrameType, TouchAction

Bridge = jclass("com.sagi.appleremotebridge.CompanionPythonBridge")
_LOOP = None
_SERVER = None
PIN = "1337"

# Captured iOS 26 Companion HID wire codes from atvr4samsung's live-tested bridge.
HID_TO_ANDROID = {
    1: "UP", 2: "DOWN", 3: "LEFT", 4: "RIGHT",
    5: "BACK", 6: "OK", 7: "HOME",
    8: "VOLUME_UP", 9: "VOLUME_DOWN",
    14: "PLAY_PAUSE", 18: "MUTE", 19: "POWER",
}


def trace(m):
    try: Bridge.onStatus(m)
    except Exception: pass


def _atomic_json(p, v):
    p.parent.mkdir(parents=True, exist_ok=True)
    t = p.with_suffix(p.suffix + ".tmp")
    with open(t, "w", encoding="utf-8") as f:
        json.dump(v, f, separators=(",", ":")); f.flush()
    os.replace(t, p)


@dataclass(frozen=True)
class _WindowRecord:
    pin: str; expires_at: float; generation: str; server_identifier: str; server_generation: str


class AndroidPairingWindow:
    def __init__(self, p, i, g):
        self.path = p; self.state_dir = p.parent; self.server_identifier = i; self.server_generation = g
        self._record = _WindowRecord(PIN, time.time() + 86400, secrets.token_hex(16), i, g); self._save()
    def _save(self): _atomic_json(self.path, self._record.__dict__)
    @contextmanager
    def transaction(self): yield
    def active(self): return self._record if time.time() < self._record.expires_at else None
    def active_for_server(self, a, b):
        r = self.active(); return r if r and r.server_identifier == a and r.server_generation == b else None
    def mutate_if_current(self, generation, mutation, *, server_identifier=None, server_generation=None):
        r = self.active()
        if not r or r.generation != generation or (server_identifier is not None and r.server_identifier != server_identifier) or (server_generation is not None and r.server_generation != server_generation):
            return False, None
        return True, mutation()


class AndroidPairedClients:
    def __init__(self, p): self.path = p; self._clients = {}; self._load()
    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f: r = json.load(f)
            self._clients = {str(k): str(v) for k, v in r.items() if isinstance(k, str) and isinstance(v, str) and len(v) == 64} if isinstance(r, dict) else {}
        except Exception: self._clients = {}
    def _save(self): _atomic_json(self.path, self._clients)
    def reset_in_progress(self): return False
    def add(self, i, k): self.add_locked(i, k)
    def add_locked(self, i, k):
        if not i or not isinstance(k, (bytes, bytearray)) or len(k) != 32: raise ValueError("invalid paired client")
        self._clients[str(i)] = bytes(k).hex(); self._save(); trace("PS M5 • iPhone credential saved")
    def ltpk(self, i):
        self._load(); v = self._clients.get(i)
        try: return bytes.fromhex(v) if v else None
        except ValueError: return None
    def authorizes(self, i, k):
        e = self.ltpk(i); return e is not None and hmac.compare_digest(e, bytes(k))
    def count(self): self._load(); return len(self._clients)
    def empty(self): return self.count() == 0


class AndroidCompanionService(FakeCompanionService):
    _seq = 0
    def __init__(self, state, *, name, identifier, private_key, server_generation, pairing_window, paired_clients):
        super().__init__(state, device_name=name, unique_id=identifier, private_key=private_key,
            server_identity_generation=server_generation, paired_clients=paired_clients,
            require_paired=True, pairing_window=pairing_window)
        type(self)._seq += 1
        self.cid = type(self)._seq
        self._android_touch_start = None
        self._rx = 0; self._tx = 0; self._last_ok = 0.0

    # Android rotates into a fresh UUID/generation/state directory, so Linux recovery fencing is irrelevant.
    def _identity_reset_in_progress(self): return False

    def connection_made(self, t):
        trace(f"TCP #{self.cid} CONNECT • {t.get_extra_info('peername')}")
        return super().connection_made(t)
    def connection_lost(self, e):
        trace(f"TCP #{self.cid} DISCONNECT • rx={self._rx} tx={self._tx} • {e or 'peer closed'}")
        return super().connection_lost(e)
    def data_received(self, d):
        self._rx += 1; trace(f"TCP #{self.cid} RX#{self._rx} • {len(d)} bytes • encrypted={'YES' if self.chacha else 'NO'}")
        return super().data_received(d)
    def send_to_client(self, f, d):
        self._tx += 1; trace(f"TCP #{self.cid} TX#{self._tx} • {getattr(f, 'name', f)} • encrypted={'YES' if self.chacha else 'NO'}")
        return super().send_to_client(f, d)
    def enable_encryption(self, o, i):
        trace(f"TCP #{self.cid} • INSTALL AEAD KEYS")
        r = super().enable_encryption(o, i); trace(f"TCP #{self.cid} • ENCRYPTION ACTIVE"); return r
    def handle_auth_frame(self, f, d):
        label = {FrameType.PS_Start:"PS M1", FrameType.PS_Next:"PS NEXT", FrameType.PV_Start:"PV M1", FrameType.PV_Next:"PV NEXT"}.get(f, str(f))
        trace(f"PAIR • {label} • {self.authentication_phase.name}")
        ok = super().handle_auth_frame(f, d)
        trace(f"PAIR • {label} -> {'OK' if ok else 'FAILED'} • {self.authentication_phase.name}")
        return ok

    # Log actual OPACK command identifiers/keys instead of only E_OPACK.
    def handle_command_frame(self, frame_type, data):
        if isinstance(data, dict):
            ident = data.get("_i") or data.get("_t") or data.get("_m") or "?"
            keys = ",".join(str(k) for k in data.keys())
            ckeys = ",".join(str(k) for k in data.get("_c", {}).keys()) if isinstance(data.get("_c"), dict) else ""
            trace(f"OPACK RX • id={ident} • keys={keys} • content={ckeys}")
        return super().handle_command_frame(frame_type, data)

    def _dispatch(self, c):
        # Center tap arrives as both HID Select and touch click; collapse the duplicate.
        if c == "OK":
            now = time.monotonic()
            if now - self._last_ok < 0.35: return
            self._last_ok = now
        trace(f"REMOTE • {c}")
        Bridge.dispatch(c)

    # Live iOS 26 mapping from atvr4samsung: _hBtS 1=press, 2=release; act once on release.
    def handle__hidc(self, m):
        super().handle__hidc(m)
        try:
            c = m["_c"]; code = int(c["_hidC"]); state = int(c["_hBtS"])
            trace(f"HID • code={code} state={state}")
            if state == 2:
                cmd = HID_TO_ANDROID.get(code)
                if cmd: self._dispatch(cmd)
        except Exception as e: trace(f"HID decode error • {type(e).__name__}: {e}")

    def handle__mcc(self, m):
        before = self.session.latest_button
        super().handle__mcc(m)
        after = self.session.latest_button
        if after and after != before:
            cmd = {"play":"PLAY_PAUSE", "pause":"PLAY_PAUSE", "next":"MEDIA_NEXT", "previous":"MEDIA_PREVIOUS"}.get(after)
            if cmd: self._dispatch(cmd)

    # Critical iOS 26 connection gate from atvr4samsung: RPHIDTouchSession requires a device ID.
    # The protocol base replies with {}, which leaves Control Center stuck on Connecting and then disconnects.
    def handle__touchstart(self, m):
        try:
            c = m.get("_c", {})
            self.session.touch_width = int(c.get("_width", 0)); self.session.touch_height = int(c.get("_height", 0))
        except Exception: pass
        self._android_touch_start = None
        trace("SESSION • _touchStart -> touch device id 1")
        self.send_response(m, {"_i": 1})

    def handle__touchstop(self, m):
        self._android_touch_start = None
        return super().handle__touchstop(m)

    def handle__hidt(self, m):
        c = m.get("_c", {}); p = int(c.get("_tPh", -1)); x = int(c.get("_cx", 0)); y = int(c.get("_cy", 0))
        try: a = TouchAction(p)
        except Exception: a = None
        trace(f"TOUCH • phase={getattr(a, 'name', p)} x={x} y={y}")
        if a == TouchAction.Press: self._android_touch_start = (x, y)
        elif a == TouchAction.Click: self._dispatch("OK"); self._android_touch_start = None
        elif a == TouchAction.Release:
            q = self._android_touch_start; self._android_touch_start = None
            if q:
                dx, dy = x - q[0], y - q[1]
                if abs(dx) >= 28 or abs(dy) >= 28:
                    self._dispatch(("RIGHT" if dx > 0 else "LEFT") if abs(dx) > abs(dy) else ("DOWN" if dy > 0 else "UP"))
        return super().handle__hidt(m)

    # iOS 26 session hygiene copied from the Samsung bridge: don't answer common fetches with RPError.
    def handle_fetchsupportedactionsevent(self, m):
        trace("SESSION • FetchSupportedActionsEvent")
        self.send_response(m, {})
    def handle_fetchmediacontrolstatus(self, m):
        trace("SESSION • FetchMediaControlStatus -> volume supported")
        self.send_response(m, {"MediaControlFlags": 256})


def run_server(identifier, name, generation):
    global _LOOP, _SERVER
    if _LOOP is not None: return
    generation = int(generation)
    state_dir = Path(str(Bridge.getStateDir())) / f"companion-runtime-{generation}-{identifier}"
    state_dir.mkdir(parents=True, exist_ok=True)
    private_key = hashlib.sha256(f"AndroidAppleRemote:runtime:{generation}:{identifier}".encode()).digest()
    sg = hashlib.sha256(f"generation:runtime:{generation}:{identifier}".encode()).hexdigest()[:32]
    w = AndroidPairingWindow(state_dir / "pairing-window.json", identifier, sg)
    clients = AndroidPairedClients(state_dir / "paired-clients.json")
    state = FakeCompanionState()
    trace(f"STATE • {name} • PIN {PIN} • paired clients {clients.count()}")
    loop = asyncio.new_event_loop(); asyncio.set_event_loop(loop); _LOOP = loop
    def factory(): return AndroidCompanionService(state, name=name, identifier=identifier, private_key=private_key, server_generation=sg, pairing_window=w, paired_clients=clients)
    server = loop.run_until_complete(loop.create_server(factory, "0.0.0.0", 0)); _SERVER = server
    port = int(server.sockets[0].getsockname()[1]); Bridge.onServerReady(port); trace(f"{name} READY • port {port}")
    try: loop.run_forever()
    finally:
        server.close(); loop.run_until_complete(server.wait_closed()); loop.close(); _SERVER = None; _LOOP = None


def stop_server():
    if _LOOP is not None: _LOOP.call_soon_threadsafe(_LOOP.stop)
