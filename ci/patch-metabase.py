#!/usr/bin/env python3
"""Register this driver in a Metabase checkout. Run from the checkout root.

Metabase has no hook for out-of-tree drivers, so its deps files have to learn
about ours. Anchored on the vertica entries rather than line numbers, because
a diff against Metabase master goes stale within weeks.

Idempotent: already-patched files are left alone. Missing anchor is an error,
not a silent skip -- a driver that is not registered produces confusing
"could not locate namespace" failures much later.
"""

import sys
from pathlib import Path

EDITS = [
    # Make the driver's source visible as metabase/duckdb.
    (
        "modules/drivers/deps.edn",
        '  metabase/vertica            {:local/root "vertica"}}}',
        '  metabase/vertica            {:local/root "vertica"}\n'
        '  metabase/duckdb             {:local/root "duckdb"}\n'
        "  }}",
    ),
    # Put the driver's tests on the :drivers-dev test paths (first occurrence).
    (
        "deps.edn",
        '    "modules/drivers/vertica/test"]}',
        '    "modules/drivers/vertica/test"\n'
        '    "modules/drivers/duckdb/test"\n'
        "    ]}",
    ),
    # Let clj-kondo resolve :duckdb as a known driver keyword.
    (
        ".clj-kondo/config.edn",
        "              :vertica}}",
        "              :vertica\n              :duckdb}}",
    ),
]


def main() -> int:
    for name, old, new in EDITS:
        path = Path(name)
        if not path.exists():
            print(f"{name}: missing -- are you in the Metabase checkout root?", file=sys.stderr)
            return 1
        text = path.read_text()
        if new in text:
            continue
        if old not in text:
            print(
                f"{name}: anchor not found, Metabase has moved this file.\n"
                f"  looked for: {old!r}",
                file=sys.stderr,
            )
            return 1
        path.write_text(text.replace(old, new, 1))
        print(f"{name}: registered duckdb driver")
    return 0


if __name__ == "__main__":
    sys.exit(main())
