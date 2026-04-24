#!/usr/bin/env python3
"""
wfdb_to_csv.py
==============
Converts a PhysioNet/WFDB record in Format 212 (12-bit packed)
to a two-column CSV compatible with ecg_replay.py.

Usage:
    python wfdb_to_csv.py                          # uses defaults below
    python wfdb_to_csv.py --record cu07 --output cu07.csv

Output CSV columns:
    col 0: time (seconds)
    col 1: ADC value shifted to 0-4095 range  (same as real firmware)

Replay with:
    python ecg_replay.py --port COM3 --file cu07.csv --scale 1 --column 1
"""

import argparse
import os
import struct
import sys

DEFAULT_RECORD = "cu07"          # .hea / .dat in same folder as this script
DEFAULT_OUTPUT = "cu07.csv"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def read_header(hea_path):
    """Parse a minimal WFDB .hea file. Returns (n_signals, fs, n_samples, signals[])."""
    signals = []
    fs = 250
    n_samples = 0
    with open(hea_path) as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if i == 0:
                # record line: record n_signals fs n_samples
                fs = int(parts[2]) if len(parts) > 2 else 250
                n_samples = int(parts[3]) if len(parts) > 3 else 0
            else:
                # signal line: filename fmt gain bits baseline first_val checksum desc
                sig = {
                    "file":     parts[0],
                    "fmt":      int(parts[1]),
                    "gain":     float(parts[2]) if len(parts) > 2 else 200.0,
                    "bits":     int(parts[3])   if len(parts) > 3 else 12,
                    "baseline": int(parts[4])   if len(parts) > 4 else 0,
                }
                signals.append(sig)
    return fs, n_samples, signals


def decode_fmt212(dat_path, n_samples):
    """
    Decode MIT-BIH Format 212 (12-bit packed, 3 bytes per 2 samples).
    Returns list of signed 12-bit integers (range -2048 to +2047).
    """
    samples = []
    with open(dat_path, "rb") as f:
        data = f.read()

    i = 0
    byte_idx = 0
    while i < n_samples and byte_idx + 2 < len(data):
        b0 = data[byte_idx]
        b1 = data[byte_idx + 1]
        b2 = data[byte_idx + 2]

        s1 = b0 | ((b2 & 0x0F) << 8)
        s2 = b1 | ((b2 & 0xF0) << 4)

        # Sign-extend 12-bit → signed int
        if s1 >= 2048:
            s1 -= 4096
        if s2 >= 2048:
            s2 -= 4096

        samples.append(s1)
        i += 1
        if i < n_samples:
            samples.append(s2)
            i += 1

        byte_idx += 3

    return samples


def main():
    parser = argparse.ArgumentParser(
        description="Convert WFDB Format-212 record to replay-compatible CSV")
    parser.add_argument("--record", default=DEFAULT_RECORD,
                        help=f"Record name without extension (default: {DEFAULT_RECORD})")
    parser.add_argument("--output", default=None,
                        help="Output CSV path (default: <record>.csv next to this script)")
    args = parser.parse_args()

    base     = os.path.join(SCRIPT_DIR, args.record)
    hea_path = base + ".hea"
    dat_path = base + ".dat"
    out_path = args.output or os.path.join(SCRIPT_DIR, args.record + ".csv")

    for p in (hea_path, dat_path):
        if not os.path.exists(p):
            print(f"ERROR: File not found: {p}", file=sys.stderr)
            sys.exit(1)

    print(f"Reading header: {hea_path}")
    fs, n_samples, signals = read_header(hea_path)
    print(f"  Sample rate : {fs} Hz")
    print(f"  Samples     : {n_samples}  ({n_samples / fs:.1f} s)")
    print(f"  Signals     : {len(signals)}")

    if not signals:
        print("ERROR: No signal lines found in header.", file=sys.stderr)
        sys.exit(1)

    sig = signals[0]
    if sig["fmt"] != 212:
        print(f"ERROR: Only Format 212 is supported, got {sig['fmt']}", file=sys.stderr)
        sys.exit(1)

    print(f"\nDecoding {dat_path} ...")
    raw = decode_fmt212(dat_path, n_samples)
    print(f"  Decoded {len(raw)} samples")

    # Shift signed 12-bit values (-2048..+2047) → unsigned (0..4095)
    # so the output looks identical to the real firmware ADC (centred ~2048)
    baseline = sig["baseline"]   # ADC value that corresponds to 0 mV
    shifted  = [v - baseline + 2048 for v in raw]
    shifted  = [max(0, min(4095, v)) for v in shifted]

    print(f"\nWriting CSV: {out_path}")
    with open(out_path, "w") as f:
        f.write("time_s,ecg_adc\n")
        for idx, val in enumerate(shifted):
            t = idx / fs
            f.write(f"{t:.6f},{val}\n")

    print(f"  Written {len(shifted)} rows")
    print(f"\nDone. Replay with:")
    print(f"  python ecg_replay.py --port COM3 --file \"{out_path}\" --scale 1 --column 1")


if __name__ == "__main__":
    main()
