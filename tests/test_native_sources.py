"""Native preview acceptance checks independent of Windows/Android SDK availability."""
from pathlib import Path
from xml.etree import ElementTree as ET
import unittest
ROOT = Path(__file__).resolve().parents[1]
class NativeSources(unittest.TestCase):
    def test_wpf_is_windows_gui_not_pyinstaller_or_browser(self):
        project=(ROOT/'desktop/Nerqo.Desktop/Nerqo.Desktop.csproj').read_text()
        self.assertIn('<OutputType>WinExe</OutputType>',project)
        self.assertIn('<UseWPF>true</UseWPF>',project)
        self.assertIn('QRCoder',project)
        self.assertIn('Refresh network and QR', (ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml').read_text())
        code=(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml.cs').read_text()
        self.assertNotIn('WebView',code)
        self.assertNotIn('webbrowser.open',code)
        ET.parse(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml')
    def test_transfer_progress_binding_is_read_only(self):
        # WPF RangeBase.Value defaults to a TwoWay binding; read-only Percent
        # previously killed Nerqo 1.0.8 during transfer queue rendering.
        document = ET.parse(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml')
        progress = [
            node.attrib.get('Value', '')
            for node in document.iter()
            if node.tag.endswith('ProgressBar') and 'Percent' in node.attrib.get('Value', '')
        ]
        self.assertEqual(progress, ['{Binding Percent, Mode=OneWay}'])
        self.assertIn('progress-binding-verification.txt',
                      (ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml.cs').read_text())

    def test_android_is_compose_not_webview(self):
        src=(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/MainActivity.kt').read_text()
        self.assertIn('setContent',src)
        self.assertIn('ScanContract()',src)
        self.assertIn('pairFromQr(raw: String)',src)
        self.assertIn('startLocalOnlyHotspot',(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/LocalNetwork.kt').read_text())
        self.assertIn('wifiText()',src)
        self.assertNotIn('android.webkit.WebView',src)
        self.assertFalse((ROOT/'android/app/src/main/java/com/r3dzf/nerqo/MainActivity.java').exists())
        gradle=(ROOT/'android/app/build.gradle.kts').read_text()
        self.assertIn('org.jetbrains.kotlin.plugin.compose',gradle)
        self.assertIn('versionCode = 10009',gradle)
        self.assertIn('versionName = "1.0.9"',gradle)
    def test_offline_protocol_and_radio_data(self):
        host=(ROOT/'desktop/Nerqo.Desktop/Services/LanHost.cs').read_text()
        client=(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/NativeClient.kt').read_text()
        self.assertIn('NERQO_DISCOVER_V1',host)
        self.assertIn('NERQO_DISCOVER_V1',client)
        for resource in ['/api/status','/api/pair','/api/items','/api/transfers/begin','/api/files/']:
            with self.subTest(resource=resource):
                self.assertIn(resource,host)
                self.assertIn(resource,client)
        self.assertIn('SHA256.HashData',host)
        self.assertIn('UseHttps(tlsCertificate)',host)
        self.assertIn('previewIdentity',client)
        self.assertIn('trustIdentity',client)
        self.assertIn('HttpsURLConnection',client)
        self.assertIn('SHA-256',client)
        self.assertIn('44888',host)
        self.assertIn('44888',client)
    def test_permissions_and_no_private_key(self):
        ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml')
        self.assertIn('android:usesCleartextTraffic="false"',(ROOT/'android/app/src/main/AndroidManifest.xml').read_text())
        self.assertTrue((ROOT/'desktop/Nerqo.Desktop/Services/LanIdentity.cs').exists())
        self.assertFalse(list(ROOT.rglob('*.p12')))
        self.assertFalse(list(ROOT.rglob('*.jks')))
if __name__=='__main__':unittest.main()
