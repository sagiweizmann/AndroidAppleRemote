#!/usr/bin/env python3
"""Let the iOS Apple TV Remote reach the app running on an Android emulator.

The emulator sits behind the emulator's user-mode NAT (10.0.2.x), so it can
neither get its mDNS advert onto the real LAN nor accept an inbound TCP
connection from the iPhone. This script closes both gaps from the host:

  * re-advertises the app's _companion-link._tcp service on the LAN, with the
    app's exact TXT records, pointing at this Mac instead of the guest
  * relays TCP from a LAN-facing port into the guest via `adb forward`

Usage: start the app on the emulator, run this, leave it running, then pick the
device in Control Center -> Apple TV Remote (PIN 1337). Re-deploying the app is
fine: the guest's ephemeral port is re-detected automatically and the advert
stays valid, since the LAN-facing port never changes.

macOS only (uses dns-sd). Requires the app to have registered at least once
while the current logcat buffer was live.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import os
import re
import shutil
import subprocess
import sys

SERVICE_TYPE = "_companion-link._tcp"
POLL_SECONDS = 3


def host_ip() -> str:
    for iface in ("en0", "en1"):
        out = subprocess.run(["ipconfig", "getifaddr", iface],
                             capture_output=True, text=True).stdout.strip()
        if out:
            return out
    sys.exit("no LAN address on en0/en1; are you on Wi-Fi?")


def start_advert(name: str, port: int, txt: list[str], ip: str) -> subprocess.Popen:
    """Advertise the service on the LAN under its own hostname.

    -P (proxy) rather than -R: -R would publish under this Mac's hostname, telling
    iOS that an Apple TV lives on a host it already knows as the user's Mac. A
    synthetic host record keeps the emulated device a separate identity.
    """
    slug = re.sub(r"[^a-z0-9-]+", "-", name.lower()).strip("-") or "android-companion"
    return subprocess.Popen(
        ["dns-sd", "-P", name, SERVICE_TYPE, "local.", str(port), f"{slug}.local.", ip, *txt],
        stdout=subprocess.DEVNULL,
    )


def find_adb() -> str:
    for candidate in (
        os.environ.get("ADB"),
        shutil.which("adb"),
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ):
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    sys.exit("adb not found; set ADB=/path/to/adb")


def decode_txt(encoded: str) -> list[str]:
    """DNS-SD TXT records are length-prefixed strings, base64'd by NsdService."""
    raw = base64.b64decode(encoded)
    out, i = [], 0
    while i < len(raw):
        length = raw[i]
        i += 1
        out.append(raw[i : i + length].decode("utf-8", "replace"))
        i += length
    return out


def read_service(adb: str) -> tuple[str, int, list[str]] | None:
    """Pull the app's most recent mDNS registration back out of logcat.

    NsdService logs the whole registration, which saves us from having to
    recompute the app's identity-derived TXT records on this side.
    """
    try:
        log = subprocess.run(
            [adb, "logcat", "-d", "-s", "NsdService"],
            capture_output=True, text=True, timeout=30,
        ).stdout
    except subprocess.SubprocessError as exc:
        print(f"! logcat failed: {exc}")
        return None

    newest = None
    for line in log.splitlines():
        marker = "mdnssd [register, "
        at = line.find(marker)
        if at == -1 or SERVICE_TYPE not in line:
            continue
        body = line[at + len(marker) :].rstrip().rstrip("]")
        parts = [p.strip() for p in body.split(", ")]
        if len(parts) < 4:
            continue
        # Layout: id, name, type, port, base64 TXT. Name can itself contain ", ".
        try:
            port = int(parts[-2])
        except ValueError:
            continue
        newest = (", ".join(parts[1:-3]), port, decode_txt(parts[-1]))
    return newest


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while chunk := await reader.read(65536):
            writer.write(chunk)
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        writer.close()


def make_relay(local_port: int):
    async def handle(client_reader, client_writer):
        peer = client_writer.get_extra_info("peername")
        print(f"-> connection from {peer[0]}:{peer[1]}")
        try:
            guest_reader, guest_writer = await asyncio.open_connection("127.0.0.1", local_port)
        except OSError as exc:
            print(f"!  cannot reach guest via adb forward: {exc}")
            client_writer.close()
            return
        await asyncio.gather(
            pipe(client_reader, guest_writer),
            pipe(guest_reader, client_writer),
        )
        print(f"<- {peer[0]}:{peer[1]} disconnected")

    return handle


async def main() -> int:
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", type=int, default=49153, help="LAN-facing port (default: 49153)")
    ap.add_argument("--local-port", type=int, default=49152, help="loopback port for adb forward")
    args = ap.parse_args()

    adb = find_adb()
    service = read_service(adb)
    if service is None:
        return print(
            "No _companion-link._tcp registration found in logcat.\n"
            "Start the app on the emulator, then run this again."
        ) or 1

    name, guest_port, txt = service
    print(f"app: {name!r} listening on guest port {guest_port}")

    subprocess.run([adb, "forward", f"tcp:{args.local_port}", f"tcp:{guest_port}"], check=True)
    server = await asyncio.start_server(make_relay(args.local_port), "0.0.0.0", args.port)
    print(f"relay: 0.0.0.0:{args.port} -> guest:{guest_port}")

    ip = host_ip()
    advert = start_advert(name, args.port, txt, ip)
    print(f"advertising {name!r} at {ip}:{args.port} with {len(txt)} TXT records")
    print("\nControl Center -> Apple TV Remote -> "
          f"{name}   (PIN 1337)\nCtrl-C to stop.\n")

    try:
        async with server:
            while True:
                await asyncio.sleep(POLL_SECONDS)
                if advert.poll() is not None:
                    print("! dns-sd exited")
                    return 1
                current = read_service(adb)
                if not current:
                    continue
                if current[1] != guest_port:
                    guest_port = current[1]
                    print(f"app restarted; re-pointing relay at guest port {guest_port}")
                    subprocess.run(
                        [adb, "forward", f"tcp:{args.local_port}", f"tcp:{guest_port}"], check=False
                    )
                if (current[0], current[2]) != (name, txt):
                    # Identity rotated. A stale advert would hand iOS the wrong
                    # rpMRtID/rpHI and pairing could never succeed.
                    name, txt = current[0], current[2]
                    print(f"identity changed; re-advertising as {name!r}")
                    advert.terminate()
                    advert.wait(timeout=5)
                    advert = start_advert(name, args.port, txt, ip)
    except (KeyboardInterrupt, asyncio.CancelledError):
        print("\nstopping")
    finally:
        advert.terminate()
        subprocess.run([adb, "forward", "--remove", f"tcp:{args.local_port}"], check=False)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        pass
