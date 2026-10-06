"""Engiens scenario runner: runs Scenario Lab code on a host without Docker (e.g. a Railway service).

The backend sends exactly what it would give its Docker sandbox: a shell command, the files (the user's workspace,
the Engiens runner files and the hidden checks), a time limit and a result marker. This service runs the command in a
fresh directory and returns the same fields the Docker sandbox reports. Parsing results stays in the backend.

Isolation is process-level, not container-level (this runs where containers can't be started):
- each run gets its own directory, deleted afterwards, and its own process group, killed on timeout or exit;
- the command gets an emptied environment (no RUNNER_TOKEN or anything else from this service);
- resource limits: processes, open files, file size and CPU seconds; memory is bounded by the language flags in the
  commands (-Xmx, --max-old-space-size) and by this service's container limit;
- when this service runs as root (in its container), the command runs as the unprivileged user "nobody".
There is no network isolation: see deploy/RAILWAY.md.

Standard library only. Configuration (environment): PORT (8090), RUNNER_TOKEN (required), RUNNER_MAX_CONCURRENT (2).
"""
import hmac
import json
import os
import resource
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_FILES = 64
MAX_FILE_BYTES = 256 * 1024
MAX_TOTAL_BYTES = 1024 * 1024
MAX_TIMEOUT_MS = 120_000
MAX_OUTPUT_LIMIT = 1024 * 1024
MAX_LINE = 64 * 1024
MAX_RESULT_LINES = 500
NOBODY = 65534
SLOT_WAIT_S = 10
# The runner's own PATH (not secret) plus the JDK, so python3, node, javac and java resolve the same way for every run.
PATH = os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin") + (
    ":" + os.path.join(os.environ["JAVA_HOME"], "bin") if os.environ.get("JAVA_HOME") else "")


class InvalidRequest(Exception):
    pass


class Collector(threading.Thread):
    """Drains one output stream: keeps at most `limit` bytes for display and separates marked result lines.

    Same rules as the backend's OutputCollector, so the Docker sandbox and the runner report output identically."""

    def __init__(self, stream, limit, marker):
        super().__init__(daemon=True)
        self.stream, self.limit, self.marker = stream, limit, marker
        self.display = bytearray()
        self.results = []
        self.truncated = False
        self._line = bytearray()
        self._overflow = False
        self._pending_blank = False

    def run(self):
        try:
            while True:
                chunk = self.stream.read1(8192)
                if not chunk:
                    break
                for byte in chunk:
                    if byte == 10:
                        self._end_line()
                    elif len(self._line) < MAX_LINE:
                        self._line.append(byte)
                    else:
                        self._overflow = True
            if self._line:
                self._end_line()
        except (OSError, ValueError):
            pass  # killed or closed: keep what was read

    def _end_line(self):
        text = self._line.decode("utf-8", "replace")
        overflow = self._overflow
        self._line = bytearray()
        self._overflow = False
        if self.marker and text.startswith(self.marker):
            self._pending_blank = False  # the language runner writes "\n" before each result line
            if len(self.results) < MAX_RESULT_LINES and not overflow:
                self.results.append(text[len(self.marker):])
            return
        if text == "":
            if self._pending_blank:
                self._show("\n")
            self._pending_blank = True
            return
        if self._pending_blank:
            self._show("\n")
            self._pending_blank = False
        self._show(text + (" …" if overflow else "") + "\n")

    def _show(self, text):
        data = text.encode("utf-8")
        room = self.limit - len(self.display)
        if len(data) <= room:
            self.display += data
        else:
            if room > 0:
                self.display += data[:room]
            self.truncated = True

    def text(self):
        return self.display.decode("utf-8", "replace").rstrip()


def validate(request):
    if not isinstance(request, dict):
        raise InvalidRequest("the request must be a JSON object")
    command = request.get("command")
    files = request.get("files")
    marker = request.get("resultMarker")
    timeout_ms = request.get("timeoutMs")
    limit = request.get("outputLimitBytes", 16384)
    if not isinstance(command, str) or not command.strip():
        raise InvalidRequest("command is required")
    if not isinstance(files, dict) or not files:
        raise InvalidRequest("files are required")
    if len(files) > MAX_FILES:
        raise InvalidRequest("too many files")
    if not isinstance(marker, str) or len(marker) < 8:
        raise InvalidRequest("resultMarker is required")
    if not isinstance(timeout_ms, int) or not 0 < timeout_ms <= MAX_TIMEOUT_MS:
        raise InvalidRequest("timeoutMs must be between 1 and %d" % MAX_TIMEOUT_MS)
    if not isinstance(limit, int) or not 0 < limit <= MAX_OUTPUT_LIMIT:
        raise InvalidRequest("outputLimitBytes must be between 1 and %d" % MAX_OUTPUT_LIMIT)
    total = 0
    for path, content in files.items():
        if not isinstance(path, str) or not isinstance(content, str):
            raise InvalidRequest("files must map paths to text")
        parts = path.split("/")
        if not path or path.startswith("/") or "\\" in path or "\0" in path or any(p in ("", ".", "..") for p in parts):
            raise InvalidRequest("invalid file path: %r" % path[:200])
        size = len(content.encode("utf-8"))
        if size > MAX_FILE_BYTES:
            raise InvalidRequest("file too large: %s" % path[:200])
        total += size
    if total > MAX_TOTAL_BYTES:
        raise InvalidRequest("files too large in total")
    return command, files, marker, timeout_ms, limit


def limits(cpu_seconds, as_nobody):
    """Runs in the child just before the command: resource limits, then (as root) drop to nobody."""

    def apply():
        resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
        resource.setrlimit(resource.RLIMIT_FSIZE, (64 * 1024 * 1024, 64 * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
        if as_nobody:
            # Process count is per user, so it is only meaningful for the dedicated unprivileged user.
            resource.setrlimit(resource.RLIMIT_NPROC, (256, 256))
            os.setgroups([])
            os.setgid(NOBODY)
            os.setuid(NOBODY)

    return apply


def execute(command, files, marker, timeout_ms, limit):
    as_nobody = os.geteuid() == 0
    run_dir = tempfile.mkdtemp(prefix="engiens-run-")
    start = time.monotonic()
    try:
        work, tmp, home = (os.path.join(run_dir, d) for d in ("work", "tmp", "home"))
        for d in (work, tmp, home):
            os.mkdir(d)
        for path, content in files.items():
            target = os.path.join(work, *path.split("/"))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as f:
                f.write(content)
        if as_nobody:
            for root, dirs, names in os.walk(run_dir):
                for name in [root] + [os.path.join(root, n) for n in dirs + names]:
                    os.chown(name, NOBODY, NOBODY)
        env = {"PATH": PATH, "HOME": home, "TMPDIR": tmp, "LANG": "C.UTF-8"}
        proc = subprocess.Popen(["/bin/sh", "-c", command], cwd=work, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
                                preexec_fn=limits(timeout_ms // 1000 + 5, as_nobody))
        out = Collector(proc.stdout, limit, marker)
        err = Collector(proc.stderr, limit, None)
        out.start()
        err.start()
        timed_out = False
        try:
            proc.wait(timeout=timeout_ms / 1000)
        except subprocess.TimeoutExpired:
            timed_out = True
        # Whether it finished or not, nothing it started may outlive the run.
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        proc.wait()
        out.join(5)
        err.join(5)
        return {
            "exitCode": -1 if timed_out else proc.returncode,
            "timedOut": timed_out,
            "stdout": out.text(),
            "stderr": err.text(),
            "outputTruncated": out.truncated or err.truncated,
            "resultLines": list(out.results),
            "durationMs": int((time.monotonic() - start) * 1000),
        }
    finally:
        shutil.rmtree(run_dir, ignore_errors=True)


def runtime_versions():
    versions = {}
    for name, cmd in (("python", ["python3", "--version"]), ("node", ["node", "--version"]), ("java", ["java", "-version"])):
        try:
            done = subprocess.run(cmd, capture_output=True, text=True, timeout=20, env={"PATH": PATH})
            versions[name] = (done.stdout or done.stderr).strip().splitlines()[0] if done.returncode == 0 else None
        except (OSError, subprocess.TimeoutExpired, IndexError):
            versions[name] = None
    return versions


class Handler(BaseHTTPRequestHandler):
    server_version = "EngiensRunner"
    sys_version = ""

    def log_message(self, fmt, *args):  # one line per request, never request content
        sys.stderr.write("%s %s\n" % (self.command, fmt % args))

    def _reply(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/health":
            return self._reply(404, {"error": "not found"})
        runtimes = self.server.runtimes
        status = "UP" if all(runtimes.values()) else "DEGRADED"
        self._reply(200 if status == "UP" else 503, {"status": status, "runtimes": runtimes})

    def do_POST(self):
        if self.path != "/run":
            return self._reply(404, {"error": "not found"})
        token = self.headers.get("Authorization", "")
        if not hmac.compare_digest(token.encode(), ("Bearer " + self.server.token).encode()):
            return self._reply(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= MAX_BODY_BYTES:
            return self._reply(413 if length > MAX_BODY_BYTES else 400, {"error": "invalid request size"})
        try:
            command, files, marker, timeout_ms, limit = validate(json.loads(self.rfile.read(length)))
        except (InvalidRequest, ValueError) as e:
            return self._reply(400, {"error": str(e)})
        if not self.server.slots.acquire(timeout=SLOT_WAIT_S):
            return self._reply(429, {"error": "runner busy"})
        try:
            result = execute(command, files, marker, timeout_ms, limit)
        except OSError as e:
            return self._reply(503, {"error": "runner could not start the run: %s" % type(e).__name__})
        finally:
            self.server.slots.release()
        sys.stderr.write("run exit=%s timedOut=%s durationMs=%s results=%d\n" % (
            result["exitCode"], result["timedOut"], result["durationMs"], len(result["resultLines"])))
        self._reply(200, result)


class Server(ThreadingHTTPServer):
    """Listens on IPv6 and IPv4 (Railway's private network is reached over IPv6)."""
    daemon_threads = True
    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main():
    token = os.environ.get("RUNNER_TOKEN", "")
    if len(token) < 16:
        sys.exit("RUNNER_TOKEN must be set (at least 16 characters)")
    port = int(os.environ.get("PORT", "8090"))
    server = Server(("::", port), Handler)
    server.token = token
    server.slots = threading.BoundedSemaphore(int(os.environ.get("RUNNER_MAX_CONCURRENT", "2")))
    server.runtimes = runtime_versions()
    sys.stderr.write("Engiens runner listening on port %d; runtimes: %s; running code as %s\n" % (
        port, server.runtimes, "nobody" if os.geteuid() == 0 else "the current user (not root)"))
    server.serve_forever()


if __name__ == "__main__":
    main()
