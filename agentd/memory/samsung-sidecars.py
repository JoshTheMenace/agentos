"""Read only referenced, numeric health fields from jsons.zip, without extracting files.
Stdout is a private pipe to the importer, never a terminal/log. No source data is retained.
"""
import json
import math
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
allowed = ('com.samsung.health.hrv', 'com.samsung.shealth.tracker.heart_rate',
           'com.samsung.shealth.stress', 'com.samsung.health.exercise', 'com.samsung.shealth.exercise')
fields = {'start_time', 'end_time', 'heart_rate', 'heart_rate_min', 'heart_rate_max',
          'sdnn', 'rmssd', 'score', 'score_min', 'score_max', 'rhr'}

def clean(value):
    if isinstance(value, list):
        return [clean(v) for v in value]
    if not isinstance(value, dict):
        return {}
    out = {}
    for k, v in value.items():
        if k in fields and isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
            out[k] = v
        elif k == 'heart_rate' and isinstance(v, dict):
            out[k] = clean(v)
    return out

out = {}
with zipfile.ZipFile(root / 'jsons.zip') as z:
    for info in z.infolist():
        parts = Path(info.filename).parts
        if not info.filename.endswith('.json') or not any(p in allowed for p in parts):
            continue
        if info.file_size > 32 * 1024 * 1024:
            raise ValueError('Health sidecar exceeds size limit')
        data = clean(json.loads(z.read(info)))
        # Export references can include a relative path or just the basename.
        out[Path(info.filename).name] = data
json.dump(out, sys.stdout, separators=(',', ':'))
