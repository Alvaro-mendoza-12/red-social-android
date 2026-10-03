#!/usr/bin/env python3
"""Validate narrowly scoped release archives before extraction or rollback."""
import argparse
import hashlib
import pathlib
import posixpath
import tarfile


def permitted(name, snapshot):
    return (
        name in ("api", "survey-antivirus")
        or name in ("api/Dockerfile", "api/package.json", "api/package-lock.json")
        or name in ("api/src", "api/scripts")
        or name.startswith(("api/src/", "api/scripts/"))
        or (not snapshot and name in (
            "survey-antivirus/Dockerfile", "survey-antivirus/clamd.conf",
            "survey-antivirus/healthcheck.js", "survey-antivirus/start.sh"))
        or (snapshot and name in ("docker-compose.yml", ".conectate-release"))
    )


def members(archive, snapshot, root):
    result = {}
    total = 0
    for member in archive.getmembers():
        name = member.name.rstrip("/") if member.isdir() else member.name
        if (not name or posixpath.normpath(name) != name
                or ".." in name.split("/") or name.startswith("/")
                or "\\" in name or not permitted(name, snapshot)):
            raise ValueError("Unexpected archive path: " + repr(name))
        if name in result or not (member.isfile() or member.isdir()):
            raise ValueError("Duplicate path or nonregular archive member: " + repr(name))
        if member.mode & 0o7000:
            raise ValueError("Special permission bits: " + repr(name))
        total += member.size
        if total > 500 * 1024 * 1024 or len(result) >= 10000:
            raise ValueError("Archive exceeds release bounds")
        target = root / name
        for path in (target, *target.parents):
            if path == root.parent:
                break
            if path.is_symlink():
                raise ValueError("Extraction target contains a symlink: " + repr(name))
        if member.isdir() and target.exists() and not target.is_dir():
            raise ValueError("Directory target is a file: " + repr(name))
        if member.isfile() and target.exists() and not target.is_file():
            raise ValueError("File target is a directory: " + repr(name))
        result[name] = member
    if not result:
        raise ValueError("Empty release archive")
    return result


def digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(65536), b""):
        result.update(block)
    return result.digest()


def run(mode, root, snapshot_path, candidate_path=None):
    root = pathlib.Path(root)
    if root.is_symlink() or root.resolve(strict=True) != root:
        raise ValueError("Root must be an existing canonical directory")
    with tarfile.open(snapshot_path) as archive:
        entries = members(archive, mode not in ("candidate", "unchanged-candidate"), root)
        if mode in ("unchanged", "unchanged-candidate"):
            for name, member in entries.items():
                if not member.isfile():
                    continue
                target = root / name
                if not target.is_file():
                    raise ValueError("Original file disappeared: " + repr(name))
                with target.open("rb") as live, archive.extractfile(member) as saved:
                    if digest(live) != digest(saved):
                        raise ValueError("Original file changed after preparation: " + repr(name))
        elif mode == "remove-added":
            with tarfile.open(candidate_path) as candidate:
                added = members(candidate, False, root)
                # Check every added file before removing any of them.
                removals = []
                for name, member in added.items():
                    target = root / name
                    if not member.isfile() or name in entries or not target.exists():
                        continue
                    with target.open("rb") as live, candidate.extractfile(member) as saved:
                        if digest(live) != digest(saved):
                            raise ValueError("Added file changed; refusing removal: " + repr(name))
                    removals.append(target)
                for target in removals:
                    target.unlink()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("candidate", "snapshot", "unchanged", "unchanged-candidate", "remove-added"))
    parser.add_argument("root")
    parser.add_argument("archive")
    parser.add_argument("candidate", nargs="?")
    args = parser.parse_args()
    if args.mode == "remove-added" and not args.candidate:
        parser.error("remove-added requires the candidate archive")
    run(args.mode, args.root, args.archive, args.candidate)
