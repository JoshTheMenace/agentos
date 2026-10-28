#!/usr/bin/env python3
"""Overnight watchdog (read-only). Flags risky agent actions in the broker audit log and calendar shrinkage.
State and alerts live in /tmp/night-watch/. Run: python3 scripts/night-watch.py"""
import json, os, subprocess, time
D = "/tmp/night-watch"; os.makedirs(D, exist_ok=True)
STATE, ALERTS = f"{D}/state.json", f"{D}/alerts.log"
# Outward or destructive tools, plus any tap/type outside our own test apps.
RISKY = {"calendar.delete", "calendar.update", "calendar.add", "sms.send", "call.place", "notifications.reply",
         "notifications.autoreply", "alarms.delete", "contacts.update", "contacts.delete", "email.send",
         "firewall.set", "trackers.unblock", "vault.add", "vault.remove", "vault.fill", "plugins.set", "app.mod.press", "phone.unlock"}
# Taps/typing only matter in apps that can post, send or delete; or on buttons that sound like it.
SOCIAL = ("com.linkedin", "com.slack", "com.instagram", "com.discord", "com.whatsapp", "com.twitter", "com.x.",
          "com.facebook", "com.google.android.gm", "com.google.android.apps.messaging", "com.google.android.calendar",
          "com.android.chrome", "com.reddit", "com.snapchat", "org.telegram", "com.google.android.dialer")
import re
VERB = re.compile(r"\b(send|post|publish|share|reply|comment|delete|remove|tweet|submit|confirm|pay|buy|call)\b", re.I)

def sh(cmd):
    return subprocess.run(["adb", "shell", f"su -c '{cmd}'"], capture_output=True, text=True, timeout=60).stdout

st = json.load(open(STATE)) if os.path.exists(STATE) else {}
last = st.get("last_ts", 0)
alerts = []
for line in sh("tail -n 3000 /data/data/local.agentos/files/audit.jsonl").splitlines():
    try: e = json.loads(line)
    except ValueError: continue
    ts = e.get("ts", 0)
    if ts <= last: continue
    st["last_ts"] = max(st.get("last_ts", 0), ts)
    tool, pkg = e.get("tool", ""), str(e.get("pkg", ""))
    act = tool in ("tap", "type", "press", "intent")
    outside = act and (pkg.startswith(SOCIAL) and (tool == "type" or VERB.search(str(e.get("target", "")))) or
                       (not pkg.startswith("local.agentos") and VERB.search(str(e.get("target", "")))))
    outward = (e.get("rule_of_two") or {}).get("outward") and e.get("decision") in ("auto", "approved")
    if tool in RISKY or outside or outward:
        when = time.strftime("%H:%M:%S", time.localtime(ts / 1000))
        alerts.append(f"{when} {tool} pkg={pkg} decision={e.get('decision')} target={str(e.get('target',''))[:80]!r} "
                      f"text={str(e.get('text',''))[:60]!r} task={e.get('task')} caller={str(e.get('caller',''))[:40]}")

cal = sh("content query --uri content://com.android.calendar/events --projection _id --where deleted=0").count("Row:")
base = st.setdefault("cal_base", cal)
if cal < base: alerts.append(f"CALENDAR SHRANK: {base} -> {cal} events")
st["cal_last"] = cal
net = subprocess.run(["adb", "shell", "ping -c1 -W3 chatgpt.com >/dev/null && echo up || echo down"], capture_output=True, text=True, timeout=30).stdout.strip()
if net != "up": alerts.append("PHONE OFFLINE: chatgpt.com unreachable (Wi-Fi dropped? see NIGHT-PLAN Incidents for the fix)")
from collections import Counter
st.setdefault("seen", {})
json.dump(st, open(STATE, "w"))
stamp = time.strftime("%H:%M")
with open(ALERTS, "a") as f:
    for a in alerts: f.write(f"[{stamp}] {a}\n")
print(f"{stamp} calendar={cal} (baseline {base}); new alerts: {len(alerts)}")
for a in alerts[:40]: print("  " + a)
