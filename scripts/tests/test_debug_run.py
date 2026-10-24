# SPDX-License-Identifier: Apache-2.0
"""Mocked HTTP contract checks: no server, cluster, socket or database is started."""

import contextlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib import error

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("debug_run", SCRIPTS / "debug_run.py")
debug = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(debug)
RUN = "9fcc5bb3-53bd-474d-a965-b4f900c2cc3f"
TOKEN = "secret-test-bearer"


def progress(status="evaluated", **kwargs):
    return {
        "runId": RUN,
        "status": status,
        "dryRun": True,
        "verdict": {
            "routes": {"reorder": {"rowCount": 7, "ref": "https://evil.invalid/route"}},
            "none": 2,
        },
        **kwargs,
    }


def protocol():
    sections = {
        name: []
        for name in (
            "automation",
            "islands",
            "decision",
            "actions",
            "writes",
            "people",
            "turns",
            "signals",
            "receipts",
        )
    }
    sections["actions"] = [
        {"payload": {"event": "preview", "would": "Would replenish 7 items\n\x1b[31m"}}
    ]
    return {"schema": "protocol/v1", "run": RUN, "sections": sections}


def reply(data, code=200, headers=None):
    return (
        code,
        headers or {},
        json.dumps(data).encode() if not isinstance(data, bytes) else data,
    )


class Response(io.BytesIO):
    def __init__(self, response):
        code, headers, data = response
        super().__init__(data)
        self.status, self.headers = code, headers


class Wire:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.requests = []

    def open(self, req, timeout):
        self.requests.append(req)
        if not 0 < timeout <= 300:
            raise AssertionError("unbounded request timeout")
        response = next(self.replies)
        if isinstance(response, Exception):
            raise response
        return Response(response)


class DebugRunSpec(unittest.TestCase):
    def invoke(
        self, replies, extra=(), base="https://service.invalid/gateway", token=TOKEN
    ):
        wire = Wire(replies)
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch.dict(os.environ, {"AUTOMATION_BEARER": token}),
            patch.object(debug.request, "build_opener", return_value=wire),
            patch.object(debug.time, "sleep") as sleep,
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            result = debug.main([base, "hartland.recipe/a", "deployment", *extra])
        self.assertNotIn(
            token, stdout.getvalue() + stderr.getvalue()
        ) if token else None
        return result, wire, stdout.getvalue(), stderr.getvalue(), sleep

    def successful(self, *middle):
        return [
            reply({"runId": RUN, "location": "https://evil.invalid/steal"}, 201),
            reply(
                {"runId": RUN, "status": "running"},
                202,
                {"Location": "https://evil.invalid/steal", "Retry-After": "15"},
            ),
            *middle,
            reply(progress()),
            reply(
                {"rows": [{"id": 1, "verdict_reason": "low stock"}], "truncated": True}
            ),
            reply(protocol()),
        ]

    def test_async_walkthrough_preserves_auth_dry_run_raw_artifacts_and_decimal_keys(
        self,
    ):
        with tempfile.TemporaryDirectory() as parent:
            output = Path(parent) / "capture"
            result, wire, stdout, stderr, sleep = self.invoke(
                self.successful(
                    reply(progress("running"), headers={"Retry-After": "2"})
                ),
                [
                    "--output",
                    str(output),
                    "--as-of",
                    "2026-10-23T00:00:00Z",
                    "--port",
                    '{"keys":[{"amount":12345678901234567890.123456789}]}',
                ],
            )
            self.assertEqual((result, stderr), (0, ""))
            sleep.assert_called_once_with(2)
            self.assertEqual(
                [r.method for r in wire.requests],
                ["POST", "POST", "GET", "GET", "GET", "GET"],
            )
            self.assertTrue(
                all(
                    r.full_url.startswith("https://service.invalid/gateway/automation/")
                    for r in wire.requests
                )
            )
            self.assertTrue(
                all(
                    r.get_header("Authorization") == "Bearer " + TOKEN
                    for r in wire.requests
                )
            )
            self.assertIn("hartland.recipe%2Fa/runs", wire.requests[0].full_url)
            opened = wire.requests[0].data.decode()
            self.assertIn("12345678901234567890.123456789", opened)
            payload = json.loads(opened)
            self.assertIs(payload["dryRun"], True)
            self.assertEqual(payload["asOf"], "2026-10-23T00:00:00Z")
            self.assertEqual(payload["deployment"], "deployment")
            self.assertIn('Route "reorder": 7 row(s)', stdout)
            self.assertIn("preview truncated", stdout)
            self.assertIn("Would: ", stdout)
            self.assertNotIn("\x1b", stdout)
            self.assertIn("\\u001b", stdout)
            self.assertEqual(
                json.loads((output / "status.json").read_bytes())["status"], "evaluated"
            )
            self.assertEqual(
                (output / "protocol.json").read_bytes(), json.dumps(protocol()).encode()
            )
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)
            self.assertTrue(
                all(path.stat().st_mode & 0o777 == 0o600 for path in output.iterdir())
            )

    def test_none_and_positional_typed_composite_keys(self):
        for keys, expected in [
            ([], {"none": True}),
            (
                ["17", '"001"', "plain", '{"store":1,"sku":"001"}'],
                {"keys": [17, "001", "plain", {"store": 1, "sku": "001"}]},
            ),
        ]:
            with self.subTest(keys=keys):
                result, wire, *_ = self.invoke(self.successful(), keys)
                self.assertEqual(result, 0)
                self.assertEqual(json.loads(wire.requests[0].data)["port"], expected)

    def test_running_open_is_polled_without_a_second_evaluate(self):
        result, wire, *_ = self.invoke(
            [
                reply({"runId": RUN}, 202),
                reply(progress()),
                reply({"rows": [], "truncated": False}),
                reply(protocol()),
            ]
        )
        self.assertEqual(result, 0)
        self.assertEqual(
            [r.method for r in wire.requests], ["POST", "GET", "GET", "GET"]
        )

    def test_closed_replay_uses_only_durable_readers(self):
        result, wire, *_ = self.invoke(
            [
                reply({"runId": RUN, "replayed": True}),
                reply(progress("closed")),
                reply(protocol()),
            ]
        )
        self.assertEqual(result, 0)
        self.assertEqual(len(wire.requests), 3)
        self.assertTrue(wire.requests[-1].full_url.endswith("/protocol?render=raw"))

    def test_failure_and_waiting_are_not_reported_as_success(self):
        for status in ("failed", "abandoned", "waiting", "unknown"):
            with self.subTest(status=status):
                result, wire, _, stderr, _ = self.invoke(
                    [reply({"runId": RUN}), reply(progress(status))]
                )
                self.assertEqual(result, 1)
                self.assertIn(status, stderr)
                self.assertEqual(len(wire.requests), 2)

    def test_non_dry_or_wrong_run_status_refuses(self):
        for overrides in (
            {"dryRun": False},
            {"dryRun": None},
            {"runId": "another-run"},
        ):
            result, _, _, stderr, _ = self.invoke(
                [reply({"runId": RUN}), reply(progress(**overrides))]
            )
            self.assertEqual(result, 1)
            self.assertIn("requested dry run", stderr)

    def test_invalid_input_refuses_before_any_request(self):
        for extra in (
            ["--port", "[]"],
            ["--port", '{"key":NaN}'],
            ["--port", "{}", "1"],
            ["[]"],
            ["null"],
        ):
            with self.subTest(extra=extra):
                result, wire, *_ = self.invoke([], extra)
                self.assertEqual(result, 1)
                self.assertFalse(wire.requests)
        for base in (
            "https://user:password@service.invalid",
            "https://service.invalid?x=1",
            "file:///tmp/run",
            "https://[broken",
            "https://service.invalid:99999",
            "https://service.invalid\n/path",
        ):
            with self.subTest(base=base):
                result, wire, *_ = self.invoke([], base=base)
                self.assertEqual(result, 1)
                self.assertFalse(wire.requests)
        for token in ("", "bad\ntoken"):
            result, wire, *_ = self.invoke([], token=token)
            self.assertEqual(result, 1)
            self.assertFalse(wire.requests)

    def test_transport_http_and_redirect_failures_are_not_retried_or_leaked(self):
        for failure in (
            error.URLError(TOKEN),
            TimeoutError(TOKEN),
            error.HTTPError(
                "https://other.invalid/" + TOKEN,
                302,
                TOKEN,
                {"Location": "https://other.invalid"},
                io.BytesIO(TOKEN.encode()),
            ),
            error.HTTPError(
                "https://service.invalid", 403, TOKEN, {}, io.BytesIO(TOKEN.encode())
            ),
        ):
            with self.subTest(failure=type(failure).__name__):
                result, wire, _, stderr, _ = self.invoke([failure])
                self.assertEqual(result, 1)
                self.assertEqual(len(wire.requests), 1)
                self.assertNotIn(TOKEN, stderr)
        self.assertIsNone(
            debug.NoRedirect().redirect_request(
                None, None, 307, "", {}, "https://other.invalid"
            )
        )

    def test_invalid_response_and_run_ids_refuse(self):
        for response in (
            reply(b"not json"),
            reply(b"[]"),
            reply({"runId": "not-uuid"}),
            reply({}),
            reply(b'{"runId":NaN}'),
            reply({"runId": RUN.upper()}),
            reply({}, 204),
        ):
            result, wire, *_ = self.invoke([response])
            self.assertEqual(result, 1)
            self.assertEqual(len(wire.requests), 1)

    def test_existing_capture_directory_refuses_before_open_and_keeps_its_contents(
        self,
    ):
        with tempfile.TemporaryDirectory() as parent:
            path = Path(parent) / "keep.json"
            path.write_text("keep")
            result, wire, *_ = self.invoke([], ["--output", parent])
            self.assertEqual(result, 1)
            self.assertFalse(wire.requests)
            self.assertEqual(path.read_text(), "keep")

    def test_final_status_raw_numbers_are_not_reencoded(self):
        raw = (
            json.dumps(progress())
            .replace(
                '"dryRun": true',
                '"dryRun": true, "exact": 12345678901234567890.123456789',
            )
            .encode()
        )
        with tempfile.TemporaryDirectory() as parent:
            output = Path(parent) / "capture"
            result, *_ = self.invoke(
                [
                    reply({"runId": RUN}),
                    reply(raw),
                    reply({"rows": [], "truncated": False}),
                    reply(protocol()),
                ],
                ["--output", str(output)],
            )
            self.assertEqual(result, 0)
            self.assertEqual((output / "status.json").read_bytes(), raw)

    def test_deadline_and_untrusted_retry_hint_are_bounded(self):
        with patch.object(
            debug.time, "monotonic", side_effect=[0, 0, 0, 0, 0, 300, 300]
        ):
            result, wire, _, stderr, sleep = self.invoke(
                [
                    reply({"runId": RUN}),
                    reply(progress("running"), headers={"Retry-After": "999999"}),
                ]
            )
        self.assertEqual(result, 1)
        self.assertIn("deadline exceeded", stderr)
        self.assertEqual(len(wire.requests), 2)
        sleep.assert_not_called()

    def test_invalid_protocol_is_not_reported_as_success(self):
        for invalid in (
            {**protocol(), "run": "other"},
            {**protocol(), "sections": {}},
            {**protocol(), "schema": "different"},
        ):
            result, _, _, stderr, _ = self.invoke(
                [
                    reply({"runId": RUN}),
                    reply(progress()),
                    reply({"rows": []}),
                    reply(invalid),
                ]
            )
            self.assertEqual(result, 1)
            self.assertIn("protocol/v1", stderr)

    def test_alarm_interrupts_blocking_work_and_restores_handler(self):
        previous = debug.signal.getsignal(debug.signal.SIGALRM)
        with (
            self.assertRaisesRegex(debug.DebugError, "deadline exceeded"),
            debug.deadline(300),
        ):
            debug.signal.raise_signal(debug.signal.SIGALRM)
        self.assertIs(debug.signal.getsignal(debug.signal.SIGALRM), previous)
        self.assertEqual(debug.signal.getitimer(debug.signal.ITIMER_REAL), (0, 0))

    def test_oversize_and_invalid_route_payloads_refuse(self):
        result, _, _, stderr, _ = self.invoke([reply(b" " * (8 * 1024 * 1024 + 1))])
        self.assertEqual(result, 1)
        self.assertIn("8 MiB", stderr)
        for page in ({"rows": {}}, {"rows": [1]}, {}):
            result, _, _, stderr, _ = self.invoke(
                [reply({"runId": RUN}), reply(progress()), reply(page)]
            )
            self.assertEqual(result, 1)
            self.assertIn("invalid rows", stderr)

    def test_sync_evaluate_empty_routes_and_invalid_retry_hint(self):
        empty = progress(verdict={"routes": {}, "none": 0})
        document = protocol()
        document["sections"]["actions"] = []
        result, wire, stdout, stderr, sleep = self.invoke(
            [
                reply({"runId": RUN}, 201),
                reply({"verdict": {}}, 200),
                reply(progress("running"), headers={"Retry-After": "tomorrow"}),
                reply(empty),
                reply(document),
            ]
        )
        self.assertEqual((result, stderr), (0, ""))
        sleep.assert_called_once_with(1)
        self.assertNotIn("Would:", stdout)
        self.assertNotIn("/routes/", " ".join(r.full_url for r in wire.requests))

    def test_would_lines_are_bounded_without_truncating_the_saved_protocol(self):
        document = protocol()
        document["sections"]["actions"] *= 12
        with tempfile.TemporaryDirectory() as parent:
            output = Path(parent) / "capture"
            result, _, stdout, *_ = self.invoke(
                [
                    reply({"runId": RUN}),
                    reply(progress("closed")),
                    reply(document),
                ],
                ["--output", str(output)],
            )
            self.assertEqual(result, 0)
            self.assertEqual(stdout.count("Would:"), 10)
            self.assertIn("2 more would-lines", stdout)
            self.assertEqual(
                len(
                    json.loads((output / "protocol.json").read_bytes())["sections"][
                        "actions"
                    ]
                ),
                12,
            )

    def test_shell_entry_point_works_from_another_directory(self):
        result = subprocess.run(
            ["bash", str(SCRIPTS / "debug-run.sh"), "--help"],
            cwd="/tmp",
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("AUTOMATION_BEARER", result.stdout)


if __name__ == "__main__":
    unittest.main()
