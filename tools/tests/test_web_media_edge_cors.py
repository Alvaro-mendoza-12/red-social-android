from pathlib import Path
import re,unittest

class WebMediaEdgeCorsTest(unittest.TestCase):
    def setUp(self):
        text=(Path(__file__).resolve().parents[2]/'infrastructure/local-fallback/nginx/default.conf').read_text()
        self.block=re.search(r'location \^~ /_novvin_storage/ \{([\s\S]*?)\n  \}',text).group(1)
    def test_only_api_origin_decision_is_forwarded(self):
        self.assertIn('Access-Control-Allow-Origin $upstream_http_access_control_allow_origin always;',self.block)
        self.assertNotRegex(self.block,r'Access-Control-Allow-Origin\s+["\']?\*')
    def test_credentials_and_range_headers_survive_internal_redirect(self):
        for header,variable in [('Access-Control-Allow-Credentials','access_control_allow_credentials'),('Access-Control-Expose-Headers','access_control_expose_headers'),('Vary','vary')]:
            self.assertIn(f'{header} $upstream_http_{variable} always;',self.block)
    def test_storage_remains_internal_and_permission_guarded(self):
        self.assertIn('internal;',self.block)
        self.assertIn('X-Novvin-Playback-Cache $upstream_http_x_novvin_playback_cache always;',self.block)
        self.assertIn('etag off;',self.block)

if __name__=='__main__':unittest.main()
