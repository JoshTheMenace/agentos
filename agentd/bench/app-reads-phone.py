#!/usr/bin/env python3
"""Read-only phone benchmark; prints counts/timings, never notification/message bodies or tokens.
scripts/withphone python3 agentd/bench/app-reads-phone.py
The outer wrapper holds the phone lock for every adb/RPC operation, including the source-integrity comparison.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import statistics
import time

ROOT = Path(__file__).resolve().parents[2]
assert Path('/tmp/agentos-device.lock/pid').read_text().strip() == str(os.getppid()), 'Run through scripts/withphone'
spec = importlib.util.spec_from_file_location('phone', ROOT / 'android/tools-client.py')
phone = importlib.util.module_from_spec(spec)
spec.loader.exec_module(phone)
token = phone.token()  # read locally; never printed

def digest():
    return phone.adb('shell', "su -c 'sha256sum /data/user/0/com.linkedin.android/databases/messenger-sdk /data/user/0/com.linkedin.android/databases/messenger-sdk-wal'")

def sample(method, params=None, count=3):
    times, rows = [], []
    for _ in range(count):
        t = time.monotonic()
        r = phone.call(token, method, params or {})
        times.append(round((time.monotonic() - t) * 1000, 1))
        assert r.get('ok') is not False, (method, r.get('reason', r.get('error')))
        rows.append(r)
    return times, rows

before = digest()
screen_before = phone.call(token, 'screen.read').get('pkg')
results = []
for app in ['linkedin', 'x', 'slack', 'instagram', 'discord', 'whatsapp', 'gmail', 'spotify', 'youtube']:
    method = app + '.summary'
    times, rows = sample(method)
    r = rows[-1]
    assert all(x.get('trusted') is False and len(x['text']) <= 600 for x in r['items'])
    results.append(dict(method=method, installed=r['installed'], listener=r['listener'], available=r['available'],
                        returned=len(r['items']), median_ms=statistics.median(times), samples_ms=times))
times, rows = sample('linkedin.unread', {'since': 0, 'n': 20})
r = rows[-1]
assert r['cache'] == 'read', r.get('cache_note')
assert all(x.get('unread') is True for x in r['items'] if x['method'] == 'local_cache')
results.append(dict(method='linkedin.unread', cache=r['cache'], available=r['available'], returned=len(r['items']),
                    worth_looking_at=sum(x['worth_looking_at'] for x in r['items']),
                    cache_rows=sum(x['method'] == 'local_cache' for x in r['items']),
                    median_ms=statistics.median(times), samples_ms=times))
future = phone.call(token, 'linkedin.unread', {'since': 4102444800000})
assert future['items'] == [], 'since must filter cache and notifications'
limited = phone.call(token, 'linkedin.unread', {'since': 0, 'n': 1})
assert len(limited['items']) <= 1
screen_times, _ = sample('screen.read')
screen_after = phone.call(token, 'screen.read').get('pkg')
after = digest()
report = dict(date=time.strftime('%Y-%m-%d'), serial='ZY22J3TK8S', results=results,
              screen_read_median_ms=statistics.median(screen_times), foreground_unchanged=screen_before == screen_after,
              linkedin_source_unchanged=before == after, source_digest=hashlib.sha256(before.encode()).hexdigest(),
              note='RPC + broker guard latency; no model latency. No UI app benchmark: opening inboxes can mark messages read. Source hashes exclude SHM; SQLite never opens the originals.')
assert before == after, 'Source changed during benchmark; repeat while LinkedIn is idle before claiming integrity'
assert screen_before == screen_after, 'Foreground changed during benchmark; repeat without another phone task'
print(json.dumps(report, indent=2))
