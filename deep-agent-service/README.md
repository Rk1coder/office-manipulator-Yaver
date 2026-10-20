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
