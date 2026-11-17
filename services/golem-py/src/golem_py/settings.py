# SPDX-License-Identifier: Apache-2.0
"""Service settings, and the ONE inequality that must hold before the service starts.

    snapshot_ttl_s  ≥  core_token_max_age_s

⚑ P4.2·T3 names this "the likely first production bug", in advance, because the two
numbers live in different processes and neither side can see the other's. The core's
`resolver.resume-token.max-age-seconds` bounds how long its signed option set may be
replayed (default **3600 s**, and a MISSING key falls back to that default rather than
meaning "eternal" — RG-P6 review K). Our snapshot TTL bounds how long we keep the state
that token belongs to.

If ours is SHORTER, a user answering inside the window the core still honours gets
"that question expired" from us — a self-inflicted failure that looks like a core bug
and reproduces only under load, when snapshots are swept sooner. So the inequality is
validated at STARTUP and a violating config refuses to boot, which is the same posture
the resolver takes about an empty key set.

The value is deliberately NOT inferred from the core: asking it would make the check a
round trip that can fail, and a config that disagrees with reality is exactly what
should stop a deployment.
"""

from __future__ import annotations

import os

from pydantic import BaseModel, ConfigDict, SecretStr, model_validator

from golem_py.errors import ConfigInvalid

# The resolver's own default (`DEFAULT_RESUME_MAX_AGE_SECONDS`, Application.kt).
DEFAULT_CORE_TOKEN_MAX_AGE_S = 3600


class GolemSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    core_target: str = "localhost:7276"
    # What the CORE will still honour. Mirror of its config, not a wish.
    core_token_max_age_s: int = DEFAULT_CORE_TOKEN_MAX_AGE_S
    # What WE will still hold. Must cover the core's window.
    snapshot_ttl_s: int = DEFAULT_CORE_TOKEN_MAX_AGE_S
    snapshot_dir: str = ""  # empty ⇒ the in-memory store (tests, single-shot CLI)
    profile: str = "CHAT_QUICK"

    # ── OL W1c: the LLM lane's three services (OL-C11…C14). An empty target is "not
    # configured", and the lane is on only when the switch is on AND a gateway AND a door
    # are configured (OL-C11) — so a deployment that sets none of these answers exactly as
    # it did before W1c. The model slice reads `meta.v1`.
    query_target: str = ""  # GOLEM_QUERY_TARGET — `query.v1` (the door)
    query_world: str = ""  # GOLEM_QUERY_WORLD — the world every lane query runs in
    query_timeout_s: float = 30.0  # GOLEM_QUERY_TIMEOUT_S
    llm_lane_enabled: bool = True  # GOLEM_LLM_LANE_ENABLED (OL-C11: default on)
    # GOLEM_LLM_TARGET — the open gateway's HTTP base URL, ending in `/v1` (OL-C13, D-43)
    llm_target: str = ""
    # GOLEM_LLM_API_KEY — the team's virtual key, injected from a Secret. A `SecretStr`, so
    # neither `repr`, a validation error nor a log line ever shows it.
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = ""  # GOLEM_LLM_MODEL — empty: the gateway picks within the class
    # GOLEM_LLM_MODEL_CLASS — the gateway tier sent as `model_tags` (OL-C13: `smart`; the
    # catalog has no `capable` tag, S3a Found 1). The LADDER rung is still named `capable`.
    llm_model_class: str = "smart"
    llm_model_slice_entities: int = 40  # GOLEM_LLM_MODEL_SLICE_ENTITIES (OL-C14)
    meta_target: str = ""  # GOLEM_META_TARGET — `meta.v1`
    meta_packages: list[str] = []  # GOLEM_META_PACKAGES — comma-separated
    meta_timeout_s: float = 10.0  # GOLEM_META_TIMEOUT_S

    # GOLEM_LEXICON_ARCHIVE_PATHS — the compiled lexicon archives the operator library is read
    # from, estate first (`os.pathsep`-separated, the `*_LEXICON_ARCHIVE_PATH` mount). Empty:
    # no library, and a question naming an operator refuses NO_CAPABLE_PLUGIN, as before.
    lexicon_archive_paths: list[str] = []

    @model_validator(mode="after")
    def _ttl_covers_the_token(self) -> GolemSettings:
        if self.snapshot_ttl_s < self.core_token_max_age_s:
            raise ValueError(
                f"snapshot_ttl_s ({self.snapshot_ttl_s}s) is shorter than the core's "
                f"resume-token max age ({self.core_token_max_age_s}s): a user answering "
                "inside the window the core still honours would be told their question "
                "expired. Raise the snapshot TTL, or lower the core's max-age to match."
            )
        if self.snapshot_ttl_s <= 0 or self.core_token_max_age_s <= 0:
            raise ValueError("TTLs must be positive")
        return self

    @model_validator(mode="after")
    def _lane_numbers_positive(self) -> GolemSettings:
        if self.llm_model_slice_entities <= 0:
            raise ValueError(
                f"llm_model_slice_entities must be positive, got {self.llm_model_slice_entities}"
            )
        if self.query_timeout_s <= 0 or self.meta_timeout_s <= 0:
            raise ValueError("lane timeouts must be positive")
        return self

    @classmethod
    def from_env(cls) -> GolemSettings:
        """`GOLEM_*` env vars, validated. Raises `ConfigInvalid` at STARTUP."""

        def _int(name: str, default: int) -> int:
            raw = os.getenv(name)
            return default if raw is None or raw == "" else int(raw)

        def _float(name: str, default: float) -> float:
            raw = os.getenv(name)
            return default if raw is None or raw == "" else float(raw)

        def _bool(name: str, default: bool) -> bool:
            raw = (os.getenv(name) or "").strip().lower()
            if raw == "":
                return default
            if raw in ("1", "true", "yes", "on"):
                return True
            if raw in ("0", "false", "no", "off"):
                return False
            raise ValueError(f"{name} must be true or false, got {raw!r}")

        def _list(name: str, sep: str = ",") -> list[str]:
            return [part.strip() for part in (os.getenv(name) or "").split(sep) if part.strip()]

        try:
            return cls(
                core_target=os.getenv("GOLEM_CORE_TARGET") or "localhost:7276",
                core_token_max_age_s=_int(
                    "GOLEM_CORE_TOKEN_MAX_AGE_S", DEFAULT_CORE_TOKEN_MAX_AGE_S
                ),
                snapshot_ttl_s=_int("GOLEM_SNAPSHOT_TTL_S", DEFAULT_CORE_TOKEN_MAX_AGE_S),
                snapshot_dir=os.getenv("GOLEM_SNAPSHOT_DIR") or "",
                profile=os.getenv("GOLEM_PROFILE") or "CHAT_QUICK",
                query_target=os.getenv("GOLEM_QUERY_TARGET") or "",
                query_world=os.getenv("GOLEM_QUERY_WORLD") or "",
                query_timeout_s=_float("GOLEM_QUERY_TIMEOUT_S", 30.0),
                llm_lane_enabled=_bool("GOLEM_LLM_LANE_ENABLED", True),
                llm_target=os.getenv("GOLEM_LLM_TARGET") or "",
                llm_api_key=SecretStr((os.getenv("GOLEM_LLM_API_KEY") or "").strip()),
                llm_model=os.getenv("GOLEM_LLM_MODEL") or "",
                llm_model_class=os.getenv("GOLEM_LLM_MODEL_CLASS") or "smart",
                llm_model_slice_entities=_int("GOLEM_LLM_MODEL_SLICE_ENTITIES", 40),
                meta_target=os.getenv("GOLEM_META_TARGET") or "",
                meta_packages=_list("GOLEM_META_PACKAGES"),
                meta_timeout_s=_float("GOLEM_META_TIMEOUT_S", 10.0),
                lexicon_archive_paths=_list("GOLEM_LEXICON_ARCHIVE_PATHS", os.pathsep),
            )
        except ValueError as exc:
            raise ConfigInvalid(str(exc)) from exc
