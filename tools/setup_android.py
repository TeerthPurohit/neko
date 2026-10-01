"""Download project-local, official Android build tools; never installs globally."""
from pathlib import Path
import hashlib, json, urllib.request, zipfile, subprocess, os

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def download(url, name, checksum=None):
    path = TOOLS / name
    if not path.exists():
        print('Downloading ' + name, flush=True)
        urllib.request.urlretrieve(url, path)
    if checksum and hashlib.sha256(path.read_bytes()).hexdigest() != checksum.strip():
        raise RuntimeError('Checksum mismatch: ' + name)
    return path

def unzip(path, target):
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            resolved = (target / entry.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):
                raise RuntimeError('Archive traversal')
        archive.extractall(target)

if not list(TOOLS.glob('jdk-*/bin/java.exe')):
    unzip(download('https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip', 'jdk17.zip'), TOOLS)
jdk = next(TOOLS.glob('jdk-*/bin/java.exe')).parent.parent
gradle = TOOLS / 'gradle-8.13'
if not gradle.exists():
    with urllib.request.urlopen('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256', timeout=30) as response:
        checksum = response.read().decode()
    unzip(download('https://services.gradle.org/distributions/gradle-8.13-bin.zip', 'gradle.zip', checksum), TOOLS)
sdk = TOOLS / 'android-sdk'
sdkmanager = sdk / 'cmdline-tools/latest/bin/sdkmanager.bat'
if not sdkmanager.exists():
    sdk.mkdir(exist_ok=True)
    unzip(download('https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip', 'android-tools.zip'), sdk / 'cmdline-tools/latest-stage')
    (sdk / 'cmdline-tools/latest-stage/cmdline-tools').rename(sdk / 'cmdline-tools/latest')
env = os.environ.copy()
env['JAVA_HOME'] = str(jdk)
env['ANDROID_HOME'] = str(sdk)
env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']
process = subprocess.run([str(sdkmanager), '--sdk_root=' + str(sdk), '--licenses'], input='y\n' * 100, text=True, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
if process.returncode:
    raise RuntimeError('SDK licenses could not be accepted')
process = subprocess.run([str(sdkmanager), '--sdk_root=' + str(sdk), 'platform-tools', 'platforms;android-36', 'build-tools;36.0.0'], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
if process.returncode:
    print(process.stdout[-3000:])
    raise RuntimeError('SDK package installation failed')
(ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk).replace('\\', '/') + '\n', encoding='utf-8')
print('Android toolchain ready: JDK 17, Gradle 8.13, SDK 36.', flush=True)
