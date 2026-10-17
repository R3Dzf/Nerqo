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
        document = ET.parse(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml')
        progress = [node.attrib.get('Value', '') for node in document.iter()
                    if node.tag.endswith('ProgressBar') and 'Percent' in node.attrib.get('Value', '')]
        self.assertEqual(progress, ['{Binding Percent, Mode=OneWay}'])
        self.assertIn('progress-binding-verification.txt',
                      (ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml.cs').read_text())

    def test_live_connection_is_event_driven_not_seven_second_polling(self):
        desktop=(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml.cs').read_text()
        server=(ROOT/'desktop/Nerqo.Desktop/Services/LanHost.cs').read_text()
        client=(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/NativeClient.kt').read_text()
        activity=(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/MainActivity.kt').read_text()
        service=(ROOT/'android/app/src/main/java/com/r3dzf/nerqo/LiveConnectionService.kt').read_text()
        manifest=(ROOT/'android/app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn('TimeSpan.FromMilliseconds(500)', desktop)
        self.assertIn('service.Changed += RefreshFromEvent', desktop)
        self.assertIn('"/api/events"', server)
        self.assertIn('text/event-stream', server)
        self.assertIn('Channel.CreateBounded', server)
        self.assertIn('"/api/events"', client)
        self.assertIn('NativeClient.watchEvents(', service)
        self.assertIn('LiveConnectionState.items', activity)
        self.assertNotIn('delay(7000)', activity)
        self.assertNotIn('private fun refresh(silent', activity)
        self.assertIn('FOREGROUND_SERVICE_CONNECTED_DEVICE', manifest)
        self.assertIn('android:foregroundServiceType="connectedDevice"', manifest)

    def test_dark_history_dropdown_uses_themed_popup(self):
        palette=(ROOT/'desktop/Nerqo.Desktop/App.xaml').read_text()
        window=(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml').read_text()
        ET.parse(ROOT/'desktop/Nerqo.Desktop/App.xaml')
        ET.parse(ROOT/'desktop/Nerqo.Desktop/MainWindow.xaml')
        self.assertIn('x:Key="HistoryFilterStyle"',palette)
        self.assertIn('x:Key="HistoryFilterItem"',palette)
        self.assertIn('Background="{DynamicResource Panel}"',palette)
        self.assertIn('Style="{StaticResource HistoryFilterStyle}"',window)

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
        self.assertIn('versionCode = 10010',gradle)
        self.assertIn('versionName = "1.0.10"',gradle)
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
