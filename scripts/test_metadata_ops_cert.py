#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline checks for certification refusals: python3 scripts/test_metadata_ops_cert.py."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).with_name("metadata-ops-cert.py")
spec = importlib.util.spec_from_file_location("cert", SCRIPT)
cert = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cert)


class BranchGateCheck(unittest.TestCase):
    def gate(self, http_error="branch_not_allowed", rpc_error="branch_not_allowed", version="accepted"):
        versions = iter(["accepted", version])

        def http(path, code=200, method="GET"):
            if path == "/status":
                return {"model_version": next(versions)}
            self.assertEqual((path, code, method), ("/refresh?branch=preview", 409, "POST"))
            return {"status": "rejected", "errors": [http_error]}

        def rpc(method, request):
            self.assertEqual(method, "Refresh")
            self.assertEqual(request, {"branch": "preview", "force": True})
            return {"status": "rejected", "results": [{"errorMessage": rpc_error}]}

        cert.certify_branch_gate(http, rpc, "accepted")

    def test_exact_branch_policy_refusal(self):
        self.gate()

    def test_http_in_flight_is_not_branch_gate(self):
        with self.assertRaises(AssertionError):
            self.gate(http_error="refresh_in_flight")

    def test_rpc_in_flight_is_not_branch_gate(self):
        with self.assertRaises(AssertionError):
            self.gate(rpc_error="refresh_in_flight")

    def test_rpc_refusal_cannot_change_serving_version(self):
        with self.assertRaises(AssertionError):
            self.gate(version="changed")

    def test_optimized_python_refused_before_preparing_or_creating_resources(self):
        result = subprocess.run([sys.executable, "-O", str(SCRIPT), "--prepare-only"], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("remove -O/PYTHONOPTIMIZE", result.stderr)


if __name__ == "__main__":
    unittest.main()
