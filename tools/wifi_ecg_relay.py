#!/usr/bin/env python3
"""
ECGGuard – WiFi ECG Relay
=========================
Bridges the ESP32's WiFi TCP stream to the Android emulator via ADB.

  ESP32 (WiFi) ──TCP:9998──► this script ──TCP:9999──► adb forward ──► emulator

Quick start:
  1. adb forward tcp:9999 tcp:9999
  2. Emulator app → Settings → START USB LISTENER
  3. python tools/wifi_ecg_relay.py
  4. Flash ecg_standalone.ino with WIFI_MIRROR_ENABLED true + correct LAPTOP_IP
"""

import argparse
import socket
import sys
import threading
import time


def bridge(src, dst, stop):
    try:
        while not stop.is_set():
            data = src.recv(4096)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        stop.set()


def run(esp_port, emu_host, emu_port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", esp_port))
    server.listen(1)
    print(f"Listening for ESP32 on port {esp_port}")
    print(f"Will forward to {emu_host}:{emu_port}  (run: adb forward tcp:{emu_port} tcp:{emu_port})")
    print("Ctrl+C to stop.\n")

    while True:
        print("Waiting for ESP32 …")
        try:
            esp_sock, addr = server.accept()
        except KeyboardInterrupt:
            break
        print(f"ESP32 connected from {addr[0]}")

        try:
            emu_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            emu_sock.settimeout(5)
            emu_sock.connect((emu_host, emu_port))
            emu_sock.settimeout(None)
            print(f"Emulator connected at {emu_host}:{emu_port}")
        except (OSError, socket.timeout) as e:
            print(f"Cannot reach emulator: {e}")
            print("  → Did you run 'adb forward tcp:9999 tcp:9999'?")
            print("  → Is the app open with USB listener started?")
            esp_sock.close()
            time.sleep(2)
            continue

        stop = threading.Event()
        threading.Thread(target=bridge, args=(esp_sock, emu_sock, stop), daemon=True).start()
        stop.wait()

        for s in (esp_sock, emu_sock):
            try: s.close()
            except OSError: pass

        print("Session ended.\n")

    server.close()


def main():
    p = argparse.ArgumentParser(description="Relay ESP32 WiFi ECG to Android emulator")
    p.add_argument("--esp-port", type=int, default=9998, help="Port to accept ESP32 on (default 9998)")
    p.add_argument("--emu-host", default="localhost", help="Emulator host (default localhost)")
    p.add_argument("--emu-port", type=int, default=9999, help="Emulator TCP port (default 9999)")
    args = p.parse_args()
    try:
        run(args.esp_port, args.emu_host, args.emu_port)
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
