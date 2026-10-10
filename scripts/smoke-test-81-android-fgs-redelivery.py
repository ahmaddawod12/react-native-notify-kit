#!/usr/bin/env python3
"""Bounded Issue #81 device driver. --mode kill opts into ONE SIGKILL.

Builds and installs a bundled release smoke APK using existing Gradle properties.
Does not change permissions, device settings, manifests, or the native contract.
The safety deadline uses JS timers: suspension or hung native work can defer
cleanup. The host driver remains bounded and records that limitation.
R3/R4 need independent native review; an observed JS request is not a native
START barrier. Use --help for bounds. All commands, raw output and exits survive.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.notifeeexample'
COMPONENT = PACKAGE + '/app.notifee.core.ForegroundService'
HEAD = '12ae7acf917e5d9384e30de99a0277f7f139db68'


class ContractFailure(RuntimeError):
    pass


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def service_blocks(text, package):
    return [block for block in re.split(r'(?=^\s*\* ServiceRecord\{)', text, flags=re.M)
            if re.match(r'\s*\* ServiceRecord\{[^\n]*\bu0 ' + re.escape(package) +
                        r'/app\.notifee\.core\.ForegroundService(?:\s|\})', block)]


def service_active(text, package, pid):
    blocks = service_blocks(text, package)
    return len(blocks) == 1 and bool(re.search(r'\bisForeground=true\b', blocks[0])) and bool(
        re.search(r'\bapp=ProcessRecord\{\S+\s+' + re.escape(pid) + ':' + re.escape(package) + r'/', blocks[0]))


def foreground_id(text, package):
    blocks = service_blocks(text, package)
    match = re.search(r'\bforegroundId=(-?\d+)\b', blocks[0]) if len(blocks) == 1 else None
    return int(match[1]) if match else None


def notification_sequence(text, package, correlation, notification_id):
    # Only the active Notification List. History, snoozed and enqueued records
    # elsewhere in dumpsys are never publication evidence.
    match = re.search(r'^  Notification List:\s*\n(.*?)(?=^  \S|\Z)', text, re.M | re.S)
    if not match:
        return None
    for block in re.split(r'(?=^    NotificationRecord\()', match[1], flags=re.M):
        header = block.split('\n')[0]
        if not re.search(r'\bpkg=' + re.escape(package) + r'\s', header):
            continue
        if not re.search(r'\bid=' + re.escape(str(notification_id)) + r'\s', header):
            continue
        if 'FOREGROUND_SERVICE' not in header + '\n' + block:
            continue
        sequence = re.search(r'android\.text=\w+ \(issue81:' + re.escape(correlation) + r':update:(\d+)\)', block)
        if sequence:
            return int(sequence[1])
    return None


def markers(text, correlation):
    result = []
    for index, line in enumerate(text.splitlines()):
        match = re.match(r'^\s*\d+\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+ReactNativeJS\s*:\s*ISSUE81:([A-Z_]+) (\{.*\})\s*$', line)
        if not match:
            continue
        try:
            payload = json.loads(match[3])
        except (ValueError, TypeError):
            continue
        if not isinstance(payload, dict) or payload.get('correlationId') != correlation:
            continue
        result.append(dict(payload, marker=match[2], logPid=match[1], logLine=index))
    return result


def log_pid(line):
    match = re.match(r'^\s*\d+\.\d+\s+(\d+)\s+\d+\s+[VDIWEF]\s+', line)
    return match[1] if match else None


def session_marker(item, *, since, pid=None, run_id=None):
    return (item['logLine'] >= since and item.get('scenario') == 'issue81-fgs' and
            item.get('notificationId') == 'issue81-fgs' and
            isinstance(item.get('processRunId'), str) and bool(item['processRunId']) and
            item.get('logPid') is not None and (pid is None or item['logPid'] == pid) and
            (run_id is None or item['processRunId'] == run_id))


def session_error(item, *, since, pid, run_id):
    # App's terminal ERROR has correlation/PID but no harness run metadata. It
    # may deny success in its command window; it can never authorize READY/STOP.
    return (session_marker(item, since=since, pid=pid, run_id=run_id) or
            item['marker'] == 'ERROR' and item['logLine'] >= since and pid is not None and
            item.get('logPid') == pid and item.get('processRunId') is None)


def target_crash(text, package, pids, since=0):
    # Replay framework lifetimes in log order. A PID is not permanent ownership:
    # death or a later start for another package revokes the earlier attribution.
    owners = {pid: package for pid in pids}
    for index, line in enumerate(text.splitlines()):
        entry = re.match(r'^\s*\d+\.\d+\s+(\d+)\s+\d+\s+([VDIWEF])\s+(\S+)\s*:\s*(.*)$', line)
        if not entry:
            continue
        pid, priority, tag, message = entry.groups()
        start = re.match(r'\[\d+,(\d+),\d+,([^,]+),', message) if tag == 'am_proc_start' else None
        human_start = re.match(r'Start proc (\d+):([^/\s]+)/', message) if tag == 'ActivityManager' else None
        if start or human_start:
            match = start or human_start
            owners[match[1]] = match[2]
        died = re.match(r'\[\d+,(\d+),([^,]+),', message) if tag == 'am_proc_died' else None
        if died:
            owners[died[1]] = None
        if index < since:
            continue
        # Tags must come from the epoch log header, never from a JS payload.
        if owners.get(pid) == package and priority in ['E', 'F'] and tag in [
                'AndroidRuntime', 'libc', 'DEBUG', 'ActivityManager'] and re.search(
                r'\b(FATAL EXCEPTION|Fatal signal|ANR in|ForegroundServiceDidNotStartInTimeException)\b', message):
            return True
        crash = re.match(r'\[\d+,(\d+),([^,]+),', message) if tag in ['am_crash', 'am_anr'] else None
        if crash and crash[2] == package and owners.get(crash[1], package) == package:
            return True
    return False


def native_recovery_lines(text, pids):
    return '\n'.join(line for line in text.splitlines() if log_pid(line) in pids and
        re.search(r'\s[WE]\s+NOTIFEE\s*:\s*\(ForegroundService\): Framework redelivered START promotion denied', line))


def r1_result(*, candidate, background, service, published_sequences, completed_sequences, stopped, errors, restart=True):
    if errors:
        return 'FAIL', 'api_failure_or_target_crash'
    if not candidate:
        return 'BLOCKED', 'candidate_unproven'
    if not service:
        return 'FAIL', 'foreground_service_disappeared_during_background_observation'
    if not background:
        return 'BLOCKED', 'background_unproven'
    if len(published_sequences & completed_sequences) < 2:
        return 'BLOCKED', 'two_background_api_completions_and_native_readbacks_required'
    if not stopped:
        return 'FAIL', 'explicit_stop_not_verified'
    if not restart:
        return 'FAIL', 'new_session_after_stop_not_verified'
    return 'PASS', 'background_service_updates_and_explicit_stop_verified'


def kill_identity_ok(pid, cmdline, current_pid, package):
    return bool(re.fullmatch(r'[1-9]\d*', pid)) and int(pid) > 1 and pid == current_pid.strip() and cmdline.rstrip('\x00\r\n') == package


def recovery_result(killed, new_pid, native_log, service_absent):
    if not killed:
        return 'NOT_RUN', 'no_verified_sigkill'
    if 'Framework redelivered START promotion denied' in native_log:
        return 'BLOCKED', 'native_recovery_diagnostic_observed_independent_review_required'
    if new_pid:
        return 'BLOCKED', 'new_process_without_native_recovery_evidence'
    if service_absent:
        return 'NOT_REPRODUCED', 'no_new_process_or_native_redelivery_in_bounded_window'
    return 'BLOCKED', 'redelivery_and_final_service_state_unproven'


class Evidence:
    def __init__(self, output):
        self.output = output
        self.number = 0
        self.collector = None
        self.handles = []

    def event(self, **payload):
        with (self.output / 'timeline.jsonl').open('a') as handle:
            handle.write(json.dumps(dict(hostTime=utc(), monotonic=time.monotonic(), **payload)) + '\n')

    def run(self, argv, *, timeout=30, required=True, cwd=ROOT):
        self.number += 1
        name = f'command-{self.number:04d}'
        started = utc()
        begin = time.monotonic()
        try:
            result = subprocess.run([str(a) for a in argv], cwd=cwd, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, check=False)
            code, out, err = result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired as exc:
            code, out, err = 124, exc.stdout or b'', exc.stderr or b''
        except OSError as exc:
            code, out, err = 127, b'', str(exc).encode()
        (self.output / (name + '.stdout')).write_bytes(out)
        (self.output / (name + '.stderr')).write_bytes(err)
        with (self.output / 'commands.jsonl').open('a') as handle:
            handle.write(json.dumps(dict(argv=[str(a) for a in argv], cwd=str(cwd), started=started,
                ended=utc(), duration=time.monotonic() - begin, exitCode=code,
                stdout=name + '.stdout', stderr=name + '.stderr')) + '\n')
        if required and code != 0:
            raise RuntimeError(f'command_failed:{name}:exit={code}')
        return code, out.decode('utf-8', errors='replace')

    def collect(self, argv):
        self.handles = [(self.output / 'logcat.raw.log').open('wb'), (self.output / 'logcat.stderr.log').open('wb')]
        self.collector = subprocess.Popen(argv, stdout=self.handles[0], stderr=self.handles[1])
        self.event(action='collector_start', argv=argv, hostPid=self.collector.pid)

    def log(self):
        if self.collector and self.collector.poll() is not None:
            raise RuntimeError('logcat_collector_terminated')
        return (self.output / 'logcat.raw.log').read_text(errors='replace')

    def close(self):
        if self.collector:
            if self.collector.poll() is None:
                self.collector.terminate()
            try:
                code = self.collector.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.collector.kill()  # Only the owned HOST logcat collector.
                code = self.collector.wait(timeout=5)
            self.event(action='collector_end', exitCode=code)
        for handle in self.handles:
            handle.close()


def sources(evidence):
    _, listing = evidence.run(['git', 'ls-files', '-co', '--exclude-standard', '-z'])
    hashes = {name: sha(ROOT / name) for name in sorted(set(listing.split('\x00'))) if name and (ROOT / name).is_file()}
    digest = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    return dict(fingerprint=digest, files=hashes)


def bounded_int(low, high):
    def parse(value):
        number = int(value)
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f'must be between {low} and {high}')
        return number
    return parse


def arguments():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--output', type=Path, help='new evidence directory outside repository')
    parser.add_argument('--mode', choices=['normal', 'kill'], default='normal', help='kill opts into one SIGKILL; no retry loop')
    parser.add_argument('--type', choices=['dataSync', 'microphone'], default='dataSync')
    parser.add_argument('--background-seconds', type=bounded_int(3, 180), default=15)
    parser.add_argument('--observe-seconds', type=bounded_int(5, 180), default=60)
    parser.add_argument('--ready-seconds', type=bounded_int(5, 120), default=45)
    parser.add_argument('--interval-ms', type=bounded_int(100, 60000), default=2000)
    parser.add_argument('--max-updates', type=bounded_int(1, 10000), default=120)
    parser.add_argument('--safety-timeout-ms', type=bounded_int(1000, 600000), default=300000)
    parser.add_argument('--expected-head', default=HEAD)
    parser.add_argument('--kill-wait-seconds', type=bounded_int(1, 60), default=10,
                        help='bounded wait for a new unresolved JS update marker; native START currentness remains UNKNOWN')
    return parser.parse_args()


def execute(args, evidence, summary):
    def run(argv, **kwargs):
        return evidence.run(argv, **kwargs)[1]
    adb_base = [args.adb]
    devices = run(adb_base + ['devices', '-l'])
    available = re.findall(r'^(\S+)\s+device\b', devices, re.M)
    if args.serial:
        if args.serial not in available:
            raise RuntimeError('selected_device_unavailable')
    elif len(available) == 1:
        args.serial = available[0]
    else:
        raise RuntimeError('select_exact_device_with_--serial')
    adb_base += ['-s', args.serial]
    def adb(*argv, **kwargs):
        return run(adb_base + list(argv), **kwargs)
    def pid():
        return adb('shell', 'pidof', PACKAGE, required=False).strip()
    def services():
        return adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    def launch(action, correlation, fresh_task=False):
        query = f'action={action}&correlationId={correlation}&type={args.type}&intervalMs={args.interval_ms}&maxUpdates={args.max_updates}&safetyTimeoutMs={args.safety_timeout_ms}'
        # NEW_TASK | CLEAR_TASK apply only to the smoke Activity, never FGS START flags.
        flags = ['-f', '0x10008000'] if fresh_task else ['--activity-single-top']
        return adb('shell', 'am', 'start', '-W', *flags, '-n', PACKAGE + '/.MainActivity',
                   '-a', 'android.intent.action.VIEW', '-d', shlex.quote('notifykit://issue81/fgs?' + query))
    summary['device'] = dict(serial=args.serial, properties=adb('shell', 'getprop'))
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk').strip()
    if not sdk.isdecimal() or int(sdk) < 31:
        raise RuntimeError('api_31_required')
    if service_blocks(services(), PACKAGE):
        raise RuntimeError('preexisting_target_service_preserved')
    identity = dict(head=run(['git', 'rev-parse', 'HEAD']).strip(),
                    branch=run(['git', 'branch', '--show-current']).strip(),
                    status=run(['git', 'status', '--short', '--branch', '-uall']))
    if identity['head'] != args.expected_head:
        raise RuntimeError('unexpected_candidate_head')
    before = sources(evidence)
    (evidence.output / 'candidate-sources.json').write_text(json.dumps(dict(identity=identity, **before), indent=2))
    abi = adb('shell', 'getprop', 'ro.product.cpu.abi').strip()
    if abi not in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
        raise RuntimeError('unsupported_device_abi')
    print('Building bundled release smoke APK; raw build output is in command files.', flush=True)
    run(['./gradlew', ':app:assembleRelease', '-PnotifeeForegroundServiceType=microphone|dataSync',
         '-PreactNativeArchitectures=' + abi, '--no-build-cache'], cwd=ROOT / 'apps/smoke/android', timeout=1800)
    if sources(evidence) != before:
        raise RuntimeError('candidate_changed_during_build')
    apk = ROOT / 'apps/smoke/android/app/build/outputs/apk/release/app-release.apk'
    shutil.copyfile(apk, evidence.output / 'candidate.apk')
    with zipfile.ZipFile(apk) as archive:
        if 'assets/index.android.bundle' not in archive.namelist():
            raise RuntimeError('bundled_js_missing_candidate_unproven')
        summary['bundleHash'] = hashlib.sha256(archive.read('assets/index.android.bundle')).hexdigest()
    adb('install', '-r', str(apk), timeout=120)
    installed = adb('shell', 'pm', 'path', PACKAGE).strip().splitlines()
    if len(installed) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_./~+=-]+\.apk', installed[0]):
        raise RuntimeError('installed_apk_path_unproven')
    adb('pull', installed[0][8:], str(evidence.output / 'installed.apk'), timeout=120)
    candidate = sha(apk) == sha(evidence.output / 'installed.apk')
    summary['candidate'] = dict(**identity, fingerprint=before['fingerprint'], apkHash=sha(apk), installedMatches=candidate)
    package_dump = adb('shell', 'dumpsys', 'package', PACKAGE)
    required_permissions = ['POST_NOTIFICATIONS'] if int(sdk) >= 33 else []
    if args.type == 'microphone':
        required_permissions.append('RECORD_AUDIO')
    for permission in required_permissions:
        if not re.search(r'android\.permission\.' + permission + r': granted=true', package_dump):
            raise RuntimeError('permission_prerequisite_missing:' + permission)
    evidence.collect(adb_base + ['logcat', '-b', 'all', '-v', 'epoch', '-T', '1'])
    correlation = 'i81_' + uuid.uuid4().hex
    summary['correlationId'] = correlation
    start_index = len(evidence.log().splitlines())
    def events():
        return markers(evidence.log(), correlation)
    def wait_marker(marker, seconds, since, expected_pid=None, expected_run=None, cid=correlation):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            current_pid = expected_pid() if callable(expected_pid) else expected_pid
            raw = evidence.log()
            if target_crash(raw, PACKAGE, set(), since=start_index):
                raise ContractFailure('target_crash_before_marker:' + marker)
            all_current = markers(raw, cid)
            current = [item for item in all_current if session_marker(
                item, since=since, pid=current_pid, run_id=expected_run)]
            if any(item['marker'] in ['ERROR', 'START_FAILED', 'UPDATE_FAILED', 'STOP_FAILED', 'INTERFERENCE'] and
                   session_error(item, since=since, pid=current_pid, run_id=expected_run) for item in all_current):
                raise ContractFailure('harness_api_failure_or_interference')
            found = [item for item in current if item['marker'] == marker]
            if found:
                return found[-1]
            if marker == 'STOP_RESOLVED' and args.mode == 'kill' and any(item['marker'] == 'NO_SESSION' for item in current):
                return None
            if any(item['marker'] == 'BLOCKED' for item in current):
                return None
            time.sleep(0.1)
        if target_crash(evidence.log(), PACKAGE, set(), since=start_index):
            raise ContractFailure('target_crash_before_marker:' + marker)
        return None
    launch('start', correlation, fresh_task=True)
    summary['started'] = True
    ready = wait_marker('READY', args.ready_seconds, start_index, expected_pid=pid)
    if not ready:
        raise RuntimeError('ready_not_observed')
    initial_pid = pid()
    if not initial_pid.isdecimal() or ready.get('logPid') != initial_pid:
        raise RuntimeError('ready_pid_correlation_unproven')
    run_id = ready['processRunId']
    runner = wait_marker('RUNNER_STARTED', 10, start_index, initial_pid, run_id)
    if not runner:
        raise RuntimeError('persistent_runner_unproven')
    summary['initialPid'] = initial_pid
    record = services()
    notification_id = foreground_id(record, PACKAGE)
    if not service_active(record, PACKAGE, initial_pid) or notification_id is None:
        raise RuntimeError('foreground_service_record_unproven')
    background_index = len(evidence.log().splitlines())
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    background = wait_marker('BACKGROUND', 10, background_index, initial_pid, run_id)
    if not background or background.get('logPid') != initial_pid or background.get('processRunId') != run_id:
        raise RuntimeError('background_unproven')
    published = set()
    service_verified = True
    begin = time.monotonic()
    while time.monotonic() - begin < args.background_seconds:
        current_pid = pid()
        record = services()
        active = current_pid == initial_pid and service_active(record, PACKAGE, initial_pid) and foreground_id(record, PACKAGE) == notification_id
        service_verified &= active
        text = adb('shell', 'dumpsys', 'notification', '--noredact')
        sequence = notification_sequence(text, PACKAGE, correlation, notification_id)
        if sequence is not None and sequence > 0 and active:
            published.add(sequence)
        evidence.event(action='background_sample', pid=current_pid, serviceActive=active,
                       notificationSequence=sequence, elapsed=time.monotonic() - begin)
        time.sleep(0.5)
    completed = {item['sequence'] for item in events() if item['marker'] == 'UPDATE_RESOLVED' and
        session_marker(item, since=background_index, pid=initial_pid, run_id=run_id) and
        item.get('requestedAppState') == 'background' and item.get('appState') == 'background' and
        isinstance(item.get('sequence'), int) and item['sequence'] > 0}
    summary['background'] = dict(serviceVerified=service_verified, publishedSequences=sorted(published), completedSequences=sorted(completed))
    target_pids = {initial_pid}
    sessions = [(correlation, start_index)]
    def target_errors():
        raw = evidence.log()
        return any(item['marker'] in ['ERROR', 'START_FAILED', 'UPDATE_FAILED', 'STOP_FAILED', 'INTERFERENCE', 'SAFETY_TIMEOUT'] and
                   session_error(item, since=since, pid=initial_pid, run_id=run_id)
                   for cid, since in sessions for item in markers(raw, cid)) or target_crash(
                       raw, PACKAGE, target_pids, since=ready['logLine']) or target_crash(
                       raw, PACKAGE, set(), since=start_index)
    killed = False
    new_pid = False
    native_log = ''
    if args.mode == 'kill':
        if not service_verified or len(published & completed) < 2:
            raise RuntimeError('kill_prerequisites_missing_background_updates_or_service')
        # Select an API request that has not settled in the log snapshot. This
        # increases timing opportunities; it is NOT evidence of native START in flight.
        seen = {item.get('sequence') for item in events() if item['marker'] == 'UPDATE_REQUESTED'}
        pending = None
        deadline = time.monotonic() + args.kill_wait_seconds
        while time.monotonic() < deadline:
            current = events()
            settled = {item.get('sequence') for item in current if item['marker'] in ['UPDATE_RESOLVED', 'UPDATE_FAILED']}
            fresh = [item for item in current if item['marker'] == 'UPDATE_REQUESTED' and
                     item.get('logPid') == initial_pid and item.get('processRunId') == run_id and
                     item.get('appState') == 'background' and item.get('sequence') not in seen | settled]
            if fresh:
                pending = fresh[-1]
                break
            time.sleep(0.01)
        if not pending:
            raise RuntimeError('no_unresolved_js_update_marker_in_kill_window_no_kill_sent')
        current_pid = pid()
        cmdline = adb('shell', 'cat', '/proc/' + initial_pid + '/cmdline')
        stat = adb('shell', 'cat', '/proc/' + initial_pid + '/stat').split()
        if not kill_identity_ok(initial_pid, cmdline, current_pid, PACKAGE) or len(stat) < 22 or not stat[21].isdecimal():
            raise RuntimeError('kill_identity_unproven_no_kill_sent')
        start_ticks = stat[21]
        evidence.event(action='kill_coordination', jsRequest=pending, nativeStartInFlight='UNKNOWN',
                       warning='request may have settled before SIGKILL; no native delivery barrier')
        # Device-side atomic guard against wrong package, changed PID and PID reuse.
        guard = f'current=$(pidof {PACKAGE}); [ "$current" = "{initial_pid}" ] || exit 41; '
        guard += f'[ "$(tr \'\\000\' \'\\n\' < /proc/{initial_pid}/cmdline)" = "{PACKAGE}" ] || exit 42; '
        guard += f'[ "$(awk \'{{print $22}}\' /proc/{initial_pid}/stat)" = "{start_ticks}" ] || exit 43; '
        guard += f'kill -9 {initial_pid}'
        code, _ = evidence.run(adb_base + ['shell', guard], required=False)
        summary['killAttempt'] = dict(pid=initial_pid, exitCode=code, singleAttempt=True, nativeStartInFlight='UNKNOWN')
        if code:
            raise RuntimeError('single_sigkill_denied_or_identity_changed_no_retry')
        observation = time.monotonic()
        while time.monotonic() - observation < args.observe_seconds:
            next_pid = pid()
            new_pid |= bool(next_pid and next_pid != initial_pid)
            if next_pid.isdecimal():
                target_pids.add(next_pid)
            record = services()
            evidence.event(action='post_kill_sample', pid=next_pid, servicePresent=bool(service_blocks(record, PACKAGE)),
                           elapsed=time.monotonic() - observation)
            time.sleep(0.5)
        exit_info = adb('shell', 'dumpsys', 'activity', 'exit-info', PACKAGE)
        # Pair SIGNALED/status 9 with the actual killed PID, not historical exits.
        killed = any(re.search(r'\bpid=' + initial_pid + r'\b', block) and
                     'SIGNALED' in block and re.search(r'\bstatus=9\b', block)
                     for block in re.split(r'(?=\s*ApplicationExitInfo #)', exit_info))
        # Include short-lived framework-created target processes missed by polling.
        for line in evidence.log().splitlines():
            match = re.search(r'am_proc_start:\s*\[\d+,(\d+),\d+,' + re.escape(PACKAGE) + r',service,', line)
            if match:
                target_pids.add(match[1])
                new_pid |= match[1] != initial_pid
        native_log = native_recovery_lines(evidence.log(), target_pids - {initial_pid})
        summary['R2'] = dict(status=('PASS' if killed and new_pid and native_log else 'NOT_REPRODUCED' if killed and not new_pid and not native_log else 'BLOCKED'),
            reason='bounded_observation_only', verifiedSigkill=killed, newPidObserved=new_pid)
        r3 = recovery_result(killed, new_pid, native_log, not service_blocks(services(), PACKAGE))
        summary['R3'] = dict(status=r3[0], reason=r3[1], nativeDiagnostic=native_log,
                             nativeStartInFlight='UNKNOWN')
    # Cleanup is outside the kill observation window, so Activity launch cannot
    # masquerade as a framework restart. No re-launch until observation has ended.
    stop_index = len(evidence.log().splitlines())
    launch('stop', correlation)
    # Normal cleanup must stay in the READY process; kill cleanup may run in a
    # fresh Activity process and needs its own observed PID/run admission.
    cleanup_pid = pid() if args.mode == 'kill' else initial_pid
    cleanup_run = run_id if args.mode == 'normal' or cleanup_pid == initial_pid else None
    stop_event = wait_marker('STOP_RESOLVED', args.ready_seconds, stop_index, cleanup_pid, cleanup_run)
    no_session = any(item['marker'] == 'NO_SESSION' and session_marker(item, since=stop_index, pid=cleanup_pid, run_id=cleanup_run) for item in events())
    deadline = time.monotonic() + 10
    stopped = False
    while time.monotonic() < deadline:
        record = services()
        if not service_blocks(record, PACKAGE):
            stopped = bool(stop_event and stop_event.get('reason') == 'explicit' and stop_event.get('nativeStopAttempted') is True)
            if args.mode == 'kill' and no_session:
                stopped = True
            break
        time.sleep(0.5)
    adb('shell', 'dumpsys', 'notification', '--noredact')
    if args.mode == 'normal' and stopped and not target_errors():
        second = 'i81_' + uuid.uuid4().hex
        summary['cleanupCorrelation'] = second
        second_index = len(evidence.log().splitlines())
        sessions.append((second, second_index))
        launch('start', second)
        second_pid = pid()
        # This is a new session within the same normal-mode process lifetime.
        second_ready = wait_marker('READY', args.ready_seconds, second_index, initial_pid, run_id, second)
        restart_active = bool(second_ready and second_pid == initial_pid and
                              service_active(services(), PACKAGE, second_pid))
        # Always target cleanup at the new correlation, including failed START.
        second_stop_index = len(evidence.log().splitlines())
        launch('stop', second)
        second_stop = wait_marker('STOP_RESOLVED', args.ready_seconds,
                                  second_stop_index, initial_pid, run_id, second)
        deadline = time.monotonic() + args.ready_seconds
        restart_stopped = False
        while time.monotonic() < deadline:
            if (second_stop and second_stop.get('reason') == 'explicit' and
                    second_stop.get('nativeStopAttempted') is True and not service_blocks(services(), PACKAGE)):
                restart_stopped = True
                break
            time.sleep(0.2)
        target_pids.add(second_pid)
        summary['restart'] = dict(correlationId=second, activeVerified=restart_active, stopVerified=restart_stopped)
    if args.mode == 'normal':
        result = r1_result(candidate=candidate, background=True, service=service_verified,
            published_sequences=published, completed_sequences=completed, stopped=stopped, errors=target_errors(),
            restart=bool(summary.get('restart', {}).get('activeVerified') and summary.get('restart', {}).get('stopVerified')))
        summary['R1'] = dict(status=result[0], reason=result[1])
    else:
        summary['R1'] = dict(status='NOT_RUN', reason='normal_stop_session_not_executed_in_kill_mode')
        summary['cleanup'] = dict(stopped=stopped, stopMarker=stop_event is not None)
    if args.mode == 'kill' and target_crash(evidence.log(), PACKAGE, target_pids, since=ready['logLine']):
        summary['R3'] = dict(status='FAIL', reason='target_crash_during_kill_observation')
    if sources(evidence) != before:
        for key in ['R1', 'R2', 'R3']:
            summary[key] = dict(status='BLOCKED', reason='candidate_drift_after_runtime')
    summary['final'] = dict(pid=pid(), servicePresent=bool(service_blocks(services(), PACKAGE)), time=utc())
    summary['started'] = False
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')


def main():
    args = arguments()
    output = args.output or Path(tempfile.gettempdir()) / ('issue81-runtime-' + uuid.uuid4().hex)
    output = output.absolute()
    if output.resolve().is_relative_to(ROOT):
        raise SystemExit('Evidence directory must be outside the repository')
    output.mkdir(parents=True, exist_ok=False)
    evidence = Evidence(output)
    summary = dict(started=False, mode=args.mode, limits=vars(args).copy(),
        R1=dict(status='NOT_RUN'), R2=dict(status='NOT_RUN'), R3=dict(status='NOT_RUN'),
        R4=dict(status='BLOCKED', reason='native_concurrency_barriers_not_available'))
    summary['limits']['output'] = str(output)
    print('Evidence: ' + str(output), flush=True)
    code = 2
    try:
        execute(args, evidence, summary)
        statuses = [summary[key]['status'] for key in ['R1', 'R2', 'R3']]
        code = 1 if 'FAIL' in statuses else 0 if summary['R1']['status'] == 'PASS' or args.mode == 'kill' and summary['R2']['status'] == 'NOT_REPRODUCED' and summary['R3']['status'] == 'NOT_REPRODUCED' and summary.get('cleanup', {}).get('stopped') else 2
    except (RuntimeError, OSError, KeyboardInterrupt) as error:
        summary['error'] = str(error)
        key = 'R2' if args.mode == 'kill' else 'R1'
        status = 'FAIL' if isinstance(error, ContractFailure) else 'BLOCKED'
        summary[key] = dict(status=status, reason=str(error))
        code = 1 if status == 'FAIL' else 2
        # Best effort targeted cleanup only after this driver issued START. Safety
        # timeout remains the last resort if adb or JS is unavailable.
        if summary.get('started') and summary.get('correlationId') and args.serial:
            uri = 'notifykit://issue81/fgs?action=stop&correlationId=' + summary.get('cleanupCorrelation', summary['correlationId'])
            evidence.run([args.adb, '-s', args.serial, 'shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity',
                          '-a', 'android.intent.action.VIEW', '-d', shlex.quote(uri)], required=False)
            # Allow the targeted STOP to settle; preserve UNKNOWN on adb failure.
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                service_exit, final_services = evidence.run([args.adb, '-s', args.serial, 'shell', 'dumpsys', 'activity', 'services', PACKAGE], required=False)
                if service_exit == 0 and not service_blocks(final_services, PACKAGE):
                    break
                time.sleep(0.2)
            exit_code, final_pid = evidence.run([args.adb, '-s', args.serial, 'shell', 'pidof', PACKAGE], required=False)
            summary['final'] = dict(pid=final_pid.strip(), servicePresent=bool(service_blocks(final_services, PACKAGE)) if service_exit == 0 else 'UNKNOWN', time=utc())
            evidence.run([args.adb, '-s', args.serial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME'], required=False)
    finally:
        evidence.close()
        summary['exitCode'] = code
        (output / 'summary.json').write_text(json.dumps(summary, indent=2, default=str))
        if (output / 'logcat.raw.log').exists():
            lines = (output / 'logcat.raw.log').read_text(errors='replace').splitlines()
            (output / 'key-timeline.log').write_text('\n'.join(line for line in lines if
                'ISSUE81:' in line or PACKAGE in line or 'ForegroundService' in line or 'FATAL EXCEPTION' in line))
        hashes = {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()}
        (output / 'ARTIFACTS.sha256.json').write_text(json.dumps(hashes, indent=2))
        print(json.dumps({key: summary[key] for key in ['R1', 'R2', 'R3', 'R4', 'exitCode']}), flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
