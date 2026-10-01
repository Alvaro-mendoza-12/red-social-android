"""Read-only comparison of the deployed API JavaScript source; never reads .env or credentials."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess


def source_hashes(directory):
    return {
        file.relative_to(directory).as_posix(): hashlib.sha256(
            file.read_bytes().replace(b"\r\n", b"\n")
        ).hexdigest()
        for file in sorted(directory.rglob("*.js")) if file.is_file()
    }


def comparison(local, remote, ready):
    different = sorted(name for name, digest in local.items() if remote.get(name) != digest)
    extra = sorted(set(remote) - set(local))
    return {
        "scope": "API src JavaScript only; excludes configuration, schema and other services",
        "ok": bool(local) and ready is True and not different and not extra,
        "sourceFiles": len(local),
        "matched": len(local) - len(different),
        "different": different,
        "extra": extra,
        "ready": ready is True,
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
    parser.add_argument("--ssh-target", required=True, help="SSH user@host; key login must already work")
    parser.add_argument("--identity", required=True, type=pathlib.Path, help="Existing SSH identity file")
    parser.add_argument("--container", default="conectate-local-fallback-api-main")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.:-]*", args.ssh_target):
        parser.error("Use a plain user@host SSH target")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", args.container):
        parser.error("Invalid container name")
    if not args.identity.is_file():
        parser.error("SSH identity file does not exist")
    ssh = shutil.which("ssh")
    if not ssh:
        parser.error("OpenSSH client is required")
    root = pathlib.Path(__file__).resolve().parents[3]
    local = source_hashes(root / "infrastructure/local-fallback/api/src")
    command = [ssh, "-i", str(args.identity.resolve()), "-o", "BatchMode=yes", "-o",
               "ConnectTimeout=6", args.ssh_target, f"docker exec -i {args.container} node"]
    try:
        process = subprocess.run(command, input=REMOTE.encode(), capture_output=True, timeout=25)
        if process.returncode:
            raise RuntimeError("Remote comparison failed")
        data = json.loads(process.stdout)
        report = comparison(local, data["hashes"], data["ready"])
        report["checkedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        report["releaseMetadata"] = {key: data.get("release", {}).get(key)
                                     for key in ["release", "commit", "sourceDigest", "deployedAtUtc", "environment"]}
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired):
        parser.exit(1, "Read-only comparison failed; SSH output and server data withheld.\n")
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
