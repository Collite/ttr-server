# SPDX-License-Identifier: Apache-2.0
"""Typed failures. Every one of these is a CONTRACT being reported, not a crash.

A paused conversation that cannot be resumed is a normal thing to have to explain to a
user — "that question expired" is an answer; a stack trace is not. So each error below
names the contract it enforces and carries the numbers a caller needs to act on.
"""

from __future__ import annotations


class GolemError(Exception):
    """Base for everything this service raises deliberately."""


class SnapshotNotFound(GolemError):
    def __init__(self, snapshot_id: str):
        self.snapshot_id = snapshot_id
        super().__init__(f"no snapshot {snapshot_id!r} — it was never stored, or it was swept")


class SnapshotExpired(GolemError):
    """The snapshot outlived its TTL.

    Names the TTL contract rather than the elapsed seconds alone: the store's TTL is
    held ≥ the core's resume-token max age on purpose (see `settings.py`), so a caller
    seeing this knows the token is dead too and a retry is pointless.
    """

    def __init__(self, snapshot_id: str, age_s: float, ttl_s: int):
        self.snapshot_id = snapshot_id
        self.age_s = age_s
        self.ttl_s = ttl_s
        super().__init__(
            f"snapshot {snapshot_id!r} expired: age {age_s:.0f}s > TTL {ttl_s}s "
            f"(the core's resume token is no older than this by contract — ask again)"
        )


class IdentitySubjectMismatch(GolemError):
    """A resume presented under a different OBO subject than the one that asked.

    The core would refuse this anyway — the subject is signed into the resume token and
    re-checked (RG-P6 review C). Refusing here as well is defence in depth, and it means
    we do not ship a round trip whose only possible outcome is a rejection.
    """

    def __init__(self, expected: str, presented: str):
        self.expected = expected
        self.presented = presented
        super().__init__(
            "resume presented under a different subject than the one the ask was issued "
            f"to ({presented!r} ≠ {expected!r})"
        )


class UnknownOption(GolemError):
    """A pin naming an option the core never signed.

    Refusing rather than looking it up is why the signed set is STORED: the option ids
    are the core's, and honouring an id we cannot find would mean trusting the caller to
    tell us what the user was offered.
    """

    def __init__(self, option_id: str):
        self.option_id = option_id
        super().__init__(f"option {option_id!r} is not in the set the core signed")


class GateUnavailable(GolemError):
    """A pin arrived with no `resolve.gate:v1` client configured.

    Not a degradation: a pin becomes a binding ONLY by surviving the gate (RV-7). With
    no gate there is no honest way to honour it, and quietly treating the pin as an
    answer would be exactly the fabrication the design forbids.
    """

    def __init__(self) -> None:
        super().__init__(
            "a pin can only become a binding through resolve.gate:v1 (RV-7) and no gate "
            "client is configured"
        )


class CoreResumeUnavailable(GolemError):
    """A period pin arrived and the configured core cannot redeem one.

    A `period` option (ttr-server#231) is a grounding kernel's reading, signed into the core's
    token: only the core's own resume can ground it, and the gate has nothing to gate (the
    option carries no ref). With no resume there is no honest way to honour the pin.
    """

    def __init__(self) -> None:
        super().__init__(
            "a period pin is redeemed by the core's own resume (ttr-server#231) and the "
            "configured core has none"
        )


class PickRefused(GolemError):
    """The core would not ground a signed period pick (`FAILED_PRECONDITION`): the kernel
    refused the id on re-reading the span. Not retryable, and not a re-ask."""

    def __init__(self, option_id: str, detail: str = ""):
        self.option_id = option_id
        super().__init__(
            f"the core refused the pick {option_id!r}" + (f": {detail}" if detail else "")
        )


class ConfigInvalid(GolemError):
    """A configuration that must fail at startup rather than mid-conversation."""
