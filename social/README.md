# social/: launch images and feature videos

Everything here is made in code, so the next post or video reuses the same look. Think of `kit/` as a print shop: you hand it
one small JSON "order" (which screenshot or recording, what words, what to blur), and it prints the finished file.

```
kit/frame.html     draws every layer: A2 wallpaper, phone frame, label/caption, speed badge, title and end cards
kit/render.mjs     still <spec.json> → 1080×1350 PNG     video <edit.json> → MP4 + poster PNG
kit/devtools.mjs   asks the launcher's WebViews where things are (tap targets in phone pixels)
launch/            the two launch images (home.json, linkedin.json → home.png, linkedin.png; src/ = raw phone shots, not in git)
videos/NN-name/    one folder per video: raw/ (phone recordings, not in git), record.sh (how the takes were driven), edit.json, out .mp4 + cover
```

Needs: Google Chrome (headless, for drawing), ffmpeg/ffprobe, Node 22+. Nothing to install.

## Make a still
1. Take a fresh shot: `scripts/withphone adb -s ZY22J3TK8S exec-out screencap -p > social/launch/src/<name>.png`
2. Copy `launch/home.json`, set `shot`, `label` (3 words max), `out`.
3. **Privacy:** list every real person's name, face, avatar, message text, email and phone number in `redact`, in the shot's
   own pixels (open it at full size and read the coordinates off). Each box is `{x,y,w,h}` plus one of:
   `"blur": 30` (frosted copy of what's under it), `"fill": "#1c1c1e"` (solid block), or `"text": "Alex", "size": 40` (a stand-in).
   `"r"` rounds the corners (avatars: r = w/2).
4. `node social/kit/render.mjs still social/launch/<name>.json`, then open the PNG and check every area again.
   `geo` overrides the phone size/position, e.g. `{"sw": 660, "sy": 250}`.

## Make the next video (about 30 minutes)
1. `mkdir social/videos/02-<feature>/raw` and copy `01-make-an-app/record.sh` and `edit.json` in.
2. **Record** the real phone. Turn on Do Not Disturb and touch dots first (record.sh does it and puts them back), then
   `screenrecord` (3 min cap) while you drive the phone from adb. Use `scripts/withphone` for every adb command.
   Find tap targets with `node social/kit/devtools.mjs 'agentos\.home/index' "rect('#askIn')"` (home) or `'agentos\.app'` (micro-apps;
   add the status-bar height, the app window starts below it). Type with `adb shell input text` one word at a time so it looks typed.
   Short takes are fine: cut between them on a screen that looks the same in both.
3. **Edit** `edit.json`: `title` (2 s hook), `segments` (`from`/`to` in seconds of the raw file, `raw` to cut from another take, `caption` with `*word*` for the
   teal accent, `speed` 2 or 4 for waits, which always shows the "2×"/"4×" badge; never speed anything up without it), optional
   `redact` boxes (raw-video pixels, blurred for that segment), `end` (`mark`, `sub`), `posterAt`.
   To find cut points, make a contact sheet: `ffmpeg -i raw/take.mp4 -vf fps=1,scale=180:-1,tile=10x4 sheet.png`.
4. `node social/kit/render.mjs video social/videos/02-<feature>/edit.json` (about a minute), then watch it muted. Check: 20–40 s,
   captions readable on a phone, no private data in any frame (scrub with the contact sheet again), ≤ 100 MB.
5. Clean up the phone: delete anything the video created, by its exact id. A micro-app's data outlives `apps.remove` (WebView storage
   per app id), so first run `localStorage.clear()` in it with devtools.mjs, or the next app with that id starts with old data.

## Style rules
- 4:5, 1080×1350, H.264, no voiceover. Music only if royalty-free and quiet.
- Captions: short, present tense, what the viewer is looking at ("It builds it. Live."). One idea per segment.
- Real phone only. Waits are sped up with a visible badge, never hidden; cutting dead time while the phone sat idle is fine.
- Privacy is mandatory: other people's names, faces, avatars, messages, emails and phone numbers are blurred or replaced.
  The founder's own UI chrome can stay.
