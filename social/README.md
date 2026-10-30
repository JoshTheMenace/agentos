# social/: launch images and feature videos

Everything here is made in code, so the next post or video reuses the same look. Think of `kit/` as a print shop: you hand it
one small JSON "order" (which screenshot or recording, what words, what to blur), and it prints the finished file.

```
kit/frame.html     draws every layer: A2 wallpaper, phone frame, caption, speed badge, tap ripple, optional title/end cards
kit/render.mjs     still <spec.json> → 1080×1350 PNG     pair <spec.json> → 1600×1000 before/after PNG     video <edit.json> → MP4 + poster PNG
kit/devtools.mjs   asks the launcher's WebViews where things are (tap targets in phone pixels)
kit/rec.sh         sourced by record.sh: rec/stop, and tap/swipe that log every touch with its time to take-N.taps (the camera reads it)
launch/            the launch images (01-home-ask.json → 01-home-ask.png … 09; sheet.png = all nine for review; src/ = raw phone shots, not in git;
                   shoot.sh = how each raw shot was driven, one phase per image, asks sent through the launcher's own sheet)
videos/NN-name/    one folder per video: raw/ (phone recordings, not in git), record.sh (how the takes were driven), edit.json, out .mp4 + cover
```

Needs: Google Chrome (headless, for drawing), ffmpeg/ffprobe, Node 22+. Nothing to install.

## Make a still
1. Take a fresh shot: `scripts/withphone adb -s ZY22J3TK8S exec-out screencap -p > social/launch/src/<name>.png`
2. Copy `launch/01-home-ask.json`, set `shot`, `label`, `out`. `"style": "plain"` is the casual look: a small `asked: "…"` line that wraps;
   without it the label is the big bold headline (3 words max). `geo: {"sw": 470, "sy": 236}` fits the whole phone (an answer or a keyboard
   at the bottom); the default bleeds off the bottom.
3. **Privacy:** list every real person's name, face, avatar, message text, email and phone number in `redact`, in the shot's
   own pixels (open it at full size and read the coordinates off). Each box is `{x,y,w,h}` plus one of:
   `"blur": 30` (frosted copy of what's under it), `"fill": "#1c1c1e"` (solid block), or `"text": "Alex", "size": 40` (a stand-in).
   `"r"` rounds the corners (avatars: r = w/2).
4. `node social/kit/render.mjs still social/launch/<name>.json`, then open the PNG and check every area again.
   `geo` overrides the phone size/position, e.g. `{"sw": 660, "sy": 250}`.

## Make a pair (two moments of one ask, or a mod off and on)
Two shots side by side in phone frames, a small plain tag over each, and an optional `label` across the top (the ask; wraps). `"h": 1350`
fits whole phones at `sw` 460 (the 1000-tall default bleeds). `redact` on a phone, or shared. Before/after of a mod:
1. Shoot "after" with the mod on. Then switch the accessibility service off, shoot "before", switch it back on (the mod spec is saved in
   prefs and comes back enabled); don't remove the mod, re-applying needs the owner's tap. `linkedin-before-after.json` shows the shape:
   `phones: [{shot, label}]`, one shared `redact` list (same pixels in both shots), `geo: {sw, gap}`.
2. `node social/kit/render.mjs pair social/launch/<name>.json`, then check both phones for faces and names.

## Make the next video (about 30 minutes)
1. `mkdir social/videos/02-<feature>/raw` and copy `01-make-an-app/record.sh` and `edit.json` in.
2. **Record** the real phone. Turn on Do Not Disturb and touch dots first (record.sh does it and puts them back), then
   `rec N` … `stop N` (kit/rec.sh; 3 min cap) while you drive the phone with its `tap X Y` / `swipe X1 Y1 X2 Y2`, never a bare
   `input tap`: each touch is logged to `take-N.taps` with its time, and that log is what lets the video zoom toward the finger.
   Use `scripts/withphone` for every adb command.
   Find tap targets with `node social/kit/devtools.mjs 'agentos\.home/index' "rect('#askIn')"` (home) or `'agentos\.app'` (micro-apps;
   add the status-bar height, the app window starts below it). Type with `adb shell input text` one word at a time so it looks typed.
   Short takes are fine: cut between them on a screen that looks the same in both.
3. **Edit** `edit.json` (see `01-make-an-app/casual.json`): `segments` (`from`/`to` in seconds of the raw file, `raw` to cut from another take,
   `caption`: short, plain, lowercase is fine; `speed` 2 or 4 for waits, which always shows the "2×"/"4×" badge; never speed anything up
   without it), optional `redact` boxes (raw-video pixels, blurred for that segment), `posterAt`.
   **Camera** (`"camera": "follow"`, the default; `"off"` globally or on a segment): about 0.5 s before each logged tap the view eases
   in 1.8× toward it, a soft ring marks the touch, it holds 1 s while the screen reacts, then eases out; taps close together stay zoomed
   and pan. Tune with `"camera": {"zoom", "lead", "hold", "out", "settle"}`, or `"camera": {"zoom": 1.4}` on one segment. For things the
   agent does (a field being typed into, a streaming answer, a new icon) add `"focus": {"at", "until", "x", "y"}` to the segment (raw seconds,
   phone pixels; `until` optional, `zoom` optional). If a log's times are off against the footage, `"tapShift": -0.2` moves them all.
   Title and end cards are off unless you add `title` (2 s hook) or `end` (`mark`, `sub`); `"captions": "bold"` brings back the big teal-accent style.
   To find cut points, make a contact sheet: `ffmpeg -i raw/take.mp4 -vf fps=1,scale=180:-1,tile=10x4 sheet.png`.
   A take ends at its last screen change (screenrecord writes no frames while the screen is still), so a final screen you want to
   linger on gets `"hold": 2` on its segment: the last frame stays that long.
   Scene changes line up with the tap log within ~0.15 s: `ffmpeg -i raw/take.mp4 -vf "select='gt(scene,0.015)',showinfo" -f null -`
   lists them (pts_time) if a log looks off.
4. `node social/kit/render.mjs video social/videos/02-<feature>/edit.json` (about half a minute), then watch it muted. Check: 20–40 s,
   captions readable on a phone, each zoom lands on the thing that changes (`fps=30,trim=…` strips around a tap), no private data in any
   frame (scrub with the contact sheet again), ≤ 100 MB.
5. Clean up the phone: delete anything the video created, by its exact id. A micro-app's data outlives `apps.remove` (WebView storage
   per app id), so first run `localStorage.clear()` in it with devtools.mjs, or the next app with that id starts with old data.
   To put an app back to first-run for a retake (`record-japanese.sh reset`): a micro-app saves on every change and its activity stays
   alive across HOME, and reload/navigation are blocked in its WebView, so clear the storage with `Storage.prototype.setItem` stubbed out,
   call `AgentOSApp.close()`, then reopen it from Home (its tile summary refreshes on that open). devtools.mjs talks to the page that is on
   screen: a finished app activity keeps its WebView around for a while, so one app can show several pages.

## Style rules
- 4:5, 1080×1350, H.264, no voiceover. Music only if royalty-free and quiet.
- Tone: a casual build-in-public clip ("here's a thing I'm working on"), not an ad. No title/end cards by default, small plain captions,
  and the camera shows what is being done instead of cutting between screens.
- Captions: short, present tense, what the viewer is looking at ("logging a few cups"). One idea per segment.
- Real phone only. Waits are sped up with a visible badge, never hidden; cutting dead time while the phone sat idle is fine.
- Privacy is mandatory: other people's names, faces, avatars, messages, emails and phone numbers are blurred or replaced.
  The founder's own UI chrome can stay.
