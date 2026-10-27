#!/usr/bin/env python3
"""Small client for the AgentOS tool server (local.agentos, 127.0.0.1:7777 on the phone, via adb forward
to Mac port $AGENTOS_PORT, default 17777).

  tools-client.py call METHOD ['{"json":"params"}']   one call, prints the JSON reply
  tools-client.py e2e [--no-firewall] [--press-approve]
                                                      Pigeon chat task (P3 block, fingerprint Send) + Beacon firewall
  tools-client.py audit [N]                            last N audit entries + offline chain check

The bearer token is read with root from /data/data/local.agentos/files/token and never printed.
"""
import hashlib
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

ADB = ["adb", "-s", "ZY22J3TK8S"]
PORT = os.environ.get("AGENTOS_PORT", "17777")  # Mac-side port, forwarded to the phone's 7777
PRESS = "--press-approve" in sys.argv  # operator taps the card's Approve via adb (720x1600 Moto layout)
PIGEON, BEACON = "local.agentos.pigeon", "local.agentos.beacon"


def adb(*args):
    return subprocess.check_output(ADB + list(args), text=True).strip()


def token():
    adb("forward", "tcp:" + PORT, "tcp:7777")
    return adb("exec-out", "su -c 'cat /data/data/local.agentos/files/token'")


def call(tok, method, params=None, timeout=75):
    body = json.dumps({"method": method, "params": params or {}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/rpc", data=body,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def brief(screen):
    return f"{screen['pkg']}/{screen['activity']} secure={screen['secure']} nodes={len(screen['nodes'])}"


def find(screen, pred):
    return next((n["id"] for n in screen["nodes"] if pred(n)), None)


def approve(tok, method, params, press_approve=False, window=90):
    """An outward call: the phone shows the card; a human approves with a fingerprint within `window` seconds."""
    params = dict(params, approval_timeout_s=window)
    print(f"\n>>> {method} {json.dumps(params)}")
    if press_approve:  # the test operator presses Approve over adb (a human-side touch); the fingerprint stays human
        threading.Timer(2.5, lambda: adb("shell", "input tap 468 1428")).start()
        threading.Timer(4, lambda: print(f">>> TOUCH SENSOR NOW (power button) -- fingerprint prompt is up, {window} s window", flush=True)).start()
    else:
        print(f">>> ON THE PHONE: tap 'Approve with fingerprint', then TOUCH SENSOR NOW ({window} s window)", flush=True)
    t0 = time.time()
    r = call(tok, method, params, timeout=window + 20)
    print(f"    -> {r['decision']}: {r.get('reason')} ({time.time() - t0:.1f}s)")
    return r


def e2e(firewall=True):
    """Runs on our own demo apps: Pigeon (chat) and Beacon (network)."""
    tok = token()
    s = call(tok, "screen.read")
    print("screen.read:", brief(s))
    print("open_app without a task:", call(tok, "open_app", {"pkg": PIGEON})["reason"])
    # Test harness (not the agent): stage a message with a one-time code in our own chat app.
    adb("shell", f"am start -n {PIGEON}/.Main --es inject 'Your Pigeon login code is 482913' --es from Alice")
    time.sleep(1.5)
    lease = call(tok, "task.start", {"goal": "e2e: tell Alice I am running late", "apps": [PIGEON, "com.android.settings"]})
    print("task.start:", lease["task_id"], lease["apps"], "refused:", lease["refused_apps"])
    s = call(tok, "open_app", {"pkg": PIGEON})["screen"]
    print("open_app:", brief(s))
    alice = find(s, lambda n: (n.get("text") or "").startswith("Alice"))
    if alice is not None:  # list screen: open Alice's chat (harmless navigation tap)
        r = call(tok, "tap", {"id": alice})
        print("tap 'Alice':", r["decision"], r["tier"], r["rule_of_two"])
        s = r["screen"]
    field = find(s, lambda n: "edit" in n["actions"])
    if field is None:
        sys.exit("no text field on screen: " + brief(s))
    r = call(tok, "type", {"id": field, "text": "the code is 482913"})
    print("type the code it just read:", r["decision"], "-", r["reason"])
    r = call(tok, "type", {"id": field, "text": "Running 10 min late, sorry!"})
    print("type a benign reply:", r["decision"], r["tier"], r["rule_of_two"])
    send = find(r["screen"], lambda n: n.get("text") == "Send" or n.get("desc") == "Send")
    r = approve(tok, "tap", {"id": send}, PRESS)
    if r["decision"] == "approved":
        print("    sent; last bubbles:", [n.get("text") for n in r["screen"]["nodes"] if n.get("text")][-3:])
    if firewall:
        r = approve(tok, "firewall.set", {"pkg": BEACON, "mode": "block"}, PRESS)
        print("firewall.list:", call(tok, "firewall.list")["rules"])
        if r["decision"] == "approved":
            r = approve(tok, "firewall.clear", {}, PRESS)
        if r["decision"] != "approved":  # never leave a rule behind: the test operator (root) flushes the chain
            adb("shell", "su -c 'iptables -w -F AGENTOS_FW; ip6tables -w -F AGENTOS_FW'")
        print("firewall.list after clear:", call(tok, "firewall.list")["rules"])
    print("task.stop:", call(tok, "task.stop", {"task_id": lease["task_id"]})["reason"])
    print("tap after stop:", call(tok, "tap", {"id": 0})["reason"])
    audit(tok, 14)


def audit(tok, n):
    a = call(tok, "audit.tail", {"n": n})
    for e in a["entries"]:
        print(f"  {e['ts']} {e['task']:9} {e['tool']:14} {e.get('decision', ''):9} {e.get('target', '')[:40]!r} {e.get('reason', '')[:60]}")
    print(f"chain_ok={a['chain_ok']} count={a['count']} head={a['head'][:16]}…")
    # Independent check of the whole file: body = line minus ',"hash":"<64>"}' plus '}'; hash = sha256(prev + body).
    prev, ok = "0" * 64, True
    for line in adb("exec-out", "su -c 'cat /data/data/local.agentos/files/audit.jsonl'").splitlines():
        body, h = line[:-75] + "}", line[-66:-2]
        ok = ok and json.loads(body)["prev_hash"] == prev and hashlib.sha256((prev + body).encode()).hexdigest() == h
        prev = h
    print("offline chain check (Mac side):", ok)


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "e2e":
        e2e(firewall="--no-firewall" not in sys.argv)
    elif cmd == "audit":
        audit(token(), int(sys.argv[2]) if len(sys.argv) > 2 else 20)
    elif cmd == "call":
        print(json.dumps(call(token(), sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}), indent=2, ensure_ascii=False))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
