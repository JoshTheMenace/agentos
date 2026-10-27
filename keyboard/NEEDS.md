# keyboard/ needs from other tracks

Things the keyboard (`local.agentos.keys`) wants from `agentd/`, `android/` and `demo-apps/`. Nothing here blocks the demo; the keyboard has a workaround for each.

## agentd
- **Token hand-off:** agentd copies its token into `/data/data/local.agentos.keys/files/agentd.token` at start (default `AGENTD_CLIENT_PKGS` already lists `local.agentos.keys`). Please keep that default. Until agentd is restarted after a fresh keyboard install, `keyboard/install.sh` does the same copy itself (root), plus the tool-server token as `phone.token`.
- **Suggestion length:** cells in the QuickType row are ~105 dp wide, so anything over ~18 characters gets an ellipsis. The `suggest` prompt could ask for short replies first (ideal: under 20 chars, like "Red or white?") and put longer ones last.
- **`how`/model tag in the reply:** a `provider` or `model` field on `suggestions` would let the strip's detail line say "on-device" vs "cloud" truthfully (today it just says `agentd`).
- (Optional) The keyboard also sends `field.app` (human app label). Harmless if ignored.

## android (tool server)
- **`screen.read` includes views that are not visible to the user.** On Pigeon's chat screen it returns the chat *and* the conversation list that sits behind it (both layers, full-screen bounds). Skipping nodes where `!isVisibleToUser()` would fix it at the source. The keyboard works around it by splitting nodes into "layers" (a jump back to the top of the screen) and keeping the layer that holds the text field.
- **A `focused` flag on nodes** (or `focused_id` on the reply) would make that choice exact instead of a heuristic.
- **Guard counts:** the keyboard reports `flagged_node_ids` from `guard` as "N flagged" in the detail line; please keep that field.

## demo-apps / Pigeon
- When a chat is open, set the conversation list to `View.GONE` (or `importantForAccessibility=no`). Right now it stays in the accessibility tree under the chat, which confuses any reader (see above).
- The message field has no `imeOptions` action, so the keyboard shows "return" (newline). `IME_ACTION_SEND` on the field would give the blue **send** key from the A2 mockup, with the keyboard calling the same code as the Send button.

## design
- `design/A2-tokens.md` and `web/theme.css` did not exist yet when the keyboard was styled; its `Theme.java` took the values straight from the `:root` block of `design/mockups/A2-calm-glass-ios-dark.html`. When the shared tokens land, diff them against `keyboard/src/local/agentos/keys/Theme.java` (one file).
