# 璇璣計畫 XUANJI — Multi-Agent Control Plane (Prototype)

> **Status:** architectural prototype, not a production agent runtime. No real Ollama, PC control, mobile APK, internet tools, or memory writes are connected.

## Three UI regions

1. **最高指令區 / Command Desk:** user submits a command, observes task IDs, approves privileged operations, sees global progress.
2. **主腦區 / J.A.R.V.I.S. Supervisor:** plans, delegates to independent agents, tracks statuses, reviews evidence and outcomes, escalates failures.
3. **員工區 / Independent Agents:** separate identity, specialty, tool capability grants, tasks, reports, proof artifacts, evaluation, error and retries.

```text
USER -> COMMAND DESK -> JARVIS SUPERVISOR
                              |   |   |
                         research dev audit  ... other workers
                              |   |   |
                              +---+---+
                                  |
                          REPORTS + EVIDENCE
                                  |
                         SUPERVISOR VERIFICATION
                                  |
                         USER DASHBOARD / APPROVAL
```

## Included now

- `xuanji/core.py` – no-dependency prototype: named agents, delegated tasks, lifecycle transitions, evidence-dependent acceptance, JSONL audit trail.
- `tests/test_core.py` – basic lifecycle / denial tests.
- Run demonstration from repository root: `python -m xuanji.core`
- Run tests: `python -m unittest discover -s tests -v`

## Critical security boundary

The previously defined rule **「好可以修改」** is a prerequisite for memory changes, **not itself sufficient authorization**. Real implementation must combine explicit contemporaneous approval, authenticated operator/session, purpose-bound one-time request ID, expiration, audit records, and enforcement at the memory write API/storage boundary. Agents must never modify long-term memory directly. The prototype blocks obvious memory-modifying commands but is NOT a secure policy engine and intentionally has NO memory write capability.

`demo://...` is illustrative evidence, not a verified real deliverable. Production acceptance must check physical artifacts or independent test results, provenance, and scoped permissions.

## Implementation roadmap

- **Runtime:** FastAPI gateway + SQL event/task store + queue + orchestration service; independent agent workers with per-agent credentials and tool allowlists. Agents operate with separate sessions, not mere role prompts.
- **LLM:** Ollama local models with tool-calling workers; optional external model/search adapters for fresh information (separate grants and budgets).
- **Evaluation:** artifact validation, tests, independent audit worker, completion rate, retries, elapsed time, tokens/cost and actionable feedback.
- **Interface:** Three-column responsive web UI; Windows desktop wrapper (Tauri/Electron) and Android app share authenticated API/WebSocket updates, not three independent brains.
- **Security:** least privilege, human approval on memory/deletion/spending/system actions, encrypted secret store, allowlisted tools, sandboxed execution, durable logs and rollback.
- **Delivery:** CI tests, local packaging and APK signing after actual build environment is established.

## Agent task envelope (target)

```json
{
  "task_id": "uuid",
  "parent_task_id": null,
  "issuer": "jarvis",
  "agent_id": "developer",
  "goal": "Create backend endpoint",
  "acceptance_criteria": ["integration tests pass", "review approved"],
  "allowed_tools": ["repository-read", "sandbox-exec"],
  "status": "queued",
  "report": {
    "summary": null,
    "artifacts": [],
    "evidence": [],
    "errors": [],
    "quality_score": null
  }
}
```

Do not place private records, API tokens, real health/financial data, or personal memory in this public repository.
