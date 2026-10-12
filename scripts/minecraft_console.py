#!/usr/bin/env python3
"""Send one bounded command to the running Minecraft console through Docker attach."""

from __future__ import annotations

import os
import pty
import select
import subprocess
import sys
import time


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: minecraft_console.py <container> <command> <expected-output>", file=sys.stderr)
        return 2
    container, command, expected = sys.argv[1:]
    master, slave = pty.openpty()
    process = subprocess.Popen(
        ["docker", "attach", "--sig-proxy=false", "--detach-keys=ctrl-p,ctrl-q", container],
        stdin=slave,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        close_fds=True,
    )
    os.close(slave)
    output = bytearray()
    detached = False
    try:
        os.write(master, command.encode("utf-8") + b"\n")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                break
            ready, _, _ = select.select([process.stdout], [], [], 0.25)
            if not ready:
                continue
            chunk = os.read(process.stdout.fileno(), 4096)
            if not chunk:
                break
            output.extend(chunk)
            if expected.encode("utf-8") in output:
                os.write(master, b"\x10\x11")
                detached = True
                break
        if not detached:
            os.write(master, b"\x10\x11")
            raise TimeoutError(f"Minecraft did not confirm command {command!r}; output: {output[-1200:].decode(errors='replace')}")
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=3)
        # Docker reports exit 1 when the configured attach-detach sequence is used.
        if process.returncode not in (0, 1, 137):
            raise RuntimeError(f"docker attach exited with {process.returncode}: {output[-1200:].decode(errors='replace')}")
        print(output[-1200:].decode(errors="replace"), end="")
        return 0
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
        os.close(master)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"minecraft console command failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
