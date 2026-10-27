# A2 · Calm Glass iOS Dark — design tokens

Source: `design/mockups/A2-calm-glass-ios-dark.html`. The CSS lives in `web/theme.css` (same names, `--` prefixed).
Native surfaces (island, approval card, keyboard strip) copy the values below. Say **Fingerprint**, never "Touch ID".

## Color
| Token | Value | Use |
|---|---|---|
| `bg` | `#000` (page `#050506`) | screen background |
| `grp` / `grp2` / `grp3` | `#1C1C1E` / `#2C2C2E` / `#3A3A3C` | grouped surfaces, bubbles, selected tab |
| `l1` | `#FFFFFF` | primary text |
| `l2` | `rgba(235,235,245,.6)` | secondary text |
| `l3` | `rgba(235,235,245,.3)` | tertiary text, eyebrow labels |
| `l4` | `rgba(235,235,245,.18)` | disabled |
| `hair` | `rgba(255,255,255,.09)` | hairline dividers |
| `blue` | `#0A84FF` | primary action, approval state |
| `green` | `#30D158` | done, allowed |
| `orange` | `#FF9F0A` | working state |
| `red` | `#FF453A` | alert, blocked, stop |
| `pink` | `#FF375F` | health |
| `purple` | `#BF5AF2` | groups, voice |
| `teal` | `#64D2FF` | voice |
| `idle` | `#8E8E93` | idle orb |
| `acc` | defaults to `idle` | state accent; set per element (`working`→orange, `approval`→blue, `done`→green, `alert`→red) |

Wallpaper: black with three slow radial blobs — indigo `rgba(70,60,220,.36)`, teal `rgba(40,170,190,.22)`, pink `rgba(230,90,150,.2)`, 560/560/480 px, drifting 24–32 s on `transform` only.

## Type (Inter, `-webkit-font-smoothing: antialiased`, features `cv11 ss03`)
| Role | Size / weight / tracking |
|---|---|
| Clock | 78 / 700 / −.05em, line-height 1 |
| Voice transcript (user) | 28 / 600 / −.035em |
| Voice transcript (agent) | 21 / 500 / −.025em |
| Screen title | 21 / 700 / −.035em |
| Card title | 17 / 600 / −.02em |
| Big number | 30 / 700 / −.04em (unit 12 / 600) |
| Row | 14 / 600 / −.01em; sub 12 / 500 |
| Body | 14 / 500 |
| Button | 15 / 600 / −.01em |
| Eyebrow `k` | 11 / 700 / +.02em uppercase, color `l3` |
| Tag | 11.5 / 600 |
| Chip | 10.5 / 600 |
| Log | 12 / 500, time tabular 600 |

## Radius
`r` 22 (cards, modules) · dock 32 · island pill 20 → card 44 · app icon 15 (60 px) · small app icon 9–10 (32–36 px) · toggle 14 (46×28) · pills 999.

## Glass layers (no real blur: cross-window blur is off on the phone)
| Layer | Recipe |
|---|---|
| `glass` | fill `linear-gradient(160deg, rgba(255,255,255,.12), rgba(255,255,255,.06))` + 1 px hairline ring `linear-gradient(160deg, .28, .07 40%, .03 70%, .14)` via mask |
| `ink` | `linear-gradient(170deg, rgba(44,44,46,.96), rgba(28,28,30,.96))` — opaque glass for sheets over apps |
| `mod` | `rgba(72,72,82,.6)` + `inset 0 0 0 1px rgba(255,255,255,.08), inset 0 1px 0 rgba(255,255,255,.08)` — control-center modules |
| `dim` | `rgba(0,0,0,.8)` scrim |
| `btn.deny` / `pillbtn` | `rgba(255,255,255,.14)` |
| icon shadow | `0 6px 16px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.25)` |
| blue glow | `0 4px 14px rgba(10,132,255,.45)` |

## Motion
| Curve | Value | Use |
|---|---|---|
| `spring` | `cubic-bezier(.34,1.5,.64,1)` | island morph .6 s, sheets .7 s, toggles .35 s, button press .25 s (scale .94) |
| `soft` | `cubic-bezier(.25,1.2,.4,1)` | `rise` entrance .7 s (translateY 16 px, scale .98 → none), stagger .04–.24 s |
| `ease` | `cubic-bezier(.2,.8,.2,1)` | screen crossfade .45 s, ring progress 1.2 s, tabs .25 s |

Loops: `breathe` 2.4 s (scale 1→1.14, opacity .88→1) · `scan` 1.8 s (fingerprint icon) · `halo` 2.6 s · `spin` 6 s (voice edge) · `gl` 4 s (voice inset glows) · `blink` 1 s caret.
Animate only `transform` and `opacity`; the phone is mid-range.

## State colors
Orb / island / task row: `working` orange · `approval` blue · `done` green · `alert` red · idle grey. Rule-of-Two chips light up in the current accent.
