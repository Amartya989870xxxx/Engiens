"""Runs the hidden checks against the user's workspace and reports each result on its own marked line.

Owned by Engiens. The marker is read from a file that is deleted before any user code is loaded, and the
results go to a duplicate of stdout taken before then, so printing from user code can't fake a result.
"""
import importlib
import json
import os
import signal
import sys
import time
import traceback

CHECK_TIMEOUT_S = 5
HERE = os.path.dirname(os.path.abspath(__file__))
OWN = {"engiens.py", "engiens_run.py", "engiens_checks.py"}


class CheckTimeout(BaseException):
    """A BaseException, so user code's `except Exception` can't swallow the timeout."""


def describe(error):
    """Type, message and the last frame in the user's own files. Check files are never shown."""
    text = f"{type(error).__name__}: {error}".strip()
    user_frames = [f for f in traceback.extract_tb(error.__traceback__)
                   if f.filename.startswith(HERE) and os.path.basename(f.filename) not in OWN]
    if user_frames:
        frame = user_frames[-1]
        text += f" (at {os.path.relpath(frame.filename, HERE)} line {frame.lineno})"
    return text[:1000]


def main():
    nonce_path = os.path.join(HERE, ".engiens_nonce")
    with open(nonce_path, encoding="utf-8") as f:
        marker = "@@ENGIENS:" + f.read().strip() + ":"
    os.remove(nonce_path)
    out = os.fdopen(os.dup(1), "w", encoding="utf-8")

    def emit(**data):
        out.write("\n" + marker + json.dumps(data) + "\n")
        out.flush()

    # 1. Syntax: compile (never run) every user file, so a typo is reported as a compile error with its line.
    for root, dirs, files in os.walk(HERE):
        dirs.sort()
        for name in sorted(files):
            if not name.endswith(".py") or name in OWN:
                continue
            path = os.path.join(root, name)
            rel = os.path.relpath(path, HERE)
            try:
                with open(path, encoding="utf-8") as source:
                    compile(source.read(), rel, "exec")
            except SyntaxError as e:
                emit(kind="summary", outcome="compile_error", message=f"{rel} line {e.lineno}: {e.msg}")
                return

    # 2. Load the checks (which import the user's modules).
    sys.path.insert(0, HERE)
    try:
        import engiens
        importlib.import_module("engiens_checks")
    except BaseException as e:
        emit(kind="summary", outcome="load_error", message=describe(e))
        return

    # 3. Run each check with its own time limit.
    def on_timeout(signum, frame):
        raise CheckTimeout()

    signal.signal(signal.SIGALRM, on_timeout)
    for name, fn in engiens._CHECKS:
        start = time.monotonic()
        passed, message = False, None
        signal.alarm(CHECK_TIMEOUT_S)
        try:
            fn()
            passed = True
        except CheckTimeout:
            message = f"Took longer than {CHECK_TIMEOUT_S} s"
        except AssertionError as e:
            message = (str(e) or "A check assertion failed")[:1000]
        except BaseException as e:  # includes SystemExit raised by user code
            message = describe(e)
        finally:
            signal.alarm(0)
        emit(kind="check", name=name, passed=passed, message=message, ms=int((time.monotonic() - start) * 1000))
    emit(kind="summary", outcome="ran")


if __name__ == "__main__":
    main()
    sys.stdout.flush()
    os._exit(0)  # don't wait for threads user code may have left running
