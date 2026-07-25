#!/usr/bin/env python3
"""
Demo for AWS Labs MySQL MCP server (stdio transport).

What it does:
1) Spawns the MCP server configured in blackbox_mcp_settings.json
2) Lists the server's advertised tools (capabilities)
3) Invokes a single tool if one looks like a natural-language-to-SQL translator

Notes:
- This script does NOT require database connectivity unless the server tool does.
- It is meant to demonstrate that the server is reachable and that MCP tool discovery works.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from typing import Any, Dict, List, Optional


def _load_blackbox_config(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _choose_tool(tools: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    # MCP tools often come in the form:
    # {"name": "...", "description": "...", ...}
    # or similar. We'll pick the first that matches likely NL->SQL functions.
    patterns = [
        r"nl.*sql",
        r"natural.*sql",
        r"translate.*sql",
        r"query.*sql",
        r"generate.*sql",
        r"question.*sql",
        r"sql.*from.*text",
    ]
    for t in tools:
        name = (t.get("name") or "").lower()
        desc = (t.get("description") or "").lower()
        hay = f"{name} {desc}"
        if any(re.search(p, hay) for p in patterns):
            return t
    return tools[0] if tools else None


def main() -> int:
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    config_path = os.path.join(repo_root, "blackbox_mcp_settings.json")
    cfg = _load_blackbox_config(config_path)

    servers = cfg.get("mcpServers") or {}
    server_key = "github.com/awslabs/mcp/tree/main/src/mysql-mcp-server"
    if server_key not in servers:
        print(f"ERROR: Expected server key not found in {config_path}: {server_key}", file=sys.stderr)
        return 2

    s = servers[server_key]
    command = s["command"]
    args = s.get("args") or []
    env = os.environ.copy()
    env.update(s.get("env") or {})

    full_cmd = [command] + list(args)

    print("Spawning MCP server:")
    print("  " + " ".join(full_cmd))
    print("  env:", {k: env[k] for k in sorted((s.get("env") or {}).keys())})

    # Try to use Python MCP stdio helpers if available.
    # If the exact import path differs by MCP version, we fail with a helpful message.
    try:
        from mcp.client.stdio import stdio_client  # type: ignore
        from mcp.client import ClientSession  # type: ignore
    except Exception as e:
        print(
            "ERROR: Could not import MCP stdio client helpers from the installed 'mcp' package.\n"
            "Run the server once (already done via uv tool run) and ensure MCP client deps are installed.\n"
            f"Import error: {e}\n",
            file=sys.stderr,
        )
        return 3

    proc = subprocess.Popen(
        full_cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        cwd=repo_root,
        text=True,
        bufsize=1,
    )

    try:
        # stdio_client() returns a transport / stream for the MCP client session.
        # We use context manager style if supported.
        # Different MCP versions expose different APIs; keep it robust.
        transport = stdio_client(proc.stdin, proc.stdout)  # type: ignore
        session = ClientSession(transport)  # type: ignore

        with session:
            # Discover tools
            tools_result = session.list_tools()  # type: ignore
            # Some versions return an object, others a dict.
            tools_payload = getattr(tools_result, "tools", None) or getattr(tools_result, "result", None) or tools_result
            tools_list = tools_payload.get("tools") if isinstance(tools_payload, dict) else getattr(tools_payload, "tools", None)
            if tools_list is None and isinstance(tools_payload, list):
                tools_list = tools_payload

            tools_list = tools_list or []
            print(f"\nDiscovered {len(tools_list)} tool(s):")
            for t in tools_list[:50]:
                print(f" - {t.get('name')} : {t.get('description','')}".strip())

            tool = _choose_tool(tools_list)
            if not tool:
                print("\nNo tools advertised; cannot demonstrate capability.", file=sys.stderr)
                return 4

            tool_name = tool["name"]
            print(f"\nSelected tool for demo: {tool_name}")

            # Attempt a generic NL->SQL call. Tool schema may vary; we try a best-effort payload.
            # If the tool requires a different input schema, the call will fail and we'll print the error.
            demo_payload_candidates = [
                {"query": "Show me the top 10 rows from the current database tables."},
                {"natural_language": "Generate SQL for: show top 10 rows from a table."},
                {"text": "Generate MySQL SQL to get the top 10 rows from a table."},
                {"input": "Generate SQL: SELECT * FROM your_table LIMIT 10;"},
            ]

            last_err = None
            for payload in demo_payload_candidates:
                try:
                    print(f"\nInvoking tool '{tool_name}' with payload: {payload}")
                    resp = session.call_tool(tool_name, payload)  # type: ignore
                    print("\nTool invocation response:")
                    print(resp)
                    return 0
                except Exception as call_err:
                    last_err = call_err
                    continue

            print(
                "\nERROR: Could not invoke the selected tool with the guessed payloads.\n"
                "This usually means the tool schema expects different parameters.\n"
                f"Last error: {last_err}\n",
                file=sys.stderr,
            )
            return 5

    finally:
        # Terminate the server process
        try:
            proc.terminate()
        except Exception:
            pass

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
