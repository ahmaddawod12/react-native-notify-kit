package app.notifee.core;

// Historical independent PoC witnesses retained as regressions; not a current review verdict.

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 35,
    shadows = {
      ForegroundServiceRunLimitTest.Platform.class,
      ForegroundServiceRunLimitTest.Alarms.class
    })
public class Issue82P7Test extends ForegroundServiceRunLimitTest {
  @After
  public void releaseFixture() throws Exception {
    app.getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
    try {
      fixtureCleanup("a");
    } catch (IllegalStateException absent) {
      /* no owned capture */
    }
  }

  Object fixture(String method, Class<?>[] types, Object... args) throws Exception {
    try {
      Class<?> c = Class.forName("app.notifee.core.Issue82StaleAlarmFixture");
      Method m = c.getDeclaredMethod(method, types);
      m.setAccessible(true);
      return m.invoke(null, args);
    } catch (ClassNotFoundException | NoSuchMethodException absent) {
      return null;
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
      throw new AssertionError(e.getCause());
    }
  }

  Intent capture(String run) throws Exception {
    Intent result =
        (Intent) fixture("capture", new Class<?>[] {Context.class, String.class}, app, run);
    assertNotNull("capture requires a real live native ticket", result);
    return result;
  }

  PendingIntent schedule(String run, String successor, long delay) throws Exception {
    PendingIntent result =
        (PendingIntent)
            fixture(
                "schedule",
                new Class<?>[] {Context.class, String.class, String.class, long.class},
                app,
                run,
                successor,
                delay);
    assertNotNull("fixture must register a distinct AlarmManager PendingIntent", result);
    return result;
  }

  void fixtureCleanup(String run) throws Exception {
    fixture("cleanup", new Class<?>[] {Context.class, String.class}, app, run);
  }

  ForegroundService p7Arm(String run) throws Exception {
    String id = "issue82-alarm-" + run;
    ForegroundService.startAlarmPoc(id.hashCode(), notification(), bundle(id, DATA), run, 1000);
    ForegroundService s = create();
    deliver(s, sender.starts.get(sender.starts.size() - 1), 1);
    return s;
  }

  void successor(ForegroundService s) throws Exception {
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    String id = "issue82-alarm-b";
    ForegroundService.startAlarmPoc(id.hashCode(), notification(), bundle(id, DATA), "b", 1000);
    deliver(s, sender.starts.get(sender.starts.size() - 1), 2);
  }

  void bIntact(
      ForegroundService s,
      ForegroundServiceRunLimitScheduler.Ticket b,
      Object runner,
      Object notification,
      int demote,
      int stop)
      throws Exception {
    assertSame(b, ticket(s));
    assertSame(b.epoch, field(s, "mForegroundEpoch"));
    assertSame(runner, field(s, "mRunnerToken"));
    assertSame(notification, field(null, "mCurrentNotificationBundle"));
    assertTrue(platform(s).foreground);
    assertEquals(demote, platform(s).demotions);
    assertEquals(stop, platform(s).stopCalls);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(b));
    assertFalse(shadowOf(b.pendingIntent).isCanceled());
    assertFalse(Alarms.cancellations.contains(b.pendingIntent));
  }

  @Test
  public void P7_T01_authenticEnvelope() throws Exception {
    ForegroundService s = p7Arm("a");
    Intent captured = capture("a");
    assertTrue(ticket(s).intent.filterEquals(captured));
    assertEquals(ticket(s).intent.getExtras().keySet(), captured.getExtras().keySet());
    assertEquals(ticket(s).wire, captured.getStringExtra("record"));
  }

  @Test
  public void P7_T02_tokenNeverInvented() throws Exception {
    ForegroundService s = p7Arm("a");
    Intent a = capture("a");
    assertEquals(ticket(s).token, a.getStringExtra("token"));
    a.putExtra("token", "foreign");
    successor(s);
    PendingIntent pi = schedule("a", "b", 1);
    assertNotEquals("foreign", shadowOf(pi).getSavedIntent().getStringExtra("token"));
  }

  @Test
  public void P7_T03_distinctProductIdentityEvenBeforeCancellation() throws Exception {
    ForegroundService s = p7Arm("a");
    PendingIntent original = ticket(s).pendingIntent;
    Intent a = capture("a");
    // Keep the product PI alive to prevent cancellation hiding an identity collision.
    ticket(s).pendingIntent = null;
    successor(s);
    try {
      PendingIntent diagnostic = schedule("a", "b", 1);
      assertNotEquals(original, diagnostic);
      assertTrue(shadowOf(diagnostic).getSavedIntent().filterEquals(a));
      assertTrue(diagnostic.isImmutable());
      assertTrue(diagnostic.isBroadcast());
    } finally {
      original.cancel();
    }
  }

  @Test
  public void P7_T04_distinctSuccessorIdentity() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    assertNotEquals(ticket(s).pendingIntent, schedule("a", "b", 1));
  }

  @Test
  public void P7_T05_normalCancelStillWorks() throws Exception {
    ForegroundService s = p7Arm("a");
    PendingIntent original = ticket(s).pendingIntent;
    capture("a");
    successor(s);
    assertTrue(shadowOf(original).isCanceled());
    assertTrue(Alarms.cancellations.contains(original));
  }

  @Test
  public void P7_T06_schedulingPreservesTicketB() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    Object runner = field(s, "mRunnerToken"),
        notification = field(null, "mCurrentNotificationBundle");
    int demote = platform(s).demotions, stop = platform(s).stopCalls;
    long deadline = b.deadline;
    PendingIntent pi = schedule("a", "b", 123);
    assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, Alarms.type);
    assertEquals(SystemClock.elapsedRealtime() + 123, Alarms.deadline);
    assertEquals(pi, Alarms.lastRequested);
    bIntact(s, b, runner, notification, demote, stop);
    assertEquals(deadline, b.deadline);
  }

  @Test
  public void P7_T07_staleRejected() throws Exception {
    ForegroundService s = p7Arm("a");
    Intent a = capture("a");
    successor(s);
    PendingIntent pi = schedule("a", "b", 1);
    assertEquals(
        a.getStringExtra("record"), shadowOf(pi).getSavedIntent().getStringExtra("record"));
    SystemClock.sleep(1100);
    ShadowLog.clear();
    receive(new Intent(shadowOf(pi).getSavedIntent()));
    assertTrue(marker("ALARM_STALE"));
    assertFalse(marker("ALARM_DELIVERED"));
  }

  @Test
  public void P7_T08_T09_noTeardownAndAllStatePreserved() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    Object runner = field(s, "mRunnerToken"),
        notification = field(null, "mCurrentNotificationBundle");
    int demote = platform(s).demotions, stop = platform(s).stopCalls;
    PendingIntent pi = schedule("a", "b", 1);
    SystemClock.sleep(1100);
    receive(new Intent(shadowOf(pi).getSavedIntent()));
    bIntact(s, b, runner, notification, demote, stop);
  }

  @Test
  public void P7_T10_validBExpiry() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    PendingIntent pi = schedule("a", "b", 1);
    SystemClock.sleep(1100);
    receive(new Intent(shadowOf(pi).getSavedIntent()));
    int demote = platform(s).demotions;
    receive(b.intent);
    assertEquals(demote + 1, platform(s).demotions);
    assertTrue(platform(s).stopped);
    assertFalse(store().contains(b.recordKey));
  }

  @Test
  public void P7_T11_duplicateStaleHarmless() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    PendingIntent pi = schedule("a", "b", 1);
    SystemClock.sleep(1100);
    int demote = platform(s).demotions, stop = platform(s).stopCalls;
    Intent old = shadowOf(pi).getSavedIntent();
    receive(old);
    receive(old);
    assertEquals(demote, platform(s).demotions);
    assertEquals(stop, platform(s).stopCalls);
    receive(ticket(s).intent);
    assertEquals(demote + 1, platform(s).demotions);
  }

  @Test
  public void P7_T12_releaseGuard() throws Exception {
    p7Arm("a");
    app.getApplicationInfo().flags &= ~android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
    assertThrows(IllegalStateException.class, () -> capture("a"));
    assertEquals(1, Alarms.requested);
  }

  @Test
  public void P7_T13_foreignAndInvalidRejected() throws Exception {
    ForegroundService s = p7Arm("a");
    assertThrows(IllegalStateException.class, () -> capture("foreign"));
    capture("a");
    assertThrows(IllegalStateException.class, () -> schedule("a", "a", 1));
    successor(s);
    assertThrows(IllegalArgumentException.class, () -> schedule("a", "b", 0));
    assertThrows(IllegalStateException.class, () -> schedule("foreign", "b", 1));
    assertThrows(IllegalStateException.class, () -> schedule("a", "foreign", 1));
    assertEquals(2, Alarms.requested);
  }

  @Test
  public void P7_T14_cleanupOnlyDiagnostic() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    PendingIntent diagnostic = schedule("a", "b", 1);
    assertThrows(IllegalStateException.class, () -> fixtureCleanup("foreign"));
    fixtureCleanup("a");
    assertTrue(shadowOf(diagnostic).isCanceled());
    assertFalse(shadowOf(b.pendingIntent).isCanceled());
    SystemClock.sleep(1100);
    receive(b.intent);
    assertTrue(platform(s).stopped);
  }

  @Test
  public void P7_T15_scheduleFailurePreservesB() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    Alarms.scheduleFailure = true;
    assertThrows(IllegalStateException.class, () -> schedule("a", "b", 1));
    Alarms.scheduleFailure = false;
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(b));
    assertFalse(shadowOf(b.pendingIntent).isCanceled());
    SystemClock.sleep(1100);
    receive(b.intent);
    assertTrue(platform(s).stopped);
  }

  @Test
  public void P7_auditForeignEnvelopeCannotBeCaptured() throws Exception {
    ForegroundService s = p7Arm("a");
    ticket(s).intent.putExtra("token", "foreign");
    assertThrows(IllegalStateException.class, () -> capture("a"));
    assertEquals(1, Alarms.requested);
  }

  @Test
  public void P7_auditNoColdCaptureOrStart() throws Exception {
    assertThrows(IllegalStateException.class, () -> capture("a"));
    assertTrue(sender.starts.isEmpty());
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void P7_auditDuplicateSchedulingIsBounded() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    schedule("a", "b", 1);
    int requested = Alarms.requested;
    assertThrows(IllegalStateException.class, () -> schedule("a", "b", 1));
    assertEquals(requested, Alarms.requested);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(ticket(s)));
  }

  @Test
  public void P7_auditUnknownScheduleAndFailedCancelRemainCleanable() throws Exception {
    ForegroundService s = p7Arm("a");
    capture("a");
    successor(s);
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    Alarms.afterSchedule =
        () -> {
          Alarms.afterSchedule = null;
          Alarms.cancelFailure = true;
          throw new IllegalStateException("unknown reply after registration");
        };
    assertThrows(IllegalStateException.class, () -> schedule("a", "b", 1));
    PendingIntent diagnostic = Alarms.lastRequested;
    assertNotEquals(b.pendingIntent, diagnostic);
    Alarms.cancelFailure = false;
    fixtureCleanup("a");
    assertTrue(shadowOf(diagnostic).isCanceled());
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(b));
    SystemClock.sleep(1100);
    receive(b.intent);
    assertTrue(platform(s).stopped);
  }
}
