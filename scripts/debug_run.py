#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""AU's dry-run walkthrough. Standard library only; HTTP is the sole service boundary."""

import argparse
import http.client
import json
import math
import os
import signal
import sys
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from urllib import error, parse, request


class DebugError(Exception):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never send the bearer to a Location, including an open/evaluate poll hint.
        return None


def strict_json(text):
    def reject_constant(value):
        raise ValueError("non-finite JSON number")

    return json.loads(text, parse_constant=reject_constant)


def port_json(args):
    if args.port is not None:
        if args.keys:
            raise DebugError("use positional keys or --port, not both")
        try:
            port = strict_json(args.port)
        except ValueError:
            raise DebugError("--port must be a JSON object") from None
        if not isinstance(port, dict):
            raise DebugError("--port must be a JSON object")
        return (
            args.port
        )  # Preserve decimal key literals exactly, without a float round trip.
    keys = []
    for key in args.keys:
        try:
            value = strict_json(key)
        except ValueError:
            value = key
            key = json.dumps(key)
        if value is None or isinstance(value, list):
            raise DebugError("keys must be JSON scalars or composite objects")
        keys.append(key)
    return '{"keys":[' + ",".join(keys) + "]}" if keys else '{"none":true}'


def positive_seconds(value):
    try:
        seconds = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be positive seconds") from None
    if not math.isfinite(seconds) or seconds <= 0:
        raise argparse.ArgumentTypeError("must be positive seconds")
    return seconds


def safe(value):
    """Keep authored text on one terminal line, including hostile control characters."""
    return json.dumps(value, ensure_ascii=True)


@contextmanager
def deadline(seconds):
    # This CLI's bash entry point targets Unix (Linux/macOS). SIGALRM bounds blocking
    # connects, reads and polling together, including a peer trickling a response.
    def expired(signum, frame):
        raise DebugError(
            "debug-run deadline exceeded; the server run may still be executing"
        )

    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


class DebugRun:
    def __init__(self, base, token, timeout, output=None):
        try:
            if any(char.isspace() or ord(char) < 32 for char in base):
                raise ValueError()
            url = parse.urlsplit(base)
            port = url.port
        except ValueError:
            raise DebugError("invalid base URL") from None
        if (
            url.scheme not in ("http", "https")
            or not url.hostname
            or url.username is not None
            or url.password is not None
            or url.query
            or url.fragment
            or (port is not None and not 1 <= port <= 65535)
        ):
            raise DebugError(
                "base must be an HTTP(S) service URL without credentials, query or fragment"
            )
        if not token or any(
            char.isspace() or ord(char) < 32 or ord(char) > 126 for char in token
        ):
            raise DebugError("set AUTOMATION_BEARER to a bearer token")
        self.base = base.rstrip("/")
        self.token = token
        self.deadline = time.monotonic() + timeout
        self.output = Path(output) if output else None
        if self.output:
            try:
                self.output.mkdir(mode=0o700, parents=False, exist_ok=False)
            except OSError:
                raise DebugError(
                    "--output must name a new directory under an existing parent"
                ) from None
        self.opener = request.build_opener(NoRedirect())
        self.run = None

    def remaining(self):
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise DebugError(
                "debug-run deadline exceeded; the server run may still be executing"
            )
        return left

    def call(self, method, path, artifact, body=None):
        req = request.Request(
            self.base + path,
            data=body.encode() if body is not None else None,
            method=method,
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with self.opener.open(req, timeout=self.remaining()) as response:
                code, headers = response.status, response.headers
                # Check the walkthrough budget between reads as well as before each request.
                raw = bytearray()
                while True:
                    self.remaining()
                    chunk = response.read1(65536)
                    if not chunk:
                        break
                    raw.extend(chunk)
                    if len(raw) > 8 * 1024 * 1024:
                        raise DebugError("response exceeds the 8 MiB debug limit")
        except error.HTTPError as exc:
            # Do not echo a response body that might repeat credentials or embed terminal escapes.
            code = exc.code
            exc.close()
            raise DebugError(f"{method} {path}: HTTP {code}") from None
        except (error.URLError, TimeoutError, OSError, http.client.HTTPException):
            raise DebugError(
                f"{method} {path}: transport failed; requests are not automatically retried"
            ) from None
        if code not in (200, 201, 202):
            raise DebugError(f"{method} {path}: unexpected HTTP {code}")
        try:
            data = strict_json(raw.decode("utf-8"))
        except (ValueError, UnicodeError):
            raise DebugError(f"{method} {path}: invalid JSON response") from None
        if not isinstance(data, dict):
            raise DebugError(f"{method} {path}: expected a JSON object")
        self.last_raw = bytes(raw)
        if artifact:
            self.save(artifact, raw)
        return code, headers, data

    def save(self, artifact, raw):
        if self.output:
            with (self.output / artifact).open("xb") as target:
                os.chmod(target.name, 0o600)
                target.write(raw)

    def status(self):
        _, headers, state = self.call("GET", f"/automation/runs/{self.run}", None)
        while state.get("status") in ("open", "running"):
            delay = headers.get("Retry-After", "1")
            delay = int(delay) if delay.isascii() and delay.isdigit() else 1
            time.sleep(min(max(1, delay), self.remaining()))
            _, headers, state = self.call("GET", f"/automation/runs/{self.run}", None)
        # Save only the final raw status, without re-encoding decimal values through floats.
        self.save("status.json", self.last_raw)
        if state.get("runId") != self.run or state.get("dryRun") is not True:
            raise DebugError("status does not identify the requested dry run")
        if state.get("status") not in ("evaluated", "closed"):
            raise DebugError("run did not evaluate: " + safe(state.get("status")))
        return state

    def execute(self, args):
        # A unique invocation avoids accidentally opening an older closed run at the same as-of.
        body = json.dumps(
            {
                "deployment": args.deployment,
                "dryRun": True,
                "origin": "debug-run",
                "idempotencyKey": str(uuid.uuid4()),
                **({"asOf": args.as_of} if args.as_of else {}),
            }
        )
        body = body[:-1] + ',"port":' + port_json(args) + "}"
        code, _, opened = self.call(
            "POST",
            "/automation/recipes/" + parse.quote(args.recipe, safe="") + "/runs",
            "open.json",
            body,
        )
        try:
            self.run = str(uuid.UUID(opened["runId"]))
            if opened["runId"] != self.run:
                raise ValueError()
        except (KeyError, ValueError, TypeError, AttributeError):
            raise DebugError("open returned an invalid runId") from None
        print(f"Run: {self.run}", flush=True)
        if code == 201:
            self.call("POST", f"/automation/runs/{self.run}/evaluate", "evaluate.json")
        state = self.status()
        print("Status: " + state["status"] + " (dry run)")
        verdict = state.get("verdict") or {}
        if not isinstance(verdict, dict):
            raise DebugError("status returned an invalid verdict")
        routes = verdict.get("routes", {})
        if not isinstance(routes, dict):
            raise DebugError("status returned invalid routes")
        for index, (name, summary) in enumerate(routes.items()):
            if not isinstance(summary, dict) or not isinstance(name, str):
                raise DebugError("status returned an invalid route")
            count = summary.get("rowCount")
            if type(count) is not int or count < 0:
                raise DebugError("status returned an invalid route count")
            print(f"Route {safe(name)}: {count} row(s)")
            if state["status"] != "closed":
                _, _, page = self.call(
                    "GET",
                    f"/automation/runs/{self.run}/routes/"
                    + parse.quote(name, safe="")
                    + "?limit=1",
                    f"route-{index}.json",
                )
                rows = page.get("rows")
                if not isinstance(rows, list) or any(
                    not isinstance(row, dict) for row in rows
                ):
                    raise DebugError("route returned invalid rows")
                for row in rows:
                    print("  " + safe(row))
                if page.get("truncated"):
                    print("  (preview truncated)")
        print("Unrouted: " + str(verdict.get("none", 0)))
        _, _, protocol = self.call(
            "GET", f"/automation/runs/{self.run}/protocol?render=raw", "protocol.json"
        )
        sections = protocol.get("sections")
        expected = [
            "automation",
            "islands",
            "decision",
            "actions",
            "writes",
            "people",
            "turns",
            "signals",
            "receipts",
        ]
        if (
            protocol.get("schema") != "protocol/v1"
            or protocol.get("run") != self.run
            or not isinstance(sections, dict)
            or list(sections) != expected
            or any(not isinstance(entries, list) for entries in sections.values())
        ):
            raise DebugError("response is not this run's protocol/v1 document")
        actions = sections.get("actions", [])
        would_count = 0
        for entry in actions:
            if not isinstance(entry, dict) or not isinstance(
                entry.get("payload"), dict
            ):
                raise DebugError("protocol returned an invalid action entry")
            payload = entry.get("payload", {})
            if payload.get("event") == "preview" and payload.get("would") is not None:
                would_count += 1
                if would_count <= 10:
                    print("Would: " + safe(payload["would"]))
        if would_count > 10:
            print(f"({would_count - 10} more would-lines in the raw protocol)")
        print(
            f"Protocol: {len(sections)} section(s), {sum(len(v) for v in sections.values())} record(s)"
        )
        print(
            "Closed preview remains available through durable readers."
            if state["status"] == "closed"
            else "Run remains available for inspection; close it explicitly when finished."
        )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Open and inspect an authenticated AU dry run. Requires AUTOMATION_BEARER."
    )
    parser.add_argument("base", help="service base URL, including any gateway prefix")
    parser.add_argument("recipe")
    parser.add_argument("deployment")
    parser.add_argument(
        "keys",
        nargs="*",
        help="JSON scalar/object keys; non-JSON text is a string (default: none port)",
    )
    parser.add_argument(
        "--port", help='explicit JSON port, e.g. {"key":123} or {"event":{...}}'
    )
    parser.add_argument(
        "--as-of", help="UTC instant or now; otherwise the program's as-of policy"
    )
    parser.add_argument(
        "--timeout",
        type=positive_seconds,
        default=300,
        help="whole walkthrough deadline in seconds (default: 300)",
    )
    parser.add_argument(
        "--output",
        help="new private directory for raw response JSON (contains run data)",
    )
    args = parser.parse_intermixed_args(argv)
    try:
        port_json(
            args
        )  # Reject input before creating artifacts or opening a server run.
        with deadline(args.timeout):
            DebugRun(
                args.base,
                os.environ.get("AUTOMATION_BEARER", ""),
                args.timeout,
                args.output,
            ).execute(args)
    except (DebugError, OSError) as exc:
        print("debug-run: " + str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print(
            "debug-run: interrupted; the server run may still be executing",
            file=sys.stderr,
        )
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
