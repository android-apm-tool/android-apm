"""Linux/WSL2 runner. Customer inputs are mounted only after a synthetic isolation probe.

The OS namespace is the enforcement boundary; strace is an independent, host-side
observer. Neither Gradle --offline nor worker-written status is a security claim.
No dependency installation, network fallback, SaaS controller or output upload.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid
from runner_seccomp import program as seccomp_program
from runner_bundle import verify as verify_bundle

POLICY_VERSION = "linux-bwrap-v2"
FORBIDDEN_ENV = ("GITHUB_ACTIONS", "GITLAB_CI", "TF_BUILD", "JENKINS_URL", "BUILDKITE", "TEAMCITY_VERSION")
SYNC_NAMES = {"onedrive", "dropbox", "google drive", "googledrive", "icloud drive"}


class Refused(RuntimeError):
    pass


def checked_path(value, private=False):
    path = Path(value).absolute()
    for component in (path, *path.parents):
        if component.is_symlink():
            raise Refused("STORAGE_BOUNDARY_UNVERIFIED")
    if any(any(part.lower() == name or part.lower().startswith(name + " -") for name in SYNC_NAMES) for part in path.parts):
        raise Refused("STORAGE_BOUNDARY_UNVERIFIED")
    if not path.is_dir():
        raise Refused("STORAGE_BOUNDARY_UNVERIFIED")
    if private:
        info = path.stat()
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise Refused("STORAGE_BOUNDARY_UNVERIFIED")
        fs = subprocess.run(["/usr/bin/findmnt", "-n", "-o", "FSTYPE", "-T", str(path)], capture_output=True, text=True, check=True).stdout.strip()
        if fs not in {"ext4", "xfs", "btrfs", "tmpfs", "overlay"}:
            raise Refused("STORAGE_BOUNDARY_UNVERIFIED")
    return path


def runtime_args():
    # /usr is administrator-controlled tool preparation, never a customer mount.
    args = ["--ro-bind", "/usr", "/usr"]
    for name in ("bin", "sbin", "lib", "lib64"):
        path = Path("/" + name)
        if path.is_symlink():
            args += ["--symlink", os.readlink(path), str(path)]
        elif path.exists():
            args += ["--ro-bind", str(path), str(path)]
    return args


def boundary(mounts=()):
    java_configs = []
    for path in ("/etc/java-17-openjdk", "/etc/java-21-openjdk", "/etc/ssl/certs/java"):
        if Path(path).is_dir():
            java_configs += ["--ro-bind", path, path]
    return ["/usr/bin/bwrap", "--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc", "--unshare-uts",
        "--die-with-parent", "--new-session", "--hostname", "apm-worker", "--cap-drop", "ALL", *runtime_args(),
        "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/home", "--dir", "/home/worker",
        "--dir", "/etc", *java_configs, "--clearenv", "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "HOME", "/home/worker",
        "--setenv", "LANG", "C.UTF-8", "--setenv", "TZ", "UTC", *mounts]


def forbidden_network(trace):
    """Only the namespace's loopback is allowed. Do not retain payloads in projection."""
    attempts = []
    for line in trace.splitlines():
        if not re.search(r"\b(connect|sendto|sendmsg|sendmmsg)\(", line):
            continue
        addresses = re.findall(r'inet_addr\("([^"\n]+)"\)|inet_pton\(AF_INET6, "([^"\n]+)"', line)
        for fields in addresses:
            address = next(x for x in fields if x)
            try:
                ip = ipaddress.ip_address(address)
                local = ip.is_loopback or isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None and ip.ipv4_mapped.is_loopback
            except ValueError:
                local = False
            if not local:
                # An unexpected successful syscall invalidates the backend, not just the run.
                blocked = bool(re.search(r"= -1 (ENETUNREACH|EHOSTUNREACH|EACCES|EPERM|EADDRNOTAVAIL|EAFNOSUPPORT)", line))
                attempts.append({"family": "IPv6" if ":" in address else "IPv4", "blocked": blocked})
    return attempts


def traced(command, directory, timeout, cancel=None):
    if cancel is not None and cancel.exists():
        return {"status": "CANCELLED", "exitCode": None, "blockedAttempts": 0,
            "auditDigest": hashlib.sha256(b"").hexdigest(), "workerStarted": False}
    trace = directory / "network.trace"
    filter_file = directory / "seccomp.bpf"
    filter_file.write_bytes(seccomp_program())
    with (directory / "worker.log").open("wb") as log, filter_file.open("rb") as seccomp:
        command = [command[0], "--seccomp", str(seccomp.fileno()), *command[1:]]
        process = subprocess.Popen(["/usr/bin/strace", "-f", "-qq", "-s", "256", "-e", "trace=network,process", "-o", str(trace), *command],
            stdin=subprocess.DEVNULL, stdout=log, stderr=log, env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}, start_new_session=True, close_fds=True, pass_fds=(seccomp.fileno(),))
        start = time.monotonic()
        status = None
        offset, pending = 0, ""
        try:
            while process.poll() is None:
                if cancel is not None and cancel.exists():
                    status = "CANCELLED"
                    break
                if time.monotonic() - start > timeout:
                    status = "TIMEOUT"
                    break
                if trace.exists():
                    with trace.open(errors="replace") as audit_stream:
                        audit_stream.seek(offset)
                        pending += audit_stream.read()
                        offset = audit_stream.tell()
                    complete, separator, pending = pending.rpartition("\n")
                    if not separator:
                        pending = complete + pending
                    elif forbidden_network(complete):
                        status = "EGRESS_ATTEMPT_BLOCKED"
                        break
                time.sleep(0.1)
        except BaseException:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise
        finally:
            if process.poll() is None:
                # strace + bwrap die-with-parent + PID namespace tear-down cover descendants.
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        if not trace.is_file() or not trace.stat().st_size:
            raise Refused("AUDIT_UNAVAILABLE")
        raw = trace.read_text(errors="replace")
        attempts = forbidden_network(raw)
        if any(not entry["blocked"] for entry in attempts):
            status = "BOUNDARY_LOST"
        elif attempts:
            status = "EGRESS_ATTEMPT_BLOCKED"
        return {"status": status or ("COMPLETED" if process.returncode == 0 else "WORKER_FAILED"),
            "exitCode": process.returncode, "blockedAttempts": len(attempts),
            "auditDigest": hashlib.sha256(raw.encode()).hexdigest()}


def probe(root):
    directory = root / ("probe-" + uuid.uuid4().hex)
    directory.mkdir(mode=0o700)
    probe_code = "import socket; s=socket.socket(); assert s.connect_ex(('192.0.2.1',443)) in (101,113,13,1)"
    result = traced(boundary() + ["/usr/bin/python3", "-c", probe_code], directory, 15)
    if result["status"] != "EGRESS_ATTEMPT_BLOCKED" or result["blockedAttempts"] != 1:
        raise Refused("ISOLATION_UNAVAILABLE")
    return result


# Runs inside the boundary; the parent does not walk/read customer source files.
COPY_INPUT = r'''
import os, pathlib, shutil, stat, sys
source = pathlib.Path('/input')
target = pathlib.Path('/work/project')
excluded = {'.git', '.gradle', '.idea', '.kotlin', 'build', 'local.properties', 'keystore.properties', 'google-services.json', '.env', '.aws', '.ssh', '.codex', '.agents'}
def copy_tree(src, dst):
    dst.mkdir(exist_ok=True)
    for item in src.iterdir():
        if item.name in excluded or item.name.startswith('.env.') or item.suffix.lower() in ('.jks', '.keystore', '.p12', '.pem'):
            continue
        mode = item.lstat().st_mode
        if stat.S_ISLNK(mode) or not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
            raise RuntimeError('UNSUPPORTED_INPUT_FILE')
        out = dst / item.name
        if item.is_dir(): copy_tree(item, out)
        else: shutil.copyfile(item, out); out.chmod(0o700 if mode & 0o111 else 0o600)
copy_tree(source, target)
'''


def _execute_inside(root, source, command, bundle=None, timeout=1800, cancel=None, synthetic_ci=False, jdk=17, host_network=None):
    if sys.platform != "linux" or os.getuid() == 0:
        raise Refused("ISOLATION_UNAVAILABLE")
    if any(os.environ.get(name) for name in FORBIDDEN_ENV) and not synthetic_ci:
        raise Refused("EXTERNAL_CONTROLLER_FORBIDDEN")
    if host_network is None or os.readlink('/proc/self/ns/net') == host_network:
        raise Refused("CONTROLLER_ISOLATION_UNAVAILABLE")
    cancel = Path(cancel) if cancel is not None else None
    for tool in ("bwrap", "strace", "findmnt"):
        if not Path("/usr/bin", tool).is_file():
            raise Refused("ISOLATION_UNAVAILABLE")
    root = checked_path(root, private=True)
    probe_result = probe(root)  # No customer path has been opened yet.
    bundle_digest = None
    if bundle is not None:
        bundle = checked_path(bundle, private=True)
        try:
            bundle_digest = verify_bundle(bundle)
        except (ValueError, OSError):
            raise Refused("BUNDLE_MISMATCH") from None
    source = checked_path(source)
    if not command or not command[0].startswith("/"):
        raise Refused("ABSOLUTE_WORKER_COMMAND_REQUIRED")
    run_id = uuid.uuid4().hex
    run = root / run_id
    run.mkdir(mode=0o700)
    for name in ("work", "output", "audit", "copy-audit"):
        (run / name).mkdir(mode=0o700)
    hosts = run / "audit/hosts"
    hosts.write_text("127.0.0.1 localhost apm-worker\n::1 localhost apm-worker\n")
    if jdk not in (17, 21) or not Path(f"/usr/lib/jvm/java-{jdk}-openjdk-amd64/bin/java").is_file():
        raise Refused("DEPENDENCY_NOT_PREPARED")
    mounts = ["--bind", str(run / "work"), "/work",
        "--bind", str(run / "output"), "/output", "--setenv", "GRADLE_USER_HOME", "/work/gradle-home",
        "--ro-bind", str(hosts), "/etc/hosts", "--setenv", "JAVA_TOOL_OPTIONS", "-Duser.home=/home/worker",
        "--setenv", "JAVA_HOME", f"/usr/lib/jvm/java-{jdk}-openjdk-amd64"]
    if bundle is not None:
        # The tool/cache bundle is read-only. Its dependencies must already be prepared.
        mounts += ["--ro-bind", str(bundle), "/bundle", "--setenv", "GRADLE_RO_DEP_CACHE", "/bundle/gradle-home/caches", "--setenv", "ANDROID_HOME", "/bundle/sdk"]
    copied = traced(boundary(["--ro-bind", str(source), "/input", "--bind", str(run / "work"), "/work"]) + ["/usr/bin/python3", "-c", COPY_INPUT], run / "copy-audit", timeout, cancel)
    # Fingerprint the accepted snapshot before executing any customer build code.
    input_digest = hashlib.sha256()
    if copied["status"] == "COMPLETED":
        for path in sorted((run / "work/project").rglob("*")):
            if path.is_file():
                input_digest.update(path.relative_to(run / "work/project").as_posix().encode())
                input_digest.update(b"\0")
                with path.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        input_digest.update(block)
                input_digest.update(b"\0")
    result = copied if copied["status"] != "COMPLETED" else traced(boundary(mounts + ["--chdir", "/work/project"]) + command, run / "audit", timeout, cancel)
    if bundle is not None:
        try:
            if verify_bundle(bundle) != bundle_digest:
                result["status"] = "BUNDLE_CHANGED_DURING_EXECUTION"
        except (ValueError, OSError):
            result["status"] = "BUNDLE_CHANGED_DURING_EXECUTION"
    policy = {"schemaVersion": 1, "policyVersion": POLICY_VERSION, "runId": run_id, "aiMode": "DISABLED",
        "backend": "LINUX_BUBBLEWRAP", "enforcement": "OS_NETWORK_NAMESPACE", "audit": "HOST_STRACE",
        "probe": probe_result, "inputCopy": copied, **result, "resultsPublished": result["status"] == "COMPLETED",
        "auditBypassProtection": "SECCOMP_X86_64_V1", "originalInputMountedInWorker": False,
        "bundleDigest": bundle_digest,
        "inputDigest": input_digest.hexdigest() if copied["status"] == "COMPLETED" else None,
        "controllerIsolation": "SEPARATE_OS_NETWORK_NAMESPACE",
        "scope": "TRUSTED_HOST_KERNEL_AND_PREPARED_SYSTEM_TOOLS", "syntheticCi": synthetic_ci}
    (run / "execution-policy.json").write_text(json.dumps(policy, indent=2) + "\n")
    if policy["resultsPublished"]:
        # Readers select an entire successful generation through one atomic pointer.
        # Failed/cancelled runs remain quarantined and cannot replace a previous success.
        pointer = root / (".last-success-" + run_id)
        pointer.write_text(json.dumps({"runId": run_id, "policyVersion": POLICY_VERSION,
            "inputDigest": policy["inputDigest"], "bundleDigest": bundle_digest}) + "\n")
        pointer.chmod(0o600)
        os.replace(pointer, root / "last-success.json")
    # Never echo worker stdout, trace payloads, source paths or command arguments.
    return policy, run


def execute(root, source, command, bundle=None, timeout=1800, cancel=None, synthetic_ci=False, jdk=17):
    """Bootstrap only sees paths and an allowlisted result; source/log readers have no egress."""
    if sys.platform != "linux" or os.getuid() == 0:
        raise Refused("ISOLATION_UNAVAILABLE")
    if any(os.environ.get(name) for name in FORBIDDEN_ENV) and not synthetic_ci:
        raise Refused("EXTERNAL_CONTROLLER_FORBIDDEN")
    root = checked_path(root, private=True)
    # Metadata validation only. No project contents or worker logs are read here.
    source = checked_path(source)
    mounts = ["--bind", str(root), str(root), "--ro-bind", str(source), str(source),
        "--ro-bind", str(Path(__file__).resolve().parent), "/runner"]
    if bundle is not None:
        bundle = checked_path(bundle, private=True)
        mounts += ["--ro-bind", str(bundle), str(bundle)]
    request = {"root": str(root), "source": str(source), "command": command, "bundle": str(bundle) if bundle is not None else None,
        "timeout": timeout, "cancel": str(cancel) if cancel is not None else None, "synthetic_ci": synthetic_ci, "jdk": jdk,
        "host_network": os.readlink('/proc/self/ns/net')}
    process = subprocess.run(boundary(mounts) + ["/usr/bin/python3", "/runner/offline_runner.py", "--controller"],
        input=json.dumps(request), capture_output=True, text=True, env={"PATH": "/usr/bin:/bin"})
    try:
        result = json.loads(process.stdout)
    except (ValueError, TypeError):
        raise Refused("CONTROLLER_ISOLATION_UNAVAILABLE") from None
    if "runId" not in result:
        raise Refused(result.get("status", "RUNNER_FAILED_CLOSED"))
    if not re.fullmatch(r"[a-f0-9]{32}", result["runId"]):
        raise Refused("INVALID_CONTROLLER_RESULT")
    return result, root / result["runId"]


def main():
    if sys.argv[1:] == ["--controller"]:
        try:
            policy, _ = _execute_inside(**json.load(sys.stdin))
            print(json.dumps(policy))
            return 0
        except (Refused, OSError, ValueError, subprocess.SubprocessError) as error:
            print(json.dumps({"status": str(error) if isinstance(error, Refused) else "RUNNER_FAILED_CLOSED"}))
            return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-root", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--bundle")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--jdk", type=int, choices=(17, 21), default=17)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        policy, _ = execute(args.private_root, args.source, command, args.bundle, args.timeout, jdk=args.jdk)
        print(json.dumps({"status": policy["status"], "runId": policy["runId"], "aiMode": "DISABLED"}))
        return 0 if policy["status"] == "COMPLETED" else 2
    except (Refused, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": str(error) if isinstance(error, Refused) else "RUNNER_FAILED_CLOSED"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
