#!/usr/bin/env python3
"""Calculate KinPilot's next base-10 semantic release and Android version code."""

import re
import sys


def next_version(latest: str) -> tuple[str, str, int]:
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", latest)
    if not match:
        raise ValueError(f"invalid release tag: {latest}")
    major, minor, patch = map(int, match.groups())
    if patch < 10:
        patch += 1
    else:
        patch = 0
        if minor < 10:
            minor += 1
        else:
            minor = 0
            major += 1
    name = f"{major}.{minor}.{patch}"
    # Leave two decimal places for minor and patch so Android upgrades remain monotonic.
    code = major * 10_000 + minor * 100 + patch
    return f"v{name}", name, code


if __name__ == "__main__":
    tag, name, code = next_version(sys.argv[1] if len(sys.argv) > 1 else "v0.0.0")
    print(tag, name, code)
