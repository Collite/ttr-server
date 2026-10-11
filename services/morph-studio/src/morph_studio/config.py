# SPDX-License-Identifier: Apache-2.0
"""Settings, from the deployment environment (NLS-P9.2 T1).

**One instance per world** (LM-5/S-4). `MORPH_WORLD` is required and has no
default: the queue, the overlay and the export are all that world's, a studio
that guessed its own identity would write one world's vocabulary into another's
file, and there is no repair for that after the fact. So it refuses to boot
rather than assume.

Everything is env-keyed rather than a config file, unlike `services/nlp`. The
difference is real: nlp's config is a *routing table* — engines, lanes, pipeline
definitions, pack sources — which is authored, reviewed and diffed. This service
has connection strings, a world id and three switches, all of which are
deployment facts that belong in a chart's values and a Secret.
"""

from __future__ import annotations

import os
import re
from collections.abc import Mapping

from pydantic import BaseModel, ConfigDict

#: A world id is a filename component (the overlay), a database value and a URL
#: query parameter. Same rule the front applies to `morph.worlds` keys.
_WORLD_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")

ENV_WORLD = "MORPH_WORLD"
ENV_DB_URL = "MORPH_STUDIO_DB_URL"
ENV_HOST = "MORPH_STUDIO_HOST"
ENV_PORT = "MORPH_STUDIO_PORT"
ENV_MODE = "MORPH_STUDIO_MODE"
ENV_EXPORT_DIR = "MORPH_STUDIO_EXPORT_DIR"
ENV_OVERLAY_DIR = "MORPH_STUDIO_OVERLAY_DIR"
ENV_FRONT_TARGET = "MORPH_STUDIO_FRONT_TARGET"
ENV_PROVISIONAL = "MORPH_STUDIO_PROVISIONAL"
ENV_VOCABULARY = "MORPH_STUDIO_VOCABULARY"
ENV_STATIC_DIR = "MORPH_STUDIO_STATIC_DIR"

#: The two lanes of contracts §7. `studio` is the ordinary one: analysts work
#: the queue in the UI and `POST /export` emits the world's layer file. `dfp` is
#: the LM-12 ruling: that world's analysts edit files in their model repository, so the
#: queue emits *proposed-entry fragments* for that lane and the authoring UI is
#: not the way in. The difference is which export endpoint is meaningful, not a
#: different data model.
MODE_STUDIO = "studio"
MODE_DFP = "dfp"

DEFAULT_PORT = 7290


class ConfigError(ValueError):
    """The service cannot serve as configured. Boot refuses."""


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid")

    #: LM-5: this instance's world. Required.
    world: str
    #: SQLAlchemy URL. `sqlite://` is legitimate for a laptop and for the test
    #: suite; a deployment passes the `postgresql+psycopg://` URL from a Secret.
    db_url: str = "sqlite+pysqlite:///:memory:"
    host: str = "0.0.0.0"
    port: int = DEFAULT_PORT
    mode: str = MODE_STUDIO
    #: Where `POST /export` writes layer files.
    export_dir: str = "/var/lib/morph-studio/export"
    #: Where the Q-7 provisional overlay is written — a directory the FRONT
    #: also mounts, which is the entire mechanism (T7).
    overlay_dir: str = ""
    #: The front's gRPC target (`host:port`), for `ReloadPacks` after an
    #: overlay changes. Empty ⇒ the overlay is written and nothing is told
    #: about it, which is right for a deployment whose front reloads on a timer
    #: and for every test.
    front_target: str = ""
    #: Q-7, per world. On by default *for the world entity layer only*, which is
    #: the whole of the narrow ruling: a core entry never goes live unverified.
    provisional: bool = True
    #: The world's model vocabulary for LM-10 routing — a comma-separated list,
    #: or empty. Small on purpose: this is a routing prior, not a lexicon.
    vocabulary: tuple[str, ...] = ()
    #: The built FI-7 frontend (`frontend/dist`), served by this service —
    #: ONE deployable, not a second nginx (NLS-P9.3 T1). Empty ⇒ the API alone,
    #: which is what the test suite, the bootstrap lane and `dfp` mode want:
    #: the DFP world has no authoring UI by ruling (LM-12), so a studio with no
    #: frontend directory is a supported deployment, not a broken one.
    static_dir: str = ""

    @property
    def dfp(self) -> bool:
        return self.mode == MODE_DFP

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        env = os.environ if env is None else env

        world = (env.get(ENV_WORLD) or "").strip()
        if not world:
            raise ConfigError(
                f"{ENV_WORLD} is not set. A morph-studio instance serves exactly "
                "one world (LM-5): its queue, its overlay and its export are that "
                "world's, and an instance that guessed would write one world's "
                "vocabulary into another's file"
            )
        if not _WORLD_ID.match(world):
            raise ConfigError(
                f"{ENV_WORLD}={world!r} is not a usable id — it becomes a "
                "filename, a database value and a URL parameter"
            )

        mode = (env.get(ENV_MODE) or MODE_STUDIO).strip().lower()
        if mode not in (MODE_STUDIO, MODE_DFP):
            raise ConfigError(
                f"{ENV_MODE}={mode!r} — the lanes are {MODE_STUDIO!r} and "
                f"{MODE_DFP!r} (contracts §7, LM-12)"
            )

        port = (env.get(ENV_PORT) or "").strip()
        vocabulary = tuple(
            term.strip()
            for term in (env.get(ENV_VOCABULARY) or "").split(",")
            if term.strip()
        )

        return cls(
            world=world,
            db_url=(env.get(ENV_DB_URL) or "").strip()
            or cls.model_fields["db_url"].default,
            host=(env.get(ENV_HOST) or "").strip() or "0.0.0.0",
            port=int(port) if port.isdigit() else DEFAULT_PORT,
            mode=mode,
            export_dir=(env.get(ENV_EXPORT_DIR) or "").strip()
            or cls.model_fields["export_dir"].default,
            overlay_dir=(env.get(ENV_OVERLAY_DIR) or "").strip(),
            front_target=(env.get(ENV_FRONT_TARGET) or "").strip(),
            provisional=_flag(env.get(ENV_PROVISIONAL), default=True),
            vocabulary=vocabulary,
            static_dir=(env.get(ENV_STATIC_DIR) or "").strip(),
        )


def _flag(raw: str | None, *, default: bool) -> bool:
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


__all__ = [
    "DEFAULT_PORT",
    "ENV_DB_URL",
    "ENV_EXPORT_DIR",
    "ENV_FRONT_TARGET",
    "ENV_MODE",
    "ENV_OVERLAY_DIR",
    "ENV_PROVISIONAL",
    "ENV_STATIC_DIR",
    "ENV_VOCABULARY",
    "ENV_WORLD",
    "MODE_DFP",
    "MODE_STUDIO",
    "ConfigError",
    "Settings",
]
