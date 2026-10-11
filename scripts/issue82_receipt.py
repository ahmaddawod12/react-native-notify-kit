"""Ephemeral host authority for smoke diagnostic receipts, not an Android qualification."""
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import uuid


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def encode(value):
    return base64.urlsafe_b64encode(canonical(value).encode()).decode().rstrip('=')


def notification_number(text):
    value = 0
    for char in text:
        value = (31*value + ord(char)) & 0xffffffff
    return str(value if value < 0x80000000 else value-0x100000000)


class ReceiptSigner:
    def __init__(self, binding):
        self.folder = tempfile.TemporaryDirectory(prefix='issue82-signing-')
        self.key = Path(self.folder.name)/'key.pem'
        subprocess.run(['openssl', 'genrsa', '-out', str(self.key), '2048'],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        os.chmod(self.key, 0o600)
        modulus = subprocess.check_output(['openssl', 'rsa', '-in', str(self.key), '-noout', '-modulus']).decode().strip().split('=', 1)[1].lower()
        self.context = dict(binding, version=1, sessionId=uuid.uuid4().hex, modulus=modulus)
        self.used = set()

    def sign(self, body):
        identity = (body['correlationId'], body['epoch'])
        if identity in self.used:
            raise RuntimeError('BLOCKED: receipt epoch already consumed by host')
        payload = canonical(body)
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)],
                                   input=payload.encode(), stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, check=True).stdout.hex()
        self.used.add(identity)
        return encode(dict(payload=payload, signature=signature))

    def close(self):
        self.folder.cleanup()


def certify(e, correlation, quiesced, markers, mode):
    """Consumes actual driver proof chain; absence alone can never enter this path."""
    d = quiesced.get('diagnostic', {})
    context = e.receipt_signer.context
    ledger = d.get('ledger', [])
    if mode not in ['feasibility','full','timeout-only']:
        raise RuntimeError('BLOCKED: unknown receipt mode')
    expected_attempt = len(ledger)+1 if mode == 'timeout-only' else len(ledger)
    if d.get('session') != context or d.get('phase') != 'quiesced' or not d.get('epoch') or \
       d.get('consumerRunId') != quiesced.get('processRunId') or quiesced.get('appState') != 'background' or \
       len(ledger) != len(e.proof_chain) or not ledger or expected_attempt != quiesced['attempt'] or \
       quiesced['correlationId'] != correlation:
        raise RuntimeError('BLOCKED: unqualified quiescence/ledger/session')
    if e.adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').strip() != context['bootId'] or \
       e.adb('shell', 'getprop', 'ro.build.fingerprint').strip() != context['build']:
        raise RuntimeError('BLOCKED: receipt device incarnation changed')
    if e.services() is not None:
        raise RuntimeError('BLOCKED: receipt requires subsequent VERIFIED_ABSENT')
    proofs = []
    for i, (entry, chain) in enumerate(zip(ledger, e.proof_chain)):
        request, process = chain['request'], chain['processIdentity']
        if entry.get('attemptId') != request['attemptId'] or entry.get('notificationId') != request['notificationId'] or \
           entry.get('processRunId') != request['processRunId'] or entry.get('dispatch') != 'resolved' or \
           request['correlationId'] != correlation or request['attempt'] != i+1 or \
           process['bootId'] != context['bootId'] or process['uid'] != context['uid'] or process['pid'] != request['logPid'] or \
           not any(m['marker']=='START_PROMISE_SETTLED' and m.get('outcome')=='resolved' and
                   m['attemptId']==request['attemptId'] and m['logPid']==request['logPid'] and
                   m['processRunId']==request['processRunId'] for m in markers):
            raise RuntimeError('BLOCKED: unknown dispatch obligation')
        # Revalidate every applicable original incarnation immediately before signing.
        lifetime = e.bind_process(request)
        if lifetime.identity != process:
            raise RuntimeError('BLOCKED: receipt process birth/owner mismatch')
        e.check_process(lifetime)
        terminal = chain.get('terminal')
        service = chain['service']
        if not service.get('identity') or not re.fullmatch(r'[1-9]\d*', str(service.get('startId', ''))):
            raise RuntimeError('BLOCKED: ServiceRecord incarnation unqualified')
        if terminal == 'stop':
            if not entry.get('stopResolved') or entry.get('terminal') != 'stop' or not any(
                m['marker']=='STOP_RESOLVED' and m['attemptId']==request['attemptId'] and
                m['logPid']==request['logPid'] and m['processRunId']==request['processRunId'] and
                m['epoch'] >= chain.get('stopSince', float('inf')) for m in markers):
                raise RuntimeError('BLOCKED: attributed STOP unavailable')
        elif terminal == 'timeout':
            if entry.get('terminal') != 'timeout' or not chain.get('timeout'):
                raise RuntimeError('BLOCKED: native timeout unproved')
            if mode == 'timeout-only' and (len(ledger) != 1 or not chain.get('promotion') or
                not chain['timeout'].get('framework') or not chain['timeout'].get('native') or
                chain['timeout'].get('demotion') != 'returned' or chain['timeout'].get('stop') != 'accepted'):
                raise RuntimeError('BLOCKED: isolated real promoted framework timeout unproved')
        elif terminal == 'denial':
            if service.get('identityKind') == 'app-owned-native-instance':
                proof=chain.get('nativeInstanceProof') or {}
                if proof.get('identity')!=service['identity'] or proof.get('identityKind')!='app-owned-native-instance' or not proof.get('absent') or str(proof.get('new',{}).get('pid'))!=request['logPid']:
                    raise RuntimeError('BLOCKED: native object identity provenance incomplete')
            denial = chain.get('denial', {})
            if not all(denial.get(k) for k in ['exactException', 'frameworkPromotion', 'budgetMessage']) or \
               denial.get('stop') != 'accepted' or not chain.get('distinct') or entry.get('terminal') != 'none':
                raise RuntimeError('BLOCKED: denied obligation unproved')
        else:
            raise RuntimeError('BLOCKED: native termination unproved')
        if mode == 'feasibility' and (len(ledger) != 1 or terminal != 'stop' or not chain.get('promotion')):
            raise RuntimeError('BLOCKED: feasibility needs promoted session and attributed STOP')
        if mode == 'full' and (len(ledger) != 2 or terminal != ['timeout', 'denial'][i]):
            raise RuntimeError('BLOCKED: full receipt must cover timeout and denial distinctly')
        if mode == 'timeout-only' and terminal != 'timeout':
            raise RuntimeError('BLOCKED: timeout-only receipt cannot certify STOP or denial')
        if chain.get('promotion') and (service.get('type') != 1 or service.get('foreground') is not True or
            service.get('pid') != process['pid'] or service.get('user') != '0' or
            service.get('notificationId') != notification_number(request['notificationId']) or
            any(chain.get('runner',{}).get(k) != request[k] for k in ['attemptId','processRunId','logPid'])):
            raise RuntimeError('BLOCKED: real matching promotion proof unavailable')
        proofs.append(dict(attemptId=entry['attemptId'], notificationId=entry['notificationId'],
            processRunId=entry['processRunId'], process={k:process[k] for k in ['pid','starttime','bootId','uid']},
            service=dict(identity=service['identity'], identityKind=service.get('identityKind','AMS-ServiceRecord'), startId=service['startId'], notificationId=entry['notificationId']),
            promotion=bool(chain.get('promotion')), terminal=terminal, stopResolved=entry.get('stopResolved'),
            denial='tracked_initial_accepted' if terminal=='denial' else None,
            absent='VERIFIED_ABSENT', evidenceHash=hashlib.sha256(canonical(chain).encode()).hexdigest()))
    if e.services() is not None:
        raise RuntimeError('BLOCKED: target reappeared during certification')
    e.active_lifetime = None
    now = int(e.now_device()*1000)
    return dict(version=1, session=context, correlationId=correlation,
                attemptId=quiesced['attemptId'], controlNonce=quiesced['attemptId'].split(':')[2],
                epoch=d['epoch'], consumerRunId=d['consumerRunId'], ledger=ledger,
                issuedAt=now, expiresAt=now+60000, proofs=proofs)
