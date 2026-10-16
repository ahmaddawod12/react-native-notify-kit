package app.notifee.core;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Internal opt-in run limit. Persistence never grants lifecycle authority. */
final class ForegroundServiceRunLimitScheduler {
  static final String ACTION = "app.notifee.core.FGS_RUN_LIMIT_EXPIRE";
  static final String PROCESS = UUID.randomUUID().toString();
  static final String PREFS = "notifee-fgs-run-limit";
  static final int SCHEMA = 1;
  // Index only new tickets. Never enumerate/adopt legacy or unindexed storage.
  static final int GC_SLOTS = 32;
  static final int GC_BATCH = 8;
  private static final Object GC_LOCK = new Object();
  private static final boolean[] RESERVED = new boolean[GC_SLOTS];
  private static final Set<String> LIVE_KEYS = new HashSet<>();
  private static int gcCursor;
  private static boolean gcRunning;

  static final class Request {
    final String run;
    final long duration;
    final boolean publicOption;

    private Request(long duration) {
      this.run = UUID.randomUUID().toString();
      this.duration = duration;
      this.publicOption = true;
    }

    Request(Context context, String run, long duration) {
      if (run == null || !run.matches("[a-zA-Z0-9_-]{1,80}") || duration < 1 || duration > 600000) {
        throw new IllegalArgumentException(
            "Explicit diagnostic run and duration (1..600000ms) required");
      }
      if (!available(context)) throw new IllegalStateException("Smoke debug receiver unavailable");
      this.run = run;
      this.duration = duration;
      this.publicOption = false;
      // Diagnostic fixture retains its reviewed admission-time sweep.
      collectOrphans(context);
    }
  }

  static Request publicRequest(Context context, long duration, int effectiveType) {
    if (effectiveType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        || !available(context, false)) {
      throw new IllegalArgumentException(
          "notification.android.foregroundServiceBestEffortStopAfterMs requires single dataSync and"
              + " an internal receiver in the app FGS process (API 35+)");
    }
    return new Request(duration);
  }

  static boolean available(Context context) {
    return available(context, true);
  }

  private static boolean available(Context context, boolean diagnostic) {
    if (context == null
        || Build.VERSION.SDK_INT < 35
        || (diagnostic
            && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0))
      return false;
    try {
      ActivityInfo receiver =
          context
              .getPackageManager()
              .getReceiverInfo(
                  new ComponentName(context, ForegroundServiceRunLimitReceiver.class), 0);
      ServiceInfo service =
          context
              .getPackageManager()
              .getServiceInfo(new ComponentName(context, ForegroundService.class), 0);
      return (service.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
              != 0
          && receiver.enabled
          && !receiver.exported
          && context.getPackageName().equals(receiver.packageName)
          && java.util.Objects.equals(receiver.processName, service.processName)
          && (diagnostic || context.getApplicationInfo().processName.equals(service.processName));
    } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
      return false;
    }
  }

  static int boot(Context context) {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  static final class Ticket {
    final ForegroundService owner;
    final Object epoch;
    final long generation;
    final long runGeneration;
    final long deadline;
    final long duration;
    final boolean publicOption;
    final int boot;
    final String token = UUID.randomUUID().toString();
    // Physical ownership: even a delayed/reentrant editor for A cannot address B's keys.
    final String recordKey = "ticket/" + PROCESS + "/" + token;
    final String stateKey = recordKey + "/state";
    final String wire;
    final Intent intent;
    PendingIntent pendingIntent;
    boolean valid = true;
    boolean scheduled;

    @androidx.annotation.RequiresApi(35)
    Ticket(
        ForegroundService owner,
        Object epoch,
        long generation,
        long runGeneration,
        Request request,
        long promotedElapsed)
        throws JSONException {
      this.owner = owner;
      this.epoch = epoch;
      this.generation = generation;
      this.runGeneration = runGeneration;
      duration = request.duration;
      publicOption = request.publicOption;
      deadline = Math.addExact(promotedElapsed, request.duration);
      boot = boot(owner);
      if (boot < 0) throw new IllegalStateException("Boot identity unavailable");
      wire =
          new JSONObject()
              .put("schema", SCHEMA)
              .put("boot", boot)
              .put("process", PROCESS)
              .put("instance", owner.alarmPocInstanceId())
              .put("run", request.run)
              .put("epoch", UUID.randomUUID().toString())
              .put("generation", generation)
              .put("runGeneration", runGeneration)
              .put("token", token)
              .put("firstSuccessfulPromotionElapsed", promotedElapsed)
              .put("deadlineElapsed", deadline)
              .put("type", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
              .toString();
      intent =
          new Intent(owner, ForegroundServiceRunLimitReceiver.class)
              .setAction(ACTION)
              // Intent identity includes data; notification hashes and extras are never identity.
              .setData(Uri.parse("notifykit-fgs-run-limit://expiry/" + PROCESS + "/" + token))
              .putExtra("schema", SCHEMA)
              .putExtra("token", token)
              .putExtra("record", wire);
    }
  }

  private static SharedPreferences ledger(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static void write(Ticket ticket, String state, int slot) {
    SharedPreferences.Editor editor = ledger(ticket.owner).edit();
    if ("ARMING".equals(state)) editor.putString("gc/slot/" + slot, ticket.recordKey);
    if (!editor
        .putString(ticket.recordKey, ticket.wire)
        .putString(ticket.stateKey, state)
        .commit()) {
      throw new IllegalStateException("Ledger commit refused");
    }
  }

  static boolean arm(Ticket ticket) {
    String phase = "STORAGE_FAILED";
    int slot = -1;
    try {
      synchronized (GC_LOCK) {
        LIVE_KEYS.add(ticket.recordKey);
      }
      slot = reserveEmptySlot(ticket.owner);
      write(ticket, "ARMING", slot);
      if (!ticket.valid) return false;
      phase = "SCHEDULE_FAILED";
      ticket.pendingIntent =
          PendingIntent.getBroadcast(ticket.owner, 0, ticket.intent, PendingIntent.FLAG_IMMUTABLE);
      ticket
          .owner
          .getSystemService(AlarmManager.class)
          .setAndAllowWhileIdle(
              AlarmManager.ELAPSED_REALTIME_WAKEUP, ticket.deadline, ticket.pendingIntent);
      if (!ticket.valid) return false;
      phase = "STORAGE_FAILED";
      write(ticket, "ARMED", slot);
      if (!ticket.valid) return false;
      ticket.scheduled = true;
      marker("ALARM_ARMED", ticket, "requestedDurationIsVoluntary=true");
      return true;
    } catch (RuntimeException failure) {
      marker(phase, ticket, failure.toString());
      return false;
    } finally {
      if (slot >= 0) releaseSlot(slot);
      if (!ticket.scheduled) cancel(ticket);
    }
  }

  static boolean matches(Ticket ticket, Intent intent) {
    try {
      return ticket.valid
          && ticket.scheduled
          && boot(ticket.owner) == ticket.boot
          && intent != null
          && ticket.intent.filterEquals(intent)
          && intent.getIntExtra("schema", -1) == SCHEMA
          && ticket.token.equals(intent.getStringExtra("token"))
          && ticket.wire.equals(intent.getStringExtra("record"));
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  static boolean ledgerMatches(Ticket ticket) {
    try {
      SharedPreferences prefs = ledger(ticket.owner);
      return ticket.wire.equals(prefs.getString(ticket.recordKey, null))
          && "ARMED".equals(prefs.getString(ticket.stateKey, null));
    } catch (RuntimeException failure) {
      marker("STORAGE_FAILED", ticket, failure.toString());
      return false;
    }
  }

  // Caller has already revoked the live authority. Cancellation cannot revoke an in-flight send.
  static void cancel(Ticket ticket) {
    ticket.valid = false;
    synchronized (GC_LOCK) {
      LIVE_KEYS.remove(ticket.recordKey);
    }
    if (ticket.pendingIntent != null) {
      try {
        ticket.owner.getSystemService(AlarmManager.class).cancel(ticket.pendingIntent);
      } catch (RuntimeException failure) {
        marker("CANCEL_FAILED", ticket, failure.toString());
      }
      try {
        ticket.pendingIntent.cancel();
      } catch (RuntimeException failure) {
        marker("CANCEL_FAILED", ticket, failure.toString());
      }
    }
    try {
      SharedPreferences prefs = ledger(ticket.owner);
      // The comparison rejects corrupt records; it is not a CAS. Safety comes from the
      // immutable ticket-specific keys at commit, including reentrancy inside remove/commit.
      // Unindexed legacy/global orphans stay inert: never adopt or bulk-delete them.
      if (ticket.wire.equals(prefs.getString(ticket.recordKey, null))
          && !prefs.edit().remove(ticket.recordKey).remove(ticket.stateKey).commit()) {
        marker("STORAGE_FAILED", ticket, "cancellation commit refused");
      }
    } catch (RuntimeException failure) {
      marker("STORAGE_FAILED", ticket, failure.toString());
    }
  }

  private static boolean reserveSlot(int slot) {
    synchronized (GC_LOCK) {
      if (RESERVED[slot]) return false;
      RESERVED[slot] = true;
      return true;
    }
  }

  private static void releaseSlot(int slot) {
    synchronized (GC_LOCK) {
      RESERVED[slot] = false;
    }
  }

  private static boolean liveKey(String key) {
    synchronized (GC_LOCK) {
      return LIVE_KEYS.contains(key);
    }
  }

  private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

  private static boolean nonnegativeInteger(Object value) {
    return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0;
  }

  private static boolean ticketKey(String key) {
    return key != null && key.matches("ticket/" + UUID_PATTERN + "/" + UUID_PATTERN);
  }

  private static int reserveEmptySlot(Context context) {
    SharedPreferences prefs = ledger(context);
    for (int slot = 0; slot < GC_SLOTS; slot++) {
      if (!reserveSlot(slot)) continue;
      boolean claimed = false;
      try {
        String key = prefs.getString("gc/slot/" + slot, null);
        if (key == null
            || (ticketKey(key)
                && !liveKey(key)
                && !prefs.contains(key)
                && !prefs.contains(key + "/state"))) {
          claimed = true;
          return slot;
        }
      } finally {
        if (!claimed) releaseSlot(slot);
      }
    }
    marker("GC_CAPACITY", null, "32 slots occupied/ambiguous; diagnostic protection not armed");
    throw new IllegalStateException("Diagnostic ledger index full; preserving existing records");
  }

  @androidx.annotation.RequiresApi(35)
  private static boolean orphanRecord(String key, String wire, String state) {
    if (!ticketKey(key)
        || wire == null
        || wire.length() > 2048
        || !("ARMING".equals(state) || "ARMED".equals(state))) return false;
    try {
      JSONObject record = new JSONObject(wire);
      String process = record.getString("process");
      String token = record.getString("token");
      return Integer.valueOf(SCHEMA).equals(record.get("schema"))
          && record.get("boot") instanceof Integer
          && record.getInt("boot") >= 0
          && key.equals("ticket/" + process + "/" + token)
          && record.getString("instance").matches(UUID_PATTERN)
          && record.getString("epoch").matches(UUID_PATTERN)
          && record.getString("run").matches("[a-zA-Z0-9_-]{1,80}")
          && nonnegativeInteger(record.get("generation"))
          && nonnegativeInteger(record.get("runGeneration"))
          && nonnegativeInteger(record.get("firstSuccessfulPromotionElapsed"))
          && nonnegativeInteger(record.get("deadlineElapsed"))
          && record.getLong("deadlineElapsed") > record.getLong("firstSuccessfulPromotionElapsed")
          && record.getLong("deadlineElapsed") - record.getLong("firstSuccessfulPromotionElapsed")
              <= 18000000
          && Integer.valueOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
              .equals(record.get("type"))
          // Another incarnation can never own a ticket here. For this incarnation, only
          // absence from the native arm registry proves non-ownership; age is irrelevant.
          && (!PROCESS.equals(process) || !liveKey(key));
    } catch (JSONException | RuntimeException ambiguous) {
      return false;
    }
  }

  static void collectOrphans(Context context) {
    // A different configured Service process may still be alive. Its PROCESS UUID is not
    // proof of a previous incarnation. Admission runs outside lifecycle locks, so verify
    // the manifest process before touching storage. Unknown process identity keeps data.
    try {
      if (Build.VERSION.SDK_INT < 35
          || !Application.getProcessName()
              .equals(
                  context
                      .getPackageManager()
                      .getServiceInfo(new ComponentName(context, ForegroundService.class), 0)
                      .processName)) {
        marker("GC_RESULT", null, "examined=0 skipped=service_process_mismatch");
        return;
      }
    } catch (PackageManager.NameNotFoundException | RuntimeException unknown) {
      marker("GC_RESULT", null, "examined=0 skipped=service_process_unknown");
      return;
    }
    int first;
    synchronized (GC_LOCK) {
      if (gcRunning) return;
      gcRunning = true;
      first = gcCursor;
      gcCursor = (gcCursor + GC_BATCH) % GC_SLOTS;
    }
    int examined = 0, removed = 0, retained = 0, failures = 0;
    try {
      SharedPreferences prefs = ledger(context);
      for (int offset = 0; offset < GC_BATCH; offset++) {
        int slot = (first + offset) % GC_SLOTS;
        examined++;
        if (!reserveSlot(slot)) {
          retained++;
          continue;
        }
        try {
          String key = prefs.getString("gc/slot/" + slot, null);
          if (key == null) continue;
          if (!ticketKey(key) || liveKey(key)) {
            retained++;
            continue;
          }
          String wire = prefs.getString(key, null);
          String state = prefs.getString(key + "/state", null);
          if (wire == null && state == null) continue;
          if (!orphanRecord(key, wire, state) || liveKey(key)) {
            retained++;
            continue;
          }
          // This slot is reserved across all external calls. Neither its immutable victim
          // address nor its index can be reassigned by a reentrant/new arm. Never remove
          // the reusable index slot here: an old editor must only contain victim keys.
          if (prefs.edit().remove(key).remove(key + "/state").commit()) removed++;
          else failures++;
        } catch (RuntimeException failure) {
          failures++;
        } finally {
          releaseSlot(slot);
        }
      }
    } catch (RuntimeException failure) {
      failures++;
    } finally {
      synchronized (GC_LOCK) {
        gcRunning = false;
      }
      marker(
          "GC_RESULT",
          null,
          "examined="
              + examined
              + " removed="
              + removed
              + " retained="
              + retained
              + " failures="
              + failures
              + " unindexed=unknown");
    }
  }

  static void marker(String event, Ticket ticket, String detail) {
    // android.util.Log only: no EventBus, ReactContext, JS event or headless task.
    Log.i(
        "ForegroundServiceRunLimit",
        event
            + " elapsed="
            + SystemClock.elapsedRealtime()
            + (ticket == null ? "" : " record=" + ticket.wire)
            + " detail="
            + detail);
  }
}
