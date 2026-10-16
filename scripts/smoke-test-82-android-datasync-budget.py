#!/usr/bin/env python3
"""Bounded notification-UI driver; no synthetic intents or Android policy bypass.

This script is an executor tool, not a runtime qualification produced by host tests.
Feasibility installs the supplied, verified APK and uses real notification-shade taps.
Full and timeout-only require a matching feasibility PASS and --authorize-budget-change.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
import importlib.util

PACKAGE = 'com.notifeeexample'
COMPONENT = PACKAGE + '/app.notifee.core.ForegroundService'
KEY = 'data_sync_fgs_timeout_duration'
APPROVED_BASELINE = '4af99ca9eb041e12b1d6c2516d7f623306b01f47'
FROZEN = {
    'packages/react-native/android/src/main/java/app/notifee/core/ForegroundService.java': '6eb3bbf53fc5ff8bdc5f1af4e4d32c0635761852d7078c421692c801f8721d0f',
    'packages/react-native/android/src/test/java/app/notifee/core/ForegroundServiceTest.java': '7158a694ee0de761d069b4d5c618a1b46f6aa35340b881211953dbe9975ecbfa',
}
AUTHORIZED_PATHS = frozenset(FROZEN) | {
    'apps/smoke/ISSUE82_DATASYNC_BUDGET.md',
    'apps/smoke/__tests__/issue82DataSyncBudgetHarness.test.ts',
    'apps/smoke/__tests__/issue82HostFixture.cjs',
    'apps/smoke/__tests__/issue82RuntimeDriver.test.py',
    'apps/smoke/issue82DataSyncBudgetHarness.ts',
    'scripts/issue82_receipt.py',
    'scripts/smoke-test-82-android-datasync-budget.py',
}
ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATHS = ['apps/smoke/android/app/src/main/java/com/notifeeexample/MainApplication.kt','apps/smoke/android/app/src/main/java/com/notifeeexample/Issue82InstanceObserver.kt','apps/smoke/issue82ArmAdmission.ts','apps/smoke/index.js','apps/smoke/App.tsx','apps/smoke/issue82DataSyncBudgetHarness.ts',
                'apps/smoke/issue82Receipt.ts','apps/smoke/issue81FgsRedeliveryHarness.ts',
                'apps/smoke/issue44FgsStopHarness.ts','scripts/smoke-test-82-android-datasync-budget.py',
                'scripts/issue82_receipt.py','apps/smoke/android/app/src/main/AndroidManifest.xml',
                'apps/smoke/android/app/src/main/java/com/notifeeexample/Issue82ReceiptReceiver.kt', *FROZEN]
# Metro resolves this workspace package through dist. Bind its source and emitted runtime
# inputs so a stale local package build cannot be inherited by a feasibility/full handoff.
SOURCE_PATHS += sorted(str(p.relative_to(ROOT)) for folder, suffix in [('src','*.ts'),('dist','*.js')]
                       for p in (ROOT/'packages/react-native'/folder).rglob(suffix)
                       if '__mocks__' not in p.parts)
_receipt_spec = importlib.util.spec_from_file_location('receipt82', ROOT/'scripts/issue82_receipt.py')
receipt82 = importlib.util.module_from_spec(_receipt_spec)
_receipt_spec.loader.exec_module(receipt82)


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require_candidate(e, report):
    """Admit only linear, allowlisted descendants and the actual frozen working bytes.

    origin/main must be an ancestor of HEAD, so an authorized local commit need not
    be pushed. Merge, shallow and grafted histories have no approved admission policy.
    All Git results are recorded by Evidence; replacement objects are disabled.
    """
    def git(*args):
        return e.run(['git', '--no-replace-objects', *args], cwd=ROOT)

    def paths(raw, context):
        foreign = set(raw.rstrip('\0').split('\0')) - {''} - AUTHORIZED_PATHS
        if foreign:
            raise RuntimeError('BLOCKED: unauthorized candidate delta in '+context+': '+repr(sorted(foreign)))

    report['approvedBaseline'] = APPROVED_BASELINE
    try:
        head = git('rev-parse', '--verify', 'HEAD^{commit}').strip()
        if not re.fullmatch(r'[0-9a-f]{40}', head):
            raise RuntimeError('BLOCKED: effective HEAD is not a verified commit')
        report['effectiveHead'] = report['head'] = head
        report['statusBefore'] = git('status', '--short', '--branch', '-uall')
        if git('rev-parse', '--verify', APPROVED_BASELINE+'^{commit}').strip() != APPROVED_BASELINE:
            raise RuntimeError('BLOCKED: approved baseline object unavailable')
        grafts = Path(git('rev-parse', '--git-path', 'info/grafts').strip())
        if not grafts.is_absolute(): grafts = ROOT/grafts
        if git('rev-parse', '--is-shallow-repository').strip() != 'false' or grafts.exists():
            raise RuntimeError('BLOCKED: ambiguous shallow/grafted candidate ancestry')
        try:
            git('merge-base', '--is-ancestor', APPROVED_BASELINE, head)
        except RuntimeError as error:
            raise RuntimeError('BLOCKED: effective HEAD ancestry does not descend from approved baseline') from error
        report['branch'] = git('branch', '--show-current').strip()
        report['upstream'] = git('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{upstream}').strip()
        if report['branch'] != 'main' or report['upstream'] != 'origin/main':
            raise RuntimeError('BLOCKED: candidate branch/upstream provenance mismatch')
        upstream = git('rev-parse', '--verify', '@{upstream}^{commit}').strip()
        report['upstreamHead'] = upstream
        git('merge-base', '--is-ancestor', APPROVED_BASELINE, upstream)
        git('merge-base', '--is-ancestor', upstream, head)
        ahead, behind = map(int, git('rev-list', '--left-right', '--count', head+'...'+upstream).split())
        report['divergence'] = dict(ahead=ahead, behind=behind)
        commits = git('rev-list', '--parents', APPROVED_BASELINE+'..'+head).splitlines()
        if any(len(line.split()) != 2 for line in commits):
            raise RuntimeError('BLOCKED: candidate descendants must have linear single-parent ancestry')
        report['candidateCommits'] = [line.split()[0] for line in commits]
        for commit in report['candidateCommits']:
            paths(git('diff-tree', '--no-commit-id', '--name-only', '--no-renames', '-r', '-z', commit), commit)
        paths(git('diff', '--name-only', '--no-renames', '-z', APPROVED_BASELINE, head), 'baseline..HEAD')
        # Check index and worktree independently: a worktree revert can hide a staged edit.
        paths(git('diff', '--cached', '--name-only', '--no-renames', '-z', head), 'index')
        paths(git('diff', '--name-only', '--no-renames', '-z'), 'worktree')
        paths(git('ls-files', '--others', '--exclude-standard', '-z'), 'untracked')
        # Foreign tracked paths may not hide their bytes from Git's worktree diff.
        hidden = [entry[2:] for entry in git('ls-files', '-v', '-z').split('\0')
                  if entry and (entry[0].islower() or entry[0] == 'S')]
        paths('\0'.join(hidden), 'hidden index flags')
        report['frozenHashes'] = {p:sha(ROOT/p) for p in FROZEN}
        if report['frozenHashes'] != FROZEN:
            raise RuntimeError('BLOCKED: frozen Slice A fingerprint mismatch')
        report['sourceHashes'] = {p:sha(ROOT/p) for p in SOURCE_PATHS}
        report['sourceFingerprint'] = hashlib.sha256(receipt82.canonical(report['sourceHashes']).encode()).hexdigest()
        if git('rev-parse', '--verify', 'HEAD^{commit}').strip() != head or git('status', '--short', '--branch', '-uall') != report['statusBefore']:
            raise RuntimeError('BLOCKED: candidate changed during host preflight')
    except (RuntimeError, OSError, ValueError) as error:
        raise RuntimeError('BLOCKED: candidate provenance verification failed: '+str(error)) from error


def markers(raw, correlation, since=0, pid=None, attempt_id=None):
    result = []
    for line in raw.splitlines():
        m = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+[VDIWEF]\s+ReactNativeJS\s*:\s*ISSUE82:([A-Z_]+) (\{.*\})\s*$', line)
        if not m or float(m[1]) < since:
            continue
        try:
            payload = json.loads(m[4])
        except ValueError:
            continue
        if not isinstance(payload, dict) or payload.get('correlationId') != correlation or payload.get('smokeScenario') != 'issue82-datasync-budget' or payload.get('fgsType') != 1:
            continue
        if not isinstance(payload.get('processRunId'), str) or not payload['processRunId']:
            continue
        aid = payload.get('attemptId')
        if not isinstance(aid, str) or not re.fullmatch(re.escape(correlation)+r':[1-9]\d{0,5}:[a-zA-Z0-9_-]{1,80}', aid):
            continue
        parts=aid.split(':')
        expected=f'issue82-fgs-{correlation}-{parts[1]}-{parts[2]}'
        if payload.get('notificationId') != expected:
            continue
        if pid is not None and m[2] != pid or attempt_id is not None and aid != attempt_id:
            continue
        result.append(dict(payload, marker=m[3], logPid=m[2], epoch=float(m[1])))
    return result


def observe_services(raw, *, exit_code=0, stderr='', complete=True, provenance=None):
    """UNKNOWN is never an absence witness. Raw-only callers provide complete fixtures."""
    result = dict(status='UNKNOWN', record=None, reason=None, provenance=provenance)
    def unknown(reason):
        return dict(result, reason=reason)
    if exit_code != 0 or stderr.strip() or not complete:
        return unknown('failed, diagnostic or incomplete AMS command')
    lines = raw.splitlines()
    if not lines or lines[0].strip() != 'ACTIVITY MANAGER SERVICES (dumpsys activity services)' or not raw.endswith('\n'):
        return unknown('unrecognized or truncated AMS dump')
    body = '\n'.join(lines[1:])
    if re.search(r"^\s*(?:Permission Denial\b|Can't find service\b|(?:Error|Exception|Failure)(?:\s|:)|DUMP TIMEOUT\b|.*\b(?:timed out while dumping|output truncated)\b)", body, re.I | re.M):
        return unknown('AMS diagnostic output')
    if body.strip() == '(nothing)':
        return dict(result, status='VERIFIED_ABSENT')
    sections = r'(?:User \d+ (?:active services|delayed start services|starting in background)|Pending services|Restarting services|Destroying services|Connection bindings to services|Active foreground apps - user \d+|Handler - user \d+)'
    for line in lines[1:]:
        if not line.strip():
            continue
        if not line.startswith(' ') and not line.startswith('\t'):
            return unknown('unrecognized AMS top-level output')
        if re.match(r'^  \S', line) and not re.match(r'^  (?:\* |'+sections+r':)', line):
            return unknown('unrecognized AMS section')
    if not body.strip() or not re.search(r'^\s*'+sections+r':\s*$', body, re.M):
        return unknown('missing recognized AMS sections')
    headers = list(re.finditer(r'^\s*\* (?:Delayed start |Starting bg |Pending |Restarting |Destroy )?ServiceRecord\{([^\n}]*)\}\s*$', body, re.M))
    if body.count('ServiceRecord{') != len(headers):
        return unknown('unparsed ServiceRecord')
    targets = []
    for i, match in enumerate(headers):
        header = re.fullmatch(r'(\S+) u(\d+) (\S+)(?: c:([a-zA-Z0-9_.]+))?', match[1])
        if not header:
            return unknown('malformed ServiceRecord header')
        block = body[match.start():headers[i+1].start() if i+1 < len(headers) else len(body)]
        if not re.search(r'\bapp=(?:ProcessRecord\{[^\n}]+\}|null)', block):
            return unknown('incomplete ServiceRecord')
        started_detail = re.search(r'\blastStartId=\d+\b|\bcreated=[^\n]+ started=(?:true|false) connections=\d+', block)
        if header[3] != COMPONENT:
            # AOSP bound-only services omit start bookkeeping. Require a valid
            # component plus lifecycle and binding detail; omission alone proves nothing.
            bound_detail = (re.fullmatch(r'[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+', header[3])
                            and re.search(r'^\s+createTime=\S+ startingBgTimeout=\S+', block, re.M)
                            and re.search(r'^\s+Bindings:\s*$', block, re.M)
                            and re.search(r'^\s+requested=(?:true|false) received=(?:true|false) hasBound=(?:true|false) doRebind=(?:true|false)\s*$', block, re.M))
            if not started_detail and not bound_detail:
                return unknown('incomplete non-target ServiceRecord detail/summary')
            continue
        if not started_detail:
            return unknown('incomplete target ServiceRecord detail/summary')
        def value(pattern):
            found = re.search(pattern, block)
            return found[1] if found else None
        # Only foreground detail declarations, never intent/extras/client text.
        declarations = [line for line in block.splitlines() if re.match(
            r'^\s+(?:isForeground=(?:true|false)\b|foregroundServiceType\b)', line)]
        fields = [token for line in declarations for token in line.split()
                  if re.match(r'^(?:types|foregroundServiceType)\b', token)]
        if len(fields) > 1:
            return unknown('duplicate or contradictory foreground service type')
        typ = None
        if fields:
            field = re.fullmatch(r'(types|foregroundServiceType)=(0x[0-9a-fA-F]+|[0-9]+|dataSync)', fields[0])
            if not field or field[2] == 'dataSync' and field[1] != 'foregroundServiceType':
                return unknown('malformed foreground service type')
            typ = 1 if field[2] == 'dataSync' else int(field[2], 16 if field[2].startswith('0x') else 10)
            if typ > 0xffffffff:
                return unknown('foreground service type mask out of range')
        pid = value(r'app=ProcessRecord\{\S+ (\d+):' + re.escape(PACKAGE) + r'/')
        start_id = value(r'lastStartId=(\d+)\b')
        if not re.search(r'\bapp=(?:ProcessRecord\{[^\n}]+\}|null)', block) or start_id is None:
            return unknown('incomplete target ServiceRecord')
        targets.append(dict(identity=header[1], user=header[2], pid=pid,
                            foreground='isForeground=true' in block,
                            type=typ,
                            notificationId=value(r'foregroundId=(-?\d+)\b'), startId=start_id, raw=block))
    if len(targets) > 1:
        return unknown('multiple target ServiceRecords')
    if targets:
        return dict(result, status='PRESENT_TARGET', record=targets[0])
    if 'app.notifee.core.ForegroundService' in body:
        return unknown('unparsed target service reference')
    # An empty section header alone is not a complete empty dump.
    if not headers and not re.search(r'^\s+\* ConnectionRecord\{[^\n}]+\}', body, re.M):
        return unknown('incomplete AMS section')
    return dict(result, status='VERIFIED_ABSENT')


def service(raw, **kwargs):
    observation = observe_services(raw, **kwargs)
    if observation['status'] == 'UNKNOWN':
        raise RuntimeError('INCONCLUSIVE: '+observation['reason'])
    return observation['record']

def config_value(raw):
    for line in raw.splitlines():
        if line.startswith(KEY + '='):
            return line.split('=', 1)[1]
    return None


def restore_config(adb, original):
    if original is None:
        adb('shell', 'device_config', 'delete', 'activity_manager', KEY)
    else:
        adb('shell', 'device_config', 'put', 'activity_manager', KEY, shlex.quote(original))


@contextmanager
def configuration(adb, original, change):
    # Restore even if the put itself fails after changing remote state.
    try:
        if change:
            adb('shell', 'device_config', 'put', 'activity_manager', KEY, '180000')
        yield
    finally:
        if change:
            restore_config(adb, original)


def verdict(*, promise=False, promotion=False, runner=False, teardown=False, background=False,
            top_seen=False, dispatch_rejected=False, crash=False, denial=False):
    if top_seen:
        return 'INCONCLUSIVE'
    if dispatch_rejected:
        return 'BLOCKED'
    if crash:
        return 'FAIL' if denial else 'INCONCLUSIVE'
    if promotion and runner and teardown and background:
        return 'PASS'
    return 'INCONCLUSIVE'


def java_hash(text):
    h = 0
    for c in text:
        h = (31 * h + ord(c)) & 0xffffffff
    return h if h < 2**31 else h - 2**32


def require_feasibility(gate, report):
    if gate.get('mode') != 'feasibility' or gate.get('verdict') != 'PASS' or any(
        gate.get(k) != report.get(k) for k in ['serial','fingerprint','bootId','uid','apkHash','sourceHashes']) or (
        report.get('approvedBaseline') != APPROVED_BASELINE or
        gate.get('approvedBaseline') != APPROVED_BASELINE or
        not re.fullmatch(r'[0-9a-f]{40}', str(report.get('effectiveHead', ''))) or
        gate.get('effectiveHead') != report.get('effectiveHead') or
        gate.get('head') != gate.get('effectiveHead') or report.get('head') != report.get('effectiveHead')):
        # No cross-HEAD equivalence has been approved, even for matching source/APK bytes.
        raise RuntimeError('BLOCKED: matching independently observed feasibility PASS required')


def timeout_proof(raw, pos, system_pid):
    """Two independently attributed log sources; elapsed time and JS are insufficient."""
    request, record = pos['request'], pos['service']
    framework, native = [], []
    for line in raw.splitlines():
        event = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+E\s+(\S+)\s*:\s*(.*)', line)
        if not event or float(event[1]) < request['epoch']:
            continue
        epoch, pid, tag, message = event.groups()
        if pid == system_pid and tag == 'ActivityManager' and re.search(
            r'FGS \(dataSync\) timed out: ServiceRecord\{'+re.escape(record['identity'])+
            r' u0 '+re.escape(COMPONENT)+r'(?: c:'+re.escape(PACKAGE)+r')?\}', message):
            framework.append(line)
        if pid == request['logPid'] and tag == 'NOTIFEE':
            match = re.search(r'Foreground timeout \(startId=(\d+), type=(\d+), runGeneration=(\d+), demotion=(\w+), stop=(\w+)\)', message)
            if match and match[1] == record['startId'] and match[2] == '1':
                native.append((line, match))
    if len(native) > 1 or len(framework) > 1:
        raise RuntimeError('FAIL: duplicate attributed timeout callback/diagnostic')
    if not native or not framework or float(native[0][0].split()[0]) < float(framework[0].split()[0]):
        return None
    line, match = native[0]
    if match[4] != 'returned' or match[5] != 'accepted':
        raise RuntimeError('FAIL: isolated owned timeout demotion/conditional stop '+match[4]+'/'+match[5])
    return dict(native=[line],framework=framework,startId=match[1],type=1,
                runGeneration=match[3],demotion=match[4],stop=match[5])


def timeout_observation(e, pos, lifetime, proof, seconds):
    deadline = time.monotonic()+seconds
    request = pos['request']
    while time.monotonic() < deadline:
        raw = e.check_process(lifetime)
        s, top = e.snapshot()
        if e.top_seen or top:
            raise RuntimeError('INCONCLUSIVE: TOP during timeout observation')
        crash = crash_attribution(raw,request['logPid'])
        if crash:
            if 'ForegroundServiceDidNotStopInTimeException' in '\n'.join(crash['evidence']):
                crash.update(verdict='FAIL',reason='qualified timeout process crashed in target teardown contract')
            raise RuntimeError(crash['verdict']+': '+crash['reason'])
        current = timeout_proof(raw,pos,e.system_pid)
        events = [m for m in markers(raw,request['correlationId'],request['epoch'],request['logPid'],request['attemptId']) if m['marker']=='FG_TIMEOUT_OBSERVED']
        if not current or len(events) != 1 or events[0].get('eventType') != 9 or events[0].get('detail',{}).get('fgsType') != 1 or str(events[0].get('detail',{}).get('startId')) != proof['startId']:
            raise RuntimeError('FAIL: timeout payload/idempotence changed during observation')
        notification = events[0]['detail'].get('notification',{})
        data = notification.get('data',{})
        if str(notification.get('id')) not in [request['notificationId'],str(java_hash(request['notificationId']))] or data.get('correlationId') != request['correlationId'] or data.get('attemptId') != request['attemptId'] or data.get('role') != 'fgs':
            raise RuntimeError('FAIL: timeout latest notification snapshot ownership mismatch')
        if s is not None or foreground_notification(e.last_notifications,java_hash(request['notificationId'])):
            raise RuntimeError('FAIL: expired foreground state reappeared after teardown')
        if any(m['marker']=='RUNNER_STARTED' and m['epoch'] >= float(proof['native'][0].split()[0]) for m in markers(raw,request['correlationId'],request['epoch'])):
            raise RuntimeError('FAIL: runner acquired after timeout without a new START')
        time.sleep(2)
    return dict(seconds=seconds,event=events[0],processSamples=len(lifetime.samples),crashAttribution='none in qualified incarnation')


def top_events(raw, uid):
    result = []
    for line in raw.splitlines():
        if re.search(r'\b(?:wm_set_resumed_activity|am_set_resumed_activity|wm_resume_activity|am_resume_activity)\s*:', line) and PACKAGE in line:
            result.append(line)
        elif re.search(r'\b(?:am_uid_procstate|am_proc_state)\s*:', line) and re.search(r'\[' + str(uid) + r',2(?:,|\])', line):
            result.append(line)
    return result


def native_denial(raw, pid, notification_id):
    matched = []
    lines = raw.splitlines()
    for i, line in enumerate(lines):
        if not re.match(r'^\s*\d+\.\d+\s+' + re.escape(pid) + r'\s+\d+\s+W\s+NOTIFEE\s*:', line):
            continue
        m = re.search(r'Tracked START PROMOTION_DENIED \(cause=UNKNOWN, requestId=([a-f0-9-]+), notificationId=' + re.escape(notification_id) +
                      r', api=(\d+), type=1, generation=(\d+), runGeneration=(\d+), startId=(\d+), flags=0, stop=(accepted|refused|deferred)\)', line)
        if m and int(m[2]) >= 35:
            stack = '\n'.join(x for x in lines[i:i+45] if re.match(r'^\s*\d+\.\d+\s+'+re.escape(pid)+r'\s+\d+\s+W\s+NOTIFEE\s*:', x))
            matched.append(dict(requestId=m[1], startId=m[5], stop=m[6], line=line, stack=stack,
                                exactException='android.app.ForegroundServiceStartNotAllowedException:' in stack,
                                frameworkPromotion='android.app.Service.startForeground(' in stack,
                                budgetMessage='Time limit already exhausted for foreground service type dataSync' in stack))
    return matched


def foreground_notification(raw, notification_id):
    # Exclude history, enqueued and snoozed notifications.
    m = re.search(r'^  Notification List:\s*\n(.*?)(?=^  \S|\Z)', raw, re.M | re.S)
    if not m:
        return False
    for block in re.split(r'(?=^    NotificationRecord\()', m[1], flags=re.M):
        header = block.split('\n')[0]
        if re.search(r'\bpkg=' + re.escape(PACKAGE) + r'\s', header) and re.search(r'\bid=' + re.escape(str(notification_id)) + r'\s', header):
            return 'FOREGROUND_SERVICE' in block
    return False


def crash_lines(raw, pids):
    owners = {pid: PACKAGE for pid in pids}
    lines = raw.splitlines()
    out = []
    for i, line in enumerate(lines):
        event = re.match(r'^\s*\d+\.\d+\s+(\d+)\s+\d+\s+([VDIWEF])\s+(\S+)\s*:\s*(.*)', line)
        if not event:
            continue
        pid, priority, tag, message = event.groups()
        start = re.match(r'\[\d+,(\d+),\d+,([^,]+),', message) if tag=='am_proc_start' else None
        if start and start[1] in owners:
            owners[start[1]]=start[2]
        died = re.match(r'\[\d+,(\d+),([^,]+),', message) if tag=='am_proc_died' else None
        if died and died[1] in owners:
            owners[died[1]]=None
        crash = re.match(r'\[\d+,(\d+),([^,]+),', message) if tag in ['am_crash','am_anr'] else None
        if crash and crash[2]==PACKAGE and owners.get(crash[1])==PACKAGE:
            out.append(line)
        elif owners.get(pid)==PACKAGE and priority in ['E','F'] and tag in ['AndroidRuntime','libc','DEBUG'] and re.search(r'FATAL EXCEPTION|Fatal signal|ForegroundServiceDidNot', message):
            out.extend(x for x in lines[i:i+12] if re.match(r'^\s*\d+\.\d+\s+'+re.escape(pid)+r'\s+\d+\s+[EF]\s+(?:AndroidRuntime|libc|DEBUG)\s*:', x))
    return out


def crash_attribution(raw, pid):
    evidence=crash_lines(raw,{pid})
    if not evidence:
        return None
    text='\n'.join(evidence)
    if 'ForegroundServiceDidNotStopInTimeException' in text:
        return dict(verdict='INCONCLUSIVE', reason='post-timeout stop crash is outside initial-promotion-denial contract', evidence=evidence)
    if 'app.notifee.core.ForegroundService' in text and (
        'ForegroundServiceDidNotStartInTimeException' in text or
        ('android.app.ForegroundServiceStartNotAllowedException' in text and 'startForeground' in text)):
        return dict(verdict='FAIL', reason='correlated denying PID crashed in target FGS initial-promotion contract', evidence=evidence)
    return dict(verdict='INCONCLUSIVE', reason='post-denial crash/ANR needs independent causal attribution', evidence=evidence)



def proc_starttime(raw, pid):
    # comm (field 2) can contain spaces and parentheses; split at its final ')'.
    match = re.fullmatch(r'(\d+) \(.*\) (.+)\s*', raw.strip())
    if not match or match[1] != str(pid):
        raise RuntimeError('BLOCKED: malformed process stat identity')
    fields = match[2].split()
    if len(fields) < 20 or not fields[19].isdigit() or int(fields[19]) <= 0:
        raise RuntimeError('BLOCKED: process starttime unavailable')
    return fields[19]


def incarnation_log(raw, request):
    """Never let a later am_proc_start assign old-PID logs to this incarnation."""
    since, pid = request['epoch'], request['logPid']
    events = []
    ambiguous = False
    for line in raw.splitlines():
        match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+([VDIWEF])\s+(\S+)\s*:\s*(.*)', line)
        relevant = any(tag in line for tag in ['am_proc_start:', 'am_proc_died:', 'am_crash:', 'am_anr:']) and (PACKAGE in line or bool(re.search(r'\b'+re.escape(pid)+r'\b', line)))
        relevant |= bool(re.search(r'\b'+re.escape(pid)+r'\s+\d+\s+[VDIWEF]\s+(?:AndroidRuntime|libc|DEBUG|NOTIFEE|ReactNativeJS)\s*:', line))
        if not match:
            ambiguous |= relevant
            continue
        epoch = float(match[1])
        if epoch < since:
            continue
        events.append((epoch, line, match[4], match[5]))
    events.sort(key=lambda event: event[0])
    out = []
    broken = None
    lifecycle_epoch = None
    for epoch, line, tag, message in events:
        if tag in ['am_proc_start', 'am_proc_died']:
            pattern = r'\[\d+,(\d+),\d+,([^,]+),' if tag == 'am_proc_start' else r'\[\d+,(\d+),([^,]+),'
            event = re.match(pattern, message)
            if not event:
                if PACKAGE in line or re.search(r'\b'+re.escape(pid)+r'\b', message):
                    ambiguous = True
                continue
            if event[1] == pid:
                if epoch == since or lifecycle_epoch == epoch:
                    ambiguous = True
                lifecycle_epoch = epoch
                broken = broken or ('qualified process died' if tag == 'am_proc_died' else 'qualified PID restarted/reused')
                # A new birth is a hard end of the original incarnation's diagnostics.
                if tag == 'am_proc_start':
                    # Equal timestamps cannot order a crash against the replacement birth.
                    before_birth = [saved for saved in out if float(saved.split()[0]) < epoch]
                    ambiguous |= len(before_birth) != len(out)
                    out = before_birth
                    break
        out.append(line)
    return '\n'.join(out), broken, ambiguous


class ProcessLifetime:
    def __init__(self, request, identity):
        self.request = dict(request)
        self.identity = dict(identity)
        if not identity.get('starttime') or not identity.get('bootId'):
            raise RuntimeError('BLOCKED: process birth identity incomplete')
        if identity.get('birthEpochUpper', float('inf')) >= request['epoch']:
            raise RuntimeError('INCONCLUSIVE: process birth cannot be placed before START_REQUESTED')
        self.invalid = None
        self.samples = []

    def check(self, identity, raw, *, denial_epoch=None):
        qualified, broken, ambiguous = incarnation_log(raw, self.request)
        crash_raw = '\n'.join(line for line in qualified.splitlines()
                              if denial_epoch is not None and float(line.split()[0]) >= denial_epoch)
        crash = crash_attribution(crash_raw, self.request['logPid'])
        # Verified target crash takes precedence over missing process/AMS evidence.
        if crash:
            self.invalid = crash['verdict']+': '+crash['reason']
            raise RuntimeError(self.invalid)
        if identity != self.identity:
            self.invalid = self.invalid or 'INCONCLUSIVE: qualified process identity changed or unavailable'
        if broken or ambiguous:
            self.invalid = self.invalid or 'INCONCLUSIVE: '+(broken or 'ambiguous lifecycle timestamp/log')
        ms = markers(qualified, self.request['correlationId'], self.request['epoch'], self.request['logPid'], self.request['attemptId'])
        if any(m['processRunId'] != self.request['processRunId'] for m in ms):
            self.invalid = self.invalid or 'INCONCLUSIVE: attempt crossed JS process incarnation'
        self.samples.append(identity)
        if self.invalid:
            raise RuntimeError(self.invalid)
        return qualified

def created_instances(raw, pid):
    """AMS event-log ServiceRecord identity; no fabricated start flags."""
    found = []
    for line in raw.splitlines():
        m = re.search(r'\bam_create_service\s*:\s*\[0,(\d+),(?:com.notifeeexample/)?app\.notifee\.core\.ForegroundService,\d+,' + re.escape(pid) + r'\]', line)
        if m:
            found.append(format(int(m[1]) & 0xffffffff, 'x'))
    return found


def native_instance_proof(raw, pid, first_since, timeout_epoch, second_since, denial_epoch):
    events=[]
    for line in raw.splitlines():
        match=re.match(r'^\s*(\d+\.\d+)\s+'+re.escape(pid)+r'\s+\d+\s+I\s+Issue82Instance\s*:\s*(INSTANCE_PRESENT|INSTANCE_ABSENT) (\{.*\})$',line)
        if not match: continue
        try: body=json.loads(match[3])
        except (ValueError,TypeError): return None
        if str(body.get('pid'))!=pid or not re.fullmatch(r'[a-f0-9-]{36}',str(body.get('observerRunId',''))) or not isinstance(body.get('instanceId'),int) or body['instanceId']<1 or not isinstance(body.get('elapsedNanos'),int): return None
        events.append(dict(body,epoch=float(match[1]),kind=match[2]))
    old=[x for x in events if x['kind']=='INSTANCE_PRESENT' and first_since<=x['epoch']<timeout_epoch and x.get('className')=='app.notifee.core.ForegroundService']
    new=[x for x in events if x['kind']=='INSTANCE_PRESENT' and second_since<=x['epoch']<=denial_epoch+0.5 and x.get('className')=='app.notifee.core.ForegroundService']
    if len(old)!=1 or len(new)!=1: return None
    a,b=old[0],new[0]
    absent=[x for x in events if x['kind']=='INSTANCE_ABSENT' and timeout_epoch<=x['epoch']<second_since and x['observerRunId']==a['observerRunId'] and x['instanceId']==a['instanceId']]
    if len(absent)!=1 or a['observerRunId']!=b['observerRunId'] or b['instanceId']!=a['instanceId']+1 or not a['elapsedNanos']<absent[0]['elapsedNanos']<b['elapsedNanos']: return None
    if 'OBSERVER_UNAVAILABLE' in raw: return None
    return dict(old=a,absent=absent[0],new=b,identity='native:'+b['observerRunId']+':'+str(b['instanceId']),identityKind='app-owned-native-instance')


def fast_service_records(raw, since, *, stderr=''):
    if stderr.strip(): return []
    records = []
    for sample in re.finditer(r'ISSUE82_FAST_BEGIN:(\d+\.\d+)\n(.*?)\nISSUE82_FAST_END:(\d+):(\d+\.\d+)\n', raw, re.S):
        begin, end = float(sample[1]), float(sample[4])
        if begin > end or end < since: continue
        observation = observe_services(sample[2]+'\n', exit_code=int(sample[3]))
        if observation['status'] == 'PRESENT_TARGET':
            records.append(dict(observation['record'], sampleBegin=begin, sampleEnd=end))
    return records


def observation_window(settings):
    keys = ['service_start_foreground_timeout_ms', 'service_start_foreground_anr_delay_ms', 'fgs_crash_extra_wait_duration']
    values = []
    for key in keys:
        m = re.search(r'\b' + key + r'=(\d+)\b', settings)
        if not m:
            raise RuntimeError('INCONCLUSIVE: effective crash/ANR window unavailable: ' + key)
        values.append(int(m[1]))
    return sum(values) / 1000 + 10


def uid_is_top(raw):
    m = re.fullmatch(r'\s*(?:\d+:\s*)?(\d+)\s*\([A-Z_ ]+\)\s*', raw)
    if not m or not 0 <= int(m[1]) <= 20:
        raise RuntimeError('INCONCLUSIVE: UID process state unproven')
    # PROCESS_STATE_TOP=2; BOUND_TOP=3 and TOP_SLEEPING=12 are conservatively rejected.
    return int(m[1]) in [2, 3, 12]


def action_coordinates(xml, correlation, title):
    root = ET.fromstring(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    targets = [n for n in root.iter('node') if n.get('text') == title]
    result = []
    for n in targets:
        owner = n
        # Narrow ancestors only. Never bind a button through the whole shade root.
        for _ in range(5):
            if owner.tag=='hierarchy' or owner.get('resource-id', '').endswith('/notification_stack_scroller'):
                break
            titles=[x.get('text') for x in owner.iter('node') if x.get('text','').startswith('Issue 82 ')]
            if titles == ['Issue 82 ' + correlation]:
                m = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    result.append(((x1+x2)//2, (y1+y2)//2))
                break
            if owner not in parents:
                break
            owner = parents[owner]
    if len(result) != 1:
        raise RuntimeError('BLOCKED: notification action UI not uniquely bound to correlation; expand manually or use --manual-actions')
    return result[0]


def expand_coordinates(xml, correlation):
    root = ET.fromstring(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = []
    for node in root.iter('node'):
        if node.get('resource-id') != 'android:id/expand_button' or node.get('clickable') != 'true':
            continue
        owner = node
        for _ in range(5):
            titles = [x.get('text') for x in owner.iter() if x.get('resource-id') == 'android:id/title']
            if titles == ['Issue 82 '+correlation] and any(x.get('text','').startswith('Attempt ') for x in owner.iter()):
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds',''))
                if bounds:
                    matches.append(((int(bounds[1])+int(bounds[3]))//2,(int(bounds[2])+int(bounds[4]))//2))
                break
            if owner not in parents: break
            owner = parents[owner]
    if len(matches) != 1: raise RuntimeError('BLOCKED: control expansion identity ambiguous')
    return matches[0]


class Evidence:
    def __init__(self, output, serial):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=False)
        self.serial = serial
        self.count = 0
        self.collector = None
        self.log_handle = None
        self.log = self.output / 'logcat.raw'
        self.device_offset = 0
        self.negative_since = None
        self.uid = None
        self.top_seen = False
        self.owned_pids = set()
        self.last_notifications = ''

    def run(self, args, *, timeout=30, check=True, cwd=None):
        self.count += 1
        label = f'{self.count:04}'
        start = utc()
        try:
            p = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
            raw, code = p.stdout, p.returncode
        except subprocess.TimeoutExpired as error:
            raw, code = (error.stdout or b'') + b'\nHOST_COMMAND_TIMEOUT\n', 124
        except BaseException:
            with (self.output / 'commands.jsonl').open('a') as f:
                f.write(json.dumps(dict(command=args, start=start, end=utc(), exit=None, interrupted=True)) + '\n')
            raise
        (self.output / (label + '.raw')).write_bytes(raw)
        with (self.output / 'commands.jsonl').open('a') as f:
            f.write(json.dumps(dict(command=args, start=start, end=utc(), exit=code, raw=label+'.raw')) + '\n')
        if check and code:
            raise RuntimeError(f'command exit {code}: {args}')
        return raw.decode('utf-8', 'replace')

    def adb(self, *args, **kw):
        return self.run(['adb', '-s', self.serial, *args], **kw)

    def services(self):
        self.count += 1
        label = f'{self.count:04}-ams'
        token = 'ISSUE82_AMS_END_' + uuid.uuid4().hex
        command = ['adb', '-s', self.serial, 'shell',
                   "dumpsys activity services " + PACKAGE + "; result=$?; printf '\\n" + token + ":%s\\n' \"$result\""]
        start = utc()
        try:
            process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            stdout, stderr, code = process.stdout, process.stderr, process.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or b'', error.stderr or b'', 124
        except BaseException:
            with (self.output/'commands.jsonl').open('a') as f:
                f.write(json.dumps(dict(command=command, start=start, end=utc(), exit=None, interrupted=True))+'\n')
            raise
        (self.output/(label+'.stdout.raw')).write_bytes(stdout)
        (self.output/(label+'.stderr.raw')).write_bytes(stderr)
        text = stdout.decode('utf-8', 'replace')
        end = re.search(r'\n'+token+r':(\d+)\n\Z', text)
        provenance = dict(command=command, start=start, end=utc(), exit=code,
                          remoteExit=int(end[1]) if end else None,
                          stdout=label+'.stdout.raw', stderr=label+'.stderr.raw')
        with (self.output/'commands.jsonl').open('a') as f:
            f.write(json.dumps(provenance)+'\n')
        observation = observe_services(text[:end.start()+1] if end else text,
                                       exit_code=code if code else (int(end[1]) if end else None),
                                       stderr=stderr.decode('utf-8', 'replace'), complete=end is not None,
                                       provenance=provenance)
        with (self.output/'ams-observations.jsonl').open('a') as f:
            f.write(json.dumps(observation)+'\n')
        if observation['status'] == 'UNKNOWN':
            raise RuntimeError('INCONCLUSIVE: '+observation['reason'])
        return observation['record']

    def process_identity(self, pid):
        if not re.fullmatch(r'[1-9]\d*', str(pid)):
            raise RuntimeError('BLOCKED: invalid process PID')
        first = proc_starttime(self.adb('shell', 'cat', '/proc/'+pid+'/stat'), pid)
        status = self.adb('shell', 'cat', '/proc/'+pid+'/status')
        name = self.adb('shell', 'cat', '/proc/'+pid+'/cmdline').split('\x00')[0].strip()
        boot = self.adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').strip()
        proc_stat = self.adb('shell', 'cat', '/proc/stat')
        ticks = self.adb('shell', 'getconf', 'CLK_TCK').strip()
        second = proc_starttime(self.adb('shell', 'cat', '/proc/'+pid+'/stat'), pid)
        uid = re.search(r'^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s*$', status, re.M)
        if first != second:
            raise RuntimeError('INCONCLUSIVE: process replaced during identity capture')
        if not uid or any(int(value) != self.uid for value in uid.groups()) or name != PACKAGE or not re.fullmatch(r'[a-f0-9-]{36}', boot):
            raise RuntimeError('BLOCKED: process package/UID/boot identity unproven')
        btime = re.search(r'^btime (\d+)$', proc_stat, re.M)
        if not btime or not ticks.isdigit() or int(ticks) <= 0:
            raise RuntimeError('BLOCKED: process birth epoch unproven')
        return dict(birthEpochUpper=int(btime[1])+int(first)/int(ticks)+1, pid=pid, package=PACKAGE, uid=self.uid, processName=name,
                    bootId=boot, starttime=first)

    def bind_process(self, request):
        qualified, _, _ = incarnation_log(self.raw(), request)
        denials = native_denial(qualified, request['logPid'], request['notificationId'])
        if denials:
            epoch = float(denials[-1]['line'].split()[0])
            crash = crash_attribution('\n'.join(line for line in qualified.splitlines() if float(line.split()[0]) >= epoch), request['logPid'])
            if crash:
                raise RuntimeError(crash['verdict']+': '+crash['reason'])
        try:
            identity = self.process_identity(request['logPid'])
        except Exception as error:
            raise RuntimeError('BLOCKED: process birth identity unavailable: '+str(error)) from error
        lifetime = ProcessLifetime(request, identity)
        self.check_process(lifetime)
        self.active_lifetime = lifetime
        return lifetime

    def check_process(self, lifetime, denial_epoch=None):
        raw = self.raw()
        if denial_epoch is None:
            qualified, _, _ = incarnation_log(raw, lifetime.request)
            denials = native_denial(qualified, lifetime.request['logPid'], lifetime.request['notificationId'])
            if denials: denial_epoch = float(denials[-1]['line'].split()[0])
        try:
            identity = self.process_identity(lifetime.request['logPid'])
        except Exception:
            identity = None
        try:
            return lifetime.check(identity, raw, denial_epoch=denial_epoch)
        finally:
            with (self.output/'process-observations.jsonl').open('a') as f:
                f.write(json.dumps(dict(attemptId=lifetime.request['attemptId'],
                                        observedAt=self.now_device(), identity=identity,
                                        qualifiedIdentity=lifetime.identity, invalid=lifetime.invalid))+'\n')

    def now_device(self):
        return time.time() + self.device_offset

    def start_collector(self):
        self.log_handle = self.log.open('wb')
        args = ['adb', '-s', self.serial, 'logcat', '-b', 'all', '-v', 'epoch', '-T', '1']
        self.collector = subprocess.Popen(args, stdout=self.log_handle, stderr=subprocess.STDOUT)
        self.fast_handle = (self.output/'ams-fast.raw').open('wb')
        self.fast_error = (self.output/'ams-fast.stderr.raw').open('wb')
        command = "while true; do printf 'ISSUE82_FAST_BEGIN:%s\\n' \"$(date +%s.%N)\"; dumpsys activity services " + COMPONENT + "; ec=$?; printf '\\nISSUE82_FAST_END:%s:%s\\n' \"$ec\" \"$(date +%s.%N)\"; sleep 0.02; done"
        self.fast_collector = subprocess.Popen(['adb','-s',self.serial,'shell',command], stdout=self.fast_handle, stderr=self.fast_error)
        self.extra_fast = []
        # Independent framed streams avoid interleaving; four readers improve
        # coverage of a service destroyed within tens of milliseconds.
        for index in range(1,4):
            handle=(self.output/f'ams-fast-{index}.raw').open('wb')
            error=(self.output/f'ams-fast-{index}.stderr.raw').open('wb')
            process=subprocess.Popen(['adb','-s',self.serial,'shell',command],stdout=handle,stderr=error)
            self.extra_fast.append((process,handle,error))
        with (self.output / 'commands.jsonl').open('a') as f:
            f.write(json.dumps(dict(command=args, start=utc(), collectorPid=self.collector.pid,fastCommand=['adb','-s',self.serial,'shell',command],fastCollectorPid=self.fast_collector.pid,extraFastPids=[p.pid for p,_,_ in self.extra_fast]))+'\n')

    def raw(self):
        if self.collector and self.collector.poll() is not None:
            raise RuntimeError('INCONCLUSIVE: continuous logcat collector exited')
        if any(process.poll() is not None for process,_,_ in getattr(self,'extra_fast',[])):
            raise RuntimeError('INCONCLUSIVE: AMS fast collector exited')
        if getattr(self,'fast_collector',None) and self.fast_collector.poll() is not None:
            raise RuntimeError('INCONCLUSIVE: AMS fast collector exited')
        return self.log.read_text(errors='replace') if self.log.exists() else ''

    def negative_raw(self):
        return '\n'.join(line for line in self.raw().splitlines()
                         if re.match(r'^\s*\d+\.\d+', line) and float(line.split()[0]) >= (self.negative_since or 0))

    def snapshot(self):
        if getattr(self, 'active_lifetime', None) is not None:
            self.check_process(self.active_lifetime)
        s = self.services()
        activities = self.adb('shell', 'dumpsys', 'activity', 'activities')
        uidstate = self.adb('shell', 'cmd', 'activity', 'get-uid-state', str(self.uid))
        top = uid_is_top(uidstate) or any(PACKAGE in line for line in activities.splitlines() if re.search(r'mResumedActivity|topResumedActivity', line))
        if self.negative_since is not None:
            self.top_seen |= top or bool(top_events(self.negative_raw(), self.uid))
        self.last_notifications = self.adb('shell', 'dumpsys', 'notification', '--noredact')
        self.adb('shell', 'pidof', PACKAGE, check=False)
        if s and s['pid']:
            self.owned_pids.add(s['pid'])
        return s, top

    def stop_collector(self):
        for process,handle,error in getattr(self,'extra_fast',[]):
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)
            handle.close(); error.close()
            with (self.output/'commands.jsonl').open('a') as f:
                f.write(json.dumps(dict(extraFastCollectorPid=process.pid,end=utc(),exit=process.returncode))+'\n')
        if getattr(self,'fast_collector',None):
            self.fast_collector.terminate()
            try: self.fast_collector.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.fast_collector.kill(); self.fast_collector.wait(timeout=10)
            self.fast_handle.close(); self.fast_error.close()
            with (self.output/'commands.jsonl').open('a') as f:
                f.write(json.dumps(dict(fastCollectorPid=self.fast_collector.pid,end=utc(),exit=self.fast_collector.returncode))+'\n')

        if self.collector:
            self.collector.terminate()
            try:
                code = self.collector.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.collector.kill()
                code = self.collector.wait(timeout=10)
            with (self.output / 'commands.jsonl').open('a') as f:
                f.write(json.dumps(dict(collectorPid=self.collector.pid, end=utc(), exit=code))+'\n')
            self.log_handle.close()


def wait_for(e, condition, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        s, top = e.snapshot()
        if e.negative_since is not None and e.top_seen:
            raise RuntimeError('INCONCLUSIVE: TOP during negative lane')
        value = condition(s, top)
        if value:
            return value
        time.sleep(2)
    raise RuntimeError('BLOCKED: deadline reached for ' + label)


def tap(e, args, correlation, title):
    if args.manual_actions:
        print(f'UI interaction required within the next bounded wait: {title} on Issue 82 {correlation}', flush=True)
        return
    remote = '/sdcard/issue82-ui-' + uuid.uuid4().hex + '.xml'
    deadline=time.monotonic()+15
    try:
        while time.monotonic()<deadline:
            e.adb('shell','cmd','statusbar','expand-notifications')
            time.sleep(1)
            e.adb('shell','uiautomator','dump',remote)
            xml=e.adb('shell','cat',remote)
            try:
                x,y=action_coordinates(xml,correlation,title)
            except RuntimeError:
                try: x,y=expand_coordinates(xml,correlation)
                except RuntimeError:
                    continue  # No action dispatched; observe the settling shade again.
                e.adb('shell','input','tap',str(x),str(y))
                e.adb('shell','uiautomator','dump',remote)
                xml=e.adb('shell','cat',remote)
                try: x,y=action_coordinates(xml,correlation,title)
                except RuntimeError: continue
            e.adb('shell','input','tap',str(x),str(y))
            if title != 'Cleanup': e.adb('shell','cmd','statusbar','collapse')
            return  # Never repeat an action after dispatch, even if its outcome is UNKNOWN.
        raise RuntimeError('BLOCKED: owned notification UI did not settle before deadline')
    finally:
        e.adb('shell','rm','-f',remote,check=False)


def arm(e, correlation):
    # Only preparation/recovery. Never call during a live negative lane.
    if e.negative_since is not None:
        raise RuntimeError('negative lane forbids Activity arm')
    try:
        e.proof_chain = []
        uri = 'notifykit://issue82/arm?correlationId='+correlation+'&diagnostic='+receipt82.encode(e.receipt_signer.context)
        e.adb('shell', 'am', 'start', '-W', '--activity-single-top', '-n', PACKAGE+'/.MainActivity',
              '-a', 'android.intent.action.VIEW', '-d', shlex.quote(uri))
        wait_for(e, lambda s, top: top, 15, 'real TOP preparation/reset')
        start = e.now_device() - 5
        wait_for(e, lambda s, top: next((m for m in markers(e.raw(), correlation, start) if m['marker']=='ARMED'), None), 45, 'ARMED')
        # Establish event-log visibility of TOP during this preparation before relying on
        # its absence between subsequent UID-state samples.
        if not top_events(e.raw(), e.uid):
            raise RuntimeError('INCONCLUSIVE: continuous resumed-Activity evidence unavailable')
    finally:
        _, own_top = e.snapshot()
        if own_top:
            e.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    wait_for(e, lambda s, top: not top and s is None, 15, 'non-TOP idle before action')
    e.negative_since = e.now_device()
    e.top_seen = False


def start_action(e, args, correlation, since):
    tap(e, args, correlation, 'Start dataSync FGS')
    def requested(s, top):
        ms = markers(e.raw(), correlation, since)
        blocked = [m for m in ms if m['marker']=='BLOCKED']
        if blocked:
            raise RuntimeError('BLOCKED: '+str(blocked[-1].get('reason')))
        for m in ms:
            if m['marker']=='START_REQUESTED' and m.get('appState')=='background' and any(a['marker']=='BACKGROUND_ACTION_RECEIVED' and a.get('action')=='issue82-start' and a.get('source')=='background-handler' and a.get('appState')=='background' and a['logPid']==m['logPid'] and a['attemptId']==m['attemptId'] and a['processRunId']==m['processRunId'] for a in ms):
                return m
        return None
    return wait_for(e, requested, 45, 'correlated background START_REQUESTED')


def positive(e, args, correlation):
    request = start_action(e, args, correlation, e.negative_since)
    aid, pid = request['attemptId'], request['logPid']
    e.owned_pids.add(pid)
    lifetime = e.bind_process(request)
    def promoted(s, top):
        ms = markers(e.check_process(lifetime), correlation, request['epoch'], pid, aid)
        rejected = any(m['marker']=='START_PROMISE_SETTLED' and m.get('outcome')=='rejected' for m in ms)
        if rejected:
            raise RuntimeError('BLOCKED: dispatch/display rejected; not a Slice D promotion witness')
        runner = next((m for m in ms if m['marker']=='RUNNER_STARTED' and m['processRunId']==request['processRunId']), None)
        if s and s['foreground'] and s['type']==1 and s['pid']==pid and s['notificationId']==str(java_hash(request['notificationId'])) and runner and foreground_notification(e.last_notifications, java_hash(request['notificationId'])):
            return dict(request=request, service=s, runner=runner, processIdentity=lifetime.identity)
        return None
    try:
        return wait_for(e, promoted, 30, 'real AMS dataSync promotion and matching runner')
    finally:
        e.active_lifetime = None


def stop_owned(e, args, correlation, request):
    before, _ = e.snapshot()
    if before is None:
        return dict(outcome='already_absent_verified')
    if before['pid'] != request['logPid'] or before.get('user') != '0' or before['notificationId'] != str(java_hash(request['notificationId'])):
        raise RuntimeError('BLOCKED: foreign or unknown FGS ownership; no STOP')
    e.bind_process(request)
    since = e.now_device()
    if getattr(e, 'proof_chain', None):
        e.proof_chain[-1]['stopSince'] = since
    tap(e, args, correlation, 'Stop owned FGS')
    wait_for(e, lambda s, top: s is None and any(m['marker']=='STOP_RESOLVED' for m in markers(e.raw(), correlation, since, request['logPid'], request['attemptId'])), 30, 'owned STOP and AMS teardown')
    e.active_lifetime = None
    return dict(outcome='stop_observed_teardown_verified')


def reply_coordinates(xml, correlation, expected_input=None):
    tree = ET.fromstring(xml)
    parents = {child:parent for parent in tree.iter() for child in parent}
    candidates = []
    for n in tree.iter():
        if n.get('class') != 'android.widget.EditText' or n.get('focused') != 'true': continue
        if expected_input is not None and n.get('text') != expected_input: continue
        owner = n
        while owner in parents:
            owner = parents[owner]
            titles = [x.get('text') for x in owner.iter() if (x.get('text') or '').startswith('Issue 82 ')]
            if titles == ['Issue 82 '+correlation]:
                sends = [x for x in owner.iter() if (x.get('resource-id') == 'com.android.systemui:id/remote_input_send' or x.get('content-desc') in ['Send','Send reply']) and
                         x.get('clickable')=='true' and (expected_input is None or x.get('enabled')=='true')]
                if len(sends)==1: candidates.append(sends[0])
                break
            if titles: break
    if len(candidates)!=1: raise RuntimeError('BLOCKED: inline reply ownership/input ambiguous')
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',candidates[0].get('bounds',''))
    if not bounds: raise RuntimeError('BLOCKED: inline reply bounds unknown')
    return str((int(bounds[1])+int(bounds[3]))//2),str((int(bounds[2])+int(bounds[4]))//2)


def submit_receipt(e, args, correlation, wire):
    # Only the signed receipt is transported; START/STOP remain real shade actions.
    service, top = e.snapshot()
    if service is not None or top or e.top_seen:
        raise RuntimeError('INCONCLUSIVE: receipt delivery requires verified absence and non-TOP')
    e.adb('shell', 'am', 'broadcast', '-n', PACKAGE+'/.Issue82ReceiptReceiver',
          '-a', PACKAGE+'.ISSUE82_SIGNED_RECEIPT', '--es', 'correlationId', correlation,
          '--es', 'wire', wire)


def cleanup_control(e, args, correlation):
    s, _ = e.snapshot()
    if s is not None:
        raise RuntimeError('BLOCKED: cannot remove controls while ServiceRecord exists')
    since = e.now_device()
    if getattr(e, 'receipt_signer', None) and getattr(e, 'proof_chain', None):
        tap(e,args,correlation,'Stop owned FGS')
        quiesced = wait_for(e,lambda s,top: next((m for m in markers(e.raw(),correlation,since)
            if m['marker']=='QUIESCED'),None),30,'persistent quiescence')
        body = receipt82.certify(e,correlation,quiesced,markers(e.raw(),correlation),getattr(e,'receipt_mode',args.mode))
        wire = e.receipt_signer.sign(body)
        (e.output/'receipt-body.json').write_text(json.dumps(body,indent=2))
        e.active_lifetime=ProcessLifetime(e.proof_chain[-1]['request'],e.proof_chain[-1]['processIdentity'])
        submit_receipt(e,args,correlation,wire)
        expected_epoch = body['epoch']
    else:
        tap(e, args, correlation, 'Cleanup')
        expected_epoch = None
    def cleaned(s, top):
        ms = markers(e.raw(), correlation, since)
        blocked = [m for m in ms if m['marker'] == 'BLOCKED']
        if blocked:
            raise RuntimeError('BLOCKED: control retained: '+str(blocked[-1].get('reason')))
        return s is None and any(m['marker'] == 'CLEANUP' and (expected_epoch is None or
            m.get('certified') is True and m.get('quiescenceEpoch')==expected_epoch) for m in ms)
    wait_for(e, cleaned, 30, 'control CLEANUP')


def ensure_installed(e, apk, expected_hash):
    # Reinstalling an identical APK restarts React and can replay a retained ARM
    # Activity intent before the new Linking listener exists. Verify bytes first.
    def pull():
        paths = e.adb('shell','pm','path',PACKAGE).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:'):
            raise RuntimeError('BLOCKED: installed APK path ambiguous')
        installed = e.output/'installed.apk'
        e.adb('pull',paths[0][8:],str(installed),timeout=120)
        return sha(installed)
    initial = pull()
    if initial == expected_hash:
        return dict(hash=initial, action='verified_identical_install_skipped')
    e.adb('install','-r',str(Path(apk).resolve()),timeout=120)
    final = pull()
    if final != expected_hash: raise RuntimeError('BLOCKED: installed APK differs')
    return dict(hash=final, action='installed_and_verified')


def execute(args):
    e = Evidence(args.output, args.serial)
    report = dict(mode=args.mode, serial=args.serial, started=utc(), verdict='NOT_RUN',
                  lanes={f'R{i}':'NOT_RUN' for i in range(1, 7)}, cleanup=[], limitations=[])
    original = None
    changed = False
    config_saved = False
    owned_cleanup_ok = True
    correlation = 'i82-' + uuid.uuid4().hex
    report['correlationId'] = correlation
    request = None
    armed = False
    try:
        require_candidate(e, report)
        report['apkHash'] = sha(args.apk)
        with zipfile.ZipFile(args.apk) as apk:
            if 'assets/index.android.bundle' not in apk.namelist() or b'issue82-datasync-budget' not in apk.read('assets/index.android.bundle') or not all(any(marker in apk.read(n) for n in apk.namelist() if n.endswith('.dex')) for marker in [b'Tracked START PROMOTION_DENIED',b'Foreground timeout (startId=',b', demotion=']):
                raise RuntimeError('BLOCKED: candidate/bundled APK identity unproven')
        aapt = str(Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk'))) / 'build-tools/36.0.0/aapt2')
        manifest = e.run([aapt,'dump','xmltree',str(Path(args.apk).resolve()),'--file','AndroidManifest.xml'])
        badging = e.run([aapt,'dump','badging',str(Path(args.apk).resolve())])
        if "package: name='"+PACKAGE+"'" not in badging or "targetSdkVersion:'36'" not in badging or not re.search(r'name[^\n]*="app.notifee.core.ForegroundService"[^\n]*\n[^\n]*exported[^\n]*false\n[^\n]*foregroundServiceType[^\n]*0x00000001', manifest):
            raise RuntimeError('BLOCKED: APK package/targetSdk/dataSync manifest mismatch')
        report['properties'] = e.adb('shell','getprop')
        e.system_pid=e.adb('shell','pidof','system_server').strip()
        if not re.fullmatch(r'[1-9]\d*',e.system_pid):
            raise RuntimeError('BLOCKED: framework log process identity unavailable')
        report['systemServerPid']=e.system_pid
        api = int(e.adb('shell','getprop','ro.build.version.sdk').strip())
        if api < 35:
            raise RuntimeError('BLOCKED: API >=35 required')
        report['fingerprint'] = e.adb('shell','getprop','ro.build.fingerprint').strip()
        before_package = e.adb('shell','dumpsys','package',PACKAGE)
        uid = re.search(r'(?:userId|appId)=(\d+)', before_package)
        if not uid:
            raise RuntimeError('BLOCKED: existing smoke installation required')
        e.uid = int(uid[1]); report['uid'] = e.uid
        report['bootId'] = e.adb('shell','cat','/proc/sys/kernel/random/boot_id').strip()
        if not re.fullmatch(r'[a-f0-9-]{36}',report['bootId']):
            raise RuntimeError('BLOCKED: device boot identity unavailable')
        config_before = e.adb('shell','device_config','list','activity_manager')
        original = config_value(config_before); config_saved=True; report['configOriginal'] = dict(present=original is not None, value=original)
        report['compatBefore'] = e.adb('shell','dumpsys','platform_compat')
        initial_settings=e.adb('shell','dumpsys','activity','settings')
        report['minimumObservationSeconds']=observation_window(initial_settings)
        if args.observation_seconds < report['minimumObservationSeconds']:
            raise RuntimeError('INCONCLUSIVE: observation shorter than effective AMS crash/ANR windows')
        if e.services() is not None:
            raise RuntimeError('BLOCKED: preexisting ServiceRecord preserved')
        # Correct clock skew for logcat epoch provenance; do not assume host and phone clocks agree.
        before_clock = time.time()
        phone_clock = float(e.adb('shell','date','+%s').strip())
        e.device_offset = phone_clock - (before_clock + time.time())/2
        report['deviceClockOffsetSeconds'] = e.device_offset
        if args.mode in ['full','timeout-only']:
            if not args.authorize_budget_change or not args.feasibility_report:
                raise RuntimeError('BLOCKED: explicit budget authorization and feasibility report required')
            gate = json.loads(Path(args.feasibility_report).read_text())
            require_feasibility(gate,report)
        installation = ensure_installed(e,args.apk,report['apkHash'])
        report['installedApkHash'] = installation['hash']
        report['installation'] = installation['action']
        report['packageAfter'] = e.adb('shell','dumpsys','package',PACKAGE)
        e.start_collector()
        e.receipt_signer = receipt82.ReceiptSigner(dict(serial=args.serial,build=report['fingerprint'],
            bootId=report['bootId'],package=PACKAGE,uid=e.uid,apkHash=report['apkHash'],
            candidate=hashlib.sha256(receipt82.canonical(report['sourceHashes']).encode()).hexdigest()))
        e.proof_chain = []
        if args.mode in ['full','timeout-only']:
            changed = True  # restore even if put has a lost/failed reply
            e.adb('shell','device_config','put','activity_manager',KEY,'180000')
            wait_for(e, lambda s, top: config_value(e.adb('shell','device_config','list','activity_manager'))=='180000' and bool(re.search(r'\b'+KEY+r'=180000\b', e.adb('shell','dumpsys','activity','settings'))), 20, 'effective shortened AMS policy')
        armed = True
        arm(e, correlation)
        pos = positive(e, args, correlation); request = pos['request']; report['initialPromotion'] = pos
        e.proof_chain = [dict(pos,promotion=True,terminal='stop')]
        initial_lifetime=ProcessLifetime(request,pos['processIdentity'])
        e.active_lifetime=initial_lifetime
        if args.mode == 'feasibility':
            stop_owned(e,args,correlation,request)
            cleanup_control(e,args,correlation); armed = False
            deadline=time.monotonic()+10
            while time.monotonic()<deadline:
                s, top=e.snapshot()
                e.check_process(initial_lifetime)
                if s or crash_lines(e.negative_raw(),e.owned_pids) or e.top_seen:
                    raise RuntimeError('INCONCLUSIVE: feasibility post-STOP integrity failed')
                time.sleep(2)
            report['verdict'] = verdict(promotion=True, runner=True, teardown=True, background=True, top_seen=e.top_seen)
            report['feasibility'] = report['verdict']
        else:
            def timeout(s, top):
                raw = e.check_process(initial_lifetime)
                proof = timeout_proof(raw,pos,e.system_pid)
                if s is None and proof:
                    return proof
                return None
            report['timeout'] = wait_for(e,timeout,240,'real native dataSync timeout and AMS teardown')
            e.proof_chain[0].update(terminal='timeout',timeout=report['timeout'])
            report['lanes']['R1']='PASS'
            def ready_after_timeout(s, top):
                raw=e.check_process(initial_lifetime)
                js=[m for m in markers(raw,correlation,request['epoch'],request['logPid'],request['attemptId']) if m['marker']=='FG_TIMEOUT_OBSERVED' and m.get('eventType')==9 and m.get('detail',{}).get('fgsType')==1 and str(m.get('detail',{}).get('startId'))==pos['service']['startId']]
                ready=[m for m in markers(raw,correlation,request['epoch']) if m['marker']=='NEXT_ATTEMPT_READY' and m.get('attempt')==2]
                return dict(event=js[-1],next=ready[-1]) if s is None and js and ready else None
            report['timeout'].update(wait_for(e,ready_after_timeout,30,'timeout event delivery and next attempt token'))
            report['initialProcessContinuitySamples']=initial_lifetime.samples
            e.active_lifetime=None
            if args.mode == 'timeout-only':
                report['timeoutObservation']=timeout_observation(e,pos,initial_lifetime,report['timeout'],args.observation_seconds)
                report['lanes'].update(R1='PASS',R3='PASS',R4='PASS')
            else:
                old_identity=pos['service']['identity']
                second_since=e.now_device()
                request=start_action(e,args,correlation,second_since)
                report['deniedRequest']=request; e.owned_pids.add(request['logPid'])
                if request['attemptId']==pos['request']['attemptId'] or request['notificationId']==pos['request']['notificationId']:
                    raise RuntimeError('INCONCLUSIVE: attempt reused')
                lifetime=e.bind_process(request)
                report['denyingProcessIdentity']=lifetime.identity
                instance_witness=[]
                def denied(s, top):
                    if s:
                        instance_witness.append(s)
                        if s['identity']==old_identity:
                            raise RuntimeError('INCONCLUSIVE: old service instance reused')
                    raw=e.check_process(lifetime)
                    ms=markers(raw,correlation,second_since,request['logPid'],request['attemptId'])
                    if any(m['marker']=='START_PROMISE_SETTLED' and m.get('outcome')=='rejected' for m in ms):
                        raise RuntimeError('BLOCKED: synchronous dispatch/display rejection is not Slice D denial')
                    ns=native_denial(raw,request['logPid'],request['notificationId'])
                    return ns[-1] if ns else None
                denial=wait_for(e,denied,45,'native tracked initial PROMOTION_DENIED')
                denial_epoch=float(denial['line'].split()[0])
                e.check_process(lifetime,denial_epoch)
                report['nativeDenial']=denial; report['newServiceSnapshots']=instance_witness
                if not all(denial[k] for k in ['exactException','frameworkPromotion','budgetMessage']):
                    raise RuntimeError('INCONCLUSIVE: exact framework promotion exception/budget evidence incomplete')
                # Previous record was absent before dispatch; native D contract proves this catch only
                # applies to an unpromoted current instance. Sampling may miss a short-lived new record.
                second_raw='\n'.join(line for line in e.negative_raw().splitlines() if re.match(r'^\s*\d+\.\d+',line) and float(line.split()[0])>=second_since)
                created=created_instances(second_raw, request['logPid'])
                fast_path=e.output/'ams-fast.raw'
                if fast_path.exists():
                    error_path=e.output/'ams-fast.stderr.raw'
                    fast=fast_service_records(fast_path.read_text(errors='replace'),request['epoch'],stderr=error_path.read_text(errors='replace') if error_path.exists() else 'missing stderr provenance')
                    for index in range(1,4):
                        path=e.output/f'ams-fast-{index}.raw';error=e.output/f'ams-fast-{index}.stderr.raw'
                        if path.exists():
                            fast.extend(fast_service_records(path.read_text(errors='replace'),request['epoch'],stderr=error.read_text(errors='replace') if error.exists() else 'missing stderr provenance'))
                    instance_witness.extend(s for s in fast if s['pid']==request['logPid'] and s['identity']!=old_identity and s['sampleBegin']<=denial_epoch)
                    report['newServiceSnapshots']=instance_witness
                distinct=any(s['identity']!=old_identity and s['pid']==request['logPid'] for s in instance_witness) or any(token!=old_identity for token in created)
                native_proof=native_instance_proof(e.check_process(initial_lifetime) if request['logPid']==pos['request']['logPid'] and lifetime.identity==initial_lifetime.identity else '',request['logPid'],pos['request']['epoch'],float(report['timeout']['native'][-1].split()[0]),request['epoch'],denial_epoch)
                if native_proof:
                    distinct=True
                    report['nativeInstanceProof']=native_proof
                report['createdServiceIdentities']=created
                if any(s.get('foreground') for s in instance_witness):
                    raise RuntimeError('FAIL: denied attempt had AMS foreground publication')
                if not distinct:
                    raise RuntimeError('INCONCLUSIVE: distinct new instance not observed in AMS snapshots/event log')
                report['newInstanceProof']='distinct native object observed in the same qualified process, old native destruction and AMS teardown before new tracked delivery' if native_proof else 'distinct AMS ServiceRecord token from complete samples or am_create_service'
                e.proof_chain.append(dict(request=request,processIdentity=lifetime.identity,promotion=False,
                    terminal='denial',denial=denial,distinct=distinct,nativeInstanceProof=native_proof,
                    service=dict(identity=next((s['identity'] for s in instance_witness if s['identity']!=old_identity),created[-1] if created else native_proof['identity']),identityKind='AMS-ServiceRecord' if instance_witness or created else native_proof['identityKind'],startId=denial['startId'])))
                report['lanes']['R2']='PASS'
                if denial['stop']!='accepted':
                    raise RuntimeError('INCONCLUSIVE: denied stop '+denial['stop']+'; additional obligations require review')
                wait_for(e,lambda s,top: s is None,15,'denied ServiceRecord teardown')
                deadline=time.monotonic()+args.observation_seconds
                while time.monotonic()<deadline:
                    qualified_raw=e.check_process(lifetime,denial_epoch)
                    s, top=e.snapshot()
                    if e.top_seen:
                        raise RuntimeError('INCONCLUSIVE: TOP during negative lane')
                    raw=qualified_raw
                    denial_epoch=float(denial['line'].split()[0])
                    post_denial='\n'.join(line for line in raw.splitlines() if re.match(r'^\s*\d+\.\d+',line) and float(line.split()[0])>=denial_epoch)
                    crash=crash_attribution(post_denial,request['logPid'])
                    if crash:
                        report['crashAttribution']=crash
                        report['lanes']['R3']=crash['verdict']; report['lanes']['R4']=crash['verdict']
                        raise RuntimeError(crash['verdict']+': '+crash['reason'])
                    if s or foreground_notification(e.last_notifications,java_hash(request['notificationId'])):
                        raise RuntimeError('INCONCLUSIVE: ServiceRecord or denied FGS notification present after accepted stop')
                    if any(m['marker']=='RUNNER_STARTED' for m in markers(raw,correlation,second_since,request['logPid'],request['attemptId'])):
                        report['lanes']['R3']='FAIL'
                        raise RuntimeError('FAIL: denied attempt acquired runner')
                    time.sleep(2)
                e.check_process(lifetime,denial_epoch)
                report['processContinuitySamples']=lifetime.samples
                # Native accepted denial means no state publication under the frozen candidate contract.
                # No private cache API is introduced; AMS/notifications/runner logs corroborate.
                report['lanes'].update(R2='PASS',R3='PASS',R4='PASS')
                report['limitations'].append('No direct core-cache introspection; static frozen contract and runtime publication/runner/AMS evidence combined. Observation window is bounded; OEM-specific longer windows require review.')
            cleanup_control(e,args,correlation); armed=False
            report['verdict']='PASS'
            if args.post_top_recovery:
                e.negative_since=None
                recovery='i82-'+uuid.uuid4().hex
                armed=True; correlation=recovery; request=None
                arm(e,recovery)
                recovery_pos=positive(e,args,recovery); request=recovery_pos['request']
                e.proof_chain=[dict(recovery_pos,promotion=True,terminal='stop')]
                e.receipt_mode='feasibility'
                stop_owned(e,args,recovery,request); cleanup_control(e,args,recovery); armed=False
                report['recovery']=recovery_pos; report['lanes']['R5']='PASS'
    except KeyboardInterrupt:
        report['verdict']='INCONCLUSIVE'; report['error']='interrupted'
    except Exception as error:
        reason=str(error); report['error']=reason
        report['verdict']=reason.split(':',1)[0] if reason.split(':',1)[0] in ['FAIL','BLOCKED','INCONCLUSIVE'] else 'INCONCLUSIVE'
        if args.mode=='full' and report.get('deniedRequest'):
            for lane in ['R3','R4']:
                if report['lanes'][lane]=='NOT_RUN': report['lanes'][lane]=report['verdict']
        if args.mode=='full' and report['lanes']['R2']=='NOT_RUN': report['lanes']['R2']=report['verdict']
    finally:
        report['topSeenDuringNegativeLane']=e.top_seen
        # A failed negative witness stays failed; cleanup itself must still be able to verify STOP.
        e.negative_since=None
        e.active_lifetime=None
        # Cleanup never uses force-stop, global stop-service, data clear or another scenario's STOP.
        if armed:
            try:
                if request is None:
                    candidates=[m for m in markers(e.raw(),correlation,e.negative_since or 0) if m['marker']=='START_REQUESTED']
                    request=candidates[-1] if candidates else None
                if request: stop_owned(e,args,correlation,request)
                cleanup_control(e,args,correlation)
                report['cleanup'].append('owned session/control cleanup verified')
            except BaseException as error:
                owned_cleanup_ok=False
                report['cleanup'].append('BLOCKED owned cleanup: '+str(error))
                if report['verdict']=='PASS': report['verdict']='INCONCLUSIVE'
        if changed:
            try:
                restore_config(e.adb,original)
                if config_value(e.adb('shell','device_config','list','activity_manager'))!=original:
                    raise RuntimeError('configuration restore mismatch')
                report['cleanup'].append('exact configuration presence/value restored')
            except BaseException as error:
                report['cleanup'].append('FAIL restore: '+str(error)); report['lanes']['R6']='FAIL'
                if report['verdict']=='PASS': report['verdict']='INCONCLUSIVE'
        try:
            report['configFinal']=e.adb('shell','device_config','list','activity_manager')
            report['compatFinal']=e.adb('shell','dumpsys','platform_compat')
            e.adb('shell','dumpsys','activity','settings')
            e.adb('shell','dumpsys','activity','exit-info',PACKAGE)
            report['statusAfter']=e.run(['git','status','--short','--branch','-uall'],cwd=ROOT)
            report['frozenHashesAfter']={p:sha(ROOT/p) for p in FROZEN}
            report['headAfter']=e.run(['git','rev-parse','HEAD'],cwd=ROOT).strip()
            report['sourceHashesAfter']={p:sha(ROOT/p) for p in report.get('sourceHashes',{})}
            if report.get('head')==report['headAfter'] and report.get('sourceHashes')==report['sourceHashesAfter'] and report.get('statusBefore')==report['statusAfter'] and report['frozenHashesAfter']==FROZEN and report.get('compatBefore')==report['compatFinal'] and config_saved and config_value(report['configFinal'])==original and owned_cleanup_ok and report['lanes']['R6']!='FAIL':
                report['lanes']['R6']='PASS'
            elif not owned_cleanup_ok and report['lanes']['R6']!='FAIL':
                report['lanes']['R6']='INCONCLUSIVE'
        except BaseException as error:
            report['cleanup'].append('final evidence error: '+str(error))
        try:
            e.stop_collector()
        except BaseException as error:
            report['cleanup'].append('collector termination error: '+str(error))
            if report['verdict']=='PASS': report['verdict']='INCONCLUSIVE'
        if report['lanes']['R6']!='PASS' and report['verdict']=='PASS': report['verdict']='INCONCLUSIVE'
        report['ended']=utc()
        if getattr(e,'receipt_signer',None): e.receipt_signer.close()
        report['limitations'].append('Host-signed diagnostic receipts use a smoke-only DUMP-protected receiver and a live React context; the serialized consumer retains signature, correlation, process, quiescence, ledger and monouse checks. Unknown obligations remain BLOCKED.')
        report['limitations'].append('Restoring device_config does not reset consumed AMS runtime budget.')
        (e.output/'report.json').write_text(json.dumps(report,indent=2))
        (e.output/'SHA256.txt').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in sorted(e.output.iterdir()) if p.is_file() and p.name!='SHA256.txt'))
        print(json.dumps(dict(verdict=report['verdict'],lanes=report['lanes'],report=str(e.output/'report.json')),indent=2))
    return 0 if report['verdict']=='PASS' else 2


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--apk',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True,help='new directory outside repository')
    p.add_argument('--mode',choices=['feasibility','full','timeout-only'],default='feasibility')
    p.add_argument('--manual-actions',action='store_true',help='operator taps real Android shade UI; waits remain bounded')
    p.add_argument('--authorize-budget-change',action='store_true')
    p.add_argument('--feasibility-report',type=Path)
    p.add_argument('--post-top-recovery',action='store_true')
    p.add_argument('--observation-seconds',type=int,default=60)
    args=p.parse_args()
    if ROOT==args.output.resolve() or ROOT in args.output.resolve().parents:
        p.error('evidence output must be outside repository')
    if not 30<=args.observation_seconds<=180:
        p.error('observation-seconds must be 30..180; qualify platform crash windows independently')
    if args.mode in ['full','timeout-only'] and (not args.authorize_budget_change or not args.feasibility_report):
        p.error('full/timeout-only require --authorize-budget-change and --feasibility-report')
    if args.mode=='timeout-only' and not args.post_top_recovery:
        p.error('timeout-only requires --post-top-recovery to qualify subsequent START/STOP')
    return execute(args)


if __name__=='__main__':
    raise SystemExit(main())
