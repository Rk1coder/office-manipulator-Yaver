# Deep Agents Migration — Benchmark (Phase 4)

Compares the legacy `ClaudeOrchestrator` against the experimental `DeepAgentOrchestrator`
on the same DOCX requests, calling `run(Job)` directly on both (no plan) — the only
capability `DeepAgentOrchestrator` implements so far (see Phase 2/3). Not an automated
pass/fail gate: it produces a report under `results/` for human review against the
acceptance table below.

**This costs real money** — 5 test cases × 2 implementations = 10 real Anthropic API
calls. Confirm with whoever is running it before starting.

## Setup

1. A disposable Postgres (never the real database), matching `DatabaseIntegrationTest`'s
   convention:
   ```bash
   docker run -d --name agent-benchmark-db -e POSTGRES_DB=office_audit_test \
     -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=office-test-only -p 5558:5432 postgres:16-alpine
   export OFFICE_TEST_DB_URL=jdbc:postgresql://localhost:5558/office_audit_test
   ```
2. A real `ANTHROPIC_API_KEY` exported (not a placeholder — both implementations make
   real LLM calls):
   ```bash
   export ANTHROPIC_API_KEY=<real key>
   ```
3. A higher budget ceiling than the default $20 is usually unnecessary for 10 calls, but
   if you're re-running repeatedly in the same session:
   ```bash
   export BUDGET_LIMIT_USD=20.00
   ```
4. `deep-agent-service` running separately (the benchmark does not start it). The test
   binds a fixed port (18095, see `AgentBenchmarkTest`'s `server.port` property) so this
   can be started before the test:
   ```bash
   cd deep-agent-service
   ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY JAVA_APP_BASE_URL=http://localhost:18095 \
   INTERNAL_TOKEN=<matches DEEP_AGENTS_INTERNAL_TOKEN below> PORT=8000 \
   .venv/bin/uvicorn app.main:app --port 8000
   ```
5. ```bash
   export DEEP_AGENTS_BASE_URL=http://localhost:8000
   export DEEP_AGENTS_INTERNAL_TOKEN=<same value as INTERNAL_TOKEN above>
   ```

## Run

```bash
RUN_AGENT_BENCHMARK=true mvn test -Dtest=AgentBenchmarkTest
```

Writes `results/<timestamp>.json` (raw per-case data) and prints a legacy vs deep-agents
summary (success rate, average duration, total cost) to the console/log.

## Acceptance table (human judgment call, not automated)

Scoped to this PoC's current capability — DOCX generation via `run()` only.

| Test | Kabul kriteri |
|---|---|
| DOCX üretimi | Her örnek için geçerli, açılabilir dosya (`FileValidator` geçmeli) |
| İşlem süresi | Deep Agents ortalaması, legacy ortalamasının en fazla %20 üzerinde |
| Token/maliyet | Artış varsa raporda gerekçelendirilmeli |
| Hata yönetimi | Başarısızlık anlamlı ve sınıflandırılmış olmalı (bkz. `DeepAgentOrchestrator.classifyFailure`) |
| Legacy fallback | `app.agent.provider=legacy` her zaman çalışmalı (Phase 1'de doğrulandı) |

Content quality (paragraph/table/text-length counts in the report) is a signal to read,
not a pass/fail threshold — judge it by actually opening a few of the generated files.

## Scope

DOCX only, `run()` only, no CI integration (real API cost), no automated gate. See the
repo's migration plan for the full 5-phase roadmap.
