#!/usr/bin/env python3
"""Verify discovery and an explicitly chosen read-only MCP workflow through phone chat.

Requires a server/tool chosen by the operator. Never retries the remote action.
Final model prose alone cannot pass this test.
"""
import argparse
import json
import time
from pathlib import Path

from chat_api_test import post


def validate_inventory(payload, server_name, tool_name):
    calls = payload.get("toolCalls", [])
    if not any(c.get("tool") == "mcp_inventory" and c.get("success") is True and not c.get("error") for c in calls):
        raise ValueError("No successful app-backed MCP inventory execution")
    if any(c.get("tool") != "mcp_inventory" or c.get("success") is not True for c in calls):
        raise ValueError("Inventory request executed an unexpected or failing tool")
    inventory = payload.get("mcpInventory", {})
    servers = [s for s in inventory.get("servers", []) if s.get("name") == server_name]
    if len(servers) != 1 or servers[0].get("status") != "READY" or servers[0].get("toolCount", 0) <= 0:
        raise ValueError("Requested MCP server is missing, ambiguous, or not ready")
    tools = [t for t in inventory.get("tools", []) if t.get("serverId") == servers[0].get("id") and t.get("name") == tool_name]
    if len(tools) != 1 or not tools[0].get("alias", "").startswith("mcp_"):
        raise ValueError("Requested tool has no unique discovered MCP alias")
    if payload.get("stopReason") != "FINAL_RESPONSE":
        raise ValueError("Inventory conversation did not finish")
    return tools[0]["alias"]


def validate_call(payload, alias, allowed_read_only_aliases=()):
    calls = payload.get("toolCalls", [])
    remote = [c for c in calls if c.get("tool") == alias]
    if len(remote) != 1 or remote[0].get("success") is not True or remote[0].get("error") or remote[0].get("verification") != "VERIFIED":
        raise ValueError("No single verified successful execution of the discovered MCP tool")
    allowed = {alias, "mcp_inventory", *allowed_read_only_aliases}
    if any(c.get("tool") not in allowed or c.get("success") is not True or c.get("error") or
           (c.get("tool") != "mcp_inventory" and c.get("verification") != "VERIFIED") for c in calls):
        raise ValueError("Unexpected or failed additional tool execution")
    if payload.get("stopReason") != "FINAL_RESPONSE":
        raise ValueError("Remote-tool conversation did not finish")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--server", required=True, help="Exact saved display name")
    parser.add_argument("--read-only-tool", required=True, help="Operator-approved read-only tool, e.g. whoami")
    parser.add_argument("--natural-prompt", help="Test ordinary read-only wording instead of naming the tool; execution must still match --read-only-tool")
    parser.add_argument("--allow-read-only-tool", action="append", default=[],
                        help="Additional operator-approved read-only tool allowed in the workflow; repeat as needed. This validates results, not execution permissions.")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--report", default="/tmp/dark-lord-mcp-api-smoke.json")
    args = parser.parse_args()
    report = {"server": args.server, "tool": args.read_only_tool,
              "allowed_read_only_tools": args.allow_read_only_tool, "passed": False, "checks": []}
    started = time.time()
    try:
        status, inventory = post(args.url, "List the configured MCP servers and explain which are enabled.", args.timeout)
        report["checks"].append({"case": "inventory", "status": status, "response": inventory})
        if status != 200:
            raise ValueError("Inventory HTTP request failed")
        alias = validate_inventory(inventory, args.server, args.read_only_tool)
        allowed_aliases = [validate_inventory(inventory, args.server, name) for name in args.allow_read_only_tool]
        print("PASS: app-backed inventory identifies ready server and tool", flush=True)
        prompt = args.natural_prompt or (f"Use MCP server {args.server!r} to call its read-only {args.read_only_tool!r} tool exactly once. "
                  "Do not modify anything, send messages, or call other remote tools. Report whether the call succeeded, "
                  "without including personal account details. Do not claim success without a tool result.")
        status, execution = post(args.url, prompt, args.timeout)
        report["checks"].append({"case": "remote_call", "status": status, "response": execution})
        if status != 200:
            raise ValueError("Remote call HTTP request failed; do not automatically retry")
        validate_call(execution, alias, allowed_aliases)
        report["passed"] = True
        print("PASS: discovered remote tool executed once with verified success", flush=True)
    except Exception as error:
        report["error"] = str(error)
        print(f"FAIL: {error}", flush=True)
    finally:
        report["duration_seconds"] = round(time.time() - started, 2)
        Path(args.report).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Report: {args.report}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
