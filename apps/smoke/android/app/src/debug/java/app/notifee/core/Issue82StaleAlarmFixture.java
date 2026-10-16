package app.notifee.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONException;
import org.json.JSONObject;

/** DUMP-Activity-only P7 fixture. Copies native authority for observation, never creates it. */
final class Issue82StaleAlarmFixture {
  private static final int REQUEST_CODE =
      82007; // Product always uses 0; preserve Intent unchanged.
  private static Capture
      captured; // One bounded, process-local diagnostic; never restored from disk.

  private static final class Capture {
    final String run;
    final ForegroundServiceRunLimitScheduler.Ticket original;
    final Intent envelope;
    PendingIntent delivery;

    Capture(String run, ForegroundServiceRunLimitScheduler.Ticket original) {
      this.run = run;
      this.original = original;
      envelope = new Intent(original.intent);
    }
  }

  private static Object read(Object owner, String name) throws ReflectiveOperationException {
    Field field = ForegroundService.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object lifecycleLock() {
    try {
      return read(null, "sLifecycleLock");
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Diagnostic source contract changed", failure);
    }
  }

  private static void available(Context context) {
    if (!ForegroundServiceRunLimitScheduler.available(context))
      throw new IllegalStateException("Debug PoC unavailable");
  }

  // Read-only reflection is confined to this Debug source set. It avoids adding even a
  // package-private capture/admission hook to the already-qualified ForegroundService core.
  private static ForegroundServiceRunLimitScheduler.Ticket live(Context context, String run) {
    available(context);
    if (run == null || !run.matches("[a-zA-Z0-9_-]{1,80}")) {
      throw new IllegalStateException("Explicit diagnostic run required");
    }
    try {
      synchronized (read(null, "sLock")) {
        Object owner = read(null, "sInstance");
        if (owner == null) throw new IllegalStateException("No live owner");
        ForegroundServiceRunLimitScheduler.Ticket ticket =
            (ForegroundServiceRunLimitScheduler.Ticket) read(owner, "mAlarmPoc");
        Method owns =
            ForegroundService.class.getDeclaredMethod(
                "ownsAlarmPoc", ForegroundServiceRunLimitScheduler.Ticket.class);
        owns.setAccessible(true);
        if (ticket == null
            || !ticket.valid
            || !ticket.scheduled
            || !Boolean.TRUE.equals(owns.invoke(owner, ticket))
            || !("issue82-alarm-" + run).equals(ForegroundService.mCurrentNotificationId)
            || !run.equals(new JSONObject(ticket.wire).getString("run"))
            || !ForegroundServiceRunLimitScheduler.matches(ticket, ticket.intent)
            || !ForegroundServiceRunLimitScheduler.ledgerMatches(ticket)
            || !Boolean.TRUE.equals(owns.invoke(owner, ticket))
            || !("issue82-alarm-" + run).equals(ForegroundService.mCurrentNotificationId)) {
          throw new IllegalStateException("No matching live diagnostic owner");
        }
        return ticket;
      }
    } catch (ReflectiveOperationException | JSONException failure) {
      throw new IllegalStateException("Diagnostic source contract changed", failure);
    }
  }

  static Intent capture(Context context, String run) {
    synchronized (lifecycleLock()) {
      if (captured != null) throw new IllegalStateException("Clean the previous capture first");
      ForegroundServiceRunLimitScheduler.Ticket ticket = live(context, run);
      captured = new Capture(run, ticket);
      Log.i(
          "Issue82AlarmPocHarness",
          "P7_CAPTURED record=" + ticket.wire + " uri=" + ticket.intent.getData());
      return new Intent(captured.envelope);
    }
  }

  private static Capture owned(Context context, String run) {
    available(context);
    if (captured == null || !captured.run.equals(run)) {
      throw new IllegalStateException("No process-local capture for this run");
    }
    return captured;
  }

  static PendingIntent schedule(Context context, String run, String successorRun, long delayMs) {
    synchronized (lifecycleLock()) {
      Capture a = owned(context, run);
      if (delayMs < 1 || delayMs > 600000)
        throw new IllegalArgumentException("delayMs 1..600000 required");
      ForegroundServiceRunLimitScheduler.Ticket b = live(context, successorRun);
      if (a.original.valid
          || a.original == b
          || a.original.epoch == b.epoch
          || a.run.equals(successorRun)
          || a.envelope.filterEquals(b.intent)) {
        throw new IllegalStateException("A must be retired and a distinct B must be foreground");
      }
      if (a.delivery != null)
        throw new IllegalStateException("A diagnostic delivery already exists");
      PendingIntent delivery =
          PendingIntent.getBroadcast(
              context, REQUEST_CODE, new Intent(a.envelope), PendingIntent.FLAG_IMMUTABLE);
      a.delivery = delivery; // Own it before Binder; unknown/partial scheduling stays cleanable.
      try {
        long deadline = Math.addExact(SystemClock.elapsedRealtime(), delayMs);
        context
            .getSystemService(AlarmManager.class)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, delivery);
        if (live(context, successorRun) != b)
          throw new IllegalStateException("B changed during scheduling");
        Log.i(
            "Issue82AlarmPocHarness",
            "P7_STALE_SCHEDULED record="
                + a.original.wire
                + " requestCode="
                + REQUEST_CODE
                + " requestedDeliveryElapsed="
                + deadline
                + " successorRecord="
                + b.wire
                + " deliveryNotYetProven=true");
        return delivery;
      } catch (RuntimeException failure) {
        // No ordinary ticket/PI/notification is touched. Keep ownership if cancel itself fails.
        try {
          context.getSystemService(AlarmManager.class).cancel(delivery);
          delivery.cancel();
          a.delivery = null;
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }
  }

  static void cleanup(Context context, String run) {
    synchronized (lifecycleLock()) {
      Capture a = owned(context, run);
      if (a.delivery != null) {
        context.getSystemService(AlarmManager.class).cancel(a.delivery);
        a.delivery.cancel();
      }
      captured = null;
      Log.i("Issue82AlarmPocHarness", "P7_CLEANED run=" + run + " productTicketsUntouched=true");
    }
  }
}
