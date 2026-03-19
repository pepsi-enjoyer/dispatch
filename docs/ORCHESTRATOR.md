# Orchestrator

The orchestrator is a persistent LLM that acts as the central decision-maker in the dispatch system. It receives voice transcripts from the radio, interprets them, and controls agents by issuing tool calls to the console runtime. It runs as a long-lived agent process inside the console, with its output monitored for tool calls and its input fed with tool results and incoming events.

The console is a thin runtime that executes the orchestrator's tool calls and renders the TUI. See CONSOLE.md for the console's responsibilities.

## System Prompt

The orchestrator is initialized with the following system prompt. It establishes the LLM's role, available tools, and behavioral guidelines.

```
You are the Dispatch orchestrator -- a persistent coordinator for a voice-driven
multi-agent coding system. You receive voice transcripts from a push-to-talk radio
and manage AI coding agents working in isolated git worktrees.

Your job is to interpret what the user wants and take action using your tools.
You are the only decision-maker. The console executes your tool calls and reports
results back to you.

## What you control

- Dispatching agents into repositories to work on tasks.
- Planning complex tasks by decomposing them into subtasks.
- Terminating agents that are stuck, misbehaving, or no longer needed.
- Merging completed task branches back into main.
- Sending follow-up messages to running agents.

## How you receive input

Voice transcripts arrive as messages prefixed with [MIC]. These are raw speech-to-text
output that has already been through a command parser on the radio. If the parser
detected a command (dispatch, terminate, switch target), the console handles it
directly -- you only receive prompts that require interpretation and action.

Completion events arrive as messages prefixed with [DONE]. These tell you an agent
has finished its task and is idle. You should decide whether to merge the work and
dispatch the next task.

## Decision guidelines

- For simple, single-task prompts: use `dispatch` directly.
- For complex prompts that need multiple agents: use `plan` to decompose first.
- When an agent completes: use `merge` to integrate the work, then check if
  dependent tasks are now unblocked.
- If an agent seems stuck or the user asks to kill it: use `terminate`.
- Use `list_agents` to check current state before making dispatch decisions.
- Use `message_agent` to provide follow-up instructions or answer agent questions.

## Response format

Think briefly about what action to take, then issue a tool call. Keep your reasoning
concise. Do not ask the user for clarification -- interpret the voice transcript as
best you can and act on it.
```

## Available Tools

The orchestrator controls the dispatch system through seven tools. Tool calls are written as tagged JSON; the console intercepts them, executes the action, and returns a structured result.

### Tool call format

```json
<tool_call>{"name": "dispatch", "input": {"repo": "myrepo", "prompt": "fix the auth bug"}}</tool_call>
```

### Tool result format

```json
<tool_result>
{"type": "dispatched", "slot": 1, "callsign": "Alpha", "task_id": "t1"}
</tool_result>
```

### Tools

**dispatch** -- Create a task, set up a git worktree, and dispatch an agent.

| Parameter | Type | Description |
|-----------|------|-------------|
| `repo` | string | Repository name or path to work in. |
| `prompt` | string | Task description / prompt for the agent. |

Returns: `{ "type": "dispatched", "slot": N, "callsign": "...", "task_id": "..." }`

**terminate** -- Kill a running agent by callsign or slot number. The slot is freed and the task is reopened for reassignment.

| Parameter | Type | Description |
|-----------|------|-------------|
| `agent` | string | Callsign (e.g. "Alpha") or slot number (e.g. "1"). |

Returns: `{ "type": "terminated", "slot": N, "callsign": "..." }`

**merge** -- Merge a completed task's worktree branch into main. On success, the worktree and branch are cleaned up. On conflict, the merge is aborted and the worktree is preserved.

| Parameter | Type | Description |
|-----------|------|-------------|
| `task_id` | string | Task ID to merge (e.g. "t1"). |

Returns: `{ "type": "merged", "task_id": "...", "success": bool, "message": "..." }`

**list_agents** -- Query all agent slots. Returns slot number, callsign, tool, busy/idle/empty status, current task, and repository for each slot.

No parameters.

Returns: `{ "type": "agents", "agents": [...] }`

**list_repos** -- List available repositories that agents can be dispatched into.

No parameters.

Returns: `{ "type": "repos", "repos": [{ "name": "...", "path": "..." }, ...] }`

**plan** -- Spawn a headless planner agent to decompose a complex prompt into subtasks. The planner writes a structured task breakdown to `.dispatch/tasks.md`. When planning completes, the orchestrator is notified and should dispatch agents for unblocked tasks.

| Parameter | Type | Description |
|-----------|------|-------------|
| `repo` | string | Repository name or path. |
| `prompt` | string | Complex task description to decompose. |

Returns: `{ "type": "plan_started", "prompt": "..." }`

The planner is a separate headless agent (no pane, no slot consumed) with its own system prompt:

```
You are the Dispatch task planner. Decompose the following task into a structured plan.

Output ONLY a markdown task list in this exact format (no other text, no code fences):

# Short plan title

- [ ] t1: First task description
- [ ] t2: Second task that depends on t1 -> t1
  - [ ] t2.1: Subtask of t2
  - [ ] t2.2: Another subtask that depends on t2.1 -> t2.1
- [ ] t3: Third task that depends on t1 and t2 -> t1, t2

Rules:
- Use t1, t2, t3 for top-level tasks. Use t1.1, t1.2 for subtasks.
- Add -> id1, id2 when a task depends on other tasks being done first.
- No arrow means the task can start immediately (no blockers).
- Keep each task small: one agent should complete it in one session.
- If the request is simple enough for one agent, output just one task entry.
- Output ONLY the markdown. No explanation, no commentary.

Task to plan:
```

**message_agent** -- Send text to a running agent's terminal (PTY). Use for follow-up instructions, clarifications, or answering agent questions.

| Parameter | Type | Description |
|-----------|------|-------------|
| `agent` | string | Callsign (e.g. "Alpha") or slot number (e.g. "1"). |
| `text` | string | Text to send to the agent's terminal. |

Returns: `{ "type": "message_sent", "agent": "...", "slot": N }`

All tools return `{ "type": "error", "message": "..." }` on failure.

## Receiving Voice Transcripts

Voice transcripts flow through this path before reaching the orchestrator:

```
Android radio
  -> SpeechRecognizer (on-device STT)
  -> Post-processing correction table (code vocabulary normalization)
  -> Command parser (keyword matcher, not LLM)
  -> WebSocket message (JSON, PSK-authenticated)
  -> Console WebSocket server (tokio async thread)
  -> Console forwards to orchestrator
```

The radio's command parser handles structured commands (dispatch, terminate, set_target) before they reach the orchestrator. What the orchestrator receives are prompts that need interpretation:

| Input type | Format | Example |
|---|---|---|
| Voice prompt | `[MIC] <transcript>` | `[MIC] refactor the auth system to use JWT` |
| Task completion | `[DONE] <callsign> <task_id>` | `[DONE] Alpha t1` |
| Plan complete | `[PLAN] <task_count> tasks` | `[PLAN] 5 tasks` |
| Plan failed | `[PLAN] failed` | `[PLAN] failed` |
| Merge conflict | `[CONFLICT] <task_id>` | `[CONFLICT] t1.3` |

## Decision-Making

### Dispatch

When a voice prompt arrives, the orchestrator decides how to handle it:

```
[MIC] prompt arrives
  |
  +-- Complex task (multi-step, broad scope)?
  |     -> call `plan` to decompose into subtasks
  |
  +-- Simple task (single agent can handle)?
  |     -> call `dispatch` to assign directly
  |
  +-- Follow-up for a running agent?
        -> call `message_agent` to relay instructions
```

The orchestrator uses its judgment to classify prompts. There is no hard word-count threshold -- the LLM decides whether a task needs planning based on its complexity.

### Plan

When the orchestrator calls `plan`:

1. The console spawns a headless planner agent (no pane, no slot).
2. The planner writes a task breakdown to `.dispatch/tasks.md` and exits.
3. The console notifies the orchestrator: `[PLAN] N tasks`.
4. The orchestrator should then call `list_agents` to see available slots and `dispatch` agents for unblocked tasks.

If planning fails, the orchestrator receives `[PLAN] failed` and should fall back to dispatching the original prompt as a single task.

### Terminate

The orchestrator terminates agents when:

- The user explicitly asks ("kill Alpha", "shut down Bravo").
- An agent appears stuck (repeatedly completing with no meaningful changes).
- A task needs to be reassigned to a different agent or repo.

After termination, the task is reopened and the worktree is preserved. The orchestrator can dispatch a new agent to pick up where the previous one left off.

### Merge

When the orchestrator receives `[DONE] <callsign> <task_id>`:

1. Call `merge` with the task ID.
2. If the merge succeeds: check if any dependent tasks are now unblocked and dispatch them.
3. If the merge has a conflict: the orchestrator is notified via `[CONFLICT] <task_id>`. The worktree is preserved for manual resolution. The orchestrator should not retry the merge -- it should continue dispatching independent tasks and flag the conflict.

## State Awareness

The orchestrator maintains awareness of the system through its tools and the event stream from the console.

### Repos

Call `list_repos` to discover available repositories. In single-repo mode, there is one repo. In multi-repo mode (console launched from a non-git parent directory), multiple repos are available and the orchestrator must specify which repo when dispatching.

### Running agents

Call `list_agents` to get the current state of all slots. Each entry includes:

- `slot` -- slot number (1-26)
- `callsign` -- NATO phonetic name or custom name
- `tool` -- agent tool (e.g. "claude-code")
- `status` -- "busy", "idle", or "empty"
- `task` -- current task ID, or null
- `repo` -- repository the agent is working in

The orchestrator should call `list_agents` before making dispatch decisions to avoid assigning tasks to busy agents or dispatching when all slots are full.

### Task status

Tasks are tracked in `.dispatch/tasks.md` at the target repo root. The orchestrator does not read this file directly -- it learns about task state through events and tool results:

- `[DONE]` events indicate task completion.
- `[PLAN]` events indicate plan creation.
- `[CONFLICT]` events indicate merge failures.
- `dispatch` results confirm task creation and assignment.
- `merge` results confirm successful integration.

Task format in `.dispatch/tasks.md`:

```
- [ ] t1: Task description                    # open
- [~] t2: Another task | agent: Alpha         # in progress
- [x] t3: Done task                            # complete
- [ ] t4: Blocked task -> t1, t2               # blocked by dependencies
```

## Communication with the Console Runtime

The orchestrator runs as a persistent agent process inside the console. Communication is bidirectional through the agent's PTY:

### Orchestrator -> Console (tool calls)

The orchestrator writes tool calls to its stdout. The console monitors the orchestrator's PTY output, parses tool calls from `<tool_call>` tags (or bare JSON with a `"name"` field), executes them, and writes results back.

```
Orchestrator PTY output:
  I'll dispatch an agent to handle this.
  <tool_call>{"name": "dispatch", "input": {"repo": "myapp", "prompt": "fix the login bug"}}</tool_call>

Console intercepts, executes, writes back:
  <tool_result>
  {"type": "dispatched", "slot": 1, "callsign": "Alpha", "task_id": "t1"}
  </tool_result>
```

### Console -> Orchestrator (events)

The console writes events to the orchestrator's PTY input:

- `[MIC] <transcript>` -- voice transcript from the radio.
- `[DONE] <callsign> <task_id>` -- agent completed its task.
- `[PLAN] <count> tasks` or `[PLAN] failed` -- planner finished.
- `[CONFLICT] <task_id>` -- merge conflict detected.

### Lifecycle

The orchestrator is launched when the console starts and runs for the entire session. It is not terminated between tasks -- it maintains context across all voice commands and agent completions within a session. Its PTY is not displayed in a pane; it runs headlessly like the planner, but persists rather than exiting after one task.

### Event loop

From the orchestrator's perspective, the interaction is a continuous conversation:

```
1. Console writes event to orchestrator PTY (e.g. [MIC] transcript)
2. Orchestrator reasons about what to do
3. Orchestrator writes tool call(s)
4. Console executes, writes tool result back
5. Orchestrator processes result, may issue more tool calls
6. Wait for next event
```

The orchestrator can issue multiple tool calls in sequence (e.g. `list_agents` followed by `dispatch`) within a single turn. It can also issue tool calls proactively when it receives completion events, without waiting for a voice prompt.

## Configuration

The orchestrator's behavior is influenced by console configuration in `config.toml`:

```toml
[tasks]
default_tool = "claude-code"         # Tool for dispatched agents
completion_timeout_secs = 60         # Inactivity timeout before marking agents idle
auto_merge = true                    # Whether to auto-merge on completion

[tools]
claude-code = "claude"               # Shell command for each tool
copilot = "gh copilot suggest"
```

Tool definitions are available as a JSON schema array (compatible with Claude/OpenAI function-calling format) via `tool_definitions()` in `console/src/tools.rs`.
