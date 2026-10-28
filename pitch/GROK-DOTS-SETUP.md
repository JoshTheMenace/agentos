# Connect Grok and ChatGPT to the phone (2026-10-28)

Address: **https://173-249-38-183.sslip.io/mcp** (VPS relay, live; the phone dials out to it).
Tested 10-28 from the Mac over this URL: OAuth sign-in with a pairing code, 5 tools, and "what's my battery?" showed the
lease card on the phone. Nobody tapped, so it timed out ("approval timed out"). The gate works. The test client was unpaired.

## 1. Make a pairing code (lasts 10 min, works once)
- On the phone: Command center › Remote › **Pair an assistant**. The code and the address show on screen.
- Or from the Mac: `agentd/deploy/tx.sh 'cd ~/agentos/agentd && node mcp-remote.js pair'`

## 2. Grok
1. grok.com → Connectors → New connector → **Custom**.
2. URL `https://173-249-38-183.sslip.io/mcp`, then Connect.
3. Our pairing page opens: type the code, pick "Grok", Connect.
4. Ask Grok: "Use AgentOS: open LinkedIn." It just opens (no card). Sends, deletes and payments still show a card on the phone.

## 3. ChatGPT
1. Settings → Apps/Plugins → turn on **Developer mode** → Add MCP server.
2. URL `https://173-249-38-183.sslip.io/mcp`, auth **OAuth**.
3. Same pairing page: type a fresh code, pick "Dots", Connect.

## 4. Stop or remove
- **Kill switch:** Command center › Remote › "Allow remote assistants" off. Every call is refused and their running tasks stop.
  Mac: `node mcp-remote.js off` (`on` to undo), via `tx.sh` as above.
- **Unpair one:** Remote › the assistant's row › Unpair (tap twice). Mac: `node mcp-remote.js status`, then `unpair <grant>`.
- **Revoke all:** Remote › "Revoke all". Mac: `node mcp-remote.js revoke`.

## Security reminder
The VPS root password was shared in chat today. After the hackathon, change it (`passwd`) and turn off SSH password login
(`PasswordAuthentication no` in `/etc/ssh/sshd_config`, then `systemctl reload ssh`). Key login over Tailscale keeps working.
Nothing was changed for you.

VPS details: relay = `agentos-relay` systemd service (`/opt/agentos-relay`, key in `/etc/agentos-relay.key`, mode 600);
Caddy serves TLS for `173-249-38-183.sslip.io`; health check `https://173-249-38-183.sslip.io/relay-health`.
