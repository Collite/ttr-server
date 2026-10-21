# SPDX-License-Identifier: Apache-2.0
"""`ttr-sqlalchemy` — a DB-API driver ([dbapi]) and the `tatrman://` SQLAlchemy dialect ([dialect]) for a Tatrman
Server's Arrow Flight SQL edge."""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__ = version("ttr-sqlalchemy")
except PackageNotFoundError:  # a source tree that was never installed
    __version__ = "0.0.0"
