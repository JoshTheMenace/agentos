#!/bin/sh
# Re-export the stage deck: stage.pdf, slides/slide-NN.png (1600x900) and slides/overview.png. Needs Google Chrome.
cd "$(dirname "$0")"; C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; U="file://$PWD/index.html"
"$C" --headless=new --disable-gpu --no-pdf-header-footer --virtual-time-budget=4000 --print-to-pdf=stage.pdf "$U?print=1" 2>/dev/null
mkdir -p slides; rm -f slides/slide-*.png
N=$(grep -c "<section class=\"slide\"" index.html); for i in $(seq 1 $N); do "$C" --headless=new --disable-gpu --hide-scrollbars --virtual-time-budget=4000 --window-size=1600,900 --screenshot="slides/slide-$(printf %02d $i).png" "$U?slide=$i" 2>/dev/null; done
{ echo '<body style="margin:0;background:#000;display:grid;grid-template-columns:repeat(3,1fr);gap:16px;padding:16px;width:1600px;box-sizing:border-box">'; for f in slides/slide-*.png; do echo "<img src=\"$PWD/$f\" style=\"width:100%;display:block;border-radius:8px\">"; done; echo '</body>'; } > /tmp/stage-overview.html
"$C" --headless=new --disable-gpu --hide-scrollbars --virtual-time-budget=4000 --window-size=1600,$((16+(N+2)/3*304)) --screenshot=slides/overview.png "file:///tmp/stage-overview.html" 2>/dev/null
ls -la stage.pdf slides | head -20
