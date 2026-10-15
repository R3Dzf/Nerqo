import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from nerqo.delta import make_delta

ROOT=Path(__file__).resolve().parents[1]
class CrossPlatformTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'),'JDK not installed')
    def test_java_applies_python_delta(self):
        with tempfile.TemporaryDirectory() as d:
            d=Path(d)
            base=d/'old.apk';new=d/'new.apk';delta=d/'patch.nrqd'; rebuilt=d/'rebuilt.apk'
            raw=os.urandom(256*1024)
            base.write_bytes(raw)
            new.write_bytes(b'header'+raw[:40000]+b'middle'+raw[40100:]+b'footer')
            make_delta(base,new,delta)
            subprocess.run(['javac','-d',str(d),str(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/DeltaPatch.java'),str(ROOT/'tests/DeltaJavaTest.java')],check=True)
            subprocess.run(['java','-cp',str(d),'DeltaJavaTest',str(base),str(delta),str(rebuilt)],check=True)
            self.assertEqual(rebuilt.read_bytes(),new.read_bytes())
