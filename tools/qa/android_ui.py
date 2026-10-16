"""Capture the actual Compose app on an attached Android test emulator.

This smoke path navigates existing UI; it never pairs or injects fake transfers.
"""
from pathlib import Path
import re
import subprocess
import time
from xml.etree import ElementTree as ET

OUTPUT = Path('ui-smoke-android')
OUTPUT.mkdir(exist_ok=True)


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], text=not binary)


def capture(name):
    time.sleep(1)
    (OUTPUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def tap(label, attr='text'):
    for _ in range(12):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/nerqo-ui.xml')
            tree = ET.fromstring(adb('shell', 'cat', '/sdcard/nerqo-ui.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(2)
            continue
        for node in tree.iter('node'):
            if node.get(attr) == label:
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4:
                    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
                    return
        time.sleep(1)
    raise RuntimeError(f'Native UI element missing: {label}')


adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'am', 'start', '-W', '-n', 'com.r3dzf.nerqo/.MainActivity')
time.sleep(5)
capture('Devices')
tap('Transfers')
capture('Transfers')
tap('Settings')
capture('Settings')
tap('Dark appearance', 'content-desc')
tap('Transfers')
capture('Transfers-dark')
if 'FATAL EXCEPTION' in adb('logcat', '-d', '-s', 'AndroidRuntime:E'):
    raise RuntimeError('Android runtime crash during native UI smoke')
print('Captured native Compose light/dark screens and exercised navigation.')
