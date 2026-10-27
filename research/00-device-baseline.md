# 00 — Device baseline + on-phone Codex spike (2026-10-26)

Device: Moto G 5G (2023), adb serial `ZY22J3TK8S`. Device lock used: `mkdir /tmp/agentos-device.lock` / `rmdir`.
Prior quirks (from scratch/.../systemui-lab/DEVICE-RESULTS.txt): `adb shell su -c "a && b"` runs only `a` as root,
so quote the whole thing: `adb shell "su -c 'a && b'"`. Zygisk is off. Overlays can't draw above the status bar.

**Bottom line:** Codex `app-server` already runs on the phone. It is **signed in (ChatGPT Pro)** and completed real turns,
including a shell tool call. You don't need to install or log in again.

## Inventory

| Item | Value |
|---|---|
| Build | `motorola/pnangn_g/pnangn:13/T1TPNS33.58-94-4`, Android 13, patch 2024-02-01, kernel 5.4.233, arm64-v8a |
| Boot / SELinux | verifiedbootstate=orange (unlocked), slot `_a`, SELinux Enforcing |
| Magisk | 30.6 (30600). Zygisk=0. **No modules.** service.d: `duolingo_adblock.sh`, `duolingo_debug_button.sh` |
| LSPosed | Not installed |
| Frida | 17.24.3 at `/data/adb/duolingo-debug-button/frida-server` (running as `frida-server-17.24.3`); also copies in `/data/local/tmp/android-agent-lab/` and Termux home (17.2.14) |
| RAM | 3.5 GB total, ~1.1–1.5 GB available, swap 2.6 GB (1.5 GB free) |
| Storage | /data 51 GB, 24 GB free |
| Network | Wi-Fi to hotspot "JOSH PHONE" (metered), IP `10.251.83.223/24`, private DNS = opportunistic |
| Tailscale | Not installed |
| Google Play Services | Yes (gms 26.41.31), Play Store present, F-Droid 1.23.2 present |
| Termux | **Installed**: com.termux 0.118.3 (uid 10401, sideloaded) + Termux:Boot 0.8.1. **Termux:API not installed** |
| Termux toolchain | Python 3.12.12, Node v25.3.0, npm 11.10.0, git 2.53.0, ssh/sshd; **Claude Code 2.1.39** installed globally (`claude --version` works) |
| Accessibility services | None enabled |
| Launcher / IME | `com.motorola.launcher3` / Gboard (`com.google.android.inputmethod.latin`) |
| Assistant | `dev.phoneagent.app/.assistant.PhoneAssistantService` (custom app v0.2.1, holds ASSISTANT role) |
| Other relevant apps | `local.agentos.uilab` (overlay/tools app + root broker `agentos-duolingo`), `com.androidagent.app`, `com.anthropic.claude`, AdAway, Slack, Discord |

## Codex on the phone: what exists

An earlier session already set up a lab (left untouched; nothing reinstalled or overwritten):

- Binary: `/data/local/tmp/android-agent-lab/codex-0.163.0/codex-aarch64-unknown-linux-musl` (static musl, **codex-cli 0.163.0**)
- Home: `/data/local/tmp/android-agent-lab/codex-home` (root-owned; contains `auth.json`, `ca-bundle.pem`; **not read or copied**)
- DNS helper: `/data/local/tmp/android-agent-lab/android-connect-proxy` (a small Android-native binary)
- Old WebSocket launcher: `codex-home/start-server.sh` (`app-server --listen ws://127.0.0.1:4500`, 20-minute timeout)

Latest upstream is rust-v0.164.0. Its musl binary also runs from adb shell (`codex --version` → `codex-cli 0.164.0`).
That was only a test copy and has been deleted.

### Why the proxy is needed (the main quirk)
The musl binary looks for DNS in `/etc/resolv.conf`, and Android doesn't have that file. So Codex can't find any server.
Without the proxy you get: `failed to refresh available models: Connection failed: error sending request`.
**Analogy:** Codex knows how to make the phone call but has no phone book. The proxy is a receptionist with Android's
phone book. Codex says "connect me to chatgpt.com", and the proxy looks the name up and connects it.
The proxy only handles HTTP CONNECT, only for `*.openai.com` and `*.chatgpt.com`, and only on loopback.
TLS uses `SSL_CERT_FILE=codex-home/ca-bundle.pem`.

## Exact commands that worked

Run as **root** (Magisk su). Wrapper pushed to `/data/local/tmp/agentos-codex-env.sh`
(source: `spikes/termux-codex/codex-env.sh`):
```sh
L=/data/local/tmp/android-agent-lab; H=$L/codex-home
env HOME=$H CODEX_HOME=$H TMPDIR=$H/tmp SHELL=/system/bin/sh PATH=/system/bin:/system/xbin:/vendor/bin \
  HTTPS_PROXY=http://127.0.0.1:18080 HTTP_PROXY=http://127.0.0.1:18080 \
  SSL_CERT_FILE=$H/ca-bundle.pem SSL_CERT_DIR=/system/etc/security/cacerts \
  $L/codex-0.163.0/codex-aarch64-unknown-linux-musl "$@"
```
1. Start the proxy (it needs the port as an argument; with no argument it exits with rc=2). **Currently running, PID 22144. It won't survive a reboot.**
   `adb -s ZY22J3TK8S shell "su -c 'cd /data/local/tmp/android-agent-lab; setsid ./android-connect-proxy 18080 > /data/local/tmp/agentos-connect-proxy.log 2>&1 < /dev/null &'"`
2. `adb -s ZY22J3TK8S shell "su -c 'sh /data/local/tmp/agentos-codex-env.sh login status'"` → `Logged in using ChatGPT`
3. stdio app-server from the Mac: `python3 -I spikes/termux-codex/appserver_probe.py [--turn "..."] [--sandbox danger-full-access]`
   This runs `adb shell -T "su -c 'sh /data/local/tmp/agentos-codex-env.sh app-server'"` and sends
   `initialize` → `initialized` → `account/read` → `thread/start {ephemeral, sandbox, approvalPolicy:"never"}` → `turn/start`.
4. To get the protocol schema: `codex app-server generate-json-schema --out DIR`. `turn/start` input is `[{"type":"text","text":"..."}]`.

## Results

| Test | Result |
|---|---|
| `initialize` (stdio) | OK in 0.18–0.62 s; `userAgent .../0.163.0 (Linux Unknown; aarch64)`, `codexHome` = lab home |
| `account/read` | `type=chatgpt planType=pro`, so signed in |
| Turn "Reply with exactly: hi" (read-only sandbox) | reply `hi`; **first token 3.81 s**, total 3.86 s |
| Turn running `getprop ro.product.model`, `sandbox=read-only` | **FAIL**: `error building bubblewrap command: No such file or directory (os error 2)` |
| Same turn, `sandbox=danger-full-access` | **OK**: ran `/bin/sh -lc 'getprop ro.product.model'`, exit 0 → `moto g 5G - 2023`; 25.4 s total |
| Transient DNS failure | During one turn, the proxy logged `native_dns_failed host=chatgpt.com error=No address associated with hostname` 4 times. The WebSocket said `HTTP CONNECT response missing status line`, retried 5/5, then succeeded (67 s). `ping chatgpt.com` worked right after. This is the hotspot's DNS; the client should retry. |

**Memory (VmRSS of app-server):** about 91–100 MB idle after `initialize`, about 207–234 MB after one turn. The proxy uses about 2.7 MB.
With ~1.1–1.5 GB available, one app-server fits. Don't plan on running several at once.

**Warning about `danger-full-access`:** the app-server runs as **root**, so tool commands run as root with no sandbox.
That's powerful enough to drive the phone (getprop, `am`, `input`, `cmd`), but there's no safety net.
Use approval policy `on-request` and gate approvals in the AgentOS UI, or run as a less-privileged uid.

## Login (not attempted; already signed in)
`codex login` supports `--device-auth` (device-code flow), `--with-api-key` (stdin), and `--with-access-token` (stdin).
Device auth prints a URL and a one-time code. The user opens the URL on any browser, signs in to ChatGPT, and types the code.
Codex then writes `$CODEX_HOME/auth.json`. Use the same wrapper env so the network goes through the proxy:
`su -c 'sh /data/local/tmp/agentos-codex-env.sh login --device-auth'`. The earlier session left `codex-home/device-login.log`.

## Termux path
Termux is already set up, so no install was needed. A Termux-env runner is in `spikes/termux-codex/tx.sh`
(it runs a snippet as uid 10401 with PREFIX/HOME/PATH/LD_PRELOAD set). Claude Code 2.1.39 runs there (`claude --version`).
`npx @anthropic-ai/claude-code` was skipped: it's already installed globally, and npx would download the latest over the metered hotspot.
Codex was **not** installed into Termux (the coordinator said not to reinstall). If wanted later, the static musl
binary would hit the same missing-`/etc/resolv.conf` problem inside Termux, so it would still need the proxy.
Termux's `$PREFIX/etc/resolv.conf` doesn't help a static musl binary. Not tested from Termux's real app SELinux context
(adb `su` runs as `u:r:magisk:s0`).

## Recommended path
Use the existing root lab install (`agentos-codex-env.sh` + `android-connect-proxy 18080`) with **stdio** app-server
spawned by the AgentOS broker. The old WebSocket mode (`--listen ws://127.0.0.1:4500 --ws-auth capability-token`)
suits multiple clients. To make it persistent: add a Magisk `service.d` script that starts the proxy at boot (not done; ask first).
Pass `sandbox: danger-full-access` + an approval policy, because bubblewrap doesn't work here.
Client must handle transient DNS/CONNECT retries. Optional: upgrade to 0.164.0 by putting it **next to** 0.163.0 (needs the user's OK).

## Files
- `spikes/termux-codex/codex-env.sh` — on-phone env wrapper (pushed to `/data/local/tmp/agentos-codex-env.sh`)
- `spikes/termux-codex/appserver_probe.py` — stdio JSON-RPC probe (initialize / account / turn / RSS)
- `spikes/termux-codex/tx.sh` — run a snippet as the Termux user in the Termux env
