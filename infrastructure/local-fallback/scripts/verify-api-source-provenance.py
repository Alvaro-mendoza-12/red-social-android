"""Read-only comparison of the deployed API JavaScript source; never reads .env or credentials."""
import argparse
import datetime
import hashlib
import io
import json
import pathlib
import re
import shutil
import subprocess
import tarfile


API_SOURCE = "infrastructure/local-fallback/api/src"


def source_digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def source_hashes(directory):
    return {
        file.relative_to(directory).as_posix(): source_digest(file.read_bytes())
        for file in sorted(directory.rglob("*.js")) if file.is_file()
    }


def git_source(root, ref):
    command = ["git", "-C", str(root)]
    revision = subprocess.check_output(
        [*command, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"],
        stderr=subprocess.DEVNULL, timeout=10, text=True,
    ).strip()
    data = subprocess.check_output(
        [*command, "archive", "--format=tar", revision, "--", API_SOURCE],
        stderr=subprocess.DEVNULL, timeout=30,
    )
    records = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for entry in archive:
            if entry.isfile() and entry.name.endswith(".js"):
                name = pathlib.PurePosixPath(entry.name).relative_to(API_SOURCE).as_posix()
                with archive.extractfile(entry) as file:
                    records[name] = source_digest(file.read())
    return revision, records


def comparison(local, remote, ready, release=None, expected_commit=None):
    missing = sorted(set(local) - set(remote))
    changed = sorted(name for name in local.keys() & remote.keys() if local[name] != remote[name])
    different = sorted(missing + changed)
    extra = sorted(set(remote) - set(local))
    metadata = release if isinstance(release, dict) else {}
    marker_matches = (
        metadata.get("commit") == expected_commit
        and bool(re.fullmatch(r"[0-9a-f]{64}", str(metadata.get("sourceDigest", ""))))
        and bool(metadata.get("release"))
    ) if expected_commit else None
    return {
        "scope": "API src JavaScript only; excludes configuration, schema and other services",
        "ok": bool(local) and ready is True and not different and not extra and marker_matches is not False,
        "sourceFiles": len(local),
        "deployedFiles": len(remote),
        "matched": len(local) - len(different),
        "different": different,
        "changed": changed,
        "missing": missing,
        "extra": extra,
        "ready": ready is True,
        "releaseCommitMatches": marker_matches,
    }


REMOTE = r"""
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const base='/usr/src/app/src'; const hashes={};
for(const file of fs.readdirSync(base,{recursive:true})) {
  if(file.endsWith('.js') && fs.statSync(path.join(base,file)).isFile()) {
    hashes[file.replace(/\\/g,'/')]=crypto.createHash('sha256')
      .update(fs.readFileSync(path.join(base,file)).toString('utf8').replace(/\r\n/g,'\n')).digest('hex');
  }
}
(async()=>{
  const response=await fetch('http://127.0.0.1:3001/ready',{signal:AbortSignal.timeout(5000)});
  const ready=await response.json();
  process.stdout.write(JSON.stringify({hashes,ready:response.ok && ready.ok===true,release:ready.release}));
})().catch(()=>{process.stderr.write('SOURCE_PROVENANCE_READ_FAILED');process.exitCode=1});
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-target", required=True, help="SSH user@host with non-interactive access")
    parser.add_argument("--identity", type=pathlib.Path, help="Override the configured SSH identity with an existing file")
    parser.add_argument("--ref", help="Compare the tracked files of a Git commit or ref")
    parser.add_argument("--require-release-commit", action="store_true",
                        help="Fail if the release marker does not name the selected Git commit")
    parser.add_argument("--container", default="conectate-local-fallback-api-main")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.:-]*", args.ssh_target):
        parser.error("Use a plain user@host SSH target")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.container):
        parser.error("Invalid container name")
    if args.identity is not None and not args.identity.is_file():
        parser.error("SSH identity file does not exist")
    if args.require_release_commit and args.ref is None:
        parser.error("--require-release-commit requires --ref")
    ssh = shutil.which("ssh")
    if not ssh:
        parser.error("OpenSSH client is required")
    root = pathlib.Path(__file__).resolve().parents[3]
    identity = ["-i", str(args.identity.resolve())] if args.identity else []
    command = [ssh, *identity, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
               "-o", "ConnectTimeout=6", args.ssh_target, f"docker exec -i {args.container} node"]
    try:
        revision, local = git_source(root, args.ref) if args.ref else (None, source_hashes(root / API_SOURCE))
        process = subprocess.run(command, input=REMOTE.encode(), capture_output=True, timeout=25)
        if process.returncode:
            raise RuntimeError("Remote comparison failed")
        data = json.loads(process.stdout)
        release = data.get("release")
        metadata = release if isinstance(release, dict) else {}
        report = comparison(local, data["hashes"], data["ready"], metadata,
                            revision if args.require_release_commit else None)
        report["checkedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        report["comparisonCommit"] = revision
        report["releaseMetadata"] = {key: metadata.get(key)
                                     for key in ["release", "commit", "sourceDigest", "deployedAtUtc", "environment"]}
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, tarfile.TarError):
        parser.exit(1, "Read-only comparison failed. Check the Git ref, SSH connection and API container.\n")
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
