"""Render neko_cat.svg to a standalone HTML page (and PNG via headless Chrome/Edge when found) for visual review.

Usage: python tools/art/preview_cat.py OUT_DIR [mood]   (mood: happy | thinking | talking)
"""
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MOODS = {
    'happy': {'eyes_open', 'mouth_closed'},      # hidden layers
    'thinking': {'eyes_happy', 'mouth_open'},
    'talking': {'eyes_open', 'mouth_closed'},
}


def main() -> None:
    out = sys.argv[1]
    mood = sys.argv[2] if len(sys.argv) > 2 else 'happy'
    os.makedirs(out, exist_ok=True)
    svg = open(os.path.join(HERE, 'neko_cat.svg'), encoding='utf-8').read()
    for layer in MOODS[mood]:
        svg = svg.replace(f'<g id="{layer}">', f'<g id="{layer}" display="none">')
    html = ('<html><body style="margin:0;background:#FFB3B3;display:flex;gap:20px;padding:20px;align-items:flex-end">'
            '<div style="width:560px">' + svg.replace('<svg ', '<svg width="560" ') + '</div>'
            '<div style="width:160px;background:#9FF3FF">' + svg.replace('<svg ', '<svg width="160" ') + '</div></body></html>')
    page = os.path.join(out, f'cat_{mood}.html')
    open(page, 'w', encoding='utf-8').write(html)
    browser = next((p for p in (
        r'C:\Program Files\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
        r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe') if os.path.exists(p)), shutil.which('chrome'))
    if browser:
        png = os.path.join(out, f'cat_{mood}.png')
        subprocess.run([browser, '--headless', '--disable-gpu', '--hide-scrollbars', '--window-size=820,660',
                        f'--screenshot={png}', 'file:///' + page.replace('\\', '/')], check=False, timeout=60)
        print(png)
    else:
        print(page)


main()
