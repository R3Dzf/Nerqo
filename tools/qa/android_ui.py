"""Capture the actual Compose app on an attached Android test emulator.

Instrumentation leaves a real, interrupted TLS/SAF download for UI recovery.
"""
from pathlib import Path
import hashlib
import re
import subprocess
import time
from xml.etree import ElementTree as ET

OUTPUT = Path('ui-smoke-android')
OUTPUT.mkdir(exist_ok=True)


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], text=not binary, timeout=45)


def capture(name):
    time.sleep(1)
    (OUTPUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def failure(message, tree=None):
    capture('Transfer-failure')
    (OUTPUT / 'failure-runtime.txt').write_text(adb('logcat', '-d', '-s', 'AndroidRuntime:E'))
    if tree is not None:
        (OUTPUT / 'failure.xml').write_text(ET.tostring(tree, encoding='unicode'))
        message += ': ' + ' | '.join(n.get('text', '') for n in tree.iter('node') if n.get('text'))
    raise RuntimeError(message)


launcher_recovered = False


def tap(label, attr='text'):
    global launcher_recovered
    tree = None
    for _ in range(12):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/nerqo-ui.xml')
            tree = ET.fromstring(adb('shell', 'cat', '/sdcard/nerqo-ui.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(2)
            continue
        # Emulator instrumentation can leave the notification shade expanded,
        # covering every Compose button. Collapse only a detected shade; do not
        # dismiss application errors, permission prompts or Nerqo dialogs.
        if any(n.get('text') == 'Clear all' and n.get('package') == 'com.android.systemui'
               for n in tree.iter('node')):
            adb('shell', 'cmd', 'statusbar', 'collapse')
            time.sleep(1)
            continue
        # A known disposable-emulator launcher ANR can cover the target app.
        # Recover that exact external package once. Never dismiss a Nerqo ANR.
        labels = [n.get('text', '') for n in tree.iter('node')]
        if any(text.startswith("Quickstep isn't responding") for text in labels):
            if launcher_recovered:
                failure('Emulator launcher remained unresponsive', tree)
            close = next((n for n in tree.iter('node') if n.get('text') == 'Close app'), None)
            if close is not None:
                bounds = list(map(int, re.findall(r'\d+', close.get('bounds', ''))))
                if len(bounds) == 4:
                    launcher_recovered = True
                    (OUTPUT / 'launcher-recovery.txt').write_text('Closed the Quickstep launcher ANR once; full Nerqo UI/transport checks still required.\n')
                    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
                    adb('shell', 'am', 'start', '-W', '-n', 'com.r3dzf.nerqo/.MainActivity')
                    continue
        for node in tree.iter('node'):
            if node.get(attr) == label:
                parents = {child: parent for parent in tree.iter() for child in parent}
                ancestor = node
                disabled = False
                while ancestor is not None:
                    disabled |= ancestor.get('enabled') == 'false'
                    ancestor = parents.get(ancestor)
                if disabled:
                    continue
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4:
                    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
                    return
        time.sleep(1)
    failure(f'Native UI element missing: {label}', tree)


def resume(must_be_active=False):
    # Compose recovery cards move as progress appears. A click is successful
    # only once the app exposes Pause (or the transfer finishes), not merely
    # because adb delivered a tap to coordinates from an accessibility dump.
    for _ in range(4):
        tap('Resume')
        time.sleep(.4)
        adb('shell', 'uiautomator', 'dump', '/sdcard/nerqo-ui.xml')
        tree = ET.fromstring(adb('shell', 'cat', '/sdcard/nerqo-ui.xml'))
        labels = [n.get('text', '') for n in tree.iter('node')]
        if 'Pause' in labels or (not must_be_active and 'Resume' not in labels):
            return
        if any(label.startswith('Interrupted ·') for label in labels):
            failure('Resume was interrupted', tree)
    failure('Resume did not start a transfer', tree)


adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'cmd', 'statusbar', 'collapse')
# Instrumentation owns the target process. Relaunch from a stopped process so
# the UI recovery check also verifies loading the persisted transfer job.
adb('shell', 'am', 'force-stop', 'com.r3dzf.nerqo')
adb('shell', 'pm', 'grant', 'com.r3dzf.nerqo', 'android.permission.POST_NOTIFICATIONS')
(OUTPUT / 'launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', 'com.r3dzf.nerqo/.MainActivity'))
time.sleep(5)
capture('Launch')
tap('Devices')
capture('Devices')
tap('Transfers')
capture('Transfer-restored')
resume(must_be_active=True)
time.sleep(1)
tap('Pause')
time.sleep(1)
capture('Transfer-paused')
resume(must_be_active=True)
# The foreground service must keep writing while the Activity is stopped and screen is off.
before = int(adb('shell', 'run-as', 'com.r3dzf.nerqo', 'stat', '-c', '%s', 'cache/transport-fixtures/ui-resume.bin').strip())
adb('shell', 'settings', 'put', 'global', 'always_finish_activities', '1')
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
time.sleep(4)
service = adb('shell', 'dumpsys', 'activity', 'services', 'com.r3dzf.nerqo')
(OUTPUT / 'background-service.txt').write_text(service)
if 'TransferService' not in service or 'isForeground=true' not in service:
    failure('Transfer is not owned by a foreground service while screen is off')
after = int(adb('shell', 'run-as', 'com.r3dzf.nerqo', 'stat', '-c', '%s', 'cache/transport-fixtures/ui-resume.bin').strip())
if after <= before:
    failure('Saved file did not grow with the Activity stopped and screen off')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'settings', 'put', 'global', 'always_finish_activities', '0')
adb('shell', 'am', 'start', '-W', '-n', 'com.r3dzf.nerqo/.MainActivity')
for _ in range(25):
    time.sleep(2)
    try:
        adb('shell', 'uiautomator', 'dump', '/sdcard/nerqo-ui.xml')
        tree = ET.fromstring(adb('shell', 'cat', '/sdcard/nerqo-ui.xml'))
        if any(n.get('text', '').startswith('Interrupted ·') for n in tree.iter('node')):
            failure('The native UI reported an interrupted transfer', tree)
        if not any(n.get('text') in ('Resume', 'Pause') for n in tree.iter('node')):
            break
    except (subprocess.CalledProcessError, ET.ParseError):
        continue
else:
    failure('Native UI transfer did not complete after resume', tree)
capture('Transfers')
received = adb('exec-out', 'run-as', 'com.r3dzf.nerqo', 'cat', 'cache/transport-fixtures/ui-resume.bin', binary=True)
expected = bytes(range(256)) * (64 * 4096) + b'ui'
if hashlib.sha256(received).digest() != hashlib.sha256(expected).digest():
    raise RuntimeError('UI-resumed document does not match the TLS source')
(OUTPUT / 'transfer-verification.txt').write_text('PASS: restored download; native Pause/Resume; screen-off foreground service kept writing; final size and SHA-256 match source.\n')
tap('History')
capture('History')
tap('Settings')
capture('Settings')
tap('Dark appearance', 'content-desc')
tap('Transfers')
capture('Transfers-dark')
if 'FATAL EXCEPTION' in adb('logcat', '-d', '-s', 'AndroidRuntime:E'):
    raise RuntimeError('Android runtime crash during native UI smoke')
print('Captured Compose light/dark screens; restored, paused and resumed an actual TLS/SAF download through native UI.')
