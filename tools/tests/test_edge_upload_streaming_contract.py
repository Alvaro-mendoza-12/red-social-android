import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
NGINX = ROOT / "infrastructure" / "local-fallback" / "nginx" / "default.conf"
API_SERVER = ROOT / "infrastructure" / "local-fallback" / "api" / "src" / "server.js"


class EdgeUploadStreamingContractTest(unittest.TestCase):
    def _location_block(self, marker: str) -> str:
        source = NGINX.read_text(encoding="utf-8")
        start = source.index(marker)
        next_location = source.find("\n  location ", start + len(marker))
        return source[start : next_location if next_location >= 0 else len(source)]

    def test_every_upload_entrypoint_streams_without_edge_temp_files(self):
        for marker in (
            "location ^~ /storage/v1/object/",
            "location ^~ /storage/v1/upload/resumable",
            "location = /v1/storage/upload",
        ):
            block = self._location_block(marker)
            for required in (
                "proxy_request_buffering off;",
                "proxy_buffering off;",
                "proxy_max_temp_file_size 0;",
                "proxy_socket_keepalive on;",
            ):
                self.assertIn(required, block, f"{marker} missing {required}")

    def test_node_timeouts_cover_mobile_uploads_but_bound_headers(self):
        source = API_SERVER.read_text(encoding="utf-8")
        for required in (
            "server.requestTimeout = 20 * 60_000",
            "server.headersTimeout = 15_000",
            "server.keepAliveTimeout = 65_000",
            "server.maxHeadersCount = 100",
        ):
            self.assertIn(required, source)


if __name__ == "__main__":
    unittest.main()
