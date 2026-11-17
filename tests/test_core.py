import tempfile
import unittest
from pathlib import Path
from xuanji.core import Agent, Supervisor, Status

class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.supervisor = Supervisor(str(Path(self.tmp.name) / "audit.jsonl"))
    def tearDown(self):
        self.tmp.cleanup()
    def test_success_requires_evidence(self):
        self.supervisor.register(Agent("research", "research", lambda _: ("ok", ["file://artifact"])))
        task = self.supervisor.dispatch("test", "research")
        self.assertEqual(task.status, Status.COMPLETED.value)
        self.assertTrue(task.reports[0].evaluation["accepted"])
    def test_missing_evidence_fails(self):
        self.supervisor.register(Agent("research", "research", lambda _: ("claim only", [])))
        task = self.supervisor.dispatch("test", "research")
        self.assertEqual(task.status, Status.FAILED.value)
    def test_exception_reported(self):
        def broken(_):
            raise RuntimeError("worker down")
        self.supervisor.register(Agent("research", "research", broken))
        task = self.supervisor.dispatch("test", "research")
        self.assertEqual(task.status, Status.FAILED.value)
        self.assertIn("worker down", task.reports[0].error)
    def test_memory_change_requires_approval_and_cannot_execute(self):
        self.supervisor.register(Agent("research", "research", lambda _: ("ok", ["proof"])))
        task = self.supervisor.dispatch("修改記憶", "research")
        self.assertEqual(task.status, Status.NEEDS_APPROVAL.value)
        with self.assertRaises(PermissionError):
            self.supervisor.execute(task.id)
    def test_reject_unregistered_agent(self):
        with self.assertRaises(ValueError):
            self.supervisor.dispatch("test", "unknown")

if __name__ == "__main__":
    unittest.main()
