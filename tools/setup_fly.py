"""Install flyctl inside this workspace without changing global PATH or UAC."""
from pathlib import Path
from urllib.request import urlopen
import io
import zipfile

root = Path(__file__).resolve().parent.parent
destination = root / '.tools' / 'fly'
destination.mkdir(parents=True, exist_ok=True)
url = urlopen('https://api.fly.io/app/flyctl_releases/windows/x86_64/latest', timeout=30).read().decode().strip()
if not url.startswith('https://'):
    raise RuntimeError('Invalid official release URL')
archive = zipfile.ZipFile(io.BytesIO(urlopen(url, timeout=120).read()))
for item in archive.infolist():
    target = (destination / item.filename).resolve()
    if not target.is_relative_to(destination.resolve()):
        raise RuntimeError('Unsafe archive path')
archive.extractall(destination)
print('Fly CLI installed in .tools/fly')
