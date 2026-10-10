"""Host checks for the Debug-only, in-process Issue 81 death probe.

No Android/core/Gradle changes, JUnit dependency, or device signal required.
Compiles the actual pure Java authorization gate and exercises stale/duplicate
admissions. APK/source-set isolation is checked again on real build artifacts.
"""
import pathlib
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[3]
DEBUG = ROOT / 'apps/smoke/android/app/src/debug'
JAVA = DEBUG / 'java/com/notifeeexample'

HARNESS = r'''
package com.notifeeexample;
public final class DeathGateChecks {
  static final String TOKEN = "a".repeat(64), OTHER = "b".repeat(64);
  static final Issue81DeathGate.Identity SELF = new Issue81DeathGate.Identity(42, 10001, 1234, "com.notifeeexample");
  static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
  static Issue81DeathGate arm() {
    Issue81DeathGate g = new Issue81DeathGate();
    check(g.arm(TOKEN, SELF, SELF, 100, 1000), "valid arm"); return g;
  }
  public static void main(String[] args) {
    Issue81DeathGate g = new Issue81DeathGate();
    check(!g.consume(TOKEN, SELF, 100), "unarmed fire");
    check(!g.arm("guess", SELF, SELF, 100, 1000), "malformed token");
    check(!g.arm(TOKEN, new Issue81DeathGate.Identity(43,10001,1234,"com.notifeeexample"), SELF,100,1000),"foreign PID");
    check(!g.arm(TOKEN, new Issue81DeathGate.Identity(42,10002,1234,"com.notifeeexample"), SELF,100,1000),"foreign UID");
    check(!g.arm(TOKEN, new Issue81DeathGate.Identity(42,10001,1235,"com.notifeeexample"), SELF,100,1000),"recycled PID");
    check(!g.arm(TOKEN, new Issue81DeathGate.Identity(42,10001,1234,"com.notifeeexample:other"), SELF,100,1000),"other process");
    check(!g.arm(TOKEN, SELF, SELF, 100, 0), "invalid TTL");
    check(!g.arm(TOKEN, SELF, SELF, 100, 10001), "unbounded TTL");
    check(!g.arm(TOKEN, SELF, new Issue81DeathGate.Identity(43,10001,1234,"com.notifeeexample"),100,1000),"wrong actual process");
    check(!g.arm(TOKEN, SELF, SELF, -1, 1000), "negative clock");
    check(!g.arm(TOKEN, SELF, SELF, Long.MAX_VALUE, 1000), "expiry overflow");
    g = arm();
    check(!g.consume(TOKEN, new Issue81DeathGate.Identity(43,10001,1234,"com.notifeeexample"),101),"fire foreign PID");
    check(!g.consume(TOKEN, new Issue81DeathGate.Identity(42,10002,1234,"com.notifeeexample"),101),"fire foreign UID");
    check(!g.consume(TOKEN, new Issue81DeathGate.Identity(42,10001,1234,"com.notifeeexample:other"),101),"fire other process");
    check(!g.arm(OTHER, SELF, SELF, 101, 1000), "cannot replace authorization");
    check(!g.arm(TOKEN, SELF, SELF, 101, 1000), "duplicate arm cannot extend expiry");
    check(!g.consume(OTHER, SELF, 101), "wrong token");
    check(!g.consume(TOKEN, new Issue81DeathGate.Identity(42,10001,1235,"com.notifeeexample"),101),"stale fire ticks");
    check(g.consume(TOKEN, SELF, 101), "authorized fire");
    check(!g.consume(TOKEN, SELF, 102), "duplicate fire");
    check(!g.arm(OTHER, SELF, SELF, 103, 1000), "one shot for process lifetime");
    check(!arm().consume(TOKEN, SELF, 1100), "expiry boundary");
    check(!arm().consume(TOKEN, SELF, 99), "clock rollback");
    check(!new Issue81DeathGate().consume(TOKEN, SELF, 101), "old token in new process");
    g = arm(); final Issue81DeathGate concurrent = g;
    java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
    Thread[] threads = new Thread[16];
    for(int i=0;i<threads.length;i++) threads[i]=new Thread(()->{if(concurrent.consume(TOKEN,SELF,101)) wins.incrementAndGet();});
    for(Thread t:threads)t.start(); for(Thread t:threads)try{t.join();}catch(Exception e){throw new RuntimeException(e);}
    check(wins.get()==1,"concurrent fire exactly once");
    System.out.println("PASS identity/token/expiry/process-lifetime/concurrent-one-shot");
  }
}
'''

class DebugDeathTests(unittest.TestCase):
    def test_gate(self):
        with tempfile.TemporaryDirectory(prefix='issue81-death-gate-') as temp:
            temp = pathlib.Path(temp)
            test = temp/'DeathGateChecks.java'; test.write_text(HARNESS)
            compiled = subprocess.run(['javac','-d',str(temp),str(JAVA/'Issue81DeathGate.java'),str(test)],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            result = subprocess.run(['java','-cp',str(temp),'com.notifeeexample.DeathGateChecks'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('PASS identity',result.stdout)

    def test_debug_manifest_and_no_main_component(self):
        ns = '{http://schemas.android.com/apk/res/android}'
        manifest = ET.parse(DEBUG/'AndroidManifest.xml')
        receiver = manifest.find('application/receiver')
        self.assertIsNotNone(receiver)
        self.assertEqual(receiver.get(ns+'name'),'com.notifeeexample.Issue81ProcessDeathReceiver')
        self.assertEqual(receiver.get(ns+'permission'),'android.permission.DUMP')
        self.assertEqual(receiver.get(ns+'exported'),'true')
        self.assertIsNone(receiver.get(ns+'process'))
        self.assertIsNone(receiver.find('intent-filter'))
        for variant in ['main','release']:
            base = DEBUG.parent/variant
            if base.exists():
                for path in base.rglob('*'):
                    if path.is_file() and path.suffix in ['.java','.kt','.xml']:
                        self.assertNotIn('Issue81ProcessDeathReceiver',path.read_text())

    def test_receiver_target_and_native_kill(self):
        text = (JAVA/'Issue81ProcessDeathReceiver.java').read_text()
        self.assertEqual(text.count('Process.killProcess('),1)
        self.assertIn('Process.killProcess(Process.myPid());',text)
        for guard in ['Application.getProcessName()', 'FLAG_DEBUGGABLE', 'expectedTicks', 'gate.consume(', 'Notification.FLAG_FOREGROUND_SERVICE', 'KILL_SELF']:
            self.assertIn(guard,text)
        self.assertNotIn('Runtime.getRuntime().exec',text)
        self.assertNotIn('stopService(',text)

if __name__ == '__main__': unittest.main()
