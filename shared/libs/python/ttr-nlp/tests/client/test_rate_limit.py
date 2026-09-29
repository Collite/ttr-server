# SPDX-License-Identifier: Apache-2.0
"""The remote tier's rate limit refuses a call over the budget; it never waits.

The limiter used to sleep until the oldest call left the one-minute window, up to
60 s, inside the caller's request. In the nlp front that sleep ran on the event
loop, so one throttled NER call stalled every other request, a TOKENIZE-only one
included (measured on a deployment whose Czech NER went to Lindat at 5/min: the
sixth call in a minute took 59 s, and a TOKENIZE-only call sent meanwhile took
57 s).
"""

from __future__ import annotations

import threading
import time

import pytest

from ttrnlp.client import backends
from ttrnlp.client.backends import (
    RATE_LIMITED_CODE,
    BackendClient,
    BackendSpec,
    RateLimited,
    _RateLimiter,
    analyze_nametag,
)


class _Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock(monkeypatch) -> _Clock:
    c = _Clock()
    monkeypatch.setattr(backends.time, "monotonic", c)
    return c


@pytest.fixture
def no_sleep(monkeypatch) -> None:
    """Any sleep at all fails the test: the refusal must be immediate."""

    def refuse(seconds: float) -> None:
        raise AssertionError(f"slept {seconds} s")

    monkeypatch.setattr(backends.time, "sleep", refuse)


def test_a_call_over_the_budget_is_refused_at_once(clock, no_sleep):
    limiter = _RateLimiter(2)
    limiter.acquire("nametag3")
    limiter.acquire("nametag3")

    with pytest.raises(RateLimited) as refused:
        limiter.acquire("nametag3")

    assert str(refused.value).startswith(f"{RATE_LIMITED_CODE}: nametag3: 2/min spent")


def test_the_refusal_says_when_the_next_slot_frees(clock, no_sleep):
    limiter = _RateLimiter(1)
    limiter.acquire("nametag3")
    clock.now += 45

    with pytest.raises(RateLimited, match="next slot in 15 s"):
        limiter.acquire("nametag3")


def test_a_refused_call_does_not_use_up_a_slot(clock, no_sleep):
    limiter = _RateLimiter(1)
    limiter.acquire()
    for _ in range(3):
        with pytest.raises(RateLimited):
            limiter.acquire()

    clock.now += 61
    limiter.acquire()  # the window moved on; the refusals were never counted


def test_no_limit_is_inert(clock, no_sleep):
    limiter = _RateLimiter(0)
    for _ in range(100):
        limiter.acquire()


def test_threads_share_the_budget_exactly(no_sleep):
    """The front calls backends from worker threads now, so the count is locked.

    A smoke test of the count, not a race detector: under the GIL the unlocked
    check-then-append almost never interleaves, so this passes without the lock too.
    """
    limiter = _RateLimiter(5)
    granted: list[bool] = []
    start = threading.Barrier(20)

    def call() -> None:
        start.wait()
        try:
            limiter.acquire()
            granted.append(True)
        except RateLimited:
            granted.append(False)

    threads = [threading.Thread(target=call) for _ in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert granted.count(True) == 5
    assert granted.count(False) == 15


class _Response:
    status_code = 200

    def json(self) -> dict:
        return {"result": "Karlovy\tB-gu\nVary\tI-gu\n"}


class _Client:
    posts = 0

    def __init__(self, **_kw) -> None:
        pass

    def __enter__(self) -> _Client:
        return self

    def __exit__(self, *exc) -> None:
        return None

    def post(self, url, json=None, data=None) -> _Response:
        _Client.posts += 1
        return _Response()


class _Httpx:
    Client = _Client
    TimeoutException = TimeoutError
    NetworkError = ConnectionError


def test_the_engine_reports_the_refusal_and_makes_no_request(
    monkeypatch, clock, no_sleep
):
    monkeypatch.setattr(backends, "_httpx", lambda: _Httpx)
    _Client.posts = 0
    client = BackendClient(
        BackendSpec(
            url="https://lindat.example/recognize",
            model="nametag3-czech-cnec2.0-240830",
            rate_limit_per_minute=1,
        ),
        name="nametag3",
    )

    first = analyze_nametag(client, "Karlovy Vary")
    second = analyze_nametag(client, "Karlovy Vary")

    assert [e.text for e in first.entities] == ["Karlovy Vary"]
    assert second.entities == []
    assert second.error.startswith(f"{RATE_LIMITED_CODE}: nametag3:")
    assert _Client.posts == 1


def test_a_refusal_is_quick_even_with_the_real_clock():
    limiter = _RateLimiter(1)
    limiter.acquire()
    started = time.perf_counter()
    with pytest.raises(RateLimited):
        limiter.acquire()
    assert time.perf_counter() - started < 0.5
