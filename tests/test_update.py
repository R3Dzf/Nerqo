import unittest
from unittest.mock import patch
from nerqo.update import choose_asset,newer, WindowsUpdater
from nerqo.version import VERSION

class UpdateLogicTests(unittest.TestCase):
    def test_version_order(self):
        self.assertTrue(newer('0.2.1','0.2.0'))
        self.assertTrue(newer('1.0.0','0.9.9'))
        self.assertFalse(newer('0.2.0','0.2.0'))
        self.assertFalse(newer('0.1.9','0.2.0'))

    def test_choose_delta_exact_hash_and_savings(self):
        group={'full':{'size':1000,'name':'exe'},'deltas':[
            {'from_version':VERSION,'base_sha256':'correct','size':250,'name':'delta'},
            {'from_version':VERSION,'base_sha256':'wrong','size':25,'name':'bad'}]}
        self.assertEqual(choose_asset(group,'correct')['name'],'delta')
        self.assertEqual(choose_asset(group,'unknown')['name'],'exe')
        group['deltas'][0]['size']=950
        self.assertEqual(choose_asset(group,'correct')['name'],'exe')

    def test_no_release(self):
        manager=WindowsUpdater()
        with patch('nerqo.update.latest',return_value=None):
            self.assertEqual(manager.check()['status'],'up-to-date')
