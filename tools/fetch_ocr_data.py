#!/usr/bin/env python3
import hashlib
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/tessdata/chi_sim.traineddata'
URL = 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/chi_sim.traineddata'
MIN_BYTES = 2_000_000
MAX_BYTES = 3_000_000

EXPECTED_SHA256 = 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730'
if OUT.is_file() and hashlib.sha256(OUT.read_bytes()).hexdigest() == EXPECTED_SHA256:
    print('Verified bundled Tesseract chi_sim fast model')
    raise SystemExit(0)

request = urllib.request.Request(URL, headers={'User-Agent': 'Qunideguanggao-B7.8-build'})
with urllib.request.urlopen(request, timeout=60) as response:
    data = response.read()

if not MIN_BYTES <= len(data) <= MAX_BYTES:
    raise SystemExit(f'unexpected chi_sim.traineddata size: {len(data)}')

if hashlib.sha256(data).hexdigest() != EXPECTED_SHA256:
    raise SystemExit('chi_sim.traineddata SHA256 differs from tested model')

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_bytes(data)
print(f'Tesseract chi_sim fast model ready bytes={len(data)} sha256={hashlib.sha256(data).hexdigest()}')
