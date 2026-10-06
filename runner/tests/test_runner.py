"""Runner service tests: the HTTP contract, request validation, and the run guarantees (limits, timeouts, cleanup).

Run: python3 -m unittest discover -s runner/tests
"""
import json
import os
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import engiens_runner  # noqa: E402

TOKEN = "test-token-0123456789"
MARKER = "@@ENGIENS:abc123:"


class RunnerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        os.environ["RUNNER_TEST_SECRET"] = "must-not-leak"
        cls.server = engiens_runner.Server(("::", 0), engiens_runner.Handler)
        cls.server.token = TOKEN
        cls.server.slots = threading.BoundedSemaphore(2)
        cls.server.runtimes = {"python": "Python 3", "node": "v24", "java": "21"}
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:%d" % cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def post(self, body, token=TOKEN):
        data = json.dumps(body).encode()
        req = urllib.request.Request(self.base + "/run", data=data, method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def run_cmd(self, command, files=None, timeout_ms=10_000, limit=16_384):
        return self.post({"command": command, "files": files or {"a.txt": "x"}, "timeoutMs": timeout_ms,
                          "memoryMb": 256, "outputLimitBytes": limit, "resultMarker": MARKER})

    def test_health_reports_the_runtimes(self):
        with urllib.request.urlopen(self.base + "/health", timeout=10) as r:
            body = json.loads(r.read())
        self.assertEqual(body["status"], "UP")
        self.assertIn("python", body["runtimes"])

    def test_requests_without_the_token_are_refused(self):
        status, body = self.post({"command": "echo hi", "files": {"a": "b"}, "timeoutMs": 1000, "resultMarker": MARKER},
                                 token="wrong")
        self.assertEqual(status, 401)

    def test_invalid_requests_fail_cleanly(self):
        cases = [
            {"files": {"a": "b"}, "timeoutMs": 1000, "resultMarker": MARKER},                       # no command
            {"command": "true", "files": {"../escape": "b"}, "timeoutMs": 1000, "resultMarker": MARKER},
            {"command": "true", "files": {"/etc/passwd": "b"}, "timeoutMs": 1000, "resultMarker": MARKER},
            {"command": "true", "files": {"a": "b"}, "timeoutMs": 999_999, "resultMarker": MARKER},
            {"command": "true", "files": {"a": "x" * (300 * 1024)}, "timeoutMs": 1000, "resultMarker": MARKER},
            {"command": "true", "files": {"a": "b"}, "timeoutMs": 1000},                             # no marker
        ]
        for body in cases:
            status, reply = self.post(body)
            self.assertEqual(status, 400, body)
            self.assertIn("error", reply)

    def test_runs_the_command_in_its_own_directory_and_separates_result_lines(self):
        status, r = self.run_cmd("cat input.txt; printf '\\n" + MARKER + "{\"kind\":\"summary\"}\\n'; echo done", {"input.txt": "hello\n"})
        self.assertEqual(status, 200)
        self.assertEqual(r["exitCode"], 0)
        self.assertFalse(r["timedOut"])
        self.assertEqual(r["resultLines"], ['{"kind":"summary"}'])
        self.assertEqual(r["stdout"], "hello\ndone")

    def test_the_command_sees_no_service_secrets_and_gets_private_temp_dirs(self):
        status, r = self.run_cmd("env; echo \"tmp=$TMPDIR home=$HOME\"; pwd")
        self.assertNotIn("must-not-leak", r["stdout"])
        self.assertNotIn("RUNNER_TOKEN", r["stdout"])
        self.assertRegex(r["stdout"], r"tmp=\S+/engiens-run-\w+/tmp home=\S+/engiens-run-\w+/home")

    def test_the_run_directory_is_deleted_afterwards(self):
        status, r = self.run_cmd("pwd")
        work = r["stdout"].strip().splitlines()[-1]
        self.assertFalse(os.path.exists(os.path.dirname(work)))

    def test_a_run_past_its_time_limit_is_killed_with_everything_it_started(self):
        start = time.monotonic()
        status, r = self.run_cmd("sleep 60 & echo $! > pid; sleep 60", timeout_ms=1000)
        self.assertEqual(status, 200)
        self.assertTrue(r["timedOut"])
        self.assertEqual(r["exitCode"], -1)
        self.assertLess(time.monotonic() - start, 10)

    def test_background_processes_do_not_outlive_a_finished_run(self):
        status, r = self.run_cmd("sleep 60 & echo $!")
        pid = int(r["stdout"].strip())
        time.sleep(0.3)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_output_is_capped(self):
        status, r = self.run_cmd("i=0; while [ $i -lt 2000 ]; do echo 0123456789; i=$((i+1)); done", limit=1000)
        self.assertTrue(r["outputTruncated"])
        self.assertLessEqual(len(r["stdout"].encode()), 1000)

    def test_exit_codes_and_stderr_are_reported(self):
        status, r = self.run_cmd("echo oops >&2; exit 3")
        self.assertEqual(r["exitCode"], 3)
        self.assertEqual(r["stderr"], "oops")


if __name__ == "__main__":
    unittest.main()
