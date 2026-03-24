Fixes 'unknown option --system-prompt' error when dispatching agents with a non-Claude tool (e.g. GitHub Copilot).

## Problem

`--system-prompt` and `--dangerously-skip-permissions` are Claude Code-specific CLI flags that were unconditionally appended to every agent PTY command in `pty.rs`. When the configured default tool is `copilot`, the spawned `gh copilot suggest` process would fail with an unknown option error.

Additionally, the WebSocket handler (`handler.rs`) hardcoded `"claude-code"` as the fallback tool when radio clients dispatched without specifying a tool, ignoring the configured default.

## Changes

- **pty.rs**: Gate `--system-prompt` and `--dangerously-skip-permissions` behind a `tool_key == "claude-code"` check so non-Claude tools are spawned without Claude-specific flags
- **handler.rs**: Add `default_tool` field to `ConsoleState`; radio dispatch now reads from this field instead of hardcoding `"claude-code"`
- **main.rs**: Set `default_tool` on `ConsoleState` from the config at startup

## Testing

All 34 workspace tests pass.
