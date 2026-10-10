package com.notifeeexample;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;

/** Debug source-set only: explicit shell command, existing DUMP permission. */
public final class Issue81ProcessDeathReceiver extends BroadcastReceiver {
  private static final String PACKAGE = "com.notifeeexample";
  private static final String ACTION = PACKAGE + ".ISSUE81_PROCESS_DEATH";
  private static final String TAG = "ISSUE81_DEATH";
  private static final Issue81DeathGate gate = new Issue81DeathGate();
  private static String armedCorrelation;

  @Override public void onReceive(Context context, Intent intent) {
    try {
      if (intent == null || !ACTION.equals(intent.getAction()) || intent.getComponent() == null
          || !getClass().getName().equals(intent.getComponent().getClassName())
          || !PACKAGE.equals(intent.getComponent().getPackageName())
          || !PACKAGE.equals(context.getPackageName())
          || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
          || !PACKAGE.equals(Application.getProcessName())) {
        reject("component_or_process"); return;
      }
      Issue81DeathGate.Identity self = new Issue81DeathGate.Identity(
          Process.myPid(), Process.myUid(), startTicks(), Application.getProcessName());
      Issue81DeathGate.Identity expected = new Issue81DeathGate.Identity(
          intent.getIntExtra("expectedPid", -1), intent.getIntExtra("expectedUid", -1),
          intent.getLongExtra("expectedTicks", -1), PACKAGE);
      String token = intent.getStringExtra("token");
      String correlation = intent.getStringExtra("correlationId");
      if (!expected.same(self) || correlation == null || !correlation.matches("[a-zA-Z0-9_-]{1,80}")) {
        reject("identity_or_correlation"); return;
      }
      // Read the actual active native notification, not a JS Promise. This is
      // a safety guard, NOT evidence that onStartCommand is still in flight.
      int sequence = nativeSequence(context, correlation);
      if (sequence < 1) { reject("no_matching_foreground_notification"); return; }
      String operation = intent.getStringExtra("operation");
      if ("arm".equals(operation)) {
        if (!gate.arm(token, expected, self, SystemClock.elapsedRealtime(), 10000)) {
          reject("arm_denied"); return;
        }
        armedCorrelation = correlation;
        marker("ARMED", self, correlation, sequence);
      } else if ("fire".equals(operation)) {
        if (!correlation.equals(armedCorrelation) || !gate.consume(token, self, SystemClock.elapsedRealtime())) {
          reject("fire_denied"); return;
        }
        marker("KILL_SELF", self, correlation, sequence);
        // No caller-controlled PID ever reaches the syscall. No service STOP,
        // clean exit, force-stop, policy change or simulated death.
        Process.killProcess(Process.myPid());
      } else { reject("unknown_operation"); }
    } catch (Exception error) {
      reject("invalid_command_or_readback:" + error.getClass().getSimpleName());
    }
  }

  private static long startTicks() throws Exception {
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/stat"))) {
      String line = reader.readLine();
      // comm can contain spaces/parentheses; fields after the final ')' begin
      // at field 3, so field 22 is offset 19.
      return Long.parseLong(line.substring(line.lastIndexOf(')') + 2).split("\\s+")[19]);
    }
  }

  private static int nativeSequence(Context context, String correlation) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    String prefix = "issue81:" + correlation + ":update:";
    for (StatusBarNotification item : manager.getActiveNotifications()) {
      Notification notification = item.getNotification();
      CharSequence body = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
      if (PACKAGE.equals(item.getPackageName()) && item.getId() == "issue81-fgs".hashCode()
          && "issue81_fgs".equals(notification.getChannelId())
          && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0
          && body != null && body.toString().startsWith(prefix)) {
        return Integer.parseInt(body.toString().substring(prefix.length()));
      }
    }
    return -1;
  }

  private static void marker(String event, Issue81DeathGate.Identity self, String correlation, int sequence) {
    // Deliberately never logs the capability token.
    Log.i(TAG, event + " pid=" + self.pid + " uid=" + self.uid + " ticks=" + self.ticks
        + " process=" + self.process + " correlation=" + correlation + " nativeSequence=" + sequence
        + " elapsedMs=" + SystemClock.elapsedRealtime());
  }
  private static void reject(String reason) { Log.w(TAG, "REJECT " + reason); }
}
