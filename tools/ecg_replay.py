#!/usr/bin/env python3
"""
ECGGuard – CSV Replay Tool
==========================
Reads an ECG CSV file on the laptop and streams it to an ESP32 flashed with
ecg_replay_esp32.ino over a USB-Serial connection.
The ESP32 then forwards the data over BLE so the ECGGuard Android app sees it
identically to a live sensor.  No changes to BLE or the Android app are needed.

Requirements:
    pip install pyserial

Usage examples:
    # CSV in millivolts, ECG in column 0, stream once
    python ecg_replay.py --port COM3 --file my_ecg.csv

    # CSV in millivolts, ECG column named "MLII", loop forever
    python ecg_replay.py --port COM3 --file my_ecg.csv --column MLII --loop

    # CSV already in raw ADC counts (no scaling needed)
    python ecg_replay.py --port COM3 --file my_ecg.csv --scale 1

    # PhysioNet wfdb-exported CSV: time col 0, signal col 1, 360 Hz recording
    python ecg_replay.py --port COM3 --file 100.csv --column 1 --sample-rate 360

Arguments:
    --port          Serial port of the ESP32 (e.g. COM3, /dev/ttyUSB0)
    --file          Path to ECG CSV file
    --column        Column index (0-based int) or column name with ECG data (default: 0)
    --scale         Multiply every sample by this before converting to int16.
                    Use 1000 if CSV is in millivolts (default).
                    Use 1 if CSV already holds raw ADC integers.
    --baud          Serial baud rate – must match the ESP32 sketch (default: 921600)
    --loop          Repeat the file indefinitely (useful for continuous demo)
    --sample-rate   Original recording sample rate in Hz (default: 250).
                    Data is always streamed at this rate regardless of value.
"""

import argparse
import csv
import sys
import time
import struct

# ── Tunable ───────────────────────────────────────────────────────────────────
CHUNK_SAMPLES = 10          # Must match CHUNK_SAMPLES in ecg_replay_esp32.ino
# ─────────────────────────────────────────────────────────────────────────────


def load_ecg(filepath: str, column) -> list:
    """
    Load ECG samples from a CSV file.
    column: int (0-based index) or str (header name).
    Returns a list of floats.
    """
    samples = []
    col_idx = None

    with open(filepath, newline='', encoding='utf-8-sig') as f:
        reader = csv.reader(f)
        for row_num, row in enumerate(reader):
            if not row or all(c.strip() == '' for c in row):
                continue  # skip empty lines

            if row_num == 0:
                # Decide whether this is a header or data row
                try:
                    float(row[0].strip().replace(',', '.'))
                    # First row is numeric → no header
                    if isinstance(column, str):
                        raise ValueError(
                            f"Column name '{column}' given but CSV has no header row. "
                            "Use --column with a 0-based integer index instead."
                        )
                    col_idx = int(column)
                    val = float(row[col_idx].strip().replace(',', '.'))
                    samples.append(val)
                    continue
                except ValueError as e:
                    if "Column name" in str(e):
                        raise
                    # First row is a header
                    header = [h.strip() for h in row]
                    if isinstance(column, str):
                        if column not in header:
                            raise ValueError(
                                f"Column '{column}' not found. "
                                f"Available columns: {header}"
                            )
                        col_idx = header.index(column)
                    else:
                        col_idx = int(column)
                    continue

            if col_idx is None:
                col_idx = int(column)

            try:
                val = float(row[col_idx].strip().replace(',', '.'))
                samples.append(val)
            except (ValueError, IndexError):
                pass  # skip malformed rows silently

    return samples


def clamp_int16(v: float) -> int:
    """Clamp a float to the signed 16-bit integer range [-32768, 32767]."""
    return max(-32768, min(32767, int(round(v))))


def replay(port: str, baud: int, filepath: str, column,
           scale: float, loop: bool, sample_rate: int, remove_dc: bool):
    try:
        import serial
    except ImportError:
        print("ERROR: 'pyserial' is not installed.  Run:  pip install pyserial",
              file=sys.stderr)
        sys.exit(1)

    print(f"Loading '{filepath}' ...")
    samples = load_ecg(filepath, column)
    if not samples:
        print("ERROR: No samples found in the CSV file.", file=sys.stderr)
        sys.exit(1)

    # Remove DC bias (the ADC midpoint ~2048 on a 12-bit ADC).
    # This leaves only the ECG AC waveform centred around 0, which means:
    #   • the app's ×0.001 gives ±millivolt-range values the model expects
    #   • the gatekeeper's abs(v)>3.0 threshold is never tripped by the DC offset
    if remove_dc:
        dc = sum(samples) / len(samples)
        samples = [s - dc for s in samples]
        print(f"  DC removed : subtracted mean = {dc:.2f}")

    raw_min = clamp_int16(min(samples) * scale)
    raw_max = clamp_int16(max(samples) * scale)
    duration_s = len(samples) / sample_rate

    print(f"  Samples  : {len(samples)}  ({duration_s:.1f} s at {sample_rate} Hz)")
    print(f"  CSV range: [{min(samples):.4f}, {max(samples):.4f}]")
    print(f"  Scale    : ×{scale}  →  int16 range [{raw_min}, {raw_max}]")
    if loop:
        print("  Mode     : LOOP (Ctrl+C to stop)")
    else:
        print(f"  Mode     : single-shot")

    print(f"\nOpening serial port {port} at {baud} baud ...")
    ser = serial.Serial(port, baud, timeout=1)
    time.sleep(2.0)          # Let ESP32 finish resetting after DTR toggle
    print("Serial open.  Streaming …\n")

    interval = CHUNK_SAMPLES / sample_rate   # target seconds between chunk sends

    idx          = 0
    total_sent   = 0
    running      = True
    start_wall   = time.perf_counter()

    try:
        while running:
            t0 = time.perf_counter()

            # ── Build one chunk ───────────────────────────────────────────────
            chunk = []
            for _ in range(CHUNK_SAMPLES):
                raw = clamp_int16(samples[idx % len(samples)] * scale)
                chunk.append(raw)
                idx += 1
                if idx >= len(samples):
                    if loop:
                        idx = 0          # wrap around
                    else:
                        running = False
                        break

            if not chunk:
                break

            # ── Send as little-endian int16 bytes ────────────────────────────
            payload = struct.pack(f'<{len(chunk)}h', *chunk)
            ser.write(payload)
            total_sent += len(chunk)

            # ── Progress every 250 samples (≈1 s of ECG) ─────────────────────
            if total_sent % 250 < CHUNK_SAMPLES:
                elapsed = time.perf_counter() - start_wall
                pct = (idx % len(samples)) / len(samples) * 100
                print(f"  [{elapsed:6.1f}s] Sent {total_sent:>6} samples | "
                      f"file pos {pct:.0f}%")

            # ── Pace to target sample rate ────────────────────────────────────
            elapsed_chunk = time.perf_counter() - t0
            sleep_time = interval - elapsed_chunk
            if sleep_time > 0.002:       # only sleep if margin > 2 ms
                time.sleep(sleep_time)

    except KeyboardInterrupt:
        pass
    finally:
        ser.close()
        total_s = total_sent / sample_rate
        print(f"\nDone.  Sent {total_sent} samples ({total_s:.1f} s of ECG).")
        print("Serial port closed.")


def main():
    parser = argparse.ArgumentParser(
        description="Stream ECG CSV to ESP32 over Serial → BLE → ECGGuard app",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('--port',        required=True,
                        help="Serial port of the ESP32 (e.g. COM3 or /dev/ttyUSB0)")
    parser.add_argument('--file',        required=True,
                        help="Path to ECG CSV file")
    parser.add_argument('--column',      default='1',
                        help="Column index (int, 0-based). "
                             "Default: 1 (your Sensor_ECG column).")
    parser.add_argument('--scale',       type=float, default=1.0,
                        help="Multiply each sample by this before packing as int16. "
                             "Default 1: sends raw ADC counts as-is, identical to the "
                             "original ESP32 firmware. Only change this if your CSV is "
                             "already in millivolts (use 1000) or other units.")
    parser.add_argument('--baud',        type=int, default=115200,
                        help="Serial baud rate – must match ecg_replay_esp32.ino (default: 115200)")
    parser.add_argument('--loop',        action='store_true',
                        help="Loop the CSV file indefinitely")
    parser.add_argument('--remove-dc',   action='store_true', default=False,
                        help="Subtract the signal mean before sending. OFF by default "
                             "because the original firmware sends raw ADC values and the "
                             "app's linearDetrend + mean-centering already removes the DC bias.")
    parser.add_argument('--no-remove-dc', dest='remove_dc', action='store_false')
    parser.add_argument('--sample-rate', type=int, default=250,
                        help="Original recording sample rate in Hz (default: 250)")
    args = parser.parse_args()

    # Allow column to be an int index or a string name
    try:
        column = int(args.column)
    except ValueError:
        column = args.column   # treat as header name

    replay(
        port=args.port,
        baud=args.baud,
        filepath=args.file,
        column=column,
        scale=args.scale,
        loop=args.loop,
        remove_dc=args.remove_dc,
        sample_rate=args.sample_rate
    )


if __name__ == '__main__':
    main()
