import importlib.util
from pathlib import Path
import sys
import json
import tempfile
import unittest
from unittest.mock import patch
import types
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('issue81_driver', ROOT / 'scripts/smoke-test-81-android-fgs-redelivery.py')
driver = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = driver
spec.loader.exec_module(driver)

PACKAGE = 'com.notifeeexample'
SERVICE = '''ACTIVITY MANAGER SERVICES (dumpsys activity services)
  * ServiceRecord{abc u0 com.notifeeexample/app.notifee.core.ForegroundService c:com.notifeeexample}
    app=ProcessRecord{abc 1234:com.notifeeexample/u0a123}
    isForeground=true foregroundId=42 types=0x00000001
    startRequested=true lastStartId=8
 startCommandResult=2
'''
NOTIFICATION = '''Current Notification Manager state:
  Notification List:
    NotificationRecord(x: pkg=com.notifeeexample user=UserHandle{0} id=42 tag=null)
      flags=ONGOING_EVENT|FOREGROUND_SERVICE
      android.text=SpannableString (issue81:run-a:update:2)
  Notification attention state:
'''

class DriverContract(unittest.TestCase):
    def test_service_requires_matching_record_pid_and_foreground_state(self):
        self.assertTrue(driver.service_active(SERVICE, PACKAGE, '1234'))
        for text, pid in [(SERVICE, '1235'), (SERVICE.replace('isForeground=true', 'isForeground=false'), '1234'),
                          (SERVICE.replace(PACKAGE, 'com.foreign'), '1234'),
                          ('isForeground=true\n' + SERVICE.replace('isForeground=true', ''), '1234')]:
            self.assertFalse(driver.service_active(text, PACKAGE, pid))

    def test_notification_readback_rejects_history_other_package_and_wrong_correlation(self):
        self.assertEqual(driver.notification_sequence(NOTIFICATION, PACKAGE, 'run-a', 42), 2)
        for text in [NOTIFICATION.replace(PACKAGE, 'com.foreign'),
                     NOTIFICATION.replace('run-a', 'run-b'),
                     NOTIFICATION.replace('FOREGROUND_SERVICE', 'NONE'),
                     NOTIFICATION.replace('Notification List:', 'Historical notifications:'),
                     NOTIFICATION.replace('id=42', 'id=43')]:
            self.assertIsNone(driver.notification_sequence(text, PACKAGE, 'run-a', 42))

    def test_markers_require_matching_correlation_and_well_formed_payload(self):
        log = 'ISSUE81:UPDATE_RESOLVED {"correlationId":"run-b","sequence":5}\nISSUE81:UPDATE_RESOLVED broken\n'
        self.assertEqual(driver.markers(log, 'run-a'), [])

    def test_marker_requires_real_js_tag_and_direct_marker_message(self):
        marker = 'ISSUE81:STOP_RESOLVED {"correlationId":"run-a","processRunId":"run","scenario":"issue81-fgs","notificationId":"issue81-fgs","nativeStopAttempted":true}'
        valid = '1790000000.001 1234 1 I ReactNativeJS: ' + marker
        self.assertEqual(len(driver.markers(valid, 'run-a')), 1)
        for raw in [valid.replace('ReactNativeJS:', 'AndroidRuntime:'),
                    valid.replace('ReactNativeJS:', 'ReactNativeJS: diagnostic:'), marker]:
            self.assertEqual(driver.markers(raw, 'run-a'), [])

    def test_no_false_r1_pass_with_missing_background_readback_service_or_candidate(self):
        good = dict(candidate=True, background=True, service=True, published_sequences={1, 2},
                    completed_sequences={1, 2}, stopped=True, errors=False)
        self.assertEqual(driver.r1_result(**good)[0], 'PASS')
        for key, value in [('candidate', False), ('background', False), ('service', False),
                           ('published_sequences', set()), ('completed_sequences', set()), ('stopped', False)]:
            bad = dict(good, **{key: value})
            self.assertNotEqual(driver.r1_result(**bad)[0], 'PASS', key)
        self.assertEqual(driver.r1_result(**dict(good, errors=True))[0], 'FAIL')
        self.assertNotEqual(driver.r1_result(**dict(good, published_sequences={3, 4}))[0], 'PASS')

    def test_kill_is_single_validated_pid_owned_by_exact_target_package(self):
        self.assertTrue(driver.kill_identity_ok('1234', 'com.notifeeexample\x00', '1234', PACKAGE))
        for pid, cmdline, current in [('0', PACKAGE, '0'), ('1', PACKAGE, '1'),
                                      ('1234 2345', PACKAGE, '1234 2345'), ('1234;reboot', PACKAGE, '1234'),
                                      ('1234', 'com.foreign\x00', '1234'), ('1234', PACKAGE + ':other\x00', '1234'),
                                      ('1234', PACKAGE, '2345')]:
            self.assertFalse(driver.kill_identity_ok(pid, cmdline, current, PACKAGE))

    def test_target_crash_uses_pid_or_framework_package_and_ignores_unrelated_process(self):
        self.assertTrue(driver.target_crash('1790000000.001 1234 1235 E AndroidRuntime: FATAL EXCEPTION: main', PACKAGE, {'1234'}))
        self.assertTrue(driver.target_crash('1790000000.002 1000 1001 I am_crash: [0,1234,com.notifeeexample,0,exception]', PACKAGE, {'1234'}))
        self.assertFalse(driver.target_crash('1790000000.003 9876 9877 E AndroidRuntime: FATAL EXCEPTION: main', PACKAGE, {'1234'}))

    def test_native_diagnostic_requires_actual_native_tag_and_target_process(self):
        line = '1790000000.001 1235 1236 W NOTIFEE : (ForegroundService): Framework redelivered START promotion denied (startId=4, flags=2, stop=accepted).'
        self.assertEqual(driver.native_recovery_lines(line, {'1235'}), line)
        self.assertEqual(driver.native_recovery_lines(line.replace('NOTIFEE', 'ReactNativeJS'), {'1235'}), '')
        self.assertEqual(driver.native_recovery_lines(line, {'9999'}), '')

    def test_exercised_service_disappearance_is_fail(self):
        self.assertEqual(driver.r1_result(candidate=True, background=True, service=False, published_sequences={1,2},
                                         completed_sequences={1,2}, stopped=True, errors=False)[0], 'FAIL')

    def test_padded_epoch_logcat_pid_is_parsed(self):
        line = '         1790000000.001 1234 1235 I ReactNativeJS: ISSUE81:READY {"correlationId":"run-a"}'
        self.assertEqual(driver.markers(line, 'run-a')[0]['logPid'], '1234')
        self.assertEqual(driver.log_pid(line), '1234')

    def test_command_failure_and_timeout_preserve_raw_bytes_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as folder:
            evidence = driver.Evidence(Path(folder))
            code, raw = evidence.run([sys.executable, '-c', 'import sys; print("raw failure"); sys.exit(7)'], required=False)
            self.assertEqual(code, 7)
            self.assertEqual(raw.strip(), 'raw failure')
            code, raw = evidence.run([sys.executable, '-c', 'import time; print("before timeout", flush=True); time.sleep(2)'], timeout=0.2, required=False)
            self.assertEqual(code, 124)
            self.assertIn('before timeout', raw)
            commands = [json.loads(line) for line in (Path(folder) / 'commands.jsonl').read_text().splitlines()]
            self.assertEqual([item['exitCode'] for item in commands], [7, 124])
            self.assertIn(b'before timeout', (Path(folder) / commands[1]['stdout']).read_bytes())

    def test_crash_attribution_rejects_embedded_tags_and_framework_messages(self):
        forged = [
            '1790000000.001 1234 1 E ReactNativeJS: E AndroidRuntime: FATAL EXCEPTION: example',
            '1790000000.001 1000 1 I ReactNativeJS: I am_proc_start: [0,9999,10123,com.notifeeexample,activity,x]\n1790000000.002 9999 1 E AndroidRuntime: FATAL EXCEPTION: main',
            '1790000000.001 1000 1 I ReactNativeJS: I am_crash: [0,1234,com.notifeeexample,0,example]',
        ]
        for raw in forged:
            with self.subTest(raw=raw):
                self.assertFalse(driver.target_crash(raw, PACKAGE, {'1234'}))

    def test_framework_start_death_reuse_and_command_window(self):
        started = '1790000000.001 1000 1 I ActivityManager: Start proc 1234:com.notifeeexample/u0a123 for activity x\n'
        fatal = '1790000000.002 1234 1 E AndroidRuntime: FATAL EXCEPTION: main\n'
        self.assertTrue(driver.target_crash(started + fatal, PACKAGE, set()))
        self.assertFalse(driver.target_crash(started + fatal, PACKAGE, set(), since=2))
        died = '1790000000.002 1000 1 I am_proc_died: [0,1234,com.notifeeexample,0,0]\n'
        self.assertFalse(driver.target_crash(started + died + fatal, PACKAGE, {'1234'}))
        reused = '1790000000.003 1000 1 I am_proc_start: [0,1234,10124,com.foreign,activity,x]\n'
        self.assertFalse(driver.target_crash(started + died + reused + fatal, PACKAGE, {'1234'}))

    def test_new_pid_and_intent_xflg_never_prove_r3(self):
        self.assertEqual(driver.recovery_result(True, True, 'Intent xflg=0x4', False), ('BLOCKED', 'new_process_without_native_recovery_evidence'))
        self.assertEqual(driver.recovery_result(True, False, '', True)[0], 'NOT_REPRODUCED')
        self.assertEqual(driver.recovery_result(False, False, '', False)[0], 'NOT_RUN')
        self.assertNotEqual(driver.recovery_result(True, True,
            'Framework redelivered START promotion denied (startId=4, flags=2, stop=accepted).', True)[0], 'PASS')
        # Even real denial plus service disappearance does not prove absence of a
        # future crash/restart chain. Automated R3 remains a reviewer decision.


class Clock:
    def __init__(self):
        self.counter = 0

    def monotonic(self):
        self.counter += 1
        return self.counter * .25

    def sleep(self, _):
        pass


class ScriptedEvidence:
    """Host-only execute witness: no adb, Gradle, installation or SIGKILL."""
    def __init__(self, output, variant):
        self.output = output
        self.variant = variant
        self.raw = ''
        self.phase = 'pre'
        self.starts = 0
        self.samples = 0
        self.correlation = None
        self.pid_calls = 0

    def event(self, **_):
        pass

    def collect(self, _):
        pass

    def log(self):
        return self.raw

    def marker(self, marker, correlation, pid='1234', run='run', **extra):
        payload = dict(correlationId=correlation, processRunId=run, scenario='issue81-fgs',
                       notificationId='issue81-fgs', appState='background', **extra)
        self.raw += f'1790000000.{len(self.raw.splitlines()) + 1:03d} {pid} 1 I ReactNativeJS: ISSUE81:{marker} ' + json.dumps(payload) + '\n'

    def run(self, argv, **_):
        argv = list(map(str, argv))
        if argv[0] == 'git':
            return 0, driver.HEAD if 'rev-parse' in argv else 'main'
        if argv[0] == './gradlew' or 'install' in argv or 'pull' in argv:
            return 0, ''
        if 'devices' in argv:
            return 0, 'test-device device\n'
        if 'getprop' in argv:
            return 0, '37' if argv[-1] == 'ro.build.version.sdk' else 'arm64-v8a' if argv[-1] == 'ro.product.cpu.abi' else ''
        if 'path' in argv:
            return 0, 'package:/data/app/test/base.apk'
        if 'package' in argv:
            return 0, 'android.permission.POST_NOTIFICATIONS: granted=true'
        if 'pidof' in argv:
            self.pid_calls += 1
            return 0, '' if self.variant == 'delayed_pid' and self.pid_calls == 1 or self.variant == 'short_lived_crash' else '1234'
        if 'services' in argv:
            return 0, SERVICE if self.phase in ['started', 'background'] else ''
        if 'notification' in argv:
            self.samples += 1
            return 0, NOTIFICATION.replace('run-a', self.correlation).replace('update:2', f'update:{1 if self.samples % 2 else 2}')
        if 'start' in argv:
            query = urllib.parse.parse_qs(argv[-1].strip("'").split('?')[1])
            cid = query['correlationId'][0]
            if query['action'][0] == 'start':
                self.starts += 1
                self.correlation = cid
                self.phase = 'started'
                if self.starts == 1 and self.variant in ['target_crash', 'short_lived_crash', 'foreign_crash', 'timeout', 'pid_reuse']:
                    if self.variant != 'timeout':
                        self.raw += '1790000000.001 1000 1 I am_proc_start: [0,1234,10123,com.notifeeexample,activity,com.notifeeexample/.MainActivity]\n'
                        if self.variant == 'pid_reuse':
                            self.raw += '1790000000.002 1000 1 I am_proc_died: [0,1234,com.notifeeexample,0,0]\n1790000000.003 1000 1 I am_proc_start: [0,1234,10124,com.foreign,activity,com.foreign/.MainActivity]\n'
                        fatal_pid = '9999' if self.variant == 'foreign_crash' else '1234'
                        self.raw += f'1790000000.004 {fatal_pid} 1 E AndroidRuntime: FATAL EXCEPTION: main\n'
                    return 0, 'launched'
                if self.variant in ['target_error', 'foreign_error']:
                    error_pid = '1234' if self.variant == 'target_error' else '9999'
                    self.raw += f'1790000000.003 {error_pid} 1 I ReactNativeJS: ISSUE81:ERROR ' + json.dumps({'correlationId': cid, 'reason': 'lookup_failed'}) + '\n'
                second = self.starts == 2
                self.marker('READY', cid, pid='9999' if second and self.variant == 'foreign_ready' else '1234',
                            run='old-run' if second and self.variant == 'stale_ready_run' else 'run')
                self.marker('RUNNER_STARTED', cid)
                # A matching but pre-command STOP must not authorize later cleanup.
                if self.variant == 'precommand_stop' or second and self.variant == 'second_precommand_stop':
                    self.marker('STOP_RESOLVED', cid, reason='explicit', nativeStopAttempted=True)
            else:
                self.phase = 'stopped'
                second = self.starts == 2
                if self.variant not in ['missing_stop', 'precommand_stop'] and not (second and self.variant == 'second_precommand_stop'):
                    self.marker('STOP_RESOLVED', cid,
                                pid='9999' if self.variant == 'foreign_stop' or second and self.variant == 'foreign_second_stop' else '1234',
                                run='old-run' if self.variant == 'stale_stop_run' else 'run',
                                reason='explicit', nativeStopAttempted='false' if self.variant == 'truthy_stop' else True)
            return 0, 'launched'
        if 'KEYCODE_HOME' in argv:
            self.phase = 'background'
            self.marker('BACKGROUND', self.correlation)
            for seq in [1, 2]:
                self.marker('UPDATE_REQUESTED', self.correlation, sequence=seq)
                self.marker('UPDATE_RESOLVED', self.correlation, sequence=seq, requestedAppState='background')
            return 0, ''
        raise AssertionError(argv)


class DriverLifecycle(unittest.TestCase):
    def execute_case(self, variant):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'apps/smoke/android/app/build/outputs/apk/release/app-release.apk'
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/index.android.bundle', 'host-witness')
            evidence = ScriptedEvidence(root, variant)
            args = types.SimpleNamespace(adb='fake-adb', serial='test-device', expected_head=driver.HEAD,
                type='dataSync', interval_ms=2000, max_updates=120, safety_timeout_ms=300000,
                ready_seconds=5, background_seconds=3, mode='normal')
            summary = {}
            with patch.object(driver, 'ROOT', root), patch.object(driver, 'time', Clock()), \
                 patch.object(driver, 'sources', return_value={'fingerprint': 'host', 'files': {}}), \
                 patch.object(driver, 'sha', return_value='host'):
                try:
                    driver.execute(args, evidence, summary)
                except RuntimeError as error:
                    summary['classification'] = 'FAIL' if isinstance(error, driver.ContractFailure) else 'BLOCKED'
                    summary['reason'] = str(error)
            return summary

    def test_owned_stop_for_both_sessions_keeps_r1_pass(self):
        for variant in ['valid', 'delayed_pid', 'foreign_error']:
            with self.subTest(variant=variant):
                summary = self.execute_case(variant)
                self.assertEqual(summary.get('R1', {}).get('status'), 'PASS', summary)

    def test_foreign_or_stale_markers_cannot_authorize_r1_pass(self):
        for variant in ['foreign_stop', 'foreign_second_stop', 'stale_stop_run', 'foreign_ready', 'stale_ready_run', 'precommand_stop', 'second_precommand_stop', 'missing_stop', 'truthy_stop']:
            with self.subTest(variant=variant):
                summary = self.execute_case(variant)
                self.assertNotEqual(summary.get('R1', {}).get('status'), 'PASS', summary)

    def test_target_fatal_before_ready_is_fail(self):
        for variant in ['target_crash', 'short_lived_crash', 'target_error']:
            with self.subTest(variant=variant):
                summary = self.execute_case(variant)
                self.assertEqual(summary.get('classification'), 'FAIL', summary)

    def test_foreign_crash_reused_pid_and_timeout_remain_blocked(self):
        for variant in ['foreign_crash', 'pid_reuse', 'timeout']:
            with self.subTest(variant=variant):
                summary = self.execute_case(variant)
                self.assertEqual(summary.get('classification'), 'BLOCKED', summary)
                self.assertEqual(summary.get('reason'), 'ready_not_observed')

if __name__ == '__main__':
    unittest.main()
