"""Public synthetic canaries only. Never run a customer project in hosted CI."""
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import unittest
from unittest.mock import patch

from offline_runner import Refused, checked_path, execute, forbidden_network, core_policy
from runner_bundle import seal, verify


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="apm-security-")
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        self.source = self.root / "source"
        self.source.mkdir(mode=0o700)
        self.canary = "SYNTHETIC_APM_CANARY_NOT_CUSTOMER_DATA"
        (self.source / "input.txt").write_text(self.canary)

    def tearDown(self):
        self.temp.cleanup()

    def run_fixture(self, code, timeout=30):
        (self.source / "fixture.py").write_text(code)
        return execute(self.root, self.source, ["/usr/bin/python3", "fixture.py"], timeout=timeout, synthetic_ci=True)

    def test_normal_input_and_output_stay_local(self):
        policy, run = self.run_fixture("from pathlib import Path\nassert Path('input.txt').read_text().startswith('SYNTHETIC_')\nPath('/output/result.json').write_text('{\"ok\":true}')\n")
        self.assertEqual("COMPLETED", policy["status"])
        self.assertTrue(policy["resultsPublished"])
        self.assertEqual({"ok": True}, json.loads((run / "output/result.json").read_text()))
        self.assertNotIn(self.canary, json.dumps(policy))

    def test_tcp_http_and_child_process_are_blocked(self):
        for code in (
            "import socket; s=socket.socket(); s.connect(('192.0.2.1',443))",
            "import urllib.request; urllib.request.urlopen('http://192.0.2.1/canary',timeout=3)",
            "import subprocess; subprocess.run(['/usr/bin/python3','-c',\"import socket; socket.socket().connect(('192.0.2.1',443))\"])",
            "import socket; socket.socket(socket.AF_INET,socket.SOCK_DGRAM).sendto(b'FAKE_DNS_CANARY',('192.0.2.53',53))",
            "import socket; socket.socket(socket.AF_INET6).connect(('2001:db8::1',443))",
        ):
            with self.subTest(code=code):
                policy, _ = self.run_fixture(code)
                self.assertEqual("EGRESS_ATTEMPT_BLOCKED", policy["status"])
                self.assertFalse(policy["resultsPublished"])
                self.assertGreater(policy["blockedAttempts"], 0)

    def test_host_loopback_relay_is_inaccessible_and_proxy_is_not_inherited(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen()
            server.settimeout(0.2)
            port = server.getsockname()[1]
            old = os.environ.get("HTTPS_PROXY")
            os.environ["HTTPS_PROXY"] = f"http://127.0.0.1:{port}"
            try:
                policy, _ = self.run_fixture(f"import os,socket\nassert 'HTTPS_PROXY' not in os.environ\nassert socket.socket().connect_ex(('127.0.0.1',{port})) != 0\nassert not os.path.exists('/var/run/docker.sock')\nassert not os.path.exists('/mnt/c')\n")
            finally:
                if old is None: os.environ.pop("HTTPS_PROXY", None)
                else: os.environ["HTTPS_PROXY"] = old
            self.assertEqual("COMPLETED", policy["status"])
            with self.assertRaises(socket.timeout):
                server.accept()

    def test_worker_logs_are_not_the_security_result(self):
        policy, run = self.run_fixture("from pathlib import Path\nprint(Path('input.txt').read_text())\nprint('networkDisabled=true')\nraise RuntimeError('synthetic crash')")
        self.assertEqual("WORKER_FAILED", policy["status"])
        self.assertFalse(policy["resultsPublished"])
        self.assertNotIn(self.canary, json.dumps(policy))
        self.assertIn(self.canary, (run / "audit/worker.log").read_text())

    def test_timeout_terminates_daemonized_descendant(self):
        policy, run = self.run_fixture("import subprocess,time\nsubprocess.Popen(['/usr/bin/python3','-c',\"import time; from pathlib import Path; time.sleep(3); Path('/output/escaped').write_text('bad')\"],start_new_session=True)\ntime.sleep(30)", timeout=1)
        self.assertEqual("TIMEOUT", policy["status"])
        threading.Event().wait(3)
        self.assertFalse((run / "output/escaped").exists())

    def test_symlink_and_sync_output_rejected(self):
        link = self.root / "link"
        link.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(Refused): checked_path(link)
        sync = self.root / "OneDrive"
        sync.mkdir(mode=0o700)
        with self.assertRaises(Refused): checked_path(sync, private=True)

    def test_input_symlink_cannot_read_host_secret(self):
        (self.source / "escape").symlink_to("/etc/hostname")
        policy, _ = self.run_fixture("raise RuntimeError('must not run')")
        self.assertEqual("WORKER_FAILED", policy["status"])

    def test_original_input_and_secret_settings_are_not_mounted_in_worker(self):
        (self.source / "local.properties").write_text("FAKE_SECRET=canary")
        (self.source / "keystore.properties").write_text("FAKE_PASSWORD=canary")
        policy, _ = self.run_fixture("from pathlib import Path\nassert not Path('/input').exists()\nassert not Path('local.properties').exists()\nassert not Path('keystore.properties').exists()\n")
        self.assertEqual("COMPLETED", policy["status"])
        self.assertFalse(policy["originalInputMountedInWorker"])

    def test_audit_escape_syscalls_are_denied(self):
        policy, _ = self.run_fixture("""import ctypes, errno
libc = ctypes.CDLL(None, use_errno=True)
for number in (101, 272, 308, 321, 425):
    ctypes.set_errno(0)
    assert libc.syscall(number, 0, 0, 0, 0, 0) == -1
    assert ctypes.get_errno() == errno.EPERM
ctypes.set_errno(0)
assert libc.syscall(435, 0, 0) == -1 and ctypes.get_errno() == errno.ENOSYS
ctypes.set_errno(0)
assert libc.syscall(56, 0x00800000, 0, 0, 0, 0) == -1 and ctypes.get_errno() == errno.EPERM
""")
        self.assertEqual("COMPLETED", policy["status"])

    def test_child_jvm_cannot_send_canary(self):
        # A JVM fixture exercises the same process boundary as settings/buildSrc/KSP.
        (self.source / "Probe.java").write_text('''import java.net.*;
class Probe { public static void main(String[] args) throws Exception {
    new Socket("192.0.2.1", 443).getOutputStream().write("SYNTHETIC_CANARY".getBytes());
} }''')
        policy, _ = self.run_fixture("import subprocess\nsubprocess.run(['/usr/lib/jvm/java-17-openjdk-amd64/bin/java','Probe.java'],check=True)\n")
        self.assertEqual("EGRESS_ATTEMPT_BLOCKED", policy["status"])

    def test_native_crash_cannot_generate_host_core_dump(self):
        policy, run = self.run_fixture("import os,resource\nassert resource.getrlimit(resource.RLIMIT_CORE)==(0,0)\nos.abort()")
        self.assertEqual("WORKER_FAILED", policy["status"])
        self.assertEqual("HARD_LIMIT_ZERO_AND_NO_HOST_HELPER", policy["coreDumpPolicy"])
        self.assertFalse(any(path.name.startswith('core') for path in (run / 'work/project').iterdir()))

    def test_a_successful_external_syscall_invalidates_boundary(self):
        attempts = forbidden_network('42 connect(3, {sa_family=AF_INET, sin_addr=inet_addr("192.0.2.1")}, 16) = 0')
        self.assertEqual([{"family": "IPv4", "blocked": False}], attempts)

    def test_host_crash_forwarding_is_rejected_before_source_read(self):
        for pattern in ("|/host/helper %p", "@/host/collector.socket"):
            with patch.object(Path, 'read_text', return_value=pattern):
                with self.assertRaisesRegex(Refused, 'HOST_CRASH_COLLECTOR_FORBIDDEN'):
                    core_policy()

    def test_hosted_controller_refuses_before_source_is_opened(self):
        old = os.environ.get("GITHUB_ACTIONS")
        os.environ["GITHUB_ACTIONS"] = "true"
        try:
            with self.assertRaisesRegex(Refused, "EXTERNAL_CONTROLLER_FORBIDDEN"):
                execute(self.root, self.root / "does-not-exist", ["/usr/bin/true"])
        finally:
            if old is None: os.environ.pop("GITHUB_ACTIONS", None)
            else: os.environ["GITHUB_ACTIONS"] = old

    def test_failed_run_preserves_successful_generation_and_each_run_is_audited(self):
        first, _ = self.run_fixture("pass")
        original = (self.root / "last-success.json").read_bytes()
        failed, _ = self.run_fixture("raise RuntimeError('failure')")
        self.assertEqual("WORKER_FAILED", failed["status"])
        self.assertEqual(original, (self.root / "last-success.json").read_bytes())
        again, _ = self.run_fixture("pass")
        self.assertEqual(first["inputDigest"], again["inputDigest"])
        self.assertNotEqual(first["runId"], again["runId"])
        self.assertEqual(1, again["probe"]["blockedAttempts"])

    def test_snapshot_digest_cannot_confuse_file_boundaries_with_content(self):
        (self.source / 'a').write_bytes(b'foo\0b\0bar')
        first, _ = self.run_fixture('pass')
        (self.source / 'a').write_bytes(b'foo')
        (self.source / 'b').write_bytes(b'bar')
        second, _ = self.run_fixture('pass')
        self.assertNotEqual(first['inputDigest'], second['inputDigest'])

    def test_cancelled_run_never_publishes(self):
        cancel = self.root / "cancel"
        cancel.touch()
        policy, _ = execute(self.root, self.source, ["/usr/bin/python3", "-c", "import time;time.sleep(10)"], cancel=cancel, synthetic_ci=True)
        self.assertEqual("CANCELLED", policy["status"])
        self.assertFalse((self.root / "last-success.json").exists())

    def test_bundle_mismatch_refuses_without_running_worker(self):
        bundle = self.root / "bundle"
        bundle.mkdir(mode=0o700)
        (bundle / "tool").write_text("public tool")
        digest = seal(bundle)
        self.assertEqual(digest, verify(bundle))
        (bundle / "tool").write_text("changed")
        with self.assertRaisesRegex(Refused, "BUNDLE_MISMATCH"):
            execute(self.root, self.source, ["/usr/bin/true"], bundle=bundle, synthetic_ci=True)
        self.assertFalse((self.root / "last-success.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
