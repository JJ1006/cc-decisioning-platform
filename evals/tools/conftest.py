"""Reuses the fixtures of the MCP server's own tests (settings that point at fake hosts, a recording audit sink, a server built on both)."""

import importlib.util
import pathlib

_path = (
    pathlib.Path(__file__).resolve().parents[2]
    / "mcp/odm-dc-mcp-server/tests/conftest.py"
)
_spec = importlib.util.spec_from_file_location("mcp_server_conftest", _path)
assert _spec and _spec.loader
_m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_m)

RecordingSink = _m.RecordingSink
make_settings = _m.make_settings
call = _m.call
sink = _m.sink
settings = _m.settings
server = _m.server
