#!/usr/bin/env python3
"""Small client for the AgentOS tool server (local.agentos, 127.0.0.1:7777 on the phone, via adb forward
to Mac port $AGENTOS_PORT, default 17777).

  tools-client.py call METHOD ['{"json":"params"}']   one call, prints the JSON reply
  tools-client.py e2e [--no-firewall] [--press-approve]
                                                      Pigeon chat task (P3 block, fingerprint Send) + Beacon firewall
  tools-client.py audit [N]                            last N audit entries + offline check of the whole chain (exit 1 if broken)
  tools-client.py audit mark REASON                    adb only: append one audit.break line naming today's link breaks (never rewrites)
  tools-client.py events [SECONDS]                     print the GET /events stream (server-sent events)
  tools-client.py settings ['{"require_user_lease":false}']
                                                      write (as root) or, with no JSON, delete files/settings.json
  tools-client.py approvals                            lease card, taint carry-over, approval channels + /events
  tools-client.py notifs                               notification feed: test chat, direct reply, P3, copy check, standing auto-reply
  tools-client.py vault add ID LABEL PKG[,PKG] USERNAME  save a password (asked here, never echoed; adb-only on the phone)
  tools-client.py vault list | vault rm ID
  tools-client.py vault fill ID [--press-approve]      on the app's login screen: lease, find the password field, vault.fill

The bearer token is read with root from /data/data/local.agentos/files/token and never printed.
"""
import base64
import hashlib
import http.client
import json
import queue
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
# Island card buttons on the 720x1600 Moto: lease "Allow", and "Approve" on a "Send" card (message bubble + "Untrusted content" chip).
LEASE_ALLOW, CARD_APPROVE = os.environ.get("AGENTOS_ALLOW_XY", "580 190"), os.environ.get("AGENTOS_APPROVE_XY", "575 325")


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


def events(tok, out, stop=None):
    """Reads GET /events and puts each (type, data) on queue `out` until `stop` is set or the stream ends."""
    c = http.client.HTTPConnection("127.0.0.1", int(PORT), timeout=30)
    c.request("GET", "/events", headers={"Authorization": "Bearer " + tok})
    r = c.getresponse()
    assert r.status == 200 and r.getheader("Content-Type") == "text/event-stream", r.status
    name = None
    while not (stop and stop.is_set()):
        line = r.readline().decode().rstrip("\n")
        if line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            out.put((name, json.loads(line[5:])))
        elif line == "" and r.isclosed():
            break


def settings(js=None):
    """Root-only switches: files/settings.json, owned by the app with the app's SELinux label (else the app can't read it)."""
    f, d = "/data/data/local.agentos/files/settings.json", "/data/data/local.agentos/files"
    if js is None:
        return adb("shell", f"su -c 'rm -f {f}'")
    b = base64.b64encode(json.dumps(json.loads(js)).encode()).decode()
    adb("shell", f"su -c 'echo {b} | base64 -d > {f} && chown $(stat -c %u:%g {d}) {f} && chmod 600 {f} && chcon $(stat -c %C {d}) {f}'")


def brief(screen):
    return f"{screen['pkg']}/{screen['activity']} secure={screen['secure']} nodes={len(screen['nodes'])}"


def find(screen, pred):
    return next((n["id"] for n in screen["nodes"] if pred(n)), None)


def approve(tok, method, params, press_approve=False, window=90):
    """An outward call: the phone shows the card; a human taps Approve (plus the fingerprint for money) within `window` seconds."""
    params = dict(params, approval_timeout_s=window)
    print(f"\n>>> {method} {json.dumps(params)}")
    if press_approve:  # the test operator presses Approve over adb (a human-side touch); the fingerprint stays human
        threading.Timer(4, lambda: adb("shell", f"input tap {CARD_APPROVE}")).start()
    else:
        print(f">>> ON THE PHONE: tap 'Approve' ({window} s window; the fingerprint only for payments)", flush=True)
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
    if PRESS:  # the lease card: the test operator taps Allow over adb
        threading.Timer(4, lambda: adb("shell", f"input tap {LEASE_ALLOW}")).start()
    else:
        print(">>> ON THE PHONE: tap 'Allow' on the lease card", flush=True)
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


def approvals():
    """Lease card, taint carry-over, risk-adaptive approval channels, all watched through GET /events."""
    tok, ev, stop = token(), queue.Queue(), threading.Event()
    threading.Thread(target=events, args=(tok, ev, stop), daemon=True).start()
    seen = []

    def wait_for(kind, secs=20):
        end = time.time() + secs
        while time.time() < end:
            try:
                t, d = ev.get(timeout=1)
            except queue.Empty:
                continue
            seen.append(t)
            if t == kind:
                return d
        sys.exit(f"no {kind} event in {secs}s")

    def bg(method, params, timeout=90):  # a blocking call (it waits for a card) on another thread
        box = {}
        th = threading.Thread(target=lambda: box.update(r=call(tok, method, params, timeout)))
        th.start()
        return lambda: (th.join(), box["r"])[1]

    def tap_card(a, xy, secs=25):  # under load the card opens late: re-tap only while this approval is still open
        end = time.time() + secs
        time.sleep(1.5)
        while time.time() < end and any(x["approval_id"] == a["approval_id"] for x in call(tok, "approval.list")["approvals"]):
            adb("shell", f"input tap {xy}")
            time.sleep(2)

    print("hello event:", wait_for("hello")["approvals"])
    # Test harness (not the agent): a clean Pigeon with a benign message from Alice, and her chat open, before any lease (a harness tap during a task auto-pauses it), so the task never
    # reads the chat list (its seeded "Unknown number" preview is a prompt injection that Prompt Guard flags).
    adb("shell", f"am start -n {PIGEON}/.Main --es scenario reset")
    time.sleep(1)
    adb("shell", f"am start -n {PIGEON}/.Main --es inject 'The meeting moved to the north gate at six tonight' --es from Alice")
    time.sleep(1)
    adb("shell", "input tap 360 255")  # Alice is now the top row
    time.sleep(1)
    settings('{"require_user_lease": true, "voice_callers": ["com.android.shell"], "reviewer_callers": ["com.android.shell"]}')
    call(tok, "task.stop")
    # 1. lease card: no answer -> denied; then an operator tap on Allow (adb input = a touchscreen tap, as a human's)
    res = bg("task.start", {"goal": "approvals test", "apps": [PIGEON], "ttl_s": 120, "approval_timeout_s": 10})
    a = wait_for("approval.shown")
    print("lease card shown:", a["summary"], a["allowed_channels"], "caller:", a["caller"])
    r = res()
    print("  no tap ->", r["ok"], r.get("reason"), "| resolved:", wait_for("approval.resolved")["decision"])
    res = bg("task.start", {"goal": "approvals test", "apps": [PIGEON], "ttl_s": 120})
    tap_card(wait_for("approval.shown"), LEASE_ALLOW)
    r = res()
    print("  tap Allow ->", r["ok"], "user_granted:", r.get("user_granted"), "| resolved:", wait_for("approval.resolved")["approver"])
    # 2. taint carry-over: read the screen, then re-lease (same caller, same apps: no new card, history kept)
    s = call(tok, "open_app", {"pkg": PIGEON})["screen"]
    r = call(tok, "task.start", {"goal": "approvals test 2", "apps": [PIGEON], "ttl_s": 120})
    print("re-lease:", "carried_over", r["carried_over"], "untrusted", r["untrusted"], "steps", r["steps"], "(no card:", r["user_granted"], ")")
    print("task.state:", wait_for("task.state")["state"])
    alice = find(s, lambda n: (n.get("text") or "").startswith("Alice"))
    if alice is not None:
        s = call(tok, "tap", {"id": alice})["screen"]
    field = find(s, lambda n: "edit" in n["actions"])

    def send(text, how):
        r = call(tok, "type", {"id": field, "text": text})
        btn = find(r["screen"], lambda n: n.get("text") == "Send" or n.get("desc") == "Send")
        res = bg("tap", {"id": btn, "approval_timeout_s": 15})
        a = wait_for("approval.shown")
        print(f"\nsend {text!r}: allowed {a['allowed_channels']} because {a['fingerprint_because']}")
        how(a)
        r = res()
        d = wait_for("approval.resolved")
        print(f"  -> {r['decision']} ({r['reason']}) | resolved {d['decision']} by {d['approver']}")
        return r

    def confirm(ch, **kw):
        return lambda a: print("  confirm", ch, "->", call(tok, "approval.confirm", dict(approval_id=a["approval_id"], channel=ch, **kw)))

    send("Reviewer test: running 10 min late", confirm("reviewer", rationale="benign reply in a chat the user named"))
    send("Voice test: running 10 min late", confirm("voice", utterance="send it"))
    send("Tap test: running 10 min late", lambda a: (time.sleep(4), subprocess.run(ADB + ["exec-out", "screencap -p"], stdout=open("/tmp/agentos-card.png", "wb")),
                                                       tap_card(a, CARD_APPROVE)))
    send("RPC tap test", lambda a: (confirm("tap")(a), call(tok, "approval.deny", {"approval_id": a["approval_id"], "channel": "test"})))
    # 3. copied from the screen -> a person must approve: the reviewer is refused, a spoken "send it" works
    send("the north gate at six tonight", lambda a: (print("  held because:", a.get("held_because")), confirm("reviewer", rationale="x")(a),
                                                       confirm("voice", utterance="send it")(a)))
    # 4. not on the allow-list -> refused
    settings('{"require_user_lease": true}')
    r = bg("firewall.set", {"pkg": BEACON, "mode": "block", "approval_timeout_s": 15})
    a = wait_for("approval.shown")
    print("\nfirewall.set: allowed", a["allowed_channels"], "because", a["fingerprint_because"])
    confirm("voice", utterance="yes")(a)
    call(tok, "approval.deny", {"approval_id": a["approval_id"], "channel": "voice", "utterance": "no"})
    print("  ->", r()["decision"], "| resolved", wait_for("approval.resolved")["decision"])
    print("\ntask.stop:", call(tok, "task.stop")["reason"], "| task.state:", wait_for("task.state")["state"])
    settings()  # back to the defaults (lease card on)
    time.sleep(1)
    while not ev.empty():
        seen.append(ev.get()[0])
    stop.set()
    print("event types seen:", sorted(set(seen)))
    audit(tok, 30)


def notifs():
    """Notification feed on our own test chat (a MessagingStyle notification with a real RemoteInput Reply action)."""
    tok, ev, stop = token(), queue.Queue(), threading.Event()
    threading.Thread(target=events, args=(tok, ev, stop), daemon=True).start()
    old = adb("exec-out", "su -c 'cat /data/data/local.agentos/files/settings.json 2>/dev/null; true'")
    settings(json.dumps(dict(json.loads(old or "{}"), voice_callers=["com.android.shell"])))  # the operator says "yes" over adb

    def wait_for(kind, secs=20, pred=lambda d: True):
        end = time.time() + secs
        while time.time() < end:
            try:
                t, d = ev.get(timeout=1)
            except queue.Empty:
                continue
            if t == kind and pred(d):
                return d
        sys.exit(f"no {kind} event in {secs}s")

    def post(frm, text):  # test harness (not the agent): a message arrives
        adb("shell", f"am broadcast -a local.agentos.UI --es notify '{text}' --es from {frm}")
        return wait_for("notification.posted", pred=lambda d: d["text"] == text)

    def reply(key, text, how=None, secs=15):
        box = {}
        th = threading.Thread(target=lambda: box.update(r=call(tok, "notifications.reply", {"key": key, "text": text, "approval_timeout_s": secs}, secs + 20)))
        th.start()
        if how:
            a = wait_for("approval.shown")
            print(f"  card: {a['summary']!r} allowed {a['allowed_channels']} held_because {a.get('held_because')} r2 {a['rule_of_two']}")
            how(a)
        th.join()
        r = box["r"]
        print(f"  reply {text!r} -> {r['decision']}: {r['reason']}")
        return r

    def confirm(ch, **kw):
        return lambda a: print("  confirm", ch, "->", call(tok, "approval.confirm", dict(approval_id=a["approval_id"], channel=ch, **kw))["ok"])

    t0 = time.time()
    n = post("Alice", "Hey are you still coming? We are at the table")
    print(f"posted -> event in {time.time() - t0:.1f}s: {n['conversation']}: {n['text']!r} can_reply={n['can_reply']} guard={n['guard']}")
    lst = call(tok, "notifications.list", {"n": 3})
    print("list:", [(x["app"], x["conversation"], x["text"][:30]) for x in lst["notifications"]], "foreground:", lst["foreground"])
    # 1. a reply in a messaging app (the test chat counts as one): all three Rule-of-Two lamps -> a person approves, not the reviewer
    t0 = time.time()
    r = reply(n["key"], "Running 10 minutes late, sorry!", lambda a: (confirm("reviewer", rationale="x")(a), confirm("voice", utterance="send it")(a)))
    back = wait_for("notification.posted", pred=lambda d: d["key"] == n["key"] and d["from_me"])
    print(f"  the app got it: thread now ends with ({back['sender']}) {back['text']!r} ({time.time() - t0:.1f}s round trip)")
    # 2. P3: a code that arrived by notification can never be sent
    c = post("Alice", "Your login code is 482913")
    reply(c["key"], "it says 482913")
    # 3. text copied from the message -> a person must approve (reviewer refused, voice ok)
    reply(n["key"], "are you still coming", lambda a: (confirm("reviewer", rationale="x")(a), call(tok, "approval.deny", {"approval_id": a["approval_id"]})))
    # 4. standing auto-reply: one human OK for the exact text, then replies with it need no card
    box = {}
    th = threading.Thread(target=lambda: box.update(r=call(tok, "notifications.autoreply", {"text": "Driving, I'll text you back soon", "minutes": 5, "approval_timeout_s": 15}, 40)))
    th.start()
    a = wait_for("approval.shown")
    print(f"autoreply card: {a['summary']!r} allowed {a['allowed_channels']}")
    confirm("reviewer", rationale="x")(a)
    confirm("voice", utterance="yes")(a)
    th.join()
    print("  ->", box["r"]["decision"])
    b = post("Bob", "Where are you?")
    t0 = time.time()
    reply(b["key"], "Driving, I'll text you back soon")
    print(f"  (no card, {time.time() - t0:.2f}s)")
    print("stop autoreply:", call(tok, "notifications.autoreply", {"minutes": 0})["reason"])
    settings(old or None)  # back to what it was
    stop.set()
    audit(tok, 8)


def vault(args):
    """The operator's side of the vault. Passwords are typed here (getpass) and go over adb to the phone, never to a model."""
    import getpass
    tok, sub = token(), args[0] if args else "list"
    if sub == "add" and len(args) == 5:
        pw = getpass.getpass(f"Password for {args[2]} ({args[4]}): ")
        r = call(tok, "vault.add", {"id": args[1], "label": args[2], "pkgs": args[3].split(","), "username": args[4], "password": pw})
    elif sub == "rm" and len(args) == 2:
        r = call(tok, "vault.remove", {"id": args[1]})
    elif sub == "fill" and len(args) >= 2:  # what the agent does, step by step, on the screen as it is now
        pkg = call(tok, "screen.read")["pkg"]
        if PRESS:
            threading.Timer(4, lambda: adb("shell", f"input tap {LEASE_ALLOW}")).start()
        lease = call(tok, "task.start", {"goal": "vault test: fill the password", "apps": [pkg]})
        s = call(tok, "screen.read")
        field = find(s, lambda n: n.get("password"))
        user = find(s, lambda n: "edit" in n["actions"] and not n.get("password"))
        print("lease:", lease.get("task_id"), lease.get("reason", ""), "| screen:", brief(s), "| password field:", field)
        if field is None:
            sys.exit("no password field on screen")
        print(">>> ON THE PHONE: touch the fingerprint sensor (60 s)", flush=True)
        r = call(tok, "vault.fill", dict({"id": args[1], "field_id": field, "task_id": lease.get("task_id", "")}, **({"user_field_id": user} if user is not None else {})), timeout=90)
        print("vault.fill ->", json.dumps(r))
        after = call(tok, "screen.read")
        print("screen.read of the field afterwards:", next((n for n in after["nodes"] if n.get("password")), None))
        call(tok, "task.stop", {"task_id": lease.get("task_id", "")})
        return
    elif sub == "list":
        r = call(tok, "vault.list")
    else:
        sys.exit(__doc__)
    print(json.dumps(r, indent=2, ensure_ascii=False))


def audit(tok, n):
    a = call(tok, "audit.tail", {"n": n})
    for e in a["entries"]:
        print(f"  {e['ts']} {e['task']:9} {e['tool']:14} {e.get('decision', ''):9} {e.get('target', '')[:40]!r} {e.get('reason', '')[:60]}")
    print(f"phone: chain_ok={a['chain_ok']} broken_at={a.get('broken_at')} marked_breaks={a.get('marked_breaks')} count={a['count']} head={a['head'][:16]}…")
    ok = verify(adb("exec-out", "su -c 'cat /data/data/local.agentos/files/audit.jsonl'").splitlines())
    if not ok:
        sys.exit(1)


def verify(lines):
    """Independent walk of the whole chain (Mac side). body = line minus ',"hash":"<64>"}' plus '}'; hash = sha256(prev_hash + body).
    A link break (the line's own seal is fine but prev_hash points elsewhere) passes only if a later audit.break line names it."""
    lines = [l for l in lines if l]
    marked = {(b["line"], b["hash"]) for l in lines if '"tool":"audit.break"' in l for b in json.loads(l).get("breaks", [])}
    prev, bad, n_marked = "0" * 64, [], 0
    for i, line in enumerate(lines):
        body, h = line[:-75] + "}", line[-66:-2]
        own = json.loads(body)["prev_hash"]
        if hashlib.sha256((own + body).encode()).hexdigest() != h:
            bad.append(f"line {i}: content altered")
        elif own != prev:
            if (i, h) in marked:
                n_marked += 1
            else:
                bad.append(f"line {i}: prev_hash {own[:12]}… expected {prev[:12]}…")
        prev = h
    print(f"offline chain check (Mac side): {'OK' if not bad else 'BROKEN'}, {len(lines)} lines, {n_marked} marked break(s)")
    for b in bad[:10]:
        print("  ", b)
    return not bad


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == "e2e":
        e2e(firewall="--no-firewall" not in sys.argv)
    elif cmd == "approvals":
        approvals()
    elif cmd == "notifs":
        notifs()
    elif cmd == "events":
        q, end = queue.Queue(), time.time() + (float(sys.argv[2]) if len(sys.argv) > 2 else 1e9)
        threading.Thread(target=events, args=(token(), q), daemon=True).start()
        while time.time() < end:
            try:
                t, d = q.get(timeout=1)
                print(t, json.dumps(d, ensure_ascii=False), flush=True)
            except queue.Empty:
                pass
    elif cmd == "settings":
        settings(sys.argv[2] if len(sys.argv) > 2 else None)
    elif cmd == "vault":
        vault([a for a in sys.argv[2:] if a != "--press-approve"])
    elif cmd == "audit" and sys.argv[2:3] == ["mark"]:  # adb only: one audit.break line naming the current breaks
        print(json.dumps(call(token(), "audit.mark_breaks", {"reason": " ".join(sys.argv[3:]) or "marked by the operator over adb"}), indent=2))
    elif cmd == "audit":
        audit(token(), int(sys.argv[2]) if len(sys.argv) > 2 else 20)
    elif cmd == "call":
        print(json.dumps(call(token(), sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}), indent=2, ensure_ascii=False))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
