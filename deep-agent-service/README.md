# Deep Agent Service (PoC)

Experimental, DOCX-only Python service for the `experiment/deep-agents-migration` branch (see
`DeepAgentOrchestrator` in the Java app). It does not generate documents itself — its one tool
calls back into the Java app's `/internal/agent-tools/create-word` endpoint, which reuses the
existing Node sandbox (`WordGenerator`) and validation (`FileValidator`).

## Setup

```bash
cd deep-agent-service
python3 -m venv .venv
.venv/bin/python -m ensurepip --upgrade   # if this fails: sudo apt-get install -y python3-venv python3-pip
.venv/bin/pip install -r requirements.txt
```

## Run

```bash
ANTHROPIC_API_KEY=<real key>          \
JAVA_APP_BASE_URL=http://localhost:8080 \
INTERNAL_TOKEN=<matches app.agent.deep-agents.internal-token on the Java side> \
PORT=8000                              \
.venv/bin/uvicorn app.main:app --port 8000
```

The Java app must be started with `AGENT_PROVIDER=deep-agents`, `DEEP_AGENTS_BASE_URL=http://localhost:8000`,
and `DEEP_AGENTS_INTERNAL_TOKEN=<same value as INTERNAL_TOKEN above>`.

## Test

```bash
.venv/bin/pip install pytest
.venv/bin/pytest tests/
```

## Scope

DOCX generation only (`run()` on the Java side). No other formats, no Docker packaging, no
benchmarking harness — those are later phases of the migration. See the repo's migration plan
for the full 5-phase roadmap.

## Safety boundaries (Phase 3)

`deepagents.create_deep_agent()` wires in `ls`/`read_file`/`write_file`/`edit_file`/`delete`/
`glob`/`grep`/`execute`/`task` by default, even though this agent only needs
`create_word_document`. `app/agent.py` explicitly excludes all of them via a registered
`HarnessProfile` (see the comment there) and uses the in-memory `StateBackend` (never the host
disk) — the agent genuinely cannot touch the filesystem or run shell commands, not just "won't
by default." Each run is also capped at `RECURSION_LIMIT` turns and `INVOKE_TIMEOUT_SECONDS`
wall-clock time (both in `app/agent.py`), so a stuck or looping generation can't run unbounded.

**No human-approval step.** This PoC runs fully automatically — every `create_word_document`
call the agent makes against the Java endpoint is logged there (job id, script hash,
success/failure; see `AgentToolsController`), but nothing pauses for a person to approve before
a file is written. deepagents supports a real pause/approve flow (`interrupt_on` /
`HumanInTheLoopMiddleware`), deliberately not wired up here since it would turn the synchronous
`POST /run` contract into an async wait/approve/resume flow — a bigger redesign than this phase
scopes. To stop Deep Agents generation entirely, set `app.agent.provider=legacy` on the Java
side (the existing feature flag) or just stop this process.
