package app.notifee.core;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;

/** Explicit shell/DUMP-only debug harness. No React Native admission or teardown dependency. */
public final class Issue82AlarmPocActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Intent intent = getIntent();
    String run = intent.getStringExtra("run");
    String command = intent.getStringExtra("command");
    try {
      if (Build.VERSION.SDK_INT < 35) throw new IllegalStateException("API 35+ required");
      if (run == null || !run.matches("[a-zA-Z0-9_-]{1,80}")) {
        throw new IllegalArgumentException("Explicit run required");
      }
      if (!ForegroundServiceRunLimitScheduler.available(this))
        throw new IllegalStateException("Unavailable");
      ContextHolder.setApplicationContext(getApplicationContext());
      String id = "issue82-alarm-" + run;
      if (("stop".equals(command) || "update".equals(command))
          && !id.equals(ForegroundService.mCurrentNotificationId)) {
        throw new IllegalStateException("No current smoke notification for this run");
      }
      if ("capture".equals(command)) {
        Issue82StaleAlarmFixture.capture(this, run);
      } else if ("schedule-stale".equals(command)) {
        Issue82StaleAlarmFixture.schedule(
            this, run, intent.getStringExtra("successorRun"), intent.getLongExtra("delayMs", -1));
      } else if ("cleanup-stale".equals(command)) {
        Issue82StaleAlarmFixture.cleanup(this, run);
      } else if ("stop".equals(command)) {
        ForegroundService.stop();
      } else if ("arm".equals(command) || "update".equals(command)) {
        String channel = "notifee-fgs-run-limit";
        getSystemService(NotificationManager.class)
            .createNotificationChannel(
                new NotificationChannel(
                    channel, "Issue 82 voluntary expiry PoC", NotificationManager.IMPORTANCE_LOW));
        Notification notification =
            new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Voluntary expiry PoC " + run)
                .setContentText("Diagnostic single-run duration; no framework budget guarantee")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        Bundle bundle = new Bundle();
        bundle.putString("id", id);
        bundle.putString("title", "Voluntary expiry PoC " + run);
        Bundle data = new Bundle();
        data.putString("smokeScenario", "issue82-alarm-poc");
        data.putString("run", run);
        bundle.putBundle("data", data);
        Bundle android = new Bundle();
        android.putString("channelId", channel);
        android.putIntegerArrayList(
            "foregroundServiceTypes",
            new ArrayList<>(
                Collections.singletonList(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)));
        bundle.putBundle("android", android);
        if ("arm".equals(command)) {
          ForegroundService.startAlarmPoc(
              id.hashCode(), notification, bundle, run, intent.getLongExtra("durationMs", -1));
        } else {
          ForegroundService.start(id.hashCode(), notification, bundle);
        }
      } else {
        throw new IllegalArgumentException(
            "command must be arm, update, stop, capture, schedule-stale or cleanup-stale");
      }
      Log.i("Issue82AlarmPocHarness", "REQUEST_RETURNED command=" + command + " run=" + run);
    } catch (RuntimeException error) {
      Log.e("Issue82AlarmPocHarness", "REQUEST_REJECTED command=" + command + " run=" + run, error);
    } finally {
      finish();
    }
  }
}
