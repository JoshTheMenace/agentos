#!/bin/sh
# Mac-side stage checks. Python is embedded to keep JSON parsing and failure handling readable.
set -eu
cd "$(dirname "$0")/.."
case " ${*:-} " in
  *' --fix '*) [ "${PREFLIGHT_LOCKED:-}" = 1 ] || exec scripts/withphone env PREFLIGHT_LOCKED=1 "$PWD/scripts/preflight.sh" "$@" ;;
esac
exec python3 - "$@" <<'PY'
import argparse, atexit, contextlib, hashlib, io, json, os, re, runpy, shlex, subprocess, sys, time
import urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description='Stage checks; RED includes anything that could not be verified. No data is deleted.')
parser.add_argument('--fix', nargs='?', const='ime,home,warm,dnd,reset,tabs,wifi', default='',
                    help='safe fixes, or a comma list: ime,home,warm,dnd,reset,tabs,wifi')
parser.add_argument('--tabs', type=Path, help='agent-created Chrome targets: JSON {serial, tabs: [{id, url}]}; only exact matches may close')
args = parser.parse_args()
fixes = set(args.fix.split(',')) - {''}
if fixes - {'ime', 'home', 'warm', 'dnd', 'reset', 'tabs', 'wifi'}:
    parser.error('unknown fix; use ime,home,warm,dnd,reset,tabs,wifi')
if fixes and os.environ.get('PREFLIGHT_LOCKED') != '1':
    parser.error('use --fix with a space before its optional comma list')
serial = os.environ.get('ANDROID_SERIAL', os.environ.get('SERIAL', 'ZY22J3TK8S'))
adb = ['adb', '-s', serial]
termux = '/data/data/com.termux/files'
remote = termux + '/home/agentos/agentd'
failures = 0
ports = []

def run(command, data=None, timeout=20):
    p = subprocess.run(command, input=data, capture_output=True, text=True, timeout=timeout)
    if p.returncode:
        raise RuntimeError('command failed; check phone connection, permissions or service')
    return p.stdout.strip()

def shell(command, root=False, **kw):
    return run(adb + ['shell', 'su -c ' + shlex.quote(command) if root else command], **kw)

def tx(command, timeout=30):
    return run(['env', 'SERIAL=' + serial, 'agentd/deploy/tx.sh', command], timeout=timeout)

def report(ok, label, detail):
    global failures
    failures += not ok
    word = 'GREEN' if ok else 'RED'
    color = ('\033[32m' if ok else '\033[31m') if sys.stdout.isatty() and not os.environ.get('NO_COLOR') else ''
    print(f'{color}{word:5}\033[0m {label}: {detail}' if color else f'{word:5} {label}: {detail}', flush=True)

def check(label, fn):
    try:
        ok, detail = fn()
        report(ok, label, detail)
    except Exception as e:
        # Never print subprocess arguments, bearer tokens, private data or arbitrary server errors.
        report(False, label, str(e) if isinstance(e, RuntimeError) else f'could not verify ({type(e).__name__})')

def forward(target):
    port = run(adb + ['forward', 'tcp:0', target])
    ports.append(port)
    return 'http://127.0.0.1:' + port

@atexit.register
def cleanup():
    for port in ports:
        subprocess.run(adb + ['forward', '--remove', 'tcp:' + port], capture_output=True, timeout=5)

def http(url, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    with urllib.request.urlopen(req, timeout=12) as r:
        return r.read().decode()

broker = None

def rpc(method, params=None):
    global broker
    if broker is None:
        broker = (forward('tcp:7777'), shell('cat /data/data/local.agentos/files/token', root=True))
    r = json.loads(http(broker[0] + '/rpc', {'method': method, 'params': params or {}}, broker[1]))
    if r.get('ok') is False or r.get('error'):
        raise RuntimeError('broker refused the check; no lease or approval will be created')
    return r

print('AgentOS stage preflight — ' + ('fix: ' + ','.join(sorted(fixes)) if fixes else 'read-only'), flush=True)
try:
    assert run(adb + ['get-state']) == 'device'
except Exception:
    report(False, 'Phone', 'not connected or not authorized: ' + serial)
    sys.exit(1)

check('agentd', lambda: (shell(f'{termux}/usr/bin/curl -fsS --max-time 5 http://127.0.0.1:8787/health', root=True) == 'ok', 'health endpoint'))

def deployed():
    # Compare committed runtime files, not someone else's uncommitted work. Never deploy or restart here.
    commit = run(['git', 'rev-parse', 'HEAD'])
    paths = run(['git', 'ls-tree', '-r', '--name-only', commit, 'agentd']).splitlines()
    paths = [p for p in paths if p.endswith(('.js', '.json', '.html', '.sh'))
             and not re.search(r'/(bench|research|runs|eval|fixtures)/|(?:^|[-/])test(?:[-.]|/)|mock-phone', p)]
    hashes = {p[7:]: hashlib.sha256(subprocess.check_output(['git', 'show', commit + ':' + p])).hexdigest() for p in paths}
    code = '''const fs=require('fs'), crypto=require('crypto');
const expected=JSON.parse(fs.readFileSync(0,'utf8')), bad=[]; let newest=0;
for(const [p,h] of Object.entries(expected)) { try { const f=process.cwd()+'/'+p;
 if(crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex')!==h) bad.push(p);
 newest=Math.max(newest,fs.statSync(f).mtimeMs); } catch { bad.push(p); } }
console.log(JSON.stringify({bad,newest}));'''
    result = json.loads(shell(f'cd {remote} && {termux}/usr/bin/node -e {shlex.quote(code)}', root=True, data=json.dumps(hashes)))
    # A matching disk tree alone is not proof that the running process loaded it.
    pid = shell("pgrep -f '^node server.js$'", root=True).splitlines()
    if len(pid) != 1:
        return False, 'expected one live server process'
    stat = shell(f'cat /proc/{int(pid[0])}/stat', root=True).rsplit(') ', 1)[1].split()
    boot = int(re.search(r'^btime (\d+)$', shell('cat /proc/stat', root=True), re.M)[1])
    ticks = int(shell('getconf CLK_TCK'))
    loaded = (boot + int(stat[19]) / ticks) * 1000 >= result['newest']
    bad = result['bad']
    return not bad and loaded, (f'{len(bad)} files differ/missing vs {commit[:8]}' if bad else f'files match {commit[:8]}') + ('; restart needed' if not loaded else '')
check('Deployed code', deployed)

def wifi():
    # Power save lets the router hold replies while the radio dozes: keyboard replies arrived in a burst 2-6 s late (keyboard/LATENCY.md).
    if 'wifi' in fixes:
        shell('cmd wifi force-low-latency-mode enabled', root=True)
    ok = 'Current operation mode: 4' in shell('dumpsys wifi | grep "Current operation mode"', root=True)
    return ok, 'low-latency mode (no power save)' if ok else 'power save on; use --fix wifi'
check('Wi-Fi latency', wifi)

check('Internet', lambda: (shell(f'{termux}/usr/bin/curl -fsS --max-time 10 https://www.google.com/generate_204 -o /dev/null && echo reachable', root=True) == 'reachable', 'phone HTTPS reachable'))

def codex():
    binary = shell("p=$(pgrep -f '^node server.js$' | head -1); tr '\\0' '\\n' < /proc/$p/environ | grep '^CODEX_BIN='", root=True)
    # Keep account identifiers out of the report; status is written on stderr.
    out = shell('sh /data/local/tmp/agentos-codex-env.sh login status 2>&1', root=True) if binary.endswith('/deploy/codex-root.sh') else tx('codex login status 2>&1')
    return 'logged in' in out.lower(), 'signed in' if 'logged in' in out.lower() else 'sign-in not confirmed'
check('Codex', codex)

def warm():
    if 'warm' in fixes:
        print('Warming local model (router only; no phone action)...', flush=True)
        tx('cd ~/agentos/agentd && node --input-type=module -e ' + shlex.quote("import {server,plan} from './providers/local.js'; await server('start'); await plan('open clock'); process.exit(0)"), timeout=100)
    model = forward('tcp:8081')
    slots = json.loads(http(model + '/slots'))
    ready = any(s.get('n_prompt_tokens', 0) > 0 and not s.get('is_processing') for s in slots)
    return ready, 'loaded with cached prompt' if ready else 'cold or busy; use --fix warm'
check('Local model', warm)

for name, component, read, write in [
    ('Keyboard', 'local.agentos.keys/.Keys', 'settings get secure default_input_method', 'ime enable local.agentos.keys/.Keys && ime set local.agentos.keys/.Keys'),
    ('Home', 'local.agentos.home/.Home', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME', 'cmd package set-home-activity local.agentos.home/.Home')]:
    def default(name=name, component=component, read=read, write=write):
        if ('ime' if name == 'Keyboard' else 'home') in fixes:
            shell(write)
        ok = component in shell(read).splitlines()
        return ok, 'AgentOS is default' if ok else 'AgentOS is not default'
    check(name, default)

def wake():
    prefs = ET.fromstring(shell('cat /data/data/local.agentos.home/shared_prefs/wake.xml', root=True))
    on = prefs.find("boolean[@name='on']")
    if on is None or on.get('value') != 'true':
        return False, 'off; use the voice button or enable it on Home'
    services = shell('dumpsys activity services local.agentos.home/.Wake')
    pid = int(shell('pidof local.agentos.home'))
    logs = shell(f"logcat -d --pid={pid} -v raw -s AgentOSWake:I '*:S'")
    states = re.findall(r'^(listening|starting|paused[^\n]*|error[^\n]*|off)$', logs, re.M)
    state = states[-1] if states else 'state unavailable; verify Listening on Home'
    return 'isForeground=true' in services and state == 'listening', state
check('Wake word', wake)

def dnd():
    if 'dnd' in fixes:
        shell('cmd notification set_dnd priority')
    mode = shell('settings get global zen_mode')
    return mode in ('1', '2', '3'), {'0': 'off', '1': 'on (priority)', '2': 'on (silent)', '3': 'on (alarms only)'}.get(mode, 'unknown')
check('Do Not Disturb', dnd)

def battery():
    s = shell('dumpsys battery')
    level = int(re.search(r'^\s*level: (\d+)', s, re.M)[1])
    scale = int(re.search(r'^\s*scale: (\d+)', s, re.M)[1])
    level = round(level * 100 / scale)
    charging = bool(re.search(r'(AC|USB|Wireless) powered: true', s))
    return level >= 50 and charging, f'{level}%, ' + ('plugged in' if charging else 'unplugged')
check('Battery', battery)

def alarms():
    try:
        items = rpc('alarms.list')['items']
    except RuntimeError:
        return False, 'cannot list without lease; check AgentOS/bench and unlabeled 06:17 in Clock'
    leftovers = [a for a in items if re.search(r'agentos|bench', a.get('label') or '', re.I)
                 or (not a.get('label') and a['time'] == '06:17')]
    detail = ', '.join(f"id {a['id']} {a['time']} ({'ON' if a['enabled'] else 'off'})" for a in leftovers)
    return not leftovers, detail + '; review in Clock, never auto-delete' if leftovers else 'no matching test alarms'
check('Test alarms', alarms)

def calendar():
    # Candidates only: even a bench-like title could belong to the founder.
    # Agent test items are labeled AgentOS; the founder's own "Test ..." events (ids 1382, 1900, 2525) are real.
    where = "deleted=0 AND (lower(title) LIKE '%agentos%' OR title='Lunch with Sam')"
    rows = shell('content query --uri content://com.android.calendar/events --projection _id --where ' + shlex.quote(where), root=True)
    if rows != 'No result found.' and not rows.startswith('Row:'):
        raise RuntimeError('calendar provider unavailable')
    ids = re.findall(r'_id=(\d+)', rows)
    return not ids, ('possible test items, ids ' + ', '.join(ids) + '; review manually') if ids else 'no test-title candidates'
check('Test calendar items', calendar)

def approvals():
    cards = rpc('approval.list')['approvals']
    return not cards, 'none open' if not cards else 'open; resolve on phone'
check('Approval cards', approvals)

def tasks():
    code = """const fs=require('fs'), WebSocket=require('ws');
const w=new WebSocket('ws://127.0.0.1:8787',{headers:{Authorization:'Bearer '+fs.readFileSync(process.env.HOME+'/.agentos/agentd.token','utf8').trim()}});
setTimeout(()=>process.exit(1),10000);
w.on('error',()=>process.exit(1)); w.on('open',()=>w.send(JSON.stringify({type:'task.list'})));
w.on('message',b=>{const m=JSON.parse(b);if(m.type==='task.list'){console.log(JSON.stringify(m.tasks));process.exit(0)}});"""
    active = [t for t in json.loads(tx('cd ~/agentos/agentd && node -e ' + shlex.quote(code))) if t['state'] not in ('done', 'failed', 'stopped')]
    lease = rpc('approval.list')['task']
    count = len(active) + bool(lease and lease['expires'] > time.time() * 1000)
    return not count, 'none running or queued' if not count else f'{count} workers/leases; resolve on phone'
check('Tasks', tasks)

def tabs():
    if '@chrome_devtools_remote' not in shell('cat /proc/net/unix', root=True):
        return False, 'cannot inspect; open Chrome tab switcher and review manually'
    base = forward('localabstract:chrome_devtools_remote')
    pages = [t for t in json.loads(http(base + '/json/list')) if t['type'] == 'page']
    owned = json.loads(args.tabs.read_text()) if args.tabs else {'serial': serial, 'tabs': []}
    if owned['serial'] != serial:
        raise RuntimeError('Chrome ownership list belongs to a different phone')
    targets = {(t['id'], t['url']) for t in owned['tabs']}
    if 'tabs' in fixes:
        for t in pages:
            if (t['id'], t['url']) in targets and t['url'].startswith(('https://', 'http://')):
                # Recheck identity immediately before closing. The manifest retains URLs for reopening.
                current = json.loads(http(base + '/json/list'))
                if any((p['id'], p['url']) == (t['id'], t['url']) for p in current):
                    url = urllib.parse.urlsplit(t['webSocketDebuggerUrl'])
                    endpoint = base.replace('http:', 'ws:') + url.path
                    # Page.close honors beforeunload; cancel any dialog so unsaved work survives.
                    code = """const WebSocket=require('./agentd/node_modules/ws');
const w=new WebSocket(process.argv[1]); const timer=setTimeout(()=>process.exit(1),10000);
w.on('error',()=>process.exit(1)); w.on('close',()=>process.exit(0));
w.on('open',()=>w.send(JSON.stringify({id:1,method:'Page.enable'})));
w.on('message',b=>{const m=JSON.parse(b);
 if(m.error) process.exit(1);
 if(m.id===1) w.send(JSON.stringify({id:2,method:'Page.close'}));
 if(m.method==='Page.javascriptDialogOpening') w.send(JSON.stringify({id:3,method:'Page.handleJavaScriptDialog',params:{accept:false}}));
 if(m.id===3) {clearTimeout(timer);w.close()}
});"""
                    run(['node', '-e', code, endpoint])
        pages = [t for t in json.loads(http(base + '/json/list')) if t['type'] == 'page']
    return not pages, 'no open tabs' if not pages else f'{len(pages)} open; only --tabs ownership matches can close, review others'
check('Chrome tabs', tabs)

check('Prompt Guard', lambda: (bool(rpc('guard.classify', {'text': 'The weather looks pleasant for our walk this afternoon.'})['model']), 'loaded and answering'))

def reset():
    if 'reset' in fixes:
        rpc('audit.demo_reset')
    entries = rpc('audit.tail', {'n': 500})['entries']
    ts = max((e['ts'] for e in entries if e['tool'] == 'demo.reset'), default=0)
    recent = 0 <= time.time() * 1000 - ts < 15 * 60 * 1000
    return recent, 'reset within 15 minutes' if recent else 'not recent; use --fix reset just before stage'
check('Demo reset', reset)

def audit():
    lines = shell('cat /data/data/local.agentos/files/audit.jsonl', root=True).splitlines()
    if not lines:
        return False, 'empty audit log'
    client = runpy.run_path('android/tools-client.py')
    with contextlib.redirect_stdout(io.StringIO()) as out:
        ok = client['verify'](lines)
    summary = out.getvalue().splitlines()[0].replace('offline chain check (Mac side): ', '')
    return ok, summary
check('Audit chain', audit)
print(f'\n{failures} red checks. ' + ('Review before stage.' if failures else 'Ready for stage.'))
sys.exit(bool(failures))
PY
