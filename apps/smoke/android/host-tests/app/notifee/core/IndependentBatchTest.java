package app.notifee.core;

// Historical independent PoC witnesses retained as regressions; not a current review verdict.

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Reviewer-owned behavioral witnesses; scratch-only. No Android runtime claim. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 35,
    shadows = {
      ForegroundServiceRunLimitTest.Platform.class,
      ForegroundServiceRunLimitTest.Alarms.class
    })
public class IndependentBatchTest extends ForegroundServiceRunLimitTest {
  @After
  public void releaseCapture() {
    Alarms.cancelFailure = false;
    try {
      Issue82StaleAlarmFixture.cleanup(app, "a");
    } catch (IllegalStateException absent) {
      /* no capture */
    }
  }

  ForegroundService startRun(String run, int startId) throws Exception {
    String id = "issue82-alarm-" + run;
    ForegroundService.startAlarmPoc(id.hashCode(), notification(), bundle(id, DATA), run, 1000);
    ForegroundService service = create();
    deliver(service, sender.starts.get(sender.starts.size() - 1), startId);
    assertNotNull(ticket(service));
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(ticket(service)));
    return service;
  }

  ForegroundService destroyAAndStartB(ForegroundService a) throws Exception {
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    controllers.get(0).destroy();
    controllers.clear();
    assertNull(field(null, "sInstance"));
    ForegroundService b = startRun("b", 1);
    assertNotSame(a, b);
    return b;
  }

  @Test
  public void RV01_authenticSeparateOwnersStaleDuplicateAndBExpiry() throws Exception {
    ForegroundService a = startRun("a", 1);
    ForegroundServiceRunLimitScheduler.Ticket ta = ticket(a);
    Intent captured = Issue82StaleAlarmFixture.capture(app, "a");
    assertTrue(ta.intent.filterEquals(captured));
    assertEquals(ta.wire, captured.getStringExtra("record"));
    assertEquals(ta.token, captured.getStringExtra("token"));
    assertEquals(
        new ComponentName(app, ForegroundServiceRunLimitReceiver.class), captured.getComponent());
    ForegroundService b = destroyAAndStartB(a);
    ForegroundServiceRunLimitScheduler.Ticket tb = ticket(b);
    Object runner = field(b, "mRunnerToken"),
        notification = field(null, "mCurrentNotificationBundle");
    Map<String, ?> ledger = new HashMap<>(store().getAll());
    PendingIntent pi = Issue82StaleAlarmFixture.schedule(app, "a", "b", 10);
    assertNotEquals(ta.pendingIntent, pi);
    assertNotEquals(tb.pendingIntent, pi);
    assertEquals(82007, shadowOf(pi).getRequestCode());
    assertTrue(pi.isImmutable());
    assertTrue(pi.isBroadcast());
    assertTrue(captured.filterEquals(shadowOf(pi).getSavedIntent()));
    assertEquals(ta.wire, shadowOf(pi).getSavedIntent().getStringExtra("record"));
    SystemClock.sleep(1100);
    receive(shadowOf(pi).getSavedIntent());
    receive(shadowOf(pi).getSavedIntent());
    assertSame(tb, ticket(b));
    assertSame(tb.epoch, field(b, "mForegroundEpoch"));
    assertSame(runner, field(b, "mRunnerToken"));
    assertSame(notification, field(null, "mCurrentNotificationBundle"));
    assertEquals(ledger, store().getAll());
    noTeardown(b);
    assertTrue(platform(b).foreground);
    Issue82StaleAlarmFixture.cleanup(app, "a");
    assertFalse(shadowOf(tb.pendingIntent).isCanceled());
    receive(tb.intent);
    assertEquals(1, platform(b).demotions);
    assertTrue(platform(b).stopped);
    assertFalse(store().contains(tb.recordKey));
  }

  @Test
  public void RV02_foreignAndUncertainCancelPreserveB() throws Exception {
    ForegroundService a = startRun("a", 1);
    assertThrows(
        IllegalStateException.class, () -> Issue82StaleAlarmFixture.capture(app, "foreign"));
    Issue82StaleAlarmFixture.capture(app, "a");
    ForegroundService b = destroyAAndStartB(a);
    ForegroundServiceRunLimitScheduler.Ticket tb = ticket(b);
    Alarms.afterSchedule =
        () -> {
          Alarms.afterSchedule = null;
          Alarms.cancelFailure = true;
          throw new IllegalStateException("reviewer: reply lost");
        };
    assertThrows(
        IllegalStateException.class, () -> Issue82StaleAlarmFixture.schedule(app, "a", "b", 1));
    int requests = Alarms.requested;
    assertThrows(
        IllegalStateException.class, () -> Issue82StaleAlarmFixture.schedule(app, "a", "b", 1));
    assertEquals(requests, Alarms.requested);
    noTeardown(b);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(tb));
    Alarms.cancelFailure = false;
    Issue82StaleAlarmFixture.cleanup(app, "a");
    SystemClock.sleep(1100);
    receive(tb.intent);
    assertEquals(1, platform(b).demotions);
    assertTrue(platform(b).stopped);
  }

  @Test
  public void RV03_gcReentryAtReadPreservesBAndExpiry() throws Exception {
    Preferences.enabled = true;
    ForegroundService service = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
    String old = gcOrphan(1);
    Preferences.afterRead = promoteSuccessor(service);
    collectCycle(service);
    assertNull(Preferences.afterRead);
    assertFalse(store().contains(old));
    assertSuccessorExpires(service, a);
  }

  @Test
  public void RV04_gcReentryAtDeleteReservesVictimSlot() throws Exception {
    Preferences.enabled = true;
    ForegroundService service = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
    String victim = gcOrphan(0); // Deliberately replace metadata; LIVE_KEYS still preserves A.
    Preferences.beforeRemove =
        () -> {
          promoteSuccessor(service).run();
          try {
            assertEquals(victim, store().getString("gc/slot/0", null));
            assertNotEquals(victim, ticket(service).recordKey);
            assertTrue(store().getAll().containsValue(ticket(service).recordKey));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        };
    collectCycle(service);
    assertNull(Preferences.beforeRemove);
    assertFalse(store().contains(victim));
    assertSuccessorExpires(service, a);
  }

  @Test
  public void RV05_gcReentryAtCommitKeepsBIndexAndAuthority() throws Exception {
    Preferences.enabled = true;
    ForegroundService service = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
    String victim = gcOrphan(0);
    Preferences.beforeCommit = promoteSuccessor(service);
    collectCycle(service);
    assertNull(Preferences.beforeCommit);
    assertFalse(store().contains(victim));
    assertTrue(store().getAll().containsValue(ticket(service).recordKey));
    assertSuccessorExpires(service, a);
  }

  @Test
  public void RV06_pendingArmingWriterCannotShareSlotWithB() throws Exception {
    Preferences.enabled = true;
    ForegroundService service = create();
    request("a", true);
    boolean[] entered = {false};
    Preferences.beforeCommit =
        () -> {
          try {
            ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
            assertNotNull(a);
            promoteSuccessor(service).run();
            ForegroundServiceRunLimitScheduler.Ticket b = ticket(service);
            assertEquals(b.recordKey, store().getString("gc/slot/1", null));
            assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(b));
            entered[0] = true;
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    deliver(service, sender.starts.get(0), 1);
    assertTrue(entered[0]);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(service);
    collectCycle(service);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(b));
    SystemClock.sleep(1100);
    receive(b.intent);
    assertTrue(platform(service).stopped);
  }

  @Test
  public void RV07_currentExpiredAndAmbiguousRecordsRemainUntouched() throws Exception {
    ForegroundService service = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
    String ambiguous = gcSeed(2, UUID.randomUUID().toString(), 41, "UNKNOWN");
    String corrupt = gcOrphan(3);
    store().edit().putString(corrupt, "{broken").commit();
    SystemClock.sleep(2000);
    int cancelled = Alarms.cancelled;
    collectCycle(app);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(a));
    assertTrue(store().contains(ambiguous));
    assertEquals("{broken", store().getString(corrupt, null));
    assertEquals(cancelled, Alarms.cancelled);
    receive(a.intent);
    assertEquals(1, platform(service).demotions);
  }

  @Test
  public void RV08_remoteProcessIsUnknownNotDead() throws Exception {
    String key = gcOrphan(0);
    ServiceInfo info =
        app.getPackageManager().getServiceInfo(new ComponentName(app, ForegroundService.class), 0);
    info.processName += ":possibly_alive";
    shadowOf(app.getPackageManager()).addOrUpdateService(info);
    Map<String, ?> before = new HashMap<>(store().getAll());
    collectCycle(app);
    assertEquals(before, store().getAll());
    assertTrue(store().contains(key));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void RV09_eightPerSweepAndThirtyTwoSlotCoverage() throws Exception {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 32; i++) keys.add(gcOrphan(i));
    for (int sweep = 1; sweep <= 4; sweep++) {
      collect(app);
      assertEquals(8L * sweep, keys.stream().filter(k -> !store().contains(k)).count());
    }
    assertEquals(32, store().getAll().size());
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void RV10_timeoutDoesNotReadGcIndex() throws Exception {
    ForegroundService service = armed("a");
    String orphan = gcOrphan(10);
    Map<String, ?> before = new HashMap<>(store().getAll());
    service.onTimeout(1, DATA);
    assertEquals(before.get(orphan), store().getString(orphan, null));
    assertTrue(platform(service).stopped);
    assertEquals(1, platform(service).demotions);
  }
}
