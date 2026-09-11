import unittest
import json
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from mcp_api_smoke import validate_inventory, validate_call


class McpApiEvidenceTest(unittest.TestCase):
    def test_existing_suite_rejects_model_only_inventory_reply(self):
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps({"reply": "I cannot access MCP servers"}).encode())

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory() as report_dir:
                completed = subprocess.run([sys.executable, str(Path(__file__).with_name("chat_api_test.py")),
                    "--url", f"http://127.0.0.1:{server.server_port}", "--case", "mcp_inventory", "--pause", "0",
                    "--report", str(Path(report_dir) / "report.json")], capture_output=True, text=True, timeout=10)
                self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)

    def inventory(self):
        return {
            "reply": "One server is ready.",
            "stopReason": "FINAL_RESPONSE",
            "toolCalls": [{"tool": "mcp_inventory", "success": True, "error": None}],
            "mcpInventory": {
                "servers": [{"id": "s1", "name": "test", "status": "READY", "toolCount": 1}],
                "tools": [{"serverId": "s1", "serverName": "test", "name": "whoami", "alias": "mcp_abc"}],
            },
        }

    def test_model_claim_without_inventory_evidence_fails(self):
        with self.assertRaises(ValueError):
            validate_inventory({"reply": "MCP works"}, "test", "whoami")

    def test_ready_server_resolves_exact_discovered_alias(self):
        self.assertEqual("mcp_abc", validate_inventory(self.inventory(), "test", "whoami"))

    def test_unreachable_server_fails_even_if_model_claims_ready(self):
        payload = self.inventory()
        payload["mcpInventory"]["servers"][0]["status"] = "UNREACHABLE"
        with self.assertRaises(ValueError):
            validate_inventory(payload, "test", "whoami")

    def test_successful_phone_tool_does_not_prove_remote_call(self):
        with self.assertRaises(ValueError):
            validate_call({"reply": "MCP worked", "toolCalls": [{"tool": "device.battery", "success": True}]}, "mcp_abc")

    def test_failed_remote_call_is_not_a_pass(self):
        with self.assertRaises(ValueError):
            validate_call({"reply": "It worked", "toolCalls": [{"tool": "mcp_abc", "success": False, "error": "NETWORK_ERROR"}]}, "mcp_abc")

    def test_actual_verified_remote_success_passes(self):
        validate_call({"reply": "Done", "stopReason": "FINAL_RESPONSE", "toolCalls": [
            {"tool": "mcp_abc", "success": True, "error": None, "verification": "VERIFIED"}
        ]}, "mcp_abc")

    def workflow(self, extra="mcp_messages", **overrides):
        return {"stopReason": "FINAL_RESPONSE", "toolCalls": [
            {"tool": "mcp_abc", "success": True, "error": None, "verification": "VERIFIED"},
            {"tool": extra, "success": True, "error": None, "verification": "VERIFIED", **overrides},
        ]}

    def test_extra_tool_requires_explicit_allowlist(self):
        with self.assertRaises(ValueError):
            validate_call(self.workflow(), "mcp_abc")

    def test_explicit_read_only_workflow_passes(self):
        validate_call(self.workflow(), "mcp_abc", ["mcp_messages"])

    def test_unexpected_write_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_call(self.workflow("mcp_send"), "mcp_abc", ["mcp_messages"])

    def test_allowed_extra_still_requires_verified_success(self):
        for overrides in ({"success": False}, {"error": "NETWORK_ERROR"}, {"verification": "UNVERIFIED"}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                validate_call(self.workflow(**overrides), "mcp_abc", ["mcp_messages"])


if __name__ == "__main__":
    unittest.main()
