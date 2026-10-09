import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import mineru_probe  # noqa: E402


class _Capture(BaseHTTPRequestHandler):
    seen = []

    def do_PUT(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        _Capture.seen.append((self.path, dict(self.headers), body))
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):  # silence
        pass


class PresignedPutTest(unittest.TestCase):
    def test_put_sends_body_without_content_type(self):
        server = HTTPServer(("127.0.0.1", 0), _Capture)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/api-upload/x.pdf?Expires=1&Signature=abc%2F"
            mineru_probe._put_presigned(url, b"%PDF-1.4 fake")
        finally:
            server.shutdown()
            server.server_close()
        path, headers, body = _Capture.seen[-1]
        self.assertEqual(path, "/api-upload/x.pdf?Expires=1&Signature=abc%2F")
        self.assertEqual(body, b"%PDF-1.4 fake")
        self.assertNotIn("content-type", {k.lower() for k in headers})
        self.assertEqual(headers.get("Content-Length"), "13")

    def test_param_key_and_body(self):
        args = mineru_probe.parse_args.__wrapped__() if hasattr(mineru_probe.parse_args, "__wrapped__") else None
        self.assertIsNone(args)  # parse_args reads sys.argv; param_key is covered through build_body below
        class A:  # minimal namespace
            file = Path("x.pdf"); is_ocr = "true"; enable_formula = "false"; enable_table = "true"; language = "ch"; model_version = "vlm"
        self.assertEqual(mineru_probe.param_key(A), "vlm_ocr-true_formula-false_table-true")
        body = mineru_probe.build_body(A, "kq-1")
        self.assertEqual(body["model_version"], "vlm")
        self.assertFalse(body["enable_formula"])
        self.assertEqual(body["files"][0], {"name": "x.pdf", "is_ocr": True, "data_id": "kq-1"})


if __name__ == "__main__":
    unittest.main()
