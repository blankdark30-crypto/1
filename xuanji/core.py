"""Xuanji: auditable supervisor / independent agent prototype (stdlib only)."""
from __future__ import annotations
from dataclasses import dataclass, asdict, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Callable
import json
import uuid

def now() -> str:
    return datetime.now(timezone.utc).isoformat()

class Status(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    NEEDS_APPROVAL = "needs_approval"
    COMPLETED = "completed"
    FAILED = "failed"

@dataclass
class Report:
    task_id: str
    agent_id: str
    status: str
    output: str
    evidence: list[str] = field(default_factory=list)
    error: str | None = None
    started_at: str = ""
    ended_at: str = ""
    evaluation: dict = field(default_factory=dict)

@dataclass
class Task:
    id: str
    command: str
    agent_id: str
    status: str = Status.QUEUED.value
    reports: list[Report] = field(default_factory=list)
    created_at: str = field(default_factory=now)

@dataclass
class Agent:
    id: str
    specialty: str
    handler: Callable[[str], tuple[str, list[str]]]
    enabled: bool = True

class Supervisor:
    """Supervisor receives commands, delegates, verifies and records results.

    This prototype runs registered handlers synchronously. Real tool access and
    LLM execution must be added behind independent permission boundaries.
    """
    def __init__(self, audit_path: str = "data/events.jsonl"):
        self.agents: dict[str, Agent] = {}
        self.tasks: dict[str, Task] = {}
        self.audit_path = Path(audit_path)

    def audit(self, kind: str, payload: dict) -> None:
        self.audit_path.parent.mkdir(parents=True, exist_ok=True)
        with self.audit_path.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"at": now(), "type": kind, "payload": payload},
                               ensure_ascii=False) + "\n")

    def register(self, agent: Agent) -> None:
        if agent.id in self.agents:
            raise ValueError("duplicate agent ID")
        self.agents[agent.id] = agent
        self.audit("agent_registered", {"agent_id": agent.id, "specialty": agent.specialty})

    def dispatch(self, command: str, agent_id: str) -> Task:
        if agent_id not in self.agents or not self.agents[agent_id].enabled:
            raise ValueError("agent missing or disabled")
        if not command.strip():
            raise ValueError("empty command")
        task = Task(id=str(uuid.uuid4()), command=command, agent_id=agent_id)
        self.tasks[task.id] = task
        self.audit("task_created", {"id": task.id, "agent_id": agent_id, "command": command})
        if "記憶" in command and any(x in command for x in ("修改", "刪除", "更新", "寫入")):
            task.status = Status.NEEDS_APPROVAL.value
            self.audit("memory_change_blocked", {"task_id": task.id})
            return task
        return self.execute(task.id)

    def execute(self, task_id: str) -> Task:
        task = self.tasks[task_id]
        if task.status == Status.NEEDS_APPROVAL.value:
            raise PermissionError("memory mutation requires separate authenticated approval flow")
        if task.status != Status.QUEUED.value:
            raise ValueError("task not queued")
        task.status = Status.RUNNING.value
        start = now()
        try:
            output, evidence = self.agents[task.agent_id].handler(task.command)
            # A report without evidence is explicitly not considered verified.
            verified = bool(evidence)
            task.status = Status.COMPLETED.value if verified else Status.FAILED.value
            report = Report(task_id=task.id, agent_id=task.agent_id,
                            status=task.status, output=output, evidence=evidence,
                            started_at=start, ended_at=now(),
                            evaluation={"evidence_present": verified, "accepted": verified},
                            error=None if verified else "missing verifiable evidence")
        except Exception as exc:
            task.status = Status.FAILED.value
            report = Report(task_id=task.id, agent_id=task.agent_id,
                            status=task.status, output="", error=str(exc),
                            started_at=start, ended_at=now(),
                            evaluation={"accepted": False})
        task.reports.append(report)
        self.audit("agent_report", asdict(report))
        return task

def demo() -> dict:
    supervisor = Supervisor()
    supervisor.register(Agent("research", "資料整理",
        lambda command: ("已生成示範研究綱要：" + command,
                         ["demo://research-outline"])))
    task = supervisor.dispatch("整理璇璣計畫架構", "research")
    return {"task_id": task.id, "status": task.status,
            "reports": [asdict(x) for x in task.reports]}

if __name__ == "__main__":
    print(json.dumps(demo(), ensure_ascii=False, indent=2))
