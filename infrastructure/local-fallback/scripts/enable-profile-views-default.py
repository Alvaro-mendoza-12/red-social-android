#!/usr/bin/env python3
"""Apply the profile-visit default to a deployed API without replacing its release.

The operator's explicit false preference is stored in profile overrides and is
left untouched. This patch only changes fallbacks for missing preferences.
"""

import argparse
import datetime
import pathlib
import shutil


def replace_exact(path: pathlib.Path, replacements: list[tuple[bytes, bytes, int]], apply: bool):
    source = path.read_bytes()
    updated = source
    for old, new, expected in replacements:
        count = updated.count(old)
        if count == 0 and updated.count(new) == expected:
            continue
        if count != expected:
            raise RuntimeError(f"{path}: expected {expected} matches, found {count}")
        updated = updated.replace(old, new)
    if updated != source and apply:
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup = path.with_name(f"{path.name}.before-profile-view-default-{timestamp}")
        shutil.copy2(path, backup)
        path.write_bytes(updated)
        print(f"updated {path}; backup {backup}")
    else:
        print(f"{'already updated' if updated == source else 'ready to update'} {path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=pathlib.Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    source = args.root / "api" / "src"
    replace_exact(
        source / "data-routes.js",
        [
            (
                b"COALESCE(o.profile->>'profile_view_history_enabled', o.profile->>'profileViewHistoryEnabled', 'false')",
                b"COALESCE(o.profile->>'profile_view_history_enabled', o.profile->>'profileViewHistoryEnabled', 'true')",
                1,
            ),
            (
                b"['profile_view_history_enabled', 'profileViewHistoryEnabled'],\n      false,",
                b"['profile_view_history_enabled', 'profileViewHistoryEnabled'],\n      true,",
                2,
            ),
        ],
        args.apply,
    )
    postgrest_path = source / "postgrest-compat-routes.js"
    line_end = b"\r\n" if b"function sanitizeProfileRow(row, identity) {\r\n" in postgrest_path.read_bytes() else b"\n"
    replace_exact(
        postgrest_path,
        [
            (
                line_end.join([b"function sanitizeProfileRow(row, identity) {", b"  const result = { ...row }", b""]),
                line_end.join([b"function sanitizeProfileRow(row, identity) {", b"  const result = { ...row }", b"  if (result.profile_view_history_enabled == null && result.profileViewHistoryEnabled == null) {", b"    result.profile_view_history_enabled = true", b"  }", b""]),
                1,
            ),
        ],
        args.apply,
    )


if __name__ == "__main__":
    main()
