import importlib.util
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('driver82', ROOT / 'scripts/smoke-test-82-android-datasync-budget.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

class DriverTests(unittest.TestCase):
    def test_marker_provenance(self):
        line = '100.1 123 456 I ReactNativeJS: ISSUE82:START_REQUESTED {"correlationId":"run","attemptId":"run:1:n","notificationId":"issue82-fgs-run-1-n","processRunId":"p","appState":"background","fgsType":1,"smokeScenario":"issue82-datasync-budget"}'
        self.assertEqual(len(d.markers(line, 'run', since=99, pid='123', attempt_id='run:1:n')), 1)
        for kw in [dict(since=101), dict(pid='999'), dict(attempt_id='run:2:n')]:
            self.assertEqual(d.markers(line, 'run', **kw), [])
        self.assertEqual(d.markers(line, 'foreign'), [])
    def test_no_promise_only_pass_and_top_is_invalidation(self):
        self.assertEqual(d.verdict(promise=True), 'INCONCLUSIVE')
        self.assertEqual(d.verdict(promotion=True, runner=True, teardown=True, background=True), 'PASS')
        self.assertEqual(d.verdict(promotion=True, runner=True, teardown=True, background=True, top_seen=True), 'INCONCLUSIVE')
        self.assertEqual(d.verdict(dispatch_rejected=True), 'BLOCKED')
        self.assertEqual(d.verdict(crash=True, denial=False), 'INCONCLUSIVE')
        self.assertEqual(d.verdict(crash=True, denial=True), 'FAIL')
    def test_exact_config_restore(self):
        calls = []
        for original, expected in [(None, ['delete', 'activity_manager', d.KEY]), ('90000', ['put', 'activity_manager', d.KEY, '90000'])]:
            d.restore_config(lambda *args: calls.append(list(args)), original)
            self.assertEqual(calls[-1], ['shell', 'device_config'] + expected)
        self.assertEqual(d.config_value('other=x\n'), None)
        self.assertEqual(d.config_value(d.KEY + '=null\n'), 'null')
    def test_service_parser(self):
        raw = 'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n  * ServiceRecord{abc u0 com.notifeeexample/app.notifee.core.ForegroundService}\n isForeground=true foregroundId=123 foregroundServiceType=0x00000001\n app=ProcessRecord{a 321:com.notifeeexample/u0a1}\n lastStartId=1\n'
        s = d.service(raw)
        self.assertEqual(s['identity'], 'abc'); self.assertEqual(s['pid'], '321')
        self.assertTrue(s['foreground']); self.assertEqual(s['type'], 1)
        self.assertIsNone(d.service('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)\n'))
    def test_interruption_finally_restores_and_reports(self):
        seen=[]
        with self.assertRaises(KeyboardInterrupt):
            with d.configuration(lambda *args: seen.append(args), '123', True):
                raise KeyboardInterrupt()
        self.assertEqual(seen[-1], ('shell', 'device_config', 'put', 'activity_manager', d.KEY, '123'))
    def test_no_synthetic_trigger_or_unbounded_wait(self):
        source = (ROOT / 'scripts/smoke-test-82-android-datasync-budget.py').read_text()
        self.assertEqual(source.count("'broadcast'"), 1); self.assertIn("PACKAGE+'.ISSUE82_SIGNED_RECEIPT'", source); self.assertNotIn('PendingIntent.send', source)
        self.assertIn('deadline', source); self.assertIn('finally:', source)


class ProvenanceTests(unittest.TestCase):
    def test_top_numeric_unknown_fail_closed(self):
        self.assertTrue(d.uid_is_top('2 (TOP)\n'))
        self.assertTrue(d.uid_is_top('123: 3 (BOUND_TOP)'))
        self.assertFalse(d.uid_is_top('20 (NONEXISTENT)'))
        with self.assertRaises(RuntimeError): d.uid_is_top('shell error')
        with self.assertRaises(RuntimeError): d.uid_is_top('')
    def test_new_instance_and_effective_windows(self):
        self.assertEqual(d.created_instances('101.1 1000 23 I am_create_service: [0,255,app.notifee.core.ForegroundService,11794,123]', '123'), ['ff'])
        self.assertEqual(d.created_instances('101.1 1000 23 I am_create_service: [0,255,app.notifee.core.ForegroundService,11794,999]', '123'), [])
        settings='service_start_foreground_timeout_ms=30000\nservice_start_foreground_anr_delay_ms=10000\nfgs_crash_extra_wait_duration=10000\n'
        self.assertEqual(d.observation_window(settings), 60)
        with self.assertRaises(RuntimeError): d.observation_window('')
    def test_native_marker_is_not_exception_or_framework_proof(self):
        raw='100.1 123 23 W NOTIFEE: (ForegroundService): Tracked START PROMOTION_DENIED (cause=UNKNOWN, requestId=aaaa-1111, notificationId=fg, api=37, type=1, generation=0, runGeneration=1, startId=1, flags=0, stop=accepted).'
        self.assertFalse(d.native_denial(raw,'123','fg')[0]['frameworkPromotion'])
        self.assertEqual(d.native_denial(raw,'999','fg'), [])
        self.assertEqual(d.native_denial(raw,'123','foreign'), [])
        raw+='\n100.2 123 23 W NOTIFEE: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync\n100.2 123 23 W NOTIFEE: at android.app.Service.startForeground(Service.java:1)'
        witness=d.native_denial(raw,'123','fg')[0]
        self.assertTrue(witness['exactException']); self.assertTrue(witness['frameworkPromotion']); self.assertTrue(witness['budgetMessage'])
    def test_notification_history_and_foreign_pid_crash_not_witnesses(self):
        raw='  Notification List:\n    NotificationRecord(pkg=com.notifeeexample id=123 flags=FOREGROUND_SERVICE)\n  Notification History:\n    NotificationRecord(pkg=com.notifeeexample id=456 flags=FOREGROUND_SERVICE)\n'
        self.assertTrue(d.foreground_notification(raw,123)); self.assertFalse(d.foreground_notification(raw,456))
        raw='100.1 1000 1 I am_proc_start: [0,123,999,other,activity,x]\n100.2 123 23 E AndroidRuntime: FATAL EXCEPTION: main'
        self.assertEqual(d.crash_lines(raw,{'123'}), [])
    def test_real_ui_selector_rejects_foreign_notification_row(self):
        xml='<hierarchy><node resource-id="x/notification_stack_scroller"><node><node text="Issue 82 run"/><node text="Start dataSync FGS" bounds="[10,20][30,40]"/></node><node><node text="Issue 82 other"/><node text="Start dataSync FGS" bounds="[50,60][70,80]"/></node></node></hierarchy>'
        self.assertEqual(d.action_coordinates(xml,'run','Start dataSync FGS'),(20,30))
        with self.assertRaises(RuntimeError): d.action_coordinates(xml,'foreign','Start dataSync FGS')



class ExecutionCleanupTests(unittest.TestCase):
    def test_partial_remote_put_interruption_restores_and_writes_report(self):
        from argparse import Namespace
        from tempfile import TemporaryDirectory
        from unittest.mock import patch
        import zipfile
        import json
        for original in [None, '123']:
            with self.subTest(original=original), TemporaryDirectory() as folder:
                base=Path(folder); apk=base/'fake.apk'
                with zipfile.ZipFile(apk,'w') as archive:
                    archive.writestr('assets/index.android.bundle',b'issue82-datasync-budget')
                    archive.writestr('classes.dex',b'Tracked START PROMOTION_DENIED')
                commands=[]
                class FakeEvidence(d.Evidence):
                    def services(self): return d.service('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)\n')
                    value=original
                    def run(self,args,**kw):
                        commands.append(tuple(args))
                        if args[:3]==['git','rev-parse','HEAD']: return d.HEAD+'\n'
                        if args[:2]==['git','branch']: return 'main\n'
                        if args[:2]==['git','status']: return 'same status\n'
                        if 'badging' in args: return "package: name='com.notifeeexample'\ntargetSdkVersion:'36'"
                        if 'xmltree' in args: return 'name="app.notifee.core.ForegroundService"\nexported=false\nforegroundServiceType=0x00000001'
                        return ''
                    def adb(self,*args,**kw):
                        commands.append(args)
                        if args==('shell','cat','/proc/sys/kernel/random/boot_id'): return 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
                        if args==('shell','getprop','ro.build.version.sdk'): return '37'
                        if args==('shell','getprop','ro.build.fingerprint'): return 'fingerprint'
                        if args[:3]==('shell','dumpsys','package'): return 'appId=11794'
                        if args[:3]==('shell','device_config','list'): return '' if self.value is None else d.KEY+'='+self.value+'\n'
                        if args[:3]==('shell','device_config','put'):
                            self.value=args[-1]
                            if args[-1]=='180000': raise KeyboardInterrupt()
                        if args[:3]==('shell','device_config','delete'): self.value=None
                        if args[:3]==('shell','dumpsys','platform_compat'): return 'compat unchanged'
                        if args==('shell','dumpsys','activity','settings'): return 'service_start_foreground_timeout_ms=30000\nservice_start_foreground_anr_delay_ms=10000\nfgs_crash_extra_wait_duration=10000'
                        if args==('shell','date','+%s'): return str(int(d.time.time()))
                        if args[:3]==('shell','pm','path'): return 'package:/data/base.apk'
                        if args[0]=='pull': Path(args[-1]).write_bytes(apk.read_bytes())
                        return ''
                    def start_collector(self): self.log.write_text('')
                    def stop_collector(self): commands.append(('collector-stopped',))
                def mock_sha(path):
                    for rel, hash_value in d.FROZEN.items():
                        if str(path).endswith(rel): return hash_value
                    return 'hash'
                paths=d.SOURCE_PATHS
                gate=base/'gate.json';gate.write_text(json.dumps(dict(mode='feasibility',verdict='PASS',serial='fake',bootId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',uid=11794,fingerprint='fingerprint',apkHash='hash',sourceHashes={p:'hash' for p in paths})))
                args=Namespace(output=base/'evidence',serial='fake',apk=apk,mode='full',authorize_budget_change=True,feasibility_report=gate,observation_seconds=60,manual_actions=False,post_top_recovery=False)
                with patch.object(d,'Evidence',FakeEvidence), patch.object(d,'sha',mock_sha):
                    self.assertEqual(d.execute(args),2)
                report=json.loads((args.output/'report.json').read_text())
                self.assertEqual(report['verdict'],'INCONCLUSIVE')
                self.assertEqual(report['error'],'interrupted')
                expected=('shell','device_config','delete','activity_manager',d.KEY) if original is None else ('shell','device_config','put','activity_manager',d.KEY,original)
                self.assertIn(expected,commands)
                self.assertIn(('collector-stopped',),commands)
                self.assertEqual(report['configOriginal']['present'],original is not None)
                self.assertTrue((args.output/'SHA256.txt').exists())



class FailClosedParsingTests(unittest.TestCase):
    def test_unknown_and_duplicate_service_records_do_not_mean_absent(self):
        with self.assertRaises(RuntimeError): d.service('unknown ServiceRecord app.notifee.core.ForegroundService')
        block='* ServiceRecord{abc u0 com.notifeeexample/app.notifee.core.ForegroundService}\n'
        with self.assertRaises(RuntimeError): d.service(block+block.replace('abc','def'))
    def test_button_cannot_bind_through_other_notification_sibling(self):
        xml='<hierarchy><node><node><node text="Issue 82 run"/></node><node><node text="Issue 82 foreign"/><node text="Start dataSync FGS" bounds="[10,20][30,40]"/></node></node></hierarchy>'
        with self.assertRaises(RuntimeError): d.action_coordinates(xml,'run','Start dataSync FGS')
    def test_invalid_marker_identity_cannot_authorize_work(self):
        base='100.1 123 456 I ReactNativeJS: ISSUE82:START_REQUESTED {"correlationId":"run","attemptId":"run:1:n","notificationId":"issue82-fgs-run-1-n","processRunId":"p","appState":"background","fgsType":1,"smokeScenario":"issue82-datasync-budget"}'
        self.assertEqual(len(d.markers(base,'run')),1)
        for old,new in [('issue82-fgs-run-1-n','foreign'),('issue82-datasync-budget','foreign'),('run:1:n','foreign:1:n')]:
            self.assertEqual(d.markers(base.replace(old,new),'run'),[])



class CrashAttributionTests(unittest.TestCase):
    def test_post_timeout_crash_is_not_initial_denial_failure(self):
        raw='100.1 123 2 E AndroidRuntime: FATAL EXCEPTION: main\n100.1 123 2 E AndroidRuntime: android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException: app.notifee.core.ForegroundService'
        self.assertEqual(d.crash_attribution(raw,'123')['verdict'],'INCONCLUSIVE')
    def test_unrelated_crash_and_causal_contract_crash_are_separate(self):
        raw='100.1 123 2 E AndroidRuntime: FATAL EXCEPTION: main\n100.1 123 2 E AndroidRuntime: unrelated application failure'
        self.assertEqual(d.crash_attribution(raw,'123')['verdict'],'INCONCLUSIVE')
        raw+='\n100.1 123 2 E AndroidRuntime: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: app.notifee.core.ForegroundService'
        self.assertEqual(d.crash_attribution(raw,'123')['verdict'],'FAIL')
        self.assertIsNone(d.crash_attribution(raw,'999'))




class CorrectiveRedTests(unittest.TestCase):
    def test_unknown_ams_never_means_absent(self):
        for raw in ['', "Can't find service: activity\n", 'Permission Denial: DUMP required\n',
                    'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n']:
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                d.service(raw)

    def test_stop_owned_unknown_ams_does_not_complete(self):
        from tempfile import TemporaryDirectory
        from argparse import Namespace
        class InvalidAMS(d.Evidence):
            def services(self): return d.service('Permission Denial: DUMP required\n')
            def adb(self, *args, **kw):
                if args[:4] == ('shell','dumpsys','activity','services'): return 'Permission Denial: DUMP required\n'
                if args[:4] == ('shell','cmd','activity','get-uid-state'): return '10 (SERVICE)\n'
                return ''
        with TemporaryDirectory() as folder:
            e=InvalidAMS(Path(folder)/'out','fake'); e.uid=11794
            with self.assertRaises(RuntimeError):
                d.stop_owned(e,Namespace(),'run',dict(logPid='123',notificationId='fg'))

import json, zipfile, tempfile, os
from argparse import Namespace
from unittest.mock import patch
class Fake(d.Evidence):
 def services(self):return d.service('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)\n')
 def process_identity(self,pid):return dict(pid=pid,package=d.PACKAGE,processName=d.PACKAGE,uid=self.uid,bootId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',starttime='50',birthEpochUpper=90)
 def run(self,args,**kw):
  if args[:3]==['git','rev-parse','HEAD']:return d.HEAD
  if args[:2]==['git','branch']:return 'main'
  if args[:2]==['git','status']:return 'unchanged'
  if 'badging' in args:return "package: name='com.notifeeexample'\ntargetSdkVersion:'36'"
  if 'xmltree' in args:return 'name="app.notifee.core.ForegroundService"\nexported=false\nforegroundServiceType=0x00000001'
  return ''
 def adb(self,*args,**kw):
  if args==('shell','cat','/proc/sys/kernel/random/boot_id'):return 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
  if args==('shell','getprop','ro.build.version.sdk'):return '36'
  if args==('shell','getprop','ro.build.fingerprint'):return 'device'
  if args[:3]==('shell','dumpsys','package'):return 'appId=11794'
  if args[:3]==('shell','device_config','list'):return '' if getattr(self,'value',None) is None else d.KEY+'='+self.value+'\n'
  if args[:3]==('shell','device_config','put'):self.value=args[-1]
  if args[:3]==('shell','device_config','delete'):self.value=None
  if args[:3]==('shell','dumpsys','platform_compat'):return 'compat'
  if args==('shell','dumpsys','activity','settings'):return 'service_start_foreground_timeout_ms=30000\nservice_start_foreground_anr_delay_ms=10000\nfgs_crash_extra_wait_duration=10000\n'+d.KEY+'=180000'
  if args==('shell','date','+%s'):return str(int(d.time.time()))
  if args[:3]==('shell','pm','path'):return 'package:/data/base.apk'
  if args[0]=='pull':Path(args[-1]).write_bytes(self.apk.read_bytes())
  if args==('shell','pidof',d.PACKAGE):return '123'
  return ''
 def now_device(self):return 104
 def snapshot(self):self.last_notifications='  Notification List:\n';return None,False
 def start_collector(self):pass
 def stop_collector(self):pass
 def raw(self):
  c=self.c
  def mark(t,name,a,**extra):
   data=dict(smokeScenario='issue82-datasync-budget',correlationId=c,fgsType=1,attempt=a,attemptId=f'{c}:{a}:n{a}',notificationId=f'issue82-fgs-{c}-{a}-n{a}',processRunId='process',appState='background',**extra)
   return f'{t}.0 123 2 I ReactNativeJS: ISSUE82:{name} '+json.dumps(data)
  raw='\n'.join(['102.0 123 2 E NOTIFEE: Foreground service timed out (startId=1, type=1)',mark(103,'FG_TIMEOUT_OBSERVED',1,eventType=9,detail=dict(fgsType=1,startId=1)),mark(103,'NEXT_ATTEMPT_READY',2),mark(104,'BACKGROUND_ACTION_RECEIVED',2,action='issue82-start',source='background-handler'),mark(104,'START_REQUESTED',2),f'105.0 123 2 W NOTIFEE: Tracked START PROMOTION_DENIED (cause=UNKNOWN, requestId=aaaa-1111, notificationId=issue82-fgs-{c}-2-n2, api=36, type=1, generation=0, runGeneration=1, startId=1, flags=0, stop=accepted)', '105.0 123 2 W NOTIFEE: android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync','105.0 123 2 W NOTIFEE: at android.app.Service.startForeground(Service.java:1)','104.0 1000 2 I am_create_service: [0,255,app.notifee.core.ForegroundService,11794,123]','106.0 1000 2 I am_proc_died: [0,123,com.notifeeexample,10,0]','107.0 1000 2 I am_proc_start: [0,123,11794,com.notifeeexample,service,com.notifeeexample/app.notifee.core.ReceiverService]'])
  return raw if getattr(self,'second',False) else '\n'.join(line for line in raw.splitlines() if float(line.split()[0])<104)
def arm(e,c):e.c=c;e.negative_since=100

original_start_action=d.start_action
def start_action_phase(e,args,c,since):
 e.second=True
 return original_start_action(e,args,c,since)

def positive(e,args,c):
 return dict(request=dict(attemptId=f'{c}:1:n1',notificationId=f'issue82-fgs-{c}-1-n1',logPid='123',epoch=101,processRunId='process',correlationId=c),service=dict(identity='abc',startId='1'),processIdentity=identity82())

class ExecuteLifetimeTests(unittest.TestCase):
    def test_execute_rejects_death_same_pid_same_package(self):
        clock=[0]
        def monotonic(): clock[0]+=1; return clock[0]
        with tempfile.TemporaryDirectory() as folder:
            base=Path(folder); apk=base/'fake.apk'; Fake.apk=apk
            with zipfile.ZipFile(apk,'w') as z:
                z.writestr('assets/index.android.bundle','issue82-datasync-budget')
                z.writestr('classes.dex','Tracked START PROMOTION_DENIED')
            source={p:d.sha(ROOT/p) for p in d.SOURCE_PATHS}
            gate=base/'gate.json'; gate.write_text(json.dumps(dict(mode='feasibility',verdict='PASS',serial='fake',bootId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',uid=11794,fingerprint='device',apkHash=d.sha(apk),sourceHashes=source)))
            args=Namespace(output=base/'out',serial='fake',mode='full',apk=apk,observation_seconds=60,authorize_budget_change=True,feasibility_report=gate,post_top_recovery=False,manual_actions=True)
            with patch.object(d,'Evidence',Fake), patch.object(d,'arm',arm), patch.object(d,'positive',positive), patch.object(d,'start_action',start_action_phase), patch.object(d,'tap',lambda *a:None), patch.object(d,'cleanup_control',lambda *a:None), patch.object(d.time,'monotonic',monotonic), patch.object(d.time,'sleep',lambda *a:None):
                code=d.execute(args)
            report=json.loads((args.output/'report.json').read_text())
            self.assertNotEqual(report['verdict'],'PASS',report)
            self.assertIn('qualified process died',report['error'])
            self.assertNotEqual(report['lanes']['R3'],'PASS',report)
            self.assertNotEqual(report['lanes']['R4'],'PASS',report)
            self.assertEqual(code,2)



AMS_EMPTY = 'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)\n'
AMS_TARGET = ('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n'
              '  * ServiceRecord{abc u0 com.notifeeexample/app.notifee.core.ForegroundService}\n'
              '    isForeground=true foregroundId=123 foregroundServiceType=0x00000001\n'
              '    app=ProcessRecord{a 123:com.notifeeexample/u0a1}\n    lastStartId=1\n')

def request82():
    return dict(logPid='123',epoch=104.,correlationId='run',attemptId='run:2:n2',
                notificationId='issue82-fgs-run-2-n2',processRunId='process')

def identity82(**changes):
    return dict(dict(pid='123',package=d.PACKAGE,processName=d.PACKAGE,uid=11794,
                     bootId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',starttime='50',birthEpochUpper=90),**changes)

class AMSObservationTests(unittest.TestCase):
    def test_explicit_three_classes_and_all_pending_sections(self):
        self.assertEqual(d.observe_services(AMS_EMPTY)['status'],'VERIFIED_ABSENT')
        self.assertEqual(d.observe_services(AMS_TARGET)['status'],'PRESENT_TARGET')
        foreign=AMS_TARGET.replace(d.COMPONENT,'com.notifeeexample/app.OtherService')
        self.assertEqual(d.observe_services(foreign)['status'],'VERIFIED_ABSENT')
        for section,prefix in [('Pending services','Pending '),('Restarting services','Restarting '),
                               ('Destroying services','Destroy '),('User 0 delayed start services','Delayed start '),
                               ('User 0 starting in background','Starting bg ')]:
            with self.subTest(section=section):
                raw=AMS_TARGET.replace('User 0 active services',section).replace('* ServiceRecord','* '+prefix+'ServiceRecord')
                self.assertEqual(d.observe_services(raw)['status'],'PRESENT_TARGET')
        for raw in ['', 'diagnostic\n', AMS_TARGET[:-4], AMS_TARGET.replace('ServiceRecord{','UnknownRecord{'),
                    AMS_TARGET.replace('lastStartId=1','unparsed'), AMS_EMPTY+'Permission Denial\n',
                    'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n']:
            with self.subTest(raw=raw): self.assertEqual(d.observe_services(raw)['status'],'UNKNOWN')
        for kw in [dict(exit_code=1),dict(stderr='Permission Denial'),dict(complete=False)]:
            self.assertEqual(d.observe_services(AMS_EMPTY,**kw)['status'],'UNKNOWN')

    def test_transport_completion_exit_stderr_and_raw_provenance(self):
        import subprocess
        for output,code,error,complete in [(AMS_EMPTY,0,b'',True),(AMS_EMPTY,1,b'',True),
                                          (AMS_EMPTY,0,b'error',True),(AMS_EMPTY,0,b'',False),
                                          ('',0,b'',True),('Permission Denial\n',0,b'',True)]:
            with self.subTest(output=output,code=code,error=error,complete=complete), tempfile.TemporaryDirectory() as folder:
                e=d.Evidence(Path(folder)/'out','fake')
                def run(command,**kw):
                    token=__import__('re').search(r'ISSUE82_AMS_END_[a-f0-9]+',command[-1])[0]
                    stdout=output+('\n'+token+':0\n' if complete else '')
                    return subprocess.CompletedProcess(command,code,stdout.encode(),error)
                with patch.object(d.subprocess,'run',run):
                    if output==AMS_EMPTY and not code and not error and complete:
                        self.assertIsNone(e.services())
                    else:
                        with self.assertRaises(RuntimeError): e.services()
                saved=json.loads((e.output/'ams-observations.jsonl').read_text())
                self.assertIn(saved['status'],['VERIFIED_ABSENT','UNKNOWN'])
                self.assertEqual(saved['provenance']['exit'],code)
                self.assertTrue((e.output/saved['provenance']['stdout']).exists())
                self.assertTrue((e.output/saved['provenance']['stderr']).exists())

    def test_foreign_record_never_authorizes_stop(self):
        class Foreign(d.Evidence):
            def snapshot(self): return dict(pid='999',notificationId='123',user='0'),False
        with tempfile.TemporaryDirectory() as folder:
            e=Foreign(Path(folder)/'out','fake')
            with patch.object(d,'tap') as tap:
                with self.assertRaisesRegex(RuntimeError,'ownership'):
                    d.stop_owned(e,Namespace(),'run',request82())
                tap.assert_not_called()

    def test_verified_absence_is_not_a_stop_acknowledgement(self):
        class Absent(d.Evidence):
            def snapshot(self): return None,False
        with tempfile.TemporaryDirectory() as folder:
            e=Absent(Path(folder)/'out','fake')
            with patch.object(d,'tap') as tap:
                self.assertEqual(d.stop_owned(e,Namespace(),'run',request82())['outcome'],'already_absent_verified')
                tap.assert_not_called()

class ProcessBirthTests(unittest.TestCase):
    def test_stat_comm_and_missing_starttime(self):
        raw='123 (name (with) spaces) S '+' '.join(['0']*18+['50'])
        self.assertEqual(d.proc_starttime(raw,'123'),'50')
        for bad in ['',raw.replace('50','0'),raw.replace('123','999',1),raw[:-3]]:
            with self.assertRaises(RuntimeError): d.proc_starttime(bad,'123')

    def test_stable_identity_and_irreversible_invalidation(self):
        tracker=d.ProcessLifetime(request82(),identity82())
        self.assertEqual(tracker.check(identity82(),''),'')
        for changed in [identity82(pid='124'),identity82(starttime='51'),None]:
            with self.subTest(changed=changed):
                tracker=d.ProcessLifetime(request82(),identity82())
                with self.assertRaisesRegex(RuntimeError,'identity changed'): tracker.check(changed,'')
                with self.assertRaises(RuntimeError): tracker.check(identity82(),'')

    def test_birth_after_admission_and_no_birth_identifier_cannot_qualify(self):
        for identity in [identity82(starttime=None),identity82(bootId=None),identity82(birthEpochUpper=105)]:
            with self.assertRaises(RuntimeError): d.ProcessLifetime(request82(),identity)

    def test_lifecycle_death_restart_ambiguity_and_stale_logs(self):
        died='106.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]'
        start='107.0 1000 1 I am_proc_start: [0,123,11794,com.notifeeexample,service,x]'
        for raw in [died,start,died+'\n'+start,'bad '+died.split(' ',1)[1],
                    '106.0 1000 1 I am_proc_died: [malformed,123,com.notifeeexample]']:
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                d.ProcessLifetime(request82(),identity82()).check(identity82(),raw)
        previous=died.replace('106.0','100.0')+'\n'+start.replace('107.0','101.0')
        self.assertEqual(d.ProcessLifetime(request82(),identity82()).check(identity82(),previous),'')

    def test_verified_target_crash_precedes_missing_identity_and_later_birth(self):
        crash='105.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.2 123 1 E AndroidRuntime: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException: app.notifee.core.ForegroundService'
        died='106.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]'
        start='107.0 1000 1 I am_proc_start: [0,123,11794,com.notifeeexample,service,x]'
        with self.assertRaisesRegex(RuntimeError,'^FAIL:'):
            d.ProcessLifetime(request82(),identity82()).check(None,crash+'\n'+died+'\n'+start,denial_epoch=105)
        # Previous-incarnation crash must not poison a legitimate new process before denial.
        previous=crash.replace('105.1','100.1').replace('105.2','100.2')
        self.assertEqual(d.ProcessLifetime(request82(),identity82()).check(identity82(),previous,denial_epoch=105),'')
        # A crash after a subsequent birth is not attributed to the original incarnation.
        with self.assertRaisesRegex(RuntimeError,'^INCONCLUSIVE:'):
            d.ProcessLifetime(request82(),identity82()).check(identity82(),start+'\n'+crash.replace('105.1','108.1').replace('105.2','108.2'),denial_epoch=105)

    def test_proc_unavailable_is_blocked_and_capture_race_is_inconclusive(self):
        class Proc(d.Evidence):
            count_stat=0
            def raw(self):return ''
            def adb(self,*args,**kwargs):
                path=args[-1]
                if path.endswith('/stat'):
                    self.count_stat+=1
                    return '123 (name) S '+' '.join(['0']*18+[str(50+self.count_stat)])
                if path.endswith('/status'):return 'Uid:\t11794\t11794\t11794\t11794\n'
                if path.endswith('/cmdline'):return d.PACKAGE+'\x00'
                if path.endswith('/boot_id'):return identity82()['bootId']
                if path=='/proc/stat':return 'btime 1\n'
                if path=='CLK_TCK':return '100'
                raise RuntimeError('proc inaccessible')
        with tempfile.TemporaryDirectory() as folder:
            e=Proc(Path(folder)/'out','fake');e.uid=11794
            with self.assertRaisesRegex(RuntimeError,'replaced during'):e.process_identity('123')
            with patch.object(e,'process_identity',side_effect=RuntimeError('Permission denied')):
                with self.assertRaisesRegex(RuntimeError,'^BLOCKED:'): e.bind_process(request82())




class AdversarialAuditTests(unittest.TestCase):
    def test_unknown_ams_tail_cannot_turn_foreign_dump_into_absence(self):
        foreign=AMS_TARGET.replace(d.COMPONENT,'com.notifeeexample/app.OtherService')
        for tail in ['UNKNOWN FORMAT\n','  Unrecognized services:\n    omitted\n']:
            self.assertEqual(d.observe_services(foreign+tail)['status'],'UNKNOWN')

    def test_foreign_crash_stack_cannot_attribute_a_target_crash(self):
        raw='105.0 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.1 999 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        self.assertEqual(d.crash_attribution(raw,'123')['verdict'],'INCONCLUSIVE')


def execute_case(kind, *, foreign_logs=False, fault=None):
    cleanup_calls=[]
    collector_stopped=[]
    evidence_raw=d.Evidence.raw
    class ExecutionFake(Fake):
        samples=0
        def process_identity(self,pid):
            if getattr(self,'second',False): self.samples+=1
            if getattr(self,'second',False) and kind in ['no_proc','target_crash']: raise RuntimeError('proc unavailable')
            if self.samples>=3 and kind=='new_starttime': return identity82(starttime='60')
            if self.samples>=3 and kind=='new_pid': return identity82(pid='124')
            return identity82()
        def raw(self):
            raw=super().raw()
            if kind!='reuse':
                raw='\n'.join(line for line in raw.splitlines() if 'am_proc_died:' not in line and 'am_proc_start:' not in line)
            if kind=='late_death' and self.samples>=5:
                raw+='\n108.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]'
            if getattr(self,'second',False) and kind=='ambiguous':raw+='\nUNKNOWN_EPOCH 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]'
            if getattr(self,'second',False) and kind in ['target_crash','old_crash']:
                epoch='105' if kind=='target_crash' else '100'
                raw+='\n'+epoch+'.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n'+epoch+'.2 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
            if foreign_logs:
                raw+='\n105.0 1000 1 I am_proc_start: [0,999,999,foreign,service,x]'
                raw+='\nbad am_proc_died: [foreign]'
                raw+='\n105.3 999 1 E AndroidRuntime: FATAL EXCEPTION: main'
                raw+='\n105.4 999 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
            self.log.write_text(raw)
            if fault=='collector' and getattr(self,'second',False):
                # Exercise the real raw() interruption guard with a host-only collector.
                self.collector=type('ExitedCollector',(),{'poll':lambda self:1})()
                return evidence_raw(self)
            return raw
        def snapshot(self):
            if fault=='ams_unknown' and getattr(self,'second',False):
                self.raw()
                # Use the real tri-state parser; UNKNOWN cannot qualify absence.
                observation=d.observe_services('Permission Denial: DUMP required\n')
                raise RuntimeError('INCONCLUSIVE: AMS '+observation['status'])
            return super().snapshot()
        def stop_collector(self): collector_stopped.append(True)
    def cleanup(e,args,c):
        cleanup_calls.append(c)
        if fault=='cleanup': raise RuntimeError('BLOCKED: host cleanup fixture lost provenance')
    clock=[0]
    def monotonic():clock[0]+=1;return clock[0]
    with tempfile.TemporaryDirectory() as folder:
        base=Path(folder);apk=base/'fake.apk';ExecutionFake.apk=apk
        with zipfile.ZipFile(apk,'w') as z:
            z.writestr('assets/index.android.bundle','issue82-datasync-budget');z.writestr('classes.dex','Tracked START PROMOTION_DENIED')
        paths=d.SOURCE_PATHS
        gate=base/'gate.json';gate.write_text(json.dumps(dict(mode='feasibility',verdict='PASS',serial='fake',bootId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',uid=11794,fingerprint='device',apkHash=d.sha(apk),sourceHashes={p:d.sha(ROOT/p) for p in paths})))
        args=Namespace(output=base/'out',serial='fake',mode='full',apk=apk,observation_seconds=60,authorize_budget_change=True,feasibility_report=gate,post_top_recovery=False,manual_actions=True)
        with patch.object(d,'Evidence',ExecutionFake),patch.object(d,'arm',arm),patch.object(d,'positive',positive), patch.object(d,'start_action',start_action_phase),patch.object(d,'tap',lambda *a:None),patch.object(d,'cleanup_control',cleanup),patch.object(d.time,'monotonic',monotonic),patch.object(d.time,'sleep',lambda *a:None):
            code=d.execute(args)
        report=json.loads((args.output/'report.json').read_text())
        if foreign_logs or fault:
            self_raw=(args.output/'logcat.raw').read_text()
            self_manifest=(args.output/'SHA256.txt').read_text()
            assert 'am_proc_start: [0,999,999,foreign' in self_raw
            assert d.sha(args.output/'logcat.raw')+'  logcat.raw' in self_manifest
            assert collector_stopped
            if fault=='ams_unknown':
                assert not cleanup_calls  # Unknown STOP ownership prevents certification.
                assert report['lanes']['R6']!='PASS'
                assert any('BLOCKED owned cleanup' in value for value in report['cleanup'])
            else:
                assert cleanup_calls
            assert 'exact configuration presence/value restored' in report['cleanup']
        print('EXECUTE WITNESS',kind,fault,code,report['verdict'],report.get('error',''))
        return code,report

class FullContinuityTests(unittest.TestCase):
    def test_real_execute_continuity_alternative_paths(self):
        for kind in ['reuse','new_starttime','new_pid','late_death','ambiguous','no_proc']:
            with self.subTest(kind=kind):
                code,report=execute_case(kind)
                self.assertEqual(code,2)
                self.assertNotEqual(report['verdict'],'PASS')
                for lane in ['R3','R4']:self.assertNotEqual(report['lanes'][lane],'PASS')
                self.assertIn('process' if kind!='ambiguous' else 'lifecycle',report['error'])
        for kind in ['stable','old_crash']:
            with self.subTest(kind=kind):
                code,report=execute_case(kind)
                self.assertEqual(code,0,report)
                self.assertEqual(report['lanes']['R3'],'PASS')
                self.assertEqual(report['lanes']['R4'],'PASS')
                self.assertGreater(len(report['processContinuitySamples']),2)

    def test_execute_verified_crash_remains_fail_even_when_proc_unavailable(self):
        code,report=execute_case('target_crash')
        self.assertEqual(code,2)
        self.assertEqual(report['verdict'],'FAIL',report)
        self.assertEqual(report['lanes']['R3'],'FAIL')
        self.assertEqual(report['lanes']['R4'],'FAIL')




class FinalAuditTests(unittest.TestCase):
    def test_malformed_crash_epoch_prevents_continuity_pass(self):
        raw='UNKNOWN_EPOCH 123 1 E AndroidRuntime: FATAL EXCEPTION: main'
        with self.assertRaisesRegex(RuntimeError,'ambiguous'):
            d.ProcessLifetime(request82(),identity82()).check(identity82(),raw,denial_epoch=105)

    def test_real_identity_capture_retains_birth_fields(self):
        stat='123 (name (with) spaces) S '+' '.join(['0']*18+['50'])
        class Stable(d.Evidence):
            def adb(self,*args,**kwargs):
                path=args[-1]
                values={'/proc/123/stat':stat,'/proc/123/status':'Uid:\t11794\t11794\t11794\t11794\n',
                        '/proc/123/cmdline':d.PACKAGE+'\x00','/proc/stat':'btime 1\n',
                        '/proc/sys/kernel/random/boot_id':identity82()['bootId'],'CLK_TCK':'100'}
                return values[path]
        with tempfile.TemporaryDirectory() as folder:
            e=Stable(Path(folder)/'out','fake');e.uid=11794
            identity=e.process_identity('123')
            self.assertEqual(identity['starttime'],'50')
            self.assertEqual(identity['uid'],11794)
            self.assertEqual(identity['bootId'],identity82()['bootId'])
            self.assertEqual(identity['birthEpochUpper'],2.5)




class AMSCompletionAuditTests(unittest.TestCase):
    def test_incomplete_foreign_record_does_not_prove_target_absent(self):
        raw='ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n  * ServiceRecord{abc u0 com.notifeeexample/app.OtherService}\n'
        self.assertEqual(d.observe_services(raw)['status'],'UNKNOWN')

    def test_complete_foreign_summary_proves_absence(self):
        raw='ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  User 0 active services:\n  * ServiceRecord{abc u0 com.notifeeexample/app.OtherService}\n    app=null\n    created=-1s started=true connections=0\n'
        self.assertEqual(d.observe_services(raw)['status'],'VERIFIED_ABSENT')




class IncarnationBoundaryAuditTests(unittest.TestCase):
    def test_equal_epoch_new_birth_and_crash_is_not_a_verified_old_process_crash(self):
        raw='107.0 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n107.0 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService\n107.0 1000 1 I am_proc_start: [0,123,11794,com.notifeeexample,service,x]'
        with self.assertRaisesRegex(RuntimeError,'^INCONCLUSIVE:'):
            d.ProcessLifetime(request82(),identity82()).check(identity82(),raw,denial_epoch=105)




class CombinedEvidenceTests(unittest.TestCase):
    def test_verified_target_crash_precedes_unknown_ams_and_missing_proc(self):
        denial='105.0 123 1 W NOTIFEE: Tracked START PROMOTION_DENIED (cause=UNKNOWN, requestId=aaaa-1111, notificationId=issue82-fgs-run-2-n2, api=36, type=1, generation=0, runGeneration=1, startId=1, flags=0, stop=accepted)'
        crash='105.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.2 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        with tempfile.TemporaryDirectory() as folder:
            e=d.Evidence(Path(folder)/'out','fake')
            e.active_lifetime=d.ProcessLifetime(request82(),identity82())
            with patch.object(e,'raw',return_value=denial+'\n'+crash),patch.object(e,'process_identity',side_effect=RuntimeError('proc unavailable')),patch.object(e,'services',side_effect=RuntimeError('AMS unknown')) as services:
                with self.assertRaisesRegex(RuntimeError,'^FAIL:'):e.snapshot()
                services.assert_not_called()




class AOSPFormatTests(unittest.TestCase):
    def test_android16_optional_calling_package_header(self):
        raw=AMS_TARGET.replace('ForegroundService}','ForegroundService c:com.notifeeexample}')
        self.assertEqual(d.observe_services(raw)['status'],'PRESENT_TARGET')
        foreign=raw.replace(d.COMPONENT,'com.notifeeexample/app.OtherService')
        self.assertEqual(d.observe_services(foreign)['status'],'VERIFIED_ABSENT')
        self.assertEqual(d.observe_services(raw.replace(' c:com.notifeeexample',' unknown:suffix'))['status'],'UNKNOWN')

    def test_unrelated_class_name_error_word_is_not_ams_diagnostic(self):
        raw=AMS_TARGET.replace(d.COMPONENT,'com.notifeeexample/app.ErrorService')
        self.assertEqual(d.observe_services(raw)['status'],'VERIFIED_ABSENT')

class IncarnationOwnershipTests(unittest.TestCase):
    def test_same_package_other_pid_birth_does_not_authorize_crash_attribution(self):
        raw='105.0 1000 1 I am_proc_start: [0,999,11794,com.notifeeexample,service,x]\n105.1 999 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.2 999 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        self.assertIsNone(d.crash_attribution(raw,'123'))
        self.assertEqual(d.ProcessLifetime(request82(),identity82()).check(identity82(),raw,denial_epoch=105),raw)

    def test_crash_after_qualified_death_without_new_birth_is_not_verified_target_fail(self):
        raw='106.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]\n107.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n107.2 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        with self.assertRaisesRegex(RuntimeError,'^INCONCLUSIVE:'):
            d.ProcessLifetime(request82(),identity82()).check(None,raw,denial_epoch=105)


class AMSStrictTypeTests(unittest.TestCase):
    # AOSP16 ServiceRecord.dump: foreground fields share a detail line; types is a mask.
    def record(self, fields):
        return AMS_TARGET.replace('foregroundServiceType=0x00000001', fields)

    def test_aosp16_numeric_mask_and_legacy_formats(self):
        for field, mask in [('types=0x00000001', 1), ('types=0x00000003', 3),
                            ('types=0x00000000', 0), ('types=0x00000080', 128),
                            ('foregroundServiceType=0x00000001', 1),
                            ('foregroundServiceType=1', 1), ('foregroundServiceType=dataSync', 1)]:
            with self.subTest(field=field):
                observation = d.observe_services(self.record(field))
                self.assertEqual(observation['status'], 'PRESENT_TARGET')
                self.assertEqual(observation['record']['type'], mask)

    def test_malformed_duplicate_or_contradictory_masks_are_unknown(self):
        for fields in ['types=garbage', 'types=0x', 'types=0x1junk', 'types=-1',
                       'types=0x100000000', 'types=dataSync', 'types=', 'types =0x1',
                       'types=0x1 types=0x1', 'types=0x1 types=0x2',
                       'types=0x1 foregroundServiceType=1',
                       'types=0x1 foregroundServiceType=2',
                       'types=0x1 foregroundServiceType:0x2', 'types=0x1 types:0x2',
                       'foregroundServiceType=1 foregroundServiceType=1',
                       'foregroundServiceType=garbage',
                       'types=0x1\n    foregroundServiceType=1']:
            with self.subTest(fields=fields):
                self.assertEqual(d.observe_services(self.record(fields))['status'], 'UNKNOWN')

    def test_incidental_types_and_missing_type_cannot_qualify_promotion(self):
        raw = self.record('') + '    intent={extras types=0x00000001}\n'
        observation = d.observe_services(raw)
        self.assertEqual(observation['status'], 'PRESENT_TARGET')
        self.assertIsNone(observation['record']['type'])
        for raw in ['', 'Permission Denial: DUMP required\n',
                    self.record('types=0x1').replace('    lastStartId=1\n', '')]:
            with self.subTest(raw=raw):
                self.assertEqual(d.observe_services(raw)['status'], 'UNKNOWN')
        self.assertEqual(d.observe_services(AMS_EMPTY)['status'], 'VERIFIED_ABSENT')

    def test_real_positive_gate_requires_exact_datasync_type(self):
        request = request82()
        notification = ('  Notification List:\n    NotificationRecord(pkg=com.notifeeexample id='
                        + str(d.java_hash(request['notificationId'])) + ' flags=FOREGROUND_SERVICE)\n')
        marker = '105.0 123 2 I ReactNativeJS: ISSUE82:RUNNER_STARTED ' + json.dumps(dict(
            smokeScenario='issue82-datasync-budget', correlationId='run', fgsType=1,
            attempt=2, attemptId=request['attemptId'], notificationId=request['notificationId'],
            processRunId=request['processRunId']))
        for fields, qualifies in [('types=0x00000001', True),
                                  ('foregroundServiceType=0x00000001', True),
                                  ('types=0x00000003', False), ('types=0x00000080', False), ('', False)]:
            with self.subTest(fields=fields), tempfile.TemporaryDirectory() as folder:
                e = d.Evidence(Path(folder)/'out', 'fake')
                record = d.service(self.record(fields).replace('foregroundId=123',
                    'foregroundId=' + str(d.java_hash(request['notificationId']))))
                e.last_notifications = notification
                clock = [0]
                def tick():
                    clock[0] += 10
                    return clock[0]
                with patch.object(d, 'start_action', return_value=request), \
                     patch.object(e, 'bind_process', return_value=d.ProcessLifetime(request, identity82())), \
                     patch.object(e, 'snapshot', return_value=(record, False)), \
                     patch.object(e, 'check_process', return_value=marker), \
                     patch.object(d.time, 'monotonic', side_effect=tick), patch.object(d.time, 'sleep'):
                    if qualifies:
                        self.assertEqual(d.positive(e, Namespace(), 'run')['service']['type'], 1)
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'deadline reached'):
                            d.positive(e, Namespace(), 'run')


class ReceiptEndToEndTests(unittest.TestCase):
    def test_real_execute_feasibility_through_actual_ts_receipt_cleanup(self):
        import subprocess
        bridge=subprocess.Popen(['node','apps/smoke/__tests__/issue82HostFixture.cjs'],cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        class Linked(Fake):
            state={}
            def app(self,**command):
                bridge.stdin.write(json.dumps(command)+'\n');bridge.stdin.flush()
                self.state=json.loads(bridge.stdout.readline())
                if 'error' in self.state: raise RuntimeError(self.state['error'])
                return self.state
            def now_device(self):return self.app(op='snapshot')['now']/1000
            def process_identity(self,pid):
                return identity82(birthEpochUpper=1)
            def raw(self):
                state=self.app(op='snapshot')
                return '\n'.join(str(x['loggedAt']/1000)+' 123 2 I ReactNativeJS: ISSUE82:'+x['marker']+' '+json.dumps(x) for x in state['markers'])
            def services(self):
                state=self.app(op='snapshot')
                n=next((x for x in state['notifications'] if x.get('android',{}).get('asForegroundService')),None)
                if not n:return d.service(AMS_EMPTY)
                raw=AMS_TARGET.replace('foregroundServiceType=0x00000001','types=0x00000001').replace('foregroundId=123','foregroundId='+str(d.java_hash(n['id'])))
                return d.service(raw)
            def snapshot(self):
                if getattr(self,'active_lifetime',None):self.check_process(self.active_lifetime)
                s=self.services();self.last_notifications='  Notification List:\n'
                if s:self.last_notifications+='    NotificationRecord(pkg=com.notifeeexample id='+s['notificationId']+' flags=FOREGROUND_SERVICE)\n'
                return s,False
        clock=[0]
        def monotonic():clock[0]+=0.5;return clock[0]
        def arm_link(e,c):e.c=c;e.app(op='arm',correlation=c,session=e.receipt_signer.context);e.negative_since=e.now_device()
        actions={'Start dataSync FGS':'issue82-start','Stop owned FGS':'issue82-stop','Cleanup':'issue82-cleanup'}
        def tap_link(e,args,c,title):e.app(op='action',action=actions[title])
        def reply_link(e,args,c,wire):e.app(op='action',action='issue82-cleanup',input=wire)
        try:
            with tempfile.TemporaryDirectory() as folder:
                base=Path(folder);apk=base/'fake.apk';Linked.apk=apk
                with zipfile.ZipFile(apk,'w') as z:
                    z.writestr('assets/index.android.bundle','issue82-datasync-budget');z.writestr('classes.dex','Tracked START PROMOTION_DENIED')
                args=Namespace(output=base/'out',serial='fake',mode='feasibility',apk=apk,observation_seconds=60,authorize_budget_change=False,feasibility_report=None,post_top_recovery=False,manual_actions=True)
                with patch.object(d,'Evidence',Linked),patch.object(d,'arm',arm_link),patch.object(d,'tap',tap_link),patch.object(d,'submit_receipt',reply_link),patch.object(d.time,'monotonic',monotonic),patch.object(d.time,'sleep'):
                    code=d.execute(args)
                report=json.loads((args.output/'report.json').read_text())
                self.assertEqual(code,0,report);self.assertEqual(report['feasibility'],'PASS');self.assertEqual(report['lanes']['R6'],'PASS')
                # Keep the true PASS criterion/report as evidence outside the checkout.
                artifact=Path(os.environ.get('ISSUE82_E2E_EVIDENCE',tempfile.gettempdir()))
                if os.environ.get('ISSUE82_E2E_EVIDENCE'):
                    (artifact/'simulated-feasibility-report.json').write_text(json.dumps(report,indent=2))
                    (artifact/'simulated-receipt-body.json').write_bytes((args.output/'receipt-body.json').read_bytes())
        finally:
            bridge.terminate();bridge.wait(timeout=5);bridge.stdin.close();bridge.stdout.close()


class ReceiptIssuanceTests(unittest.TestCase):
    def fixture(self):
        import copy
        request=dict(request82(),attempt=1,attemptId='run:1:n1',notificationId='issue82-fgs-run-1-n1')
        session=dict(version=1,sessionId='s',serial='fake',build='device',bootId=identity82()['bootId'],uid=11794,package=d.PACKAGE,apkHash='a'*64,candidate='b'*64,modulus='f'*512)
        ledger=[dict(attemptId=request['attemptId'],notificationId=request['notificationId'],processRunId='process',dispatch='resolved',terminal='stop',stopResolved=True)]
        quiesced=dict(request,epoch=110,marker='QUIESCED',appState='background',diagnostic=dict(session=session,phase='quiesced',epoch='sealed',consumerRunId='process',ledger=ledger))
        ms=[dict(request,marker='START_PROMISE_SETTLED',outcome='resolved'),dict(request,marker='STOP_RESOLVED',epoch=108)]
        chain=dict(request=request,processIdentity=identity82(),service=dict(identity='abc',startId='1',pid='123',type=1,foreground=True,user='0',notificationId=str(d.java_hash(request['notificationId']))),runner=request,promotion=True,terminal='stop',stopSince=107)
        class E:
            receipt_signer=Namespace(context=session)
            proof_chain=[copy.deepcopy(chain)]
            active_lifetime=None
            def adb(self,*args):return session['bootId'] if 'cat' in args else session['build']
            def services(self):return None
            def now_device(self):return 110
            def bind_process(self,r):return d.ProcessLifetime(r,identity82())
            def check_process(self,l):return ''
        return E(),quiesced,ms
    def test_issuer_rejects_unpromoted_unknown_foreign_process_and_ams(self):
        import copy
        e,q,ms=self.fixture();body=d.receipt82.certify(e,'run',q,ms,'feasibility');self.assertEqual(body['proofs'][0]['terminal'],'stop')
        for kind in ['promotion','pending','rejected','boot','uid','pid','starttime','stop','absent','reappeared','empty','owner','service-id','service-user','service-start','runner']:
            with self.subTest(kind=kind):
                e,q,ms=self.fixture()
                if kind=='promotion':e.proof_chain[0]['promotion']=False
                if kind in ['pending','rejected']:q['diagnostic']['ledger'][0]['dispatch']=kind
                if kind in ['boot','uid','pid','starttime']:
                    key={'boot':'bootId','uid':'uid','pid':'pid','starttime':'starttime'}[kind]
                    e.proof_chain[0]['processIdentity'][key]={'boot':'wrong','uid':999,'pid':'999','starttime':'99'}[kind]
                if kind=='stop':ms=ms[:1]
                if kind=='absent':e.services=lambda:(_ for _ in ()).throw(RuntimeError('INCONCLUSIVE: AMS UNKNOWN'))
                if kind=='reappeared':
                    count=[0]
                    def services():count[0]+=1;return None if count[0]==1 else {'identity':'foreign'}
                    e.services=services
                if kind=='empty':e.proof_chain=[]
                if kind=='owner':e.proof_chain[0]['request']['correlationId']='other'
                if kind.startswith('service-'):
                    key={'service-id':'notificationId','service-user':'user','service-start':'startId'}[kind]
                    e.proof_chain[0]['service'][key]='wrong'
                if kind=='runner':e.proof_chain[0]['runner']=dict(e.proof_chain[0]['request'],processRunId='foreign')
                with self.assertRaises(RuntimeError):d.receipt82.certify(e,'run',q,ms,'feasibility')
    def test_full_receipt_requires_two_distinct_terminal_obligations(self):
        import copy
        e,q,ms=self.fixture()
        with self.assertRaises(RuntimeError):d.receipt82.certify(e,'run',q,ms,'full')
        first=e.proof_chain[0];first.update(terminal='timeout',timeout={'native':['observed native timeout']});q['diagnostic']['ledger'][0].update(terminal='timeout',stopResolved=False)
        second=copy.deepcopy(first);second['request'].update(attempt=2,attemptId='run:2:n2',notificationId='issue82-fgs-run-2-n2')
        second.update(promotion=False,terminal='denial',distinct=True,denial=dict(stop='accepted',exactException=True,frameworkPromotion=True,budgetMessage=True));second['service']['identity']='def'
        e.proof_chain.append(second);entry=copy.deepcopy(q['diagnostic']['ledger'][0]);entry.update(attemptId='run:2:n2',notificationId='issue82-fgs-run-2-n2',terminal='none');q['diagnostic']['ledger'].append(entry);q.update(attempt=2,attemptId='run:2:n2');ms.append(dict(second['request'],marker='START_PROMISE_SETTLED',outcome='resolved'))
        body=d.receipt82.certify(e,'run',q,ms,'full');self.assertEqual([p['terminal'] for p in body['proofs']],['timeout','denial'])
        e.proof_chain[1]['denial']['stop']='refused'
        with self.assertRaises(RuntimeError):d.receipt82.certify(e,'run',q,ms,'full')
    def test_signer_is_monouse_and_keeps_key_out_of_receipt(self):
        e,q,ms=self.fixture();signer=d.receipt82.ReceiptSigner(e.receipt_signer.context)
        try:
            body=dict(correlationId='run',epoch='e');wire=signer.sign(body)
            self.assertNotIn('PRIVATE KEY',wire)
            with self.assertRaises(RuntimeError):signer.sign(body)
        finally:signer.close()

class ReceiptTransportTests(unittest.TestCase):
    def xml(self,text,bounds):
        return '<hierarchy><node><node text="Issue 82 run"/><node class="android.widget.EditText" focused="true" text="'+text+'"/><node content-desc="Send reply" clickable="true" enabled="true" bounds="'+bounds+'"/></node></hierarchy>'
    def test_reply_relocates_send_and_refuses_truncated_or_foreign_input(self):
        self.assertEqual(d.reply_coordinates(self.xml('','[0,0][10,10]'),'run'),('5','5'))
        self.assertEqual(d.reply_coordinates(self.xml('abc','[20,40][40,60]'),'run','abc'),('30','50'))
        for c,text in [('run','truncated'),('foreign','abc')]:
            with self.assertRaises(RuntimeError):d.reply_coordinates(self.xml('abc','[20,40][40,60]'),c,text)
    def test_signed_transport_is_receipt_only_after_absence_and_non_top(self):
        for service, top, seen in [(None,False,False), ({'pid':'123'},False,False), (None,True,False), (None,False,True)]:
            calls=[]
            class E:
                top_seen=seen
                def snapshot(self):return service,top
                def adb(self,*args,**kw):calls.append(args);return ''
            if service is None and not top and not seen:
                d.submit_receipt(E(),Namespace(),'run','abc')
                self.assertEqual(calls,[('shell','am','broadcast','-n',d.PACKAGE+'/.Issue82ReceiptReceiver','-a',d.PACKAGE+'.ISSUE82_SIGNED_RECEIPT','--es','correlationId','run','--es','wire','abc')])
            else:
                with self.assertRaises(RuntimeError): d.submit_receipt(E(),Namespace(),'run','abc')
                self.assertEqual(calls,[])




class RealLogBooleanRegressionTests(unittest.TestCase):
    # Epoch logcat fixtures are host parser evidence, never Android qualification.
    def test_relevance_is_boolean_and_classification_preserves_ownership(self):
        import sys
        cases = [
            ('bad 1000 1 I am_proc_died: [0,999,com.notifeeexample,10,0]', True, None),
            ('bad 1000 1 I am_proc_died: [0,123,foreign,10,0]', True, None),
            ('bad 1000 1 I am_proc_died: [0,999,foreign,10,0]', False, None),
            ('bad am_anr: [incomplete]', False, None),
            ('105.0 1000 1 I am_proc_died: [0,999,foreign,10,0]', False, None),
            ('105.0 1000 1 I am_proc_died: [0,123,foreign,10,0]', False, 'qualified process died'),
            ('105.0 1000 1 I am_proc_start: [0,123,999,foreign,service,x]', False, 'qualified PID restarted/reused'),
            ('105.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,', False, 'qualified process died'),
            ('105.0 1000 1 I am_proc_start: [0,123,11794,com.notifeeexample,', False, 'qualified PID restarted/reused'),
            ('105.0 1000 1 I am_proc_died: [incomplete,123,com.notifeeexample]', True, None),
            ('bad 123 1 E AndroidRuntime: FATAL EXCEPTION: main', True, None),
            ('ordinary unrelated text', False, None),
        ]
        for raw, ambiguous, broken in cases:
            with self.subTest(raw=raw):
                values=[]
                def trace(frame, event, arg):
                    if frame.f_code is d.incarnation_log.__code__ and event == 'line' and 'relevant' in frame.f_locals:
                        values.append(frame.f_locals['relevant'])
                    return trace
                previous=sys.gettrace()
                try:
                    sys.settrace(trace)
                    qualified, actual_broken, actual_ambiguous=d.incarnation_log(raw,request82())
                finally:
                    sys.settrace(previous)
                self.assertTrue(values)
                self.assertTrue(all(type(value) is bool for value in values), values)
                self.assertEqual(actual_ambiguous,ambiguous)
                self.assertEqual(actual_broken,broken)
                self.assertEqual(qualified,raw if raw.startswith('105.') and not ambiguous and broken != 'qualified PID restarted/reused' else '')

    def test_interleaved_foreign_events_cannot_hide_target_crash_or_restart(self):
        foreign='105.0 1000 1 I am_proc_start: [0,999,999,foreign,service,x]'
        malformed='bad am_anr: [foreign]'
        crash='105.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.2 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        died='106.0 1000 1 I am_proc_died: [0,123,com.notifeeexample,10,0]'
        restart='107.0 1000 1 I am_proc_start: [0,123,11794,com.notifeeexample,service,x]'
        # Input order is deliberately different from temporal order.
        raw='\n'.join([restart,foreign,crash,malformed,died])
        qualified,broken,ambiguous=d.incarnation_log(raw,request82())
        self.assertEqual(qualified,'\n'.join([foreign,crash,died]))
        self.assertEqual(broken,'qualified process died');self.assertFalse(ambiguous)
        with self.assertRaisesRegex(RuntimeError,'^FAIL:'):
            d.ProcessLifetime(request82(),identity82()).check(None,raw,denial_epoch=105)
        with self.assertRaisesRegex(RuntimeError,'^INCONCLUSIVE:'):
            d.ProcessLifetime(request82(),identity82()).check(identity82(),foreign+'\n'+died+'\n'+restart)

    def test_target_crash_and_anr_survive_irrelevant_and_malformed_events(self):
        foreign='105.0 1000 1 I am_proc_start: [0,999,999,foreign,service,x]'
        malformed='bad am_proc_died: [incomplete,123,com.notifeeexample]'
        for tag in ['am_crash','am_anr']:
            target=f'105.1 1000 1 I {tag}: [0,123,com.notifeeexample,reason]'
            unrelated=f'105.2 1000 1 I {tag}: [0,123,foreign,reason]'
            raw='\n'.join([foreign,malformed,target,unrelated])
            with self.subTest(tag=tag):
                qualified,broken,ambiguous=d.incarnation_log(raw,request82())
                self.assertEqual(qualified,'\n'.join([foreign,target,unrelated]))
                self.assertIsNone(broken);self.assertTrue(ambiguous)
                self.assertEqual(d.crash_lines(qualified,{'123'}),[target])
                with self.assertRaisesRegex(RuntimeError,'crash/ANR needs independent causal attribution'):
                    d.ProcessLifetime(request82(),identity82()).check(None,raw,denial_epoch=105)
        verified='105.1 123 1 E AndroidRuntime: FATAL EXCEPTION: main\n105.2 123 1 E AndroidRuntime: ForegroundServiceDidNotStartInTimeException app.notifee.core.ForegroundService'
        with self.assertRaisesRegex(RuntimeError,'^FAIL:'):
            d.ProcessLifetime(request82(),identity82()).check(None,foreign+'\n'+malformed+'\n'+verified,denial_epoch=105)

    def test_foreign_events_do_not_authorize_identity_or_provenance_changes(self):
        raw='105.0 1000 1 I am_proc_start: [0,999,999,foreign,service,x]\nbad am_proc_died: [foreign]'
        for changes in [dict(starttime='51'),dict(uid=999),dict(bootId='other'),dict(package='foreign'),dict(processName='foreign')]:
            with self.subTest(changes=changes),self.assertRaisesRegex(RuntimeError,'identity changed'):
                d.ProcessLifetime(request82(),identity82()).check(identity82(**changes),raw)
        marker='105.1 123 1 I ReactNativeJS: ISSUE82:START_REQUESTED '+json.dumps(dict(request82(),smokeScenario='issue82-datasync-budget',fgsType=1,processRunId='replacement'))
        with self.assertRaisesRegex(RuntimeError,'JS process incarnation'):
            d.ProcessLifetime(request82(),identity82()).check(identity82(),raw+'\n'+marker)
        tracker=d.ProcessLifetime(request82(),identity82())
        self.assertEqual(tracker.check(identity82(),raw),raw.splitlines()[0])
        self.assertEqual(tracker.identity,identity82())



class ForeignLifecycleExecutionRegressionTests(unittest.TestCase):
    def test_execute_foreign_events_preserve_all_continuity_verdicts(self):
        for kind in ['stable','reuse','new_starttime','new_pid','late_death','ambiguous','no_proc','target_crash']:
            with self.subTest(kind=kind):
                code,report=execute_case(kind,foreign_logs=True)
                expected='PASS' if kind=='stable' else 'FAIL' if kind=='target_crash' else 'BLOCKED' if kind=='no_proc' else 'INCONCLUSIVE'
                self.assertEqual(report['verdict'],expected,report)
                self.assertEqual(code,0 if expected=='PASS' else 2)
                for lane in ['R3','R4']:
                    self.assertEqual(report['lanes'][lane],expected)
                self.assertNotIn('TypeError',report.get('error',''))
                self.assertNotIn('unsupported operand',report.get('error',''))

    def test_execute_unknown_ams_collector_loss_and_cleanup_never_pass(self):
        for fault in ['ams_unknown','collector','cleanup']:
            with self.subTest(fault=fault):
                code,report=execute_case('stable',foreign_logs=True,fault=fault)
                self.assertEqual(code,2)
                self.assertEqual(report['verdict'],'BLOCKED' if fault=='cleanup' else 'INCONCLUSIVE',report)
                if fault=='cleanup':
                    self.assertIn('lost provenance',str(report['cleanup']))
                    self.assertNotEqual(report['lanes']['R6'],'PASS')
                else:
                    self.assertIn('AMS UNKNOWN' if fault=='ams_unknown' else 'collector exited',report['error'])
                    for lane in ['R3','R4']: self.assertNotEqual(report['lanes'][lane],'PASS')

if __name__ == '__main__': unittest.main()

class BoundNonTargetRegressionTests(unittest.TestCase):
    def test_real_bound_firebase_is_valid_without_target(self):
        raw=(Path(__file__).parent/'fixtures/issue82-ams-bound-firebase.raw').read_text()
        self.assertEqual(d.observe_services(raw)['status'], 'VERIFIED_ABSENT')
        target='  * ServiceRecord{abc u0 com.notifeeexample/app.notifee.core.ForegroundService}\n    app=null\n'
        self.assertEqual(d.observe_services(raw+target)['status'], 'UNKNOWN')
        target+='    lastStartId=1\n'
        self.assertEqual(d.observe_services(raw+target)['status'], 'PRESENT_TARGET')
        for broken in [raw.replace('    app=ProcessRecord', '    missing=ProcessRecord'),raw.replace('    createTime=', '    missingTime='),raw.replace('ServiceRecord{afd4c4b u0', 'ServiceRecord{???'),raw.rstrip(),raw+'  * ServiceRecord{truncated\n']:
            self.assertEqual(d.observe_services(broken)['status'], 'UNKNOWN')

class ItalianReplyRegressionTests(unittest.TestCase):
    def test_real_systemui_send_uses_resource_identity(self):
        raw=(Path(__file__).parent/'fixtures/issue82-inline-reply-it.xml').read_text()
        self.assertEqual(d.reply_coordinates(raw,'i82-5dc3e8874b25469b8fb2c396a83da13b'),('1212','1177'))
        with self.assertRaises(RuntimeError): d.reply_coordinates(raw,'foreign')
        with self.assertRaises(RuntimeError): d.reply_coordinates(raw,'i82-5dc3e8874b25469b8fb2c396a83da13b','wrong-wire')

class ExpansionRegressionTests(unittest.TestCase):
    def test_control_expansion_excludes_matching_fgs_title(self):
        raw=(Path(__file__).parent/'fixtures/issue82-collapsed-control.xml').read_text()
        self.assertEqual(d.expand_coordinates(raw,'i82-5dc3e8874b25469b8fb2c396a83da13b'),(1209,1291))
        with self.assertRaises(RuntimeError): d.expand_coordinates(raw,'foreign')

class AutomaticCleanupFocusTests(unittest.TestCase):
    def test_cleanup_tap_keeps_reply_open_and_other_actions_collapse(self):
        from types import SimpleNamespace
        xml='<hierarchy><node><node text="Issue 82 run"/><node text="Cleanup" bounds="[10,20][30,40]"/><node text="Stop owned FGS" bounds="[50,60][70,80]"/></node></hierarchy>'
        class UI:
            def __init__(self): self.calls=[]
            def adb(self,*args,**kw):
                self.calls.append(args)
                return xml if args[:2]==('shell','cat') else ''
        for title,collapse in [('Cleanup',False),('Stop owned FGS',True)]:
            e=UI()
            with patch.object(d.time,'sleep',lambda _:None): d.tap(e,SimpleNamespace(manual_actions=False),'run',title)
            self.assertEqual(('shell','cmd','statusbar','collapse') in e.calls,collapse)
            self.assertTrue(any(c[:3]==('shell','input','tap') for c in e.calls))

class InstalledIdentityTests(unittest.TestCase):
    def test_identical_apk_avoids_restart_but_different_apk_reinstalls_and_checks(self):
        for installed, wanted, expect_install in [(b'candidate',b'candidate',False),(b'old',b'candidate',True)]:
            with tempfile.TemporaryDirectory() as folder:
                apk=Path(folder)/'candidate.apk';apk.write_bytes(wanted)
                calls=[]
                class E:
                    output=Path(folder)
                    current=installed
                    def adb(self,*args,**kw):
                        calls.append(args)
                        if args[:3]==('shell','pm','path'):return 'package:/data/base.apk\n'
                        if args[0]=='pull':Path(args[-1]).write_bytes(self.current)
                        if args[0]=='install':self.current=wanted
                        return ''
                result=d.ensure_installed(E(),apk,d.sha(apk))
                self.assertEqual(result['hash'],d.sha(apk))
                self.assertEqual(any(c[0]=='install' for c in calls),expect_install)

class FastAMSProvenanceTests(unittest.TestCase):
    def test_complete_real_service_detail_only_and_no_truncation_or_remote_error(self):
        raw=(Path(__file__).parent/'fixtures/issue82-ams-fast-present.raw').read_text()
        witnesses=d.fast_service_records(raw,99)
        self.assertEqual(len(witnesses),1)
        self.assertEqual(witnesses[0]['pid'],'32351')
        for invalid in [raw.replace('ISSUE82_FAST_END:0:', 'ISSUE82_FAST_END:1:'),raw.split('ISSUE82_FAST_END')[0],raw.replace('ACTIVITY MANAGER SERVICES','UNKNOWN HEADER'),raw.replace('100.1','99.1')]:
            self.assertEqual(d.fast_service_records(invalid,99),[])
        self.assertEqual(d.fast_service_records(raw,101),[])
        self.assertEqual(d.fast_service_records(raw,99,stderr='permission denied'),[])

class NativeObjectProvenanceTests(unittest.TestCase):
    def raw(self,new_id=2,run='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'):
        def event(t,kind,i,nanos,observer=run):
            return f'{t}.0 123 1 I Issue82Instance: {kind} '+json.dumps(dict(observerRunId=observer,instanceId=i,pid=123,elapsedNanos=nanos,className='app.notifee.core.ForegroundService'))
        return '\n'.join([event(101,'INSTANCE_PRESENT',1,10),event(201,'INSTANCE_ABSENT',1,20),event(301,'INSTANCE_PRESENT',new_id,30)])
    def test_distinct_native_object_requires_old_live_absent_new_same_incarnation(self):
        proof=d.native_instance_proof(self.raw(),'123',100,200,300,301)
        self.assertEqual(proof['identityKind'],'app-owned-native-instance')
        self.assertTrue(proof['identity'].startswith('native:'))
        for raw in [self.raw(1),self.raw().replace('INSTANCE_ABSENT','UNPROVEN'),self.raw()+'\nOBSERVER_UNAVAILABLE',self.raw().replace('"elapsedNanos": 30','"elapsedNanos": 5'),self.raw().replace('301.0 123','301.0 999'),self.raw().replace('301.0','299.0')]:
            self.assertIsNone(d.native_instance_proof(raw,'123',100,200,300,301))
        self.assertIsNone(d.native_instance_proof(self.raw(),'999',100,200,300,301))

class NativeProofLifetimeWindowTests(unittest.TestCase):
    def test_proof_uses_first_qualified_lifetime_not_second_request_suffix(self):
        raw=NativeObjectProvenanceTests().raw()
        first=dict(epoch=100,logPid='123');second=dict(epoch=300,logPid='123')
        first_raw,broken,ambiguous=d.incarnation_log(raw,first)
        self.assertIsNone(broken);self.assertFalse(ambiguous)
        self.assertIsNotNone(d.native_instance_proof(first_raw,'123',100,200,300,301))
        second_raw,_,_=d.incarnation_log(raw,second)
        self.assertIsNone(d.native_instance_proof(second_raw,'123',100,200,300,301))

class ShadeSettlementTests(unittest.TestCase):
    def test_transient_home_observation_never_taps_until_owned_row_exists(self):
        home=(Path(__file__).parent/'fixtures/issue82-shade-transient-home.xml').read_text()
        row='<hierarchy><node><node text="Issue 82 run"/><node text="Start dataSync FGS" bounds="[10,20][30,40]"/></node></hierarchy>'
        class E:
            def __init__(self):self.calls=[];self.reads=0
            def adb(self,*args,**kw):
                self.calls.append(args)
                if args[:2]==('shell','cat'):
                    self.reads+=1;return home if self.reads==1 else row
                return ''
        e=E()
        with patch.object(d.time,'sleep',lambda _:None):d.tap(e,Namespace(manual_actions=False),'run','Start dataSync FGS')
        self.assertEqual([c for c in e.calls if c[:3]==('shell','input','tap')],[('shell','input','tap','20','30')])
        self.assertEqual(e.reads,2)
