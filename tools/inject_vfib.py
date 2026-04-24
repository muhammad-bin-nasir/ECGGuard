#!/usr/bin/env python3
"""
inject_vfib.py
==============
Reads a raw ECG CSV (time col 0, ADC counts col 1) and produces a new CSV
with the first 30 seconds intact followed by simulated Ventricular Fibrillation
(VFib) for the rest of the file.

The original file is NEVER modified — a new file is written instead.

VFib model used here:
  - No clean QRS complexes
  - Chaotic superposition of 6 sine waves at irregular frequencies (4–18 Hz)
    with random per-wave amplitude weights and random phase offsets
  - Slowly drifting amplitude envelope (±30 % over ~2 s cycles)
  - Gaussian noise on top
  All values stay within the 12-bit ADC range (0–4095) and keep the same
  DC bias (~2048) as the original signal so the replay script needs no changes.

Usage:
    python inject_vfib.py                          # uses defaults below
    python inject_vfib.py --input  E:\\FYP\\ESP\\my_baseline_20min.csv
    python inject_vfib.py --output E:\\FYP\\ESP\\my_ecg_vfib_test.csv
    python inject_vfib.py --vfib-start 30         # seconds before VFib onset
"""

import argparse
import csv
import math
import os
import random
import sys

# ── Defaults ──────────────────────────────────────────────────────────────────
DEFAULT_INPUT  = r"E:\FYP\ESP\my_baseline_20min.csv"
DEFAULT_OUTPUT = r"E:\FYP\ESP\my_ecg_vfib_test.csv"
SAMPLE_RATE    = 250          # Hz
VFIB_START_S   = 30          # seconds of clean ECG before VFib begins
ADC_BITS       = 12          # 12-bit ADC → 0–4095
ADC_MID        = 2048        # DC offset (midpoint of ADC range)
ADC_MAX        = 4095
ADC_MIN        = 0

# VFib parameters
#
# Root cause of low MSE with sine waves:
#   The app's Savgol-11 smoothing filter attenuates content above ~15 Hz,
#   then ×0.001 scaling means small amplitudes give variance < 0.30 threshold.
#
# Fix: AR(1) colored noise with corner frequency ~6 Hz (survives Savgol)
# and amplitude 1800 ADC counts (1.8 mV) so variance >> 0.30 mV² even
# if the model outputs near-zero for out-of-distribution input.
VFIB_AMPLITUDE = 1800         # ADC counts → 1.8 mV; RMS ~0.9 mV → variance ~0.81 mV²
NOISE_STD      = 120          # additive Gaussian noise (ADC counts)
VFIB_AR_ALPHA  = 0.87         # AR(1) coeff — spectral corner ≈ 250*(1-0.87)/(2π) ≈ 5.2 Hz


def simulate_vfib(n_samples: int, dc_offset: int, rng: random.Random) -> list:
    """
    Generate VFib as AR(1) colored noise — truly non-periodic, energy in 2-8 Hz band.

    Why AR(1) instead of sines:
      - Sine waves with fixed frequencies are periodic; the LSTM autoencoder can
        partially reconstruct them → low MSE.  Random AR(1) noise has NO repeating
        structure, so the model always fails → high MSE.
      - Corner frequency ~5 Hz sits below the Savgol-11 cutoff, so amplitude is
        preserved through the app's preprocessing pipeline.
    """
    alpha = VFIB_AR_ALPHA
    innov_std = math.sqrt(1.0 - alpha ** 2)   # keeps unit variance

    # Generate AR(1) process
    x = [0.0] * n_samples
    x[0] = rng.gauss(0, 1)
    for i in range(1, n_samples):
        x[i] = alpha * x[i - 1] + rng.gauss(0, innov_std)

    # Normalize to ±1 so VFIB_AMPLITUDE is exact
    max_abs = max(abs(v) for v in x)
    if max_abs > 0:
        x = [v / max_abs for v in x]

    # Slow 0.2 Hz envelope (mimics VFib waxing/waning amplitude)
    samples = []
    for i, v in enumerate(x):
        t = i / SAMPLE_RATE
        envelope = 0.75 + 0.25 * math.sin(2 * math.pi * 0.20 * t)
        value = dc_offset + int(VFIB_AMPLITUDE * envelope * v)

        # Add Gaussian noise
        value += int(rng.gauss(0, NOISE_STD))

        # Clamp to ADC range
        value = max(ADC_MIN, min(ADC_MAX, value))
        samples.append(value)

    return samples


def main():
    parser = argparse.ArgumentParser(description="Inject simulated VFib into ECG CSV")
    parser.add_argument("--input",      default=DEFAULT_INPUT,
                        help=f"Path to input CSV (default: {DEFAULT_INPUT})")
    parser.add_argument("--output",     default=DEFAULT_OUTPUT,
                        help=f"Path to output CSV (default: {DEFAULT_OUTPUT})")
    parser.add_argument("--vfib-start", type=int, default=VFIB_START_S,
                        help=f"Seconds of clean ECG before VFib onset (default: {VFIB_START_S})")
    parser.add_argument("--seed",       type=int, default=42,
                        help="Random seed for reproducible VFib shape (default: 42)")
    args = parser.parse_args()

    vfib_onset_sample = args.vfib_start * SAMPLE_RATE
    rng = random.Random(args.seed)

    if not os.path.exists(args.input):
        print(f"ERROR: Input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    print(f"Reading '{args.input}' …")
    rows = []
    has_header = False
    header_row = None

    with open(args.input, newline='', encoding='utf-8-sig') as f:
        reader = csv.reader(f)
        for row_num, row in enumerate(reader):
            if not row or all(c.strip() == '' for c in row):
                continue
            if row_num == 0:
                try:
                    float(row[0].strip())
                except ValueError:
                    has_header = True
                    header_row = row
                    continue
            rows.append(row)

    total_samples = len(rows)
    print(f"  Total samples : {total_samples} ({total_samples / SAMPLE_RATE:.1f} s)")
    print(f"  VFib onset    : sample {vfib_onset_sample} (t = {args.vfib_start} s)")

    if vfib_onset_sample >= total_samples:
        print(f"ERROR: vfib-start ({args.vfib_start} s) is beyond the end of the recording "
              f"({total_samples / SAMPLE_RATE:.1f} s).", file=sys.stderr)
        sys.exit(1)

    # Estimate DC offset from the clean section
    clean_values = []
    for row in rows[:vfib_onset_sample]:
        try:
            clean_values.append(float(row[1].strip()))
        except (ValueError, IndexError):
            pass
    dc_offset = int(sum(clean_values) / len(clean_values)) if clean_values else ADC_MID
    print(f"  DC offset     : {dc_offset} ADC counts")

    # Generate VFib samples
    n_vfib = total_samples - vfib_onset_sample
    print(f"  Generating {n_vfib} VFib samples ({n_vfib / SAMPLE_RATE:.1f} s) …")
    vfib_samples = simulate_vfib(n_vfib, dc_offset, rng)

    # Write output
    print(f"Writing '{args.output}' …")
    with open(args.output, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        if has_header and header_row:
            writer.writerow(header_row)
        for i, row in enumerate(rows):
            if i < vfib_onset_sample:
                writer.writerow(row)   # original clean ECG
            else:
                # Replace ECG column (index 1) with VFib; keep time column
                new_row = list(row)
                new_row[1] = str(vfib_samples[i - vfib_onset_sample])
                writer.writerow(new_row)

    print(f"\nDone.")
    print(f"  First {args.vfib_start} s : original normal sinus rhythm")
    print(f"  After {args.vfib_start} s : simulated VFib")
    print(f"\nReplay command:")
    print(f"  python ecg_replay.py --port COM3 --file \"{args.output}\"")


if __name__ == '__main__':
    main()
