package app.notifee.core;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

/**
 * Causal host oracle: acceptance, delivery, promotion, alarm registration and stop are separate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 35,
    shadows = {
      ForegroundServiceRunLimitTest.Platform.class,
      ForegroundServiceRunLimitTest.Alarms.class
    })
public class ForegroundServiceRunLimitTest {
  static final int DATA = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
  static final String RECEIVER = "app.notifee.core.ForegroundServiceRunLimitReceiver";
  static final String ACTION = "app.notifee.core.FGS_RUN_LIMIT_EXPIRE";
  Context app;
  Sender sender;
  final List<ServiceController<? extends ForegroundService>> controllers = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 42);
    ServiceInfo info = new ServiceInfo();
    info.packageName = app.getPackageName();
    info.name = ForegroundService.class.getName();
    info.processName = app.getPackageName();
    Field type = ServiceInfo.class.getDeclaredField("mForegroundServiceType");
    type.setAccessible(true);
    type.setInt(info, DATA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    shadowOf(app.getPackageManager()).addOrUpdateService(info);
    ActivityInfo receiver = new ActivityInfo();
    receiver.name = RECEIVER;
    receiver.packageName = app.getPackageName();
    receiver.processName = app.getPackageName();
    receiver.exported = false;
    receiver.enabled = true;
    shadowOf(app.getPackageManager()).addOrUpdateReceiver(receiver);
    app.getSystemService(android.app.NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "alarm-poc", "PoC", android.app.NotificationManager.IMPORTANCE_LOW));
    sender = new Sender(app);
    ContextHolder.setApplicationContext(sender);
    Alarms.reset();
    Platform.reset();
    Preferences.resetFaults();
  }

  @After
  public void cleanup() throws Exception {
    for (int i = controllers.size() - 1; i >= 0; i--) controllers.get(i).destroy();
    ((Map<?, ?>) field(null, "sPendingStarts")).clear();
    set(null, "sStopRequest", null);
    set(null, "sRunGeneration", 0L);
    set(null, "sInstance", null);
    set(null, "sStateOwner", null);
    shadowOf(Looper.getMainLooper()).idle();
    ContextHolder.setApplicationContext(app);
  }

  static Object field(Object owner, String name) throws Exception {
    Field f = ForegroundService.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(owner);
  }

  static void set(Object owner, String name, Object value) throws Exception {
    Field f = ForegroundService.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(owner, value);
  }

  Bundle bundle(String id, int type) {
    Bundle b = new Bundle();
    b.putString("id", id);
    b.putString("title", "latest-" + id);
    Bundle a = new Bundle();
    a.putString("channelId", "alarm-poc");
    a.putIntegerArrayList("foregroundServiceTypes", new ArrayList<>(Arrays.asList(type)));
    b.putBundle("android", a);
    return b;
  }

  Notification notification() {
    return new NotificationCompat.Builder(app, "alarm-poc")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("PoC")
        .build();
  }

  void request(String id, boolean opt) throws Exception {
    if (!opt) {
      ForegroundService.start(id.hashCode(), notification(), bundle(id, DATA));
      return;
    }
    // Baseline adapter intentionally runs the ordinary START when the experimental entrypoint
    // is absent. RED is missing alarm/teardown behavior, never a compile error.
    try {
      Method m =
          ForegroundService.class.getDeclaredMethod(
              "startAlarmPoc",
              int.class,
              Notification.class,
              Bundle.class,
              String.class,
              long.class);
      m.setAccessible(true);
      m.invoke(
          null,
          id.hashCode(),
          notification(),
          bundle(id, DATA),
          UUID.randomUUID().toString(),
          1000L);
    } catch (NoSuchMethodException absent) {
      ForegroundService.start(id.hashCode(), notification(), bundle(id, DATA));
    } catch (InvocationTargetException e) {
      throw (RuntimeException) e.getCause();
    }
  }

  ForegroundService create() {
    ServiceController<? extends ForegroundService> c =
        Preferences.enabled || Preferences.failCommit > 0
            ? Robolectric.buildService(StorageService.class)
            : Robolectric.buildService(ForegroundService.class);
    controllers.add(c);
    return c.create().get();
  }

  void deliver(ForegroundService s, Intent i, int id) {
    Platform p = (Platform) shadowOf(s);
    p.accepted = Math.max(p.accepted, id);
    p.delivered = id;
    s.onStartCommand(i, 0, id);
  }

  ForegroundService armed(String id) throws Exception {
    request(id, true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(sender.starts.size() - 1), 1);
    assertEquals(
        "one actual AlarmManager registration: "
            + ShadowLog.getLogsForTag("ForegroundServiceRunLimit"),
        1,
        Alarms.scheduled.size());
    return s;
  }

  Intent alarm() {
    assertFalse("alarm must have actually been scheduled", Alarms.scheduled.isEmpty());
    return new Intent(shadowOf(Alarms.scheduled.get(Alarms.scheduled.size() - 1)).getSavedIntent());
  }

  void receive(Intent i) throws Exception {
    try {
      ((BroadcastReceiver) Class.forName(RECEIVER).getDeclaredConstructor().newInstance())
          .onReceive(app, i);
    } catch (ClassNotFoundException baseline) {
      /* no baseline delivery path */
    }
  }

  void expire() throws Exception {
    SystemClock.sleep(1100);
    receive(alarm());
  }

  Platform platform(ForegroundService s) {
    return (Platform) shadowOf(s);
  }

  void noTeardown(ForegroundService s) {
    assertEquals(0, platform(s).demotions);
    assertEquals(0, platform(s).stopCalls);
  }

  boolean marker(String marker) {
    return ShadowLog.getLogsForTag("ForegroundServiceRunLimit").stream()
        .anyMatch(l -> l.msg.contains(marker));
  }

  @Test
  public void T01_defaultDisabled() throws Exception {
    request("a", false);
    deliver(create(), sender.starts.get(0), 1);
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void T02_onlyAfterSuccessfulPromotion() throws Exception {
    request("a", true);
    assertEquals(0, Alarms.requested);
    ForegroundService s = create();
    Platform.beforePromotion = () -> assertEquals(0, Alarms.requested);
    deliver(s, sender.starts.get(0), 1);
    assertEquals(1, Alarms.requested);
    assertEquals(1, Alarms.scheduled.size());
    assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, Alarms.type);
    assertTrue(marker("ALARM_ARMED"));
  }

  @Test
  public void T03_deniedPromotionNeverArms() throws Exception {
    request("a", true);
    ForegroundService s = create();
    Platform.promotionFailure = new ForegroundServiceStartNotAllowedException("denied");
    deliver(s, sender.starts.get(0), 1);
    assertEquals(0, Alarms.requested);
    assertEquals(1, platform(s).stopCalls);
  }

  @Test
  public void T04_elapsedClock() throws Exception {
    ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
    long before = SystemClock.elapsedRealtime();
    armed("a");
    assertEquals(before + 1000, Alarms.deadline);
  }

  @Test
  public void T05_deepSleepCounts() throws Exception {
    ForegroundService s = armed("a");
    long uptime = SystemClock.uptimeMillis();
    ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(1100));
    assertEquals(uptime, SystemClock.uptimeMillis());
    receive(alarm());
    assertEquals(1, platform(s).demotions);
    assertEquals(1, platform(s).stopCalls);
  }

  @Test
  public void T06_sameIdPreservesDeadlineAndLatestState() throws Exception {
    ForegroundService s = armed("a");
    long first = Alarms.deadline;
    SystemClock.sleep(100);
    request("a", false);
    deliver(s, sender.starts.get(1), 2);
    assertEquals(1, Alarms.requested);
    assertEquals(first, Alarms.deadline);
    assertEquals(
        "latest-a", ((Bundle) field(null, "mCurrentNotificationBundle")).getString("title"));
    expire();
    assertEquals(2, platform(s).lastStopId);
    assertFalse(ForegroundService.repostIfActive("a"));
  }

  @Test
  public void T07_uniqueImmutableExplicitPendingIntent() throws Exception {
    ForegroundService s = armed("a");
    PendingIntent a = Alarms.scheduled.get(0);
    Intent old = alarm();
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    request("a", true);
    deliver(s, sender.starts.get(1), 2);
    PendingIntent b = Alarms.scheduled.get(1);
    assertNotEquals(a, b);
    assertTrue(a.isImmutable());
    assertTrue(a.isBroadcast());
    assertEquals(new ComponentName(app, RECEIVER), old.getComponent());
    assertEquals(ACTION, old.getAction());
  }

  @Test
  public void T08_hashCollisionCannotAliasAlarm() throws Exception {
    assertEquals("Aa".hashCode(), "BB".hashCode());
    ForegroundService s = armed("Aa");
    PendingIntent a = Alarms.scheduled.get(0);
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    request("BB", true);
    deliver(s, sender.starts.get(1), 2);
    assertNotEquals(a, Alarms.scheduled.get(1));
  }

  @Test
  public void T09_invalidEnvelope() throws Exception {
    ForegroundService s = armed("a");
    SystemClock.sleep(1100);
    receive(new Intent(alarm()).setAction("bad"));
    receive(new Intent(alarm()).putExtra("token", "bad"));
    receive(new Intent(alarm()).putExtra("schema", 999));
    receive(new Intent(alarm()).setData(android.net.Uri.parse("bad://token")));
    noTeardown(s);
    receive(alarm());
    assertEquals(1, platform(s).demotions);
  }

  @Test
  public void T10_earlyDeliveryDoesNotConsume() throws Exception {
    ForegroundService s = armed("a");
    receive(alarm());
    noTeardown(s);
    assertTrue(marker("ALARM_EARLY"));
    expire();
    assertEquals(1, platform(s).demotions);
  }

  @Test
  public void T11_lateDeliveryUsesCurrentOwner() throws Exception {
    ForegroundService s = armed("a");
    SystemClock.sleep(300000);
    receive(alarm());
    assertEquals(Arrays.asList("demote", "stop:1"), platform(s).order);
    assertTrue(marker("ALARM_LATE"));
  }

  @Test
  public void T12_duplicateDeliveryRetiresOnce() throws Exception {
    ForegroundService s = armed("a");
    Intent i = alarm();
    SystemClock.sleep(1100);
    receive(i);
    receive(i);
    assertEquals(1, platform(s).demotions);
    assertEquals(1, platform(s).stopCalls);
  }

  @Test
  public void T13_oldAlarmCannotStopSuccessor() throws Exception {
    ForegroundService s = armed("a");
    Intent old = alarm();
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    request("b", true);
    deliver(s, sender.starts.get(1), 2);
    int demotions = platform(s).demotions, stops = platform(s).stopCalls;
    SystemClock.sleep(1100);
    receive(old);
    assertEquals(demotions, platform(s).demotions);
    assertEquals(stops, platform(s).stopCalls);
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    receive(alarm());
    assertEquals(demotions + 1, platform(s).demotions);
  }

  @Test
  public void T14_explicitStopInvalidatesBeforePostedStop() throws Exception {
    ForegroundService s = armed("a");
    Intent old = alarm();
    ForegroundService.stop();
    SystemClock.sleep(1100);
    receive(old);
    noTeardown(s);
    assertTrue(Alarms.cancelled > 0);
  }

  @Test
  public void T15_cancelFailureAndInFlightDelivery() throws Exception {
    ForegroundService s = armed("a");
    Intent old = alarm();
    Alarms.cancelFailure = true;
    ForegroundService.stop();
    SystemClock.sleep(1100);
    receive(old);
    noTeardown(s);
    assertTrue(marker("CANCEL_FAILED"));
  }

  @Test
  public void T16_frameworkTimeoutInvalidatesAlarm() throws Exception {
    ForegroundService s = armed("a");
    Intent old = alarm();
    s.onTimeout(1, DATA);
    int n = platform(s).demotions;
    SystemClock.sleep(1100);
    receive(old);
    assertEquals(n, platform(s).demotions);
    assertTrue(Alarms.cancelled > 0);
  }

  @Test
  public void T17_newInstanceSameIdRejectsOldToken() throws Exception {
    ForegroundService a = armed("a");
    Intent old = alarm();
    controllers.get(0).destroy();
    controllers.clear();
    request("a", true);
    ForegroundService b = create();
    deliver(b, sender.starts.get(1), 1);
    SystemClock.sleep(1100);
    receive(old);
    noTeardown(b);
    receive(alarm());
    assertEquals(1, platform(b).demotions);
  }

  @Test
  public void T18_coldReceiverHasNoOwner() throws Exception {
    armed("a");
    Intent i = alarm();
    controllers.get(0).destroy();
    controllers.clear();
    SystemClock.sleep(1100);
    int starts = sender.starts.size();
    receive(i);
    assertNull(field(null, "sInstance"));
    assertEquals(starts, sender.starts.size());
    assertTrue(marker("NO_MATCHING_LIVE_INSTANCE"));
  }

  @Test
  public void T19_unknownPendingStartPreserved() throws Exception {
    ForegroundService s = armed("a");
    sender.failure = new RuntimeException("unknown Binder reply");
    assertThrows(RuntimeException.class, () -> request("pending", false));
    expire();
    assertEquals(1, platform(s).demotions);
    assertEquals(0, platform(s).stopCalls);
    assertEquals(1, ((Map<?, ?>) field(null, "sPendingStarts")).size());
    assertTrue(marker("STOP_DEFERRED"));
  }

  @Test
  public void T20_acceptedUndeliveredStartPreserved() throws Exception {
    ForegroundService s = armed("a");
    request("pending", false);
    platform(s).accepted = 2;
    expire();
    assertEquals(1, platform(s).demotions);
    assertEquals(0, platform(s).stopCalls);
    assertEquals(1, ((Map<?, ?>) field(null, "sPendingStarts")).size());
  }

  @Test
  public void T21_runnerCompletionCannotRetireSuccessor() throws Exception {
    ForegroundService s = armed("a");
    Object runner = field(s, "mRunnerToken");
    Platform.duringDemotion =
        () -> {
          try {
            request("b", false);
            deliver(s, sender.starts.get(1), 2);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    expire();
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    assertNotSame(runner, field(s, "mRunnerToken"));
    assertEquals(0, platform(s).stopCalls);
  }

  @Test
  public void T22_typeReplacementInvalidatesWithoutRearm() throws Exception {
    ForegroundService s = armed("a");
    Intent old = alarm();
    request("a", false);
    Intent update = sender.starts.get(1);
    update.putExtra(
        "notificationBundle",
        bundle("a", DATA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK));
    deliver(s, update, 2);
    SystemClock.sleep(1100);
    receive(old);
    noTeardown(s);
    assertEquals(1, Alarms.requested);
    assertTrue(Alarms.cancelled > 0);
  }

  @Test
  public void T23_schedulingFailureCannotAuthorizeDelivery() throws Exception {
    Alarms.scheduleFailure = true;
    request("a", true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals(1, Alarms.requested);
    assertEquals(0, Alarms.scheduled.size());
    assertTrue(marker("SCHEDULE_FAILED"));
    SystemClock.sleep(1100);
    receive(new Intent(shadowOf(Alarms.lastRequested).getSavedIntent()));
    noTeardown(s);
  }

  @Test
  public void T24_corruptLedgerCannotAuthorizeDelivery() throws Exception {
    ForegroundService s = armed("a");
    Intent i = alarm();
    app.getSharedPreferences("notifee-fgs-run-limit", 0)
        .edit()
        .putString(recordKey(ticket(s)), "corrupt")
        .commit();
    SystemClock.sleep(1100);
    receive(i);
    noTeardown(s);
    assertTrue(marker("LEDGER_INVALID"));
  }

  @Test
  public void T25_demotionFailureStillAttemptsConditionalStopOnce() throws Exception {
    ForegroundService s = armed("a");
    Platform.demotionFailure = true;
    platform(s).stopResult = false;
    expire();
    receive(alarm());
    assertEquals(1, platform(s).demotions);
    assertEquals(1, platform(s).stopCalls);
    assertTrue(marker("DEMOTION_FAILED"));
    assertTrue(marker("STOP_REFUSED"));
    assertNotNull(field(s, "mRunnerToken"));
    assertFalse(ForegroundService.repostIfActive("a"));
  }

  @Test
  public void T26_frameworkNewerStartRefusesStop() throws Exception {
    ForegroundService s = armed("a");
    platform(s).accepted = 2;
    expire();
    assertFalse(platform(s).stopped);
    assertEquals(1, platform(s).stopCalls);
    assertTrue(marker("STOP_REFUSED"));
  }

  @Test
  public void T27_stopThrowsOutcomeIsDistinct() throws Exception {
    ForegroundService s = armed("a");
    Platform.stopFailure = true;
    expire();
    assertEquals(1, platform(s).stopCalls);
    assertTrue(marker("STOP_FAILED"));
    assertTrue(marker("DEMOTION_RETURNED"));
  }

  @Test
  public void T28_noReactContextOrContextHolderRequired() throws Exception {
    ForegroundService s = armed("a");
    ContextHolder.setApplicationContext(null);
    expire();
    assertEquals(1, platform(s).demotions);
    assertTrue(platform(s).stopped);
  }

  @Test
  public void T29_receiverNeverColdStartsService() throws Exception {
    armed("a");
    Intent i = alarm();
    set(null, "sInstance", null);
    set(null, "sStateOwner", null);
    int starts = sender.starts.size();
    SystemClock.sleep(1100);
    receive(i);
    assertEquals(starts, sender.starts.size());
    assertTrue(marker("NO_MATCHING_LIVE_INSTANCE"));
  }

  @Test
  public void T30_voluntaryExpiryDoesNotPublishFgTimeout() throws Exception {
    ForegroundService s = armed("a");
    List<Object> events = new ArrayList<>();
    try (org.mockito.MockedStatic<EventBus> bus = org.mockito.Mockito.mockStatic(EventBus.class)) {
      bus.when(() -> EventBus.post(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              i -> {
                events.add(i.getArgument(0));
                return null;
              });
      expire();
      assertTrue(events.isEmpty());
      assertEquals(1, platform(s).demotions);
    }
  }

  @Test
  public void auditBootChangeCannotDemote() throws Exception {
    ForegroundService s = armed("a");
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 43);
    expire();
    noTeardown(s);
  }

  @Test
  public void auditProcessIncarnationIsNotRestoredFromDisk() throws Exception {
    ForegroundService s = armed("a");
    Intent i = alarm();
    SystemClock.sleep(1100);
    String wire = i.getStringExtra("record");
    String other = new org.json.JSONObject(wire).put("process", "previous-process").toString();
    app.getSharedPreferences("notifee-fgs-run-limit", 0)
        .edit()
        .putString(recordKey(ticket(s)), other)
        .commit();
    receive(new Intent(i).putExtra("record", other));
    noTeardown(s);
    // Even a matching persisted prior-process record cannot create a native ticket.
    set(s, "mAlarmPoc", null);
    receive(new Intent(i).putExtra("record", other));
    noTeardown(s);
  }

  @Test
  public void auditEpochFenceIndependentOfEnvelope() throws Exception {
    ForegroundService s = armed("a");
    Object ticket = field(s, "mAlarmPoc");
    Intent update = new Intent(sender.starts.get(0));
    update.removeExtra("notifee.foregroundServiceStartRequestId");
    update.putExtra(
        "notificationBundle",
        bundle("a", DATA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK));
    deliver(s, update, 2);
    // Adversarial fixture presents an old authentic ticket after epoch replacement. The native
    // epoch fence must still refuse it, even with valid token, boot, generation and ledger.
    Field valid = ticket.getClass().getDeclaredField("valid");
    valid.setAccessible(true);
    valid.set(ticket, true);
    Field wire = ticket.getClass().getDeclaredField("wire");
    wire.setAccessible(true);
    app.getSharedPreferences("notifee-fgs-run-limit", 0)
        .edit()
        .putString(
            recordKey((ForegroundServiceRunLimitScheduler.Ticket) ticket),
            (String) wire.get(ticket))
        .putString(stateKey((ForegroundServiceRunLimitScheduler.Ticket) ticket), "ARMED")
        .commit();
    set(s, "mAlarmPoc", ticket);
    // Preserve the exact dataSync state to isolate epoch identity from the type guard.
    ForegroundService.mCurrentForegroundServiceType = DATA;
    expire();
    noTeardown(s);
  }

  @Test
  public void auditOptInCannotAttachToExistingRun() throws Exception {
    request("a", false);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertThrows(IllegalStateException.class, () -> request("a", true));
    assertEquals(0, Alarms.requested);
    noTeardown(s);
  }

  @Test
  public void auditMixedInitialTypeRejected() throws Exception {
    request("a", true);
    Intent i = sender.starts.get(0);
    i.putExtra(
        "notificationBundle",
        bundle("a", DATA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK));
    deliver(create(), i, 1);
    assertEquals(0, Alarms.requested);
    assertTrue(marker("ARM_REJECTED"));
  }

  @Test
  @Config(sdk = 34)
  public void auditApi34CannotOptIn() throws Exception {
    assertThrows(IllegalStateException.class, () -> request("a", true));
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void auditExportedOrOtherProcessReceiverCannotOptIn() throws Exception {
    ActivityInfo receiver =
        app.getPackageManager().getReceiverInfo(new ComponentName(app, RECEIVER), 0);
    receiver.exported = true;
    shadowOf(app.getPackageManager()).addOrUpdateReceiver(receiver);
    assertThrows(IllegalStateException.class, () -> request("a", true));
    receiver.exported = false;
    receiver.processName = app.getPackageName() + ":other";
    shadowOf(app.getPackageManager()).addOrUpdateReceiver(receiver);
    assertThrows(IllegalStateException.class, () -> request("a", true));
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void auditNonDebugCannotOptIn() throws Exception {
    app.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
    assertThrows(IllegalStateException.class, () -> request("a", true));
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void auditIncompleteArmingLedgerIsNotAuthority() throws Exception {
    ForegroundService s = armed("a");
    app.getSharedPreferences("notifee-fgs-run-limit", 0)
        .edit()
        .putString(stateKey(ticket(s)), "ARMING")
        .commit();
    expire();
    noTeardown(s);
    assertTrue(marker("LEDGER_INVALID"));
  }

  @Test
  public void auditStopReplyCannotRetireReentrantSuccessor() throws Exception {
    ForegroundService s = armed("a");
    Platform.duringStop =
        () -> {
          try {
            request("b", false);
            deliver(s, sender.starts.get(1), 2);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    expire();
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    assertFalse((boolean) field(s, "mStopInitiated"));
    assertNotNull(field(s, "mRunnerToken"));
  }

  public static final class Runners {
    final List<app.notifee.core.event.ForegroundServiceEvent> events = new ArrayList<>();

    @org.greenrobot.eventbus.Subscribe
    public void on(app.notifee.core.event.ForegroundServiceEvent event) {
      events.add(event);
    }
  }

  @Test
  public void auditRunnerCompletionDuringExpiryAndAfterSuccessor() throws Exception {
    Runners runners = new Runners();
    EventBus.register(runners);
    try {
      ForegroundService s = armed("a");
      assertEquals(1, runners.events.size());
      Platform.duringDemotion = () -> runners.events.get(0).setCompletionResult();
      expire();
      assertEquals(1, platform(s).demotions);
      request("b", false);
      deliver(s, sender.starts.get(1), 2);
      Object successorRunner = field(s, "mRunnerToken");
      runners.events.get(0).setCompletionResult();
      assertSame(successorRunner, field(s, "mRunnerToken"));
      assertEquals("b", ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(runners);
    }
  }

  @Test
  public void auditRunnerCompletionBeforeExpiryInvalidatesAlarm() throws Exception {
    Runners runners = new Runners();
    EventBus.register(runners);
    try {
      ForegroundService s = armed("a");
      Intent i = alarm();
      runners.events.get(0).setCompletionResult();
      int n = platform(s).demotions;
      SystemClock.sleep(1100);
      receive(i);
      assertEquals(n, platform(s).demotions);
      assertEquals(0, platform(s).stopCalls);
      assertTrue(Alarms.cancelled > 0);
    } finally {
      EventBus.unregister(runners);
    }
  }

  @Test
  public void auditReentrantDuplicateDeliveryDuringDemotion() throws Exception {
    ForegroundService s = armed("a");
    Intent i = alarm();
    Platform.duringDemotion =
        () -> {
          try {
            receive(i);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    expire();
    assertEquals(1, platform(s).demotions);
    assertEquals(1, platform(s).stopCalls);
  }

  @Test
  public void auditNewPendingStartDuringDemotion() throws Exception {
    ForegroundService s = armed("a");
    Platform.duringDemotion =
        () -> {
          try {
            request("b", false);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    expire();
    assertEquals(0, platform(s).stopCalls);
    assertEquals(1, ((Map<?, ?>) field(null, "sPendingStarts")).size());
  }

  @Test
  public void auditStorageFailureBeforeSchedule() throws Exception {
    Preferences.failCommit = 1;
    request("a", true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals(0, Alarms.requested);
    assertTrue(marker("STORAGE_FAILED"));
    noTeardown(s);
  }

  @Test
  public void auditStorageFailureAfterSchedule() throws Exception {
    Preferences.failCommit = 2;
    request("a", true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals(
        ShadowLog.getLogsForTag("ForegroundServiceRunLimit").toString(),
        1,
        Alarms.scheduled.size());
    assertTrue(Alarms.cancelled > 0);
    SystemClock.sleep(1100);
    receive(alarm());
    noTeardown(s);
    assertTrue(marker("STORAGE_FAILED"));
  }

  @Test
  public void auditStorageReadThrows() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    Preferences.readFailure = true;
    expire();
    noTeardown(s);
    assertTrue(marker("STORAGE_FAILED"));
  }

  @Test
  public void auditDeathDuringArmingCannotAuthorizeColdDelivery() throws Exception {
    Alarms.afterSchedule =
        () -> {
          controllers.get(0).destroy();
          controllers.clear();
        };
    request("a", true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals(
        ShadowLog.getLogsForTag("ForegroundServiceRunLimit").toString(),
        1,
        Alarms.scheduled.size());
    assertTrue(Alarms.cancelled > 0);
    SystemClock.sleep(1100);
    receive(alarm());
    noTeardown(s);
    assertTrue(marker("NO_MATCHING_LIVE_INSTANCE"));
  }

  ForegroundServiceRunLimitScheduler.Ticket ticket(ForegroundService s) throws Exception {
    return (ForegroundServiceRunLimitScheduler.Ticket) field(s, "mAlarmPoc");
  }

  // Baseline adapter changes only the address, never the expected ownership behavior.
  String recordKey(ForegroundServiceRunLimitScheduler.Ticket t) throws Exception {
    try {
      Field key = t.getClass().getDeclaredField("recordKey");
      key.setAccessible(true);
      return (String) key.get(t);
    } catch (NoSuchFieldException baseline) {
      return "record";
    }
  }

  String stateKey(ForegroundServiceRunLimitScheduler.Ticket t) throws Exception {
    String key = recordKey(t);
    return key.equals("record") ? "state" : key + "/state";
  }

  SharedPreferences store() {
    return app.getSharedPreferences(ForegroundServiceRunLimitScheduler.PREFS, 0);
  }

  // Fixed-slot contract: absence on the preimage is a behavioral no-op, never a compile RED.
  void collect(Context context) throws Exception {
    try {
      Method method =
          ForegroundServiceRunLimitScheduler.class.getDeclaredMethod(
              "collectOrphans", Context.class);
      method.setAccessible(true);
      method.invoke(null, context);
    } catch (NoSuchMethodException preimage) {
      // The preimage retains every orphan.
    } catch (InvocationTargetException failure) {
      throw new AssertionError(
          "cleanup must report storage failure without throwing", failure.getCause());
    }
  }

  String gcSeed(int slot, String process, int boot, String state) throws Exception {
    String token = UUID.randomUUID().toString();
    String key = "ticket/" + process + "/" + token;
    String wire =
        new org.json.JSONObject()
            .put("schema", 1)
            .put("boot", boot)
            .put("process", process)
            .put("instance", UUID.randomUUID().toString())
            .put("run", "orphan")
            .put("epoch", UUID.randomUUID().toString())
            .put("generation", 0)
            .put("runGeneration", 0)
            .put("token", token)
            .put("firstSuccessfulPromotionElapsed", 1)
            .put("deadlineElapsed", 1001)
            .put("type", DATA)
            .toString();
    store()
        .edit()
        .putString("gc/slot/" + slot, key)
        .putString(key, wire)
        .putString(key + "/state", state)
        .commit();
    return key;
  }

  String gcOrphan(int slot) throws Exception {
    return gcSeed(slot, UUID.randomUUID().toString(), 42, "ARMED");
  }

  void collectCycle(Context context) throws Exception {
    for (int n = 0; n < 4; n++) collect(context);
  }

  @Test
  public void GC_T01_noOrphanNoEffect() throws Exception {
    Map<String, ?> before = new HashMap<>(store().getAll());
    collectCycle(app);
    assertEquals(before, store().getAll());
  }

  @Test
  public void GC_T02_provenOrphanRemoved() throws Exception {
    String key = gcOrphan(0);
    collectCycle(app);
    assertFalse("proven orphan must be removed", store().contains(key));
    assertFalse(store().contains(key + "/state"));
  }

  @Test
  public void GC_T03_currentTicketPreserved() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    collectCycle(app);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(a));
    assertFalse(shadowOf(a.pendingIntent).isCanceled());
    expire();
    assertEquals(1, platform(s).demotions);
  }

  @Test
  public void GC_T04_cleanupAPreservesB() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    store()
        .edit()
        .putString("gc/slot/10", a.recordKey)
        .putString(a.recordKey, a.wire)
        .putString(a.stateKey, "ARMED")
        .commit();
    collectCycle(app);
    assertFalse(store().contains(a.recordKey));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void GC_T05_reentrantBAtRemove() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    String key = gcOrphan(1);
    Preferences.beforeRemove = promoteSuccessor(s);
    collectCycle(s);
    assertNull("removal witness must execute", Preferences.beforeRemove);
    assertFalse(store().contains(key));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void GC_T06_ambiguousRetained() throws Exception {
    String key = gcSeed(0, UUID.randomUUID().toString(), 42, "UNKNOWN");
    String wire = store().getString(key, null);
    collectCycle(app);
    assertEquals(wire, store().getString(key, null));
  }

  @Test
  public void GC_T07_corruptHasNoAuthority() throws Exception {
    String key = gcOrphan(0);
    store().edit().putString(key, "{corrupt").commit();
    collectCycle(app);
    assertEquals("{corrupt", store().getString(key, null));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void GC_T08_priorProcessRemoved() throws Exception {
    String key = gcSeed(0, UUID.randomUUID().toString(), 42, "ARMED");
    collectCycle(app);
    assertFalse(store().contains(key));
  }

  @Test
  public void GC_T09_priorBootRemoved() throws Exception {
    String key = gcSeed(0, UUID.randomUUID().toString(), 41, "ARMED");
    collectCycle(app);
    assertFalse(store().contains(key));
  }

  @Test
  public void GC_T10_armingCompleteRemovedIncompleteRetained() throws Exception {
    String full = gcSeed(0, UUID.randomUUID().toString(), 42, "ARMING");
    String partial = gcSeed(1, UUID.randomUUID().toString(), 42, "ARMING");
    store().edit().putString(partial, "{}").commit();
    collectCycle(app);
    assertFalse(store().contains(full));
    assertEquals("{}", store().getString(partial, null));
  }

  @Test
  public void GC_T11_failedScheduleResidueRemoved() throws Exception {
    Preferences.enabled = true;
    request("a", true);
    ForegroundService s = create();
    Alarms.afterSchedule =
        () -> {
          Alarms.afterSchedule = null;
          Preferences.commitThrows = true;
        };
    deliver(s, sender.starts.get(0), 1);
    String key = store().getString("gc/slot/0", null);
    assertNotNull("failed scheduling must remain indexed", key);
    Preferences.commitThrows = false;
    collectCycle(s);
    assertFalse(store().contains(key));
    receive(alarm());
    noTeardown(s);
  }

  @Test
  public void GC_T12_duplicateHarmless() throws Exception {
    String key = gcOrphan(0);
    collectCycle(app);
    Map<String, ?> before = new HashMap<>(store().getAll());
    collectCycle(app);
    assertEquals(before, store().getAll());
    assertFalse(store().contains(key));
  }

  @Test
  public void GC_T13_readFailureRetains() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = create();
    String key = gcOrphan(0);
    Preferences.readFailure = true;
    collectCycle(s);
    Preferences.readFailure = false;
    assertTrue(store().contains(key));
    assertTrue(marker("GC_RESULT"));
  }

  @Test
  public void GC_T14_commitFailureRetainsAndReports() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = create();
    String key = gcOrphan(0);
    Preferences.commitThrows = true;
    collectCycle(s);
    Preferences.commitThrows = false;
    assertTrue(store().contains(key));
    assertTrue(marker("GC_RESULT"));
    collectCycle(s);
    assertFalse(store().contains(key));
  }

  @Test
  public void GC_T15_boundedEight() throws Exception {
    List<String> keys = new ArrayList<>();
    for (int n = 0; n < 32; n++) keys.add(gcOrphan(n));
    collect(app);
    long removed = keys.stream().filter(k -> !store().contains(k)).count();
    assertEquals("exactly eight slots per invocation", 8, removed);
  }

  @Test
  public void GC_T16_moreThanBatchEventuallyRemoved() throws Exception {
    List<String> keys = new ArrayList<>();
    for (int n = 0; n < 32; n++) keys.add(gcOrphan(n));
    collectCycle(app);
    for (String key : keys) assertFalse(store().contains(key));
  }

  @Test
  public void GC_T17_noGcInTimeout() throws Exception {
    ForegroundService s = armed("a");
    String key = gcOrphan(10);
    ShadowLog.clear();
    s.onTimeout(1, DATA);
    assertTrue(store().contains(key));
    assertFalse(marker("GC_RESULT"));
  }

  @Test
  public void GC_T18_foreignAndLegacyKeysUntouched() throws Exception {
    store()
        .edit()
        .putString("foreign", "payload")
        .putString("record", "legacy")
        .putString("gc/slot/0", "foreign")
        .commit();
    collectCycle(app);
    assertEquals("payload", store().getString("foreign", null));
    assertEquals("legacy", store().getString("record", null));
  }

  @Test
  public void GC_T19_noPendingIntentCancellation() throws Exception {
    ForegroundService s = armed("a");
    gcOrphan(10);
    int cancelled = Alarms.cancelled;
    collectCycle(app);
    assertEquals(cancelled, Alarms.cancelled);
    assertFalse(shadowOf(ticket(s).pendingIntent).isCanceled());
  }

  @Test
  public void GC_T20_successorFullExpiry() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    String key = gcOrphan(10);
    collectCycle(app);
    assertFalse(store().contains(key));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void GC_auditFullAmbiguousIndexRefusesOnlyNewProtection() throws Exception {
    for (int n = 0; n < 32; n++) gcSeed(n, UUID.randomUUID().toString(), 42, "UNKNOWN");
    Map<String, ?> before = new HashMap<>(store().getAll());
    request("a", true);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals(before, store().getAll());
    assertEquals(0, Alarms.requested);
    assertNull(ticket(s));
    assertTrue(platform(s).foreground);
    noTeardown(s);
    assertTrue(marker("GC_CAPACITY"));
  }

  @Test
  public void GC_auditReentrantBAtCommitKeepsIndexAndExpiry() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    String key = gcOrphan(10);
    Preferences.beforeCommit = promoteSuccessor(s);
    collectCycle(s);
    assertNull(Preferences.beforeCommit);
    assertFalse(store().contains(key));
    assertTrue(store().getAll().containsValue(ticket(s).recordKey));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void GC_auditWrongTypeIndexAndUnindexedRetained() throws Exception {
    String unindexed = gcOrphan(0);
    store().edit().putInt("gc/slot/0", 7).commit();
    collectCycle(app);
    assertTrue(store().contains(unindexed));
    assertEquals(7, store().getInt("gc/slot/0", 0));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void GC_auditFractionalAndNoncanonicalRecordsAreAmbiguous() throws Exception {
    String[] fields = {
      "boot", "generation", "runGeneration", "deadlineElapsed", "instance", "epoch"
    };
    for (int slot = 0; slot < fields.length; slot++) {
      String key = gcOrphan(slot);
      org.json.JSONObject wire = new org.json.JSONObject(store().getString(key, null));
      wire.put(fields[slot], slot < 4 ? 42.5 : "------------------------------------");
      store().edit().putString(key, wire.toString()).commit();
    }
    Map<String, ?> before = new HashMap<>(store().getAll());
    collectCycle(app);
    assertEquals("ambiguous numbers and UUIDs must be retained", before, store().getAll());
  }

  @Test
  public void GC_auditAnotherConfiguredServiceProcessIsNotPreviousIncarnation() throws Exception {
    String key = gcOrphan(0);
    ServiceInfo service =
        app.getPackageManager().getServiceInfo(new ComponentName(app, ForegroundService.class), 0);
    service.processName = app.getPackageName() + ":remote";
    shadowOf(app.getPackageManager()).addOrUpdateService(service);
    collectCycle(app);
    assertTrue(
        "another potentially live service process is not a proven orphan", store().contains(key));
  }

  @Test
  public void GC_auditActiveTicketSurvivesChangedBootAndExpiredAge() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    SystemClock.sleep(10000000);
    Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 43);
    collectCycle(app);
    assertTrue(ForegroundServiceRunLimitScheduler.ledgerMatches(a));
    assertFalse(shadowOf(a.pendingIntent).isCanceled());
    noTeardown(s);
  }

  @Test
  public void GC_auditRepeatedCancelledRunsReuseFixedSlots() throws Exception {
    ForegroundService s = create();
    for (int n = 0; n < 65; n++) {
      request("a", true);
      deliver(s, sender.starts.get(sender.starts.size() - 1), n + 1);
      assertNotNull(ticket(s));
      ForegroundService.stop();
      shadowOf(Looper.getMainLooper()).idle();
    }
    assertEquals(65, Alarms.requested);
    assertEquals(1, store().getAll().size()); // reusable index slot only; no orphan growth.
  }

  @Test
  public void auditOptedSameIdCannotRearmOrExtend() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    SystemClock.sleep(100);
    try {
      request("a", true);
      deliver(s, sender.starts.get(sender.starts.size() - 1), 2);
    } catch (IllegalStateException expectedAdmissionRejection) {
      // Ordinary updates remain allowed; this explicit new diagnostic arm is rejected.
    }
    assertEquals("same-ID opt-in must not schedule a second alarm", 1, Alarms.requested);
    assertSame(a, ticket(s));
    assertEquals(a.deadline, ticket(s).deadline);
    expire();
    assertEquals(1, platform(s).demotions);
  }

  Runnable promoteSuccessor(ForegroundService s) {
    return () -> {
      try {
        ForegroundService.stop();
        shadowOf(Looper.getMainLooper()).idle();
        request("b", true);
        deliver(s, sender.starts.get(sender.starts.size() - 1), 2);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    };
  }

  void assertSuccessorExpires(ForegroundService s, ForegroundServiceRunLimitScheduler.Ticket a)
      throws Exception {
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    assertNotNull("B must retain live native authority", b);
    assertNotSame(a, b);
    assertNotSame(a.epoch, b.epoch);
    assertNotEquals(a.token, b.token);
    assertNotEquals(a.runGeneration, b.runGeneration);
    assertTrue(b.valid);
    assertTrue(b.scheduled);
    assertTrue(platform(s).foreground);
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    assertEquals(b.wire, store().getString(recordKey(b), null));
    assertEquals("ARMED", store().getString(stateKey(b), null));
    assertTrue(ForegroundServiceRunLimitScheduler.matches(b, alarm()));
    assertFalse(shadowOf(b.pendingIntent).isCanceled());
    assertFalse(Alarms.cancellations.contains(b.pendingIntent));
    long deadline = b.deadline;
    int requested = Alarms.requested;
    int demotions = platform(s).demotions, stops = platform(s).stopCalls;
    SystemClock.sleep(1100);
    receive(a.intent);
    assertEquals("A cannot demote B", demotions, platform(s).demotions);
    assertEquals(stops, platform(s).stopCalls);
    try (org.mockito.MockedStatic<EventBus> bus = org.mockito.Mockito.mockStatic(EventBus.class)) {
      receive(b.intent);
      bus.verifyNoInteractions();
    }
    assertEquals(demotions + 1, platform(s).demotions);
    assertEquals(stops + 1, platform(s).stopCalls);
    assertEquals(2, platform(s).lastStopId);
    assertTrue(platform(s).stopped);
    assertFalse(platform(s).foreground);
    assertFalse(b.valid);
    assertNull(ticket(s));
    assertNull(store().getString(recordKey(b), null));
    assertEquals(deadline, b.deadline);
    assertEquals(requested, Alarms.requested);
    assertTrue(marker("DEMOTION_ATTEMPTED"));
    assertTrue(marker("STOP_ACCEPTED"));
  }

  @Test
  public void B82_W1_reviewerCleanupPreservesSuccessorLedger() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Preferences.afterRead = promoteSuccessor(s);
    s.onTimeout(1, DATA);
    assertNull("read interleaving must execute", Preferences.afterRead);
    assertEquals(
        "old cleanup must preserve successor record",
        ticket(s).wire,
        store().getString(recordKey(ticket(s)), null));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W1_reviewerCleanupMustNotDisableSuccessorExpiry() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    Preferences.afterRead = promoteSuccessor(s);
    s.onTimeout(1, DATA);
    assertNull(Preferences.afterRead);
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    int before = platform(s).demotions;
    SystemClock.sleep(1100);
    receive(alarm());
    assertEquals("B must retain its expiry", before + 1, platform(s).demotions);
  }

  @Test
  public void B82_W2_replacementBeforeRemoval() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Preferences.beforeRemove = promoteSuccessor(s);
    s.onTimeout(1, DATA);
    assertNull(Preferences.beforeRemove);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W2_replacementDuringCommit() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Preferences.beforeCommit = promoteSuccessor(s);
    s.onTimeout(1, DATA);
    assertNull(Preferences.beforeCommit);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W3_legitimateAndAbsentCleanup() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    assertEquals(a.wire, store().getString(recordKey(a), null));
    ForegroundService.stop();
    assertNull(store().getString(recordKey(a), null));
    assertNull(store().getString(stateKey(a), null));
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertNull(store().getString(recordKey(a), null));
    assertFalse(a.valid);
  }

  @Test
  public void B82_W4_cleanupAfterSuccessor() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W5_duplicateCleanupWithSameRunName() throws Exception {
    ForegroundService s = armed("b");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    ForegroundServiceRunLimitScheduler.cancel(a);
    request("b", true);
    deliver(s, sender.starts.get(1), 2);
    ForegroundServiceRunLimitScheduler.cancel(a);
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W6_lateSchedulingFailure() throws Exception {
    ForegroundService s = create();
    ForegroundServiceRunLimitScheduler.Ticket[] a =
        new ForegroundServiceRunLimitScheduler.Ticket[1];
    Alarms.afterSchedule =
        () -> {
          Alarms.afterSchedule = null;
          try {
            a[0] = ticket(s);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
          promoteSuccessor(s).run();
          throw new IllegalStateException("late A scheduling failure");
        };
    request("a", true);
    deliver(s, sender.starts.get(0), 1);
    assertTrue(marker("SCHEDULE_FAILED"));
    assertFalse(a[0].valid);
    assertSuccessorExpires(s, a[0]);
  }

  @Test
  public void B82_W7_cancelFailureDuringReplacement() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Alarms.duringCancel = promoteSuccessor(s);
    Alarms.cancelFailure = true;
    s.onTimeout(1, DATA);
    assertTrue(marker("CANCEL_FAILED"));
    Alarms.cancelFailure = false;
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W8_storageReadFailureDuringOldCleanup() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    Preferences.readFailure = true;
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertFalse(a.valid);
    assertTrue(marker("STORAGE_FAILED"));
    Preferences.readFailure = false;
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W8_storageWriteFailureDuringReplacement() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Preferences.beforeCommit =
        () -> {
          promoteSuccessor(s).run();
          Preferences.commitThrows = true;
        };
    s.onTimeout(1, DATA);
    assertTrue(marker("STORAGE_FAILED"));
    Preferences.commitThrows = false;
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W9_corruptRecordCannotBeDeletedAsOwned() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    store().edit().putString(recordKey(a), "corrupt").commit();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertEquals("corrupt", store().getString(recordKey(a), null));
    promoteSuccessor(s).run();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_W10_fullExpiryAfterLateCleanup() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    s.onTimeout(1, DATA);
    promoteSuccessor(s).run();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_auditLateArmingCommitCannotOverwriteSuccessor() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = create();
    ForegroundServiceRunLimitScheduler.Ticket[] a =
        new ForegroundServiceRunLimitScheduler.Ticket[1];
    Preferences.beforeCommit =
        () -> {
          try {
            a[0] = ticket(s);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
          promoteSuccessor(s).run();
        };
    request("a", true);
    deliver(s, sender.starts.get(0), 1);
    assertNull(Preferences.beforeCommit);
    assertFalse(a[0].valid);
    assertNull(store().getString(recordKey(a[0]), null));
    assertSuccessorExpires(s, a[0]);
  }

  @Test
  public void B82_auditLateArmedCommitCannotOverwriteSuccessor() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = create();
    ForegroundServiceRunLimitScheduler.Ticket[] a =
        new ForegroundServiceRunLimitScheduler.Ticket[1];
    Alarms.afterSchedule =
        () -> {
          Alarms.afterSchedule = null;
          try {
            a[0] = ticket(s);
          } catch (Exception e) {
            throw new AssertionError(e);
          }
          Preferences.beforeCommit = promoteSuccessor(s);
        };
    request("a", true);
    deliver(s, sender.starts.get(0), 1);
    assertNull(Preferences.beforeCommit);
    assertFalse(a[0].valid);
    assertNull(store().getString(recordKey(a[0]), null));
    assertSuccessorExpires(s, a[0]);
  }

  @Test
  public void B82_auditOwnedOrphanCleanupPreservesForeignPayload() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
    // A foreign wire at A's address is corruption, not authority to remove that payload.
    store().edit().putString(recordKey(a), b.wire).putString(stateKey(a), "ARMED").commit();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertEquals(b.wire, store().getString(recordKey(a), null));
    // An actual own orphan may still be cleaned without touching the successor.
    store().edit().putString(recordKey(a), a.wire).commit();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertNull(store().getString(recordKey(a), null));
    assertNull(store().getString(stateKey(a), null));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_auditWrongStorageTypeFailsClosed() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    promoteSuccessor(s).run();
    store().edit().putInt(recordKey(a), 42).commit();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertEquals(42, store().getInt(recordKey(a), 0));
    assertTrue(marker("STORAGE_FAILED"));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_auditRefusedCleanupCanRetryOnlyOwnOrphan() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Preferences.failCommit = Preferences.commits + 1;
    s.onTimeout(1, DATA);
    assertEquals(a.wire, store().getString(recordKey(a), null));
    assertFalse(a.valid);
    assertTrue(marker("STORAGE_FAILED"));
    Preferences.failCommit = 0;
    promoteSuccessor(s).run();
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertNull(store().getString(recordKey(a), null));
    assertSuccessorExpires(s, a);
  }

  @Test
  public void B82_auditLegacyAndPriorProcessOrphansNeverGrantAuthority() throws Exception {
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    String legacy = a.wire;
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    String oldKey = "ticket/previous-process/old-token";
    store()
        .edit()
        .putString("record", legacy)
        .putString("state", "ARMED")
        .putString(oldKey, legacy)
        .putString(oldKey + "/state", "corrupt")
        .commit();
    set(null, "sInstance", null);
    set(null, "sStateOwner", null);
    Map<String, ?> snapshot = new HashMap<>(store().getAll());
    int starts = sender.starts.size();
    SystemClock.sleep(1100);
    receive(a.intent);
    assertEquals(starts, sender.starts.size());
    assertNull(field(null, "sInstance"));
    assertEquals(snapshot, store().getAll());
    // A fresh service needs an explicit new promoted ticket; no disk restoration occurs.
    request("b", true);
    ForegroundService b = create();
    deliver(b, sender.starts.get(1), 2);
    assertEquals(legacy, store().getString("record", null));
    assertEquals(legacy, store().getString(oldKey, null));
    ForegroundServiceRunLimitScheduler.cancel(a);
    assertEquals(legacy, store().getString("record", null));
    assertSuccessorExpires(b, a);
    assertEquals(legacy, store().getString(oldKey, null));
  }

  public static class StorageService extends ForegroundService {
    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      return Preferences.wrap(super.getSharedPreferences(name, mode), name);
    }
  }

  public static class Preferences {
    static int failCommit, commits;
    static boolean readFailure, enabled;
    static Runnable afterRead, beforeRemove, beforeCommit;
    static boolean commitThrows;

    static void resetFaults() {
      failCommit = commits = 0;
      readFailure = enabled = false;
      afterRead = beforeRemove = beforeCommit = null;
      commitThrows = false;
    }

    static SharedPreferences wrap(SharedPreferences prefs, String name) {
      if (!"notifee-fgs-run-limit".equals(name)
          || (failCommit == 0
              && !readFailure
              && afterRead == null
              && beforeRemove == null
              && beforeCommit == null
              && !commitThrows)) return prefs;
      SharedPreferences proxy =
          org.mockito.Mockito.mock(
              SharedPreferences.class,
              org.mockito.Mockito.withSettings()
                  .defaultAnswer(
                      i -> {
                        if ("getString".equals(i.getMethod().getName()) && readFailure)
                          throw new IllegalStateException("storage unreadable");
                        if ("edit".equals(i.getMethod().getName())) {
                          SharedPreferences.Editor real = prefs.edit();
                          return (SharedPreferences.Editor)
                              java.lang.reflect.Proxy.newProxyInstance(
                                  SharedPreferences.Editor.class.getClassLoader(),
                                  new Class<?>[] {SharedPreferences.Editor.class},
                                  (editorProxy, method, args) -> {
                                    if ("remove".equals(method.getName()) && beforeRemove != null) {
                                      Runnable hook = beforeRemove;
                                      beforeRemove = null;
                                      hook.run();
                                    }
                                    if ("commit".equals(method.getName())) {
                                      Runnable hook = beforeCommit;
                                      beforeCommit = null;
                                      if (hook != null) hook.run();
                                      if (commitThrows)
                                        throw new IllegalStateException("commit failed");
                                      commits++;
                                      if (commits == failCommit) return false;
                                    }
                                    Object result = method.invoke(real, args);
                                    return result instanceof SharedPreferences.Editor
                                        ? editorProxy
                                        : result;
                                  });
                        }
                        Object result = i.getMethod().invoke(prefs, i.getArguments());
                        if ("getString".equals(i.getMethod().getName()) && afterRead != null) {
                          Runnable hook = afterRead;
                          afterRead = null;
                          hook.run();
                        }
                        return result;
                      }));
      return proxy;
    }
  }

  @Test
  public void auditLedgerIpcCannotTransferExpiryToSuccessor() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    Intent old = alarm();
    Preferences.afterRead =
        () -> {
          try {
            ForegroundService.stop();
            shadowOf(Looper.getMainLooper()).idle();
            request("b", true);
            deliver(s, sender.starts.get(1), 2);
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        };
    SystemClock.sleep(1100);
    receive(old);
    assertEquals("b", ForegroundService.mCurrentNotificationId);
    assertTrue(platform(s).foreground);
    assertNotNull(field(s, "mAlarmPoc"));
  }

  @Test
  public void auditReceiverOwnerRevalidatedAfterLastLedgerRead() throws Exception {
    Preferences.enabled = true;
    ForegroundService s = armed("a");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    int[] successorDemotions = {-1};
    // First read returns the authentic A wire. The last read returns authentic ARMED A,
    // but only after B has been created. This reaches the post-ledger ownership fence.
    Preferences.afterRead =
        () ->
            Preferences.afterRead =
                () -> {
                  promoteSuccessor(s).run();
                  successorDemotions[0] = platform(s).demotions;
                };
    SystemClock.sleep(1100);
    receive(a.intent);
    assertNull(Preferences.afterRead);
    assertTrue("the final-read interleaving must execute", successorDemotions[0] >= 0);
    assertEquals(
        "old expiry cannot demote B after a matching ledger snapshot",
        successorDemotions[0],
        platform(s).demotions);
    assertSuccessorExpires(s, a);
  }

  @Test
  public void auditManifestWithoutDataSyncCannotDispatch() throws Exception {
    ServiceInfo service =
        app.getPackageManager().getServiceInfo(new ComponentName(app, ForegroundService.class), 0);
    Field type = ServiceInfo.class.getDeclaredField("mForegroundServiceType");
    type.setAccessible(true);
    type.setInt(service, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    shadowOf(app.getPackageManager()).addOrUpdateService(service);
    assertThrows(IllegalStateException.class, () -> request("a", true));
    assertTrue(sender.starts.isEmpty());
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void auditPendingIntentIdentityIsUniqueWithoutCancellation() throws Exception {
    ForegroundService s = create();
    ForegroundServiceRunLimitScheduler.Request a =
        new ForegroundServiceRunLimitScheduler.Request(app, "Aa", 1000);
    ForegroundServiceRunLimitScheduler.Request b =
        new ForegroundServiceRunLimitScheduler.Request(app, "BB", 1000);
    ForegroundServiceRunLimitScheduler.Ticket first =
        new ForegroundServiceRunLimitScheduler.Ticket(
            s, new Object(), 0, 0, a, SystemClock.elapsedRealtime());
    ForegroundServiceRunLimitScheduler.Ticket second =
        new ForegroundServiceRunLimitScheduler.Ticket(
            s, new Object(), 0, 0, b, SystemClock.elapsedRealtime());
    try {
      assertTrue(ForegroundServiceRunLimitScheduler.arm(first));
      assertTrue(ForegroundServiceRunLimitScheduler.arm(second));
      assertNotEquals(first.pendingIntent, second.pendingIntent);
      assertNotEquals(first.intent.getData(), second.intent.getData());
    } finally {
      ForegroundServiceRunLimitScheduler.cancel(first);
      ForegroundServiceRunLimitScheduler.cancel(second);
    }
  }

  Bundle publicBundle(String id, Object duration) {
    Bundle b = bundle(id, DATA);
    Bundle a = b.getBundle("android");
    a.putBoolean("asForegroundService", true);
    if (duration instanceof Double)
      a.putDouble("foregroundServiceBestEffortStopAfterMs", (Double) duration);
    else if (duration instanceof Long)
      a.putLong("foregroundServiceBestEffortStopAfterMs", (Long) duration);
    else if (duration instanceof String)
      a.putString("foregroundServiceBestEffortStopAfterMs", (String) duration);
    return b;
  }

  void publicStart(Bundle b) {
    ForegroundService.start(b.getString("id").hashCode(), notification(), b);
  }

  ForegroundService publicArmed(String id) {
    publicStart(publicBundle(id, 60000L));
    assertEquals("no registration before real promotion", 0, Alarms.requested);
    ForegroundService s = create();
    deliver(s, sender.starts.get(sender.starts.size() - 1), 1);
    assertEquals("public option must reach AlarmManager", 1, Alarms.scheduled.size());
    return s;
  }

  @Test
  public void PUB_validBridgeDoubleAndPostPromotion() throws Exception {
    publicStart(publicBundle("public", 60000.0));
    assertEquals(0, Alarms.requested);
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertEquals("public JS double must arm", 1, Alarms.scheduled.size());
    assertEquals(SystemClock.elapsedRealtime() + 60000, Alarms.deadline);
  }

  @Test
  public void PUB_nativeInvalidNeverDispatchesStart() {
    for (Object value :
        new Object[] {
          Double.NaN,
          Double.POSITIVE_INFINITY,
          60000.5,
          0L,
          -1L,
          59999L,
          18000001L,
          Long.MAX_VALUE,
          "60000"
        }) {
      assertThrows(
          "invalid option: " + value,
          IllegalArgumentException.class,
          () -> publicStart(publicBundle("bad", value)));
      assertTrue(sender.starts.isEmpty());
    }
  }

  @Test
  @Config(sdk = 34)
  public void PUB_unsupportedApiNeverDispatchesStart() {
    assertThrows(IllegalArgumentException.class, () -> publicStart(publicBundle("old", 60000L)));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void PUB_requiresAsForegroundService() {
    Bundle b = publicBundle("not-fgs", 60000L);
    b.getBundle("android").putBoolean("asForegroundService", false);
    assertThrows(IllegalArgumentException.class, () -> publicStart(b));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void PUB_mixedTypesNeverDispatchStart() {
    Bundle b = publicBundle("mixed", 60000L);
    b.getBundle("android")
        .putIntegerArrayList("foregroundServiceTypes", new ArrayList<>(Arrays.asList(DATA, 2)));
    assertThrows(IllegalArgumentException.class, () -> publicStart(b));
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void PUB_sameIdRepeatAndOmissionKeepOriginalDeadline() throws Exception {
    ForegroundService s = publicArmed("same");
    Object first = field(s, "mAlarmPoc");
    long deadline = Alarms.deadline;
    publicStart(publicBundle("same", 60000L));
    deliver(s, sender.starts.get(1), 2);
    publicStart(publicBundle("same", null));
    deliver(s, sender.starts.get(2), 3);
    assertEquals(1, Alarms.requested);
    assertEquals(deadline, Alarms.deadline);
    assertSame(first, field(s, "mAlarmPoc"));
  }

  @Test
  public void PUB_changedDurationRejectedBeforeStart() throws Exception {
    publicArmed("same");
    assertThrows(IllegalArgumentException.class, () -> publicStart(publicBundle("same", 120000L)));
    assertEquals(1, sender.starts.size());
    assertEquals(1, Alarms.requested);
  }

  @Test
  public void PUB_cannotEnableMidRun() throws Exception {
    publicStart(publicBundle("same", null));
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertThrows(IllegalArgumentException.class, () -> publicStart(publicBundle("same", 60000L)));
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void PUB_deniedPromotionNeverArms() throws Exception {
    publicStart(publicBundle("denied", 60000L));
    ForegroundService s = create();
    Platform.promotionFailure = new ForegroundServiceStartNotAllowedException("denied");
    deliver(s, sender.starts.get(0), 1);
    assertEquals(0, Alarms.requested);
  }

  @Test
  public void PUB_gcDoesNotRunBeforePromotion() throws Exception {
    publicStart(publicBundle("latency", 60000L));
    assertTrue(
        ShadowLog.getLogsForTag("ForegroundServiceRunLimit").stream()
            .noneMatch(x -> x.msg.contains("GC_RESULT")));
    ForegroundService s = create();
    Platform.beforePromotion =
        () ->
            assertTrue(
                ShadowLog.getLogsForTag("ForegroundServiceRunLimit").stream()
                    .noneMatch(x -> x.msg.contains("GC_RESULT")));
    deliver(s, sender.starts.get(0), 1);
    assertEquals(1, Alarms.scheduled.size());
  }

  @Test
  public void PUB_releaseAppArmsWithoutDebugReflection() throws Exception {
    app.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
    publicArmed("release");
  }

  @Test
  public void PUB_expiryEventUsesLatestNotificationAfterTeardownUnlocked() throws Exception {
    ForegroundService s = publicArmed("event");
    Bundle update = publicBundle("event", null);
    update.putString("title", "updated");
    publicStart(update);
    deliver(s, sender.starts.get(1), 2);
    List<app.notifee.core.event.NotificationEvent> events = new ArrayList<>();
    try (org.mockito.MockedStatic<EventBus> bus = org.mockito.Mockito.mockStatic(EventBus.class)) {
      bus.when(() -> EventBus.post(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              i -> {
                Object event = i.getArgument(0);
                if (event instanceof app.notifee.core.event.NotificationEvent) {
                  assertEquals(1, platform(s).demotions);
                  assertEquals(1, platform(s).stopCalls);
                  assertFalse(Thread.holdsLock(field(null, "sLifecycleLock")));
                  assertFalse(Thread.holdsLock(field(null, "sLock")));
                  events.add((app.notifee.core.event.NotificationEvent) event);
                }
                return null;
              });
      SystemClock.sleep(60001);
      receive(alarm());
      receive(alarm());
      assertEquals(1, events.size());
      assertEquals(10, events.get(0).getType());
      assertEquals("updated", events.get(0).getNotification().toBundle().getString("title"));
      Map<?, ?> detail = (Map<?, ?>) events.get(0).toMap().get("detail");
      Map<?, ?> meta = (Map<?, ?>) detail.get("foregroundServiceRunLimit");
      assertEquals(60000L, ((Number) meta.get("requestedDurationMs")).longValue());
      assertEquals("returned", meta.get("demotion"));
      assertEquals("accepted", meta.get("conditionalStop"));
      assertFalse(meta.containsKey("stopped"));
      assertFalse(meta.containsKey("amsState"));
      assertNull(field(s, "mRunnerToken"));
    }
  }

  @Test
  public void PUB_eventFailureCannotPreventTeardown() throws Exception {
    ForegroundService s = publicArmed("bridge-dead");
    try (org.mockito.MockedStatic<EventBus> bus = org.mockito.Mockito.mockStatic(EventBus.class)) {
      bus.when(() -> EventBus.post(org.mockito.ArgumentMatchers.any()))
          .thenThrow(new IllegalStateException("React absent"));
      SystemClock.sleep(60001);
      receive(alarm());
      assertEquals(1, platform(s).demotions);
      assertEquals(1, platform(s).stopCalls);
      assertTrue(marker("EVENT_FAILED"));
    }
  }

  @Test
  public void PUB_storageAndSchedulingFailureAreObservable() throws Exception {
    Alarms.scheduleFailure = true;
    publicStart(publicBundle("schedule-failed", 60000L));
    ForegroundService s = create();
    deliver(s, sender.starts.get(0), 1);
    assertTrue(marker("SCHEDULE_FAILED"));
    assertFalse(marker("ALARM_ARMED"));
    assertNull(field(s, "mAlarmPoc"));
    assertTrue(platform(s).foreground);
  }

  @Test
  public void PUB_nativeDeadlineOverflowRejected() {
    try (org.mockito.MockedStatic<SystemClock> clock =
        org.mockito.Mockito.mockStatic(SystemClock.class)) {
      clock.when(SystemClock::elapsedRealtime).thenReturn(Long.MAX_VALUE - 100);
      assertThrows(
          IllegalArgumentException.class, () -> publicStart(publicBundle("overflow", 60000L)));
      assertTrue(sender.starts.isEmpty());
    }
  }

  @Test
  public void PUB_gcRecognizesFullPublicRange() throws Exception {
    String key = gcOrphan(0);
    org.json.JSONObject wire = new org.json.JSONObject(store().getString(key, null));
    wire.put("deadlineElapsed", 18000001L);
    store().edit().putString(key, wire.toString()).commit();
    collectCycle(app);
    assertFalse("valid public five-hour orphan is collectible", store().contains(key));
  }

  @Test
  public void PUB_newRunAfterRunnerCompletionGetsNewAuthority() throws Exception {
    Runners runners = new Runners();
    EventBus.register(runners);
    try {
      ForegroundService s = publicArmed("completed");
      ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
      runners.events.get(0).setCompletionResult();
      shadowOf(Looper.getMainLooper()).idle();
      publicStart(publicBundle("next", 60000L));
      deliver(s, sender.starts.get(1), 2);
      ForegroundServiceRunLimitScheduler.Ticket b = ticket(s);
      assertNotNull("new real promotion must arm its own ticket", b);
      assertNotSame(a, b);
      assertNotEquals(a.token, b.token);
      assertNotSame(a.epoch, b.epoch);
      SystemClock.sleep(60001);
      receive(a.intent);
      assertSame(b, ticket(s));
    } finally {
      EventBus.unregister(runners);
    }
  }

  @Test
  public void PUB_reentrantStartDuringStopCancellationSupersedesOldStop() throws Exception {
    ForegroundService s = publicArmed("stop-order");
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(s);
    Alarms.duringCancel =
        () -> {
          publicStart(publicBundle("stop-order", null));
          deliver(s, sender.starts.get(1), 2);
        };
    ForegroundService.stop();
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse("later accepted START must supersede an older STOP", platform(s).stopped);
    assertTrue(platform(s).foreground);
    assertEquals("stop-order", ForegroundService.mCurrentNotificationId);
    assertNotNull(field(s, "mRunnerToken"));
    assertNotSame(a.epoch, field(s, "mForegroundEpoch"));
  }

  @Test
  public void PUB_completionCancellationCannotClearSuccessorState() throws Exception {
    Runners runners = new Runners();
    EventBus.register(runners);
    try {
      ForegroundService s = publicArmed("completion-a");
      ForegroundServiceRunLimitScheduler.Ticket[] b = {null};
      Alarms.duringCancel =
          () -> {
            ForegroundService.stop();
            shadowOf(Looper.getMainLooper()).idle();
            publicStart(publicBundle("completion-b", 60000L));
            deliver(s, sender.starts.get(sender.starts.size() - 1), 2);
            try {
              b[0] = ticket(s);
              assertNotNull(b[0]);
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
          };
      runners.events.get(0).setCompletionResult();
      shadowOf(Looper.getMainLooper()).idle();
      assertSame(b[0], ticket(s));
      assertEquals(
          "old cleanup must preserve successor notification",
          "completion-b",
          ForegroundService.mCurrentNotificationId);
      assertNotNull(field(s, "mRunnerToken"));
      assertTrue(b[0].valid);
      SystemClock.sleep(60001);
      receive(b[0].intent);
      assertFalse("authentic successor expiry still works", platform(s).foreground);
    } finally {
      EventBus.unregister(runners);
    }
  }

  @Test
  public void PUB_typeCommitCannotOverwriteReentrantSuccessor() throws Exception {
    ForegroundService s = publicArmed("type-a");
    Object[] successor = new Object[2];
    Alarms.duringCancel =
        () -> {
          ForegroundService.stop();
          shadowOf(Looper.getMainLooper()).idle();
          publicStart(publicBundle("type-b", null));
          deliver(s, sender.starts.get(sender.starts.size() - 1), 3);
          try {
            successor[0] = field(s, "mForegroundEpoch");
            successor[1] = field(s, "mRunnerToken");
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
    Bundle update = publicBundle("type-a", null);
    update
        .getBundle("android")
        .putIntegerArrayList("foregroundServiceTypes", new ArrayList<>(Arrays.asList(DATA, 2)));
    publicStart(update);
    deliver(s, sender.starts.get(1), 2);
    assertSame("old type commit must preserve B epoch", successor[0], field(s, "mForegroundEpoch"));
    assertSame(successor[1], field(s, "mRunnerToken"));
    assertEquals("type-b", ForegroundService.mCurrentNotificationId);
    assertEquals("type-b", ((Bundle) field(null, "mCurrentNotificationBundle")).getString("id"));
    assertEquals(DATA, ForegroundService.mCurrentForegroundServiceType);
    assertTrue(platform(s).foreground);
  }

  static Runnable publicBootHook;

  public static class BootReadService extends ForegroundService {
    @Override
    public ContentResolver getContentResolver() {
      if (publicBootHook != null) {
        Runnable hook = publicBootHook;
        publicBootHook = null;
        hook.run();
        throw new IllegalStateException("old ticket boot provider failed");
      }
      return super.getContentResolver();
    }
  }

  @Test
  public void PUB_oldConstructionFailureCannotCancelNewTicket() throws Exception {
    ServiceController<BootReadService> controller = Robolectric.buildService(BootReadService.class);
    controllers.add(controller);
    ForegroundService s = controller.create().get();
    ForegroundServiceRunLimitScheduler.Ticket[] b = {null};
    publicBootHook =
        () -> {
          ForegroundService.stop();
          shadowOf(Looper.getMainLooper()).idle();
          publicStart(publicBundle("boot-b", 60000L));
          deliver(s, sender.starts.get(sender.starts.size() - 1), 2);
          try {
            b[0] = ticket(s);
            assertNotNull(b[0]);
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
    try {
      publicStart(publicBundle("boot-a", 60000L));
      deliver(s, sender.starts.get(0), 1);
      assertNotNull(b[0]);
      assertSame("A's catch cannot cancel B", b[0], ticket(s));
      assertTrue(b[0].valid);
      SystemClock.sleep(60001);
      receive(b[0].intent);
      assertFalse(platform(s).foreground);
    } finally {
      publicBootHook = null;
    }
  }

  @Test
  public void PUB_nativeFacadeReportsValidationThroughCallback() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Exception> error =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      Notifee.getInstance()
          .displayNotification(
              publicBundle("native-invalid", Double.NaN),
              (failure, result) -> {
                error.set(failure);
                done.countDown();
              });
    } catch (RuntimeException failure) {
      fail("native facade must report validation through its async callback: " + failure);
    }
    assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
    assertNotNull(error.get());
    assertTrue(sender.starts.isEmpty());
  }

  @Test
  public void PUB_terminalCancellationCannotStopSuccessor() throws Exception {
    ForegroundService s = publicArmed("terminal-a");
    ForegroundServiceRunLimitScheduler.Ticket[] b = {null};
    int[] priorStops = {0};
    Alarms.duringCancel =
        () -> {
          ForegroundService.stop();
          shadowOf(Looper.getMainLooper()).idle();
          publicStart(publicBundle("terminal-b", 60000L));
          deliver(s, sender.starts.get(sender.starts.size() - 1), 3);
          try {
            b[0] = ticket(s);
            assertNotNull(b[0]);
            priorStops[0] = platform(s).stopCalls;
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
    deliver(s, null, 2);
    shadowOf(Looper.getMainLooper()).idle();
    assertSame("old terminal cannot revoke B", b[0], ticket(s));
    assertEquals(priorStops[0], platform(s).stopCalls);
    assertTrue(b[0].valid);
    assertEquals("terminal-b", ForegroundService.mCurrentNotificationId);
  }

  @Test
  public void PUB_terminalCancellationPreservesNewPendingStart() throws Exception {
    ForegroundService s = publicArmed("pending-a");
    Alarms.duringCancel = () -> publicStart(publicBundle("pending-b", null));
    deliver(s, null, 2);
    deliver(s, sender.starts.get(sender.starts.size() - 1), 3);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse("newer accepted pending START supersedes old terminal", platform(s).stopped);
    assertEquals("pending-b", ForegroundService.mCurrentNotificationId);
  }

  @Test
  public void PUB_frameworkTimeoutSurvivesFailedVoluntaryTeardown() throws Exception {
    ForegroundService s = publicArmed("fallback");
    Intent original = alarm();
    Platform.demotionFailure = true;
    Platform.stopFailure = true;
    SystemClock.sleep(60001);
    receive(original);
    assertTrue(platform(s).foreground);
    assertEquals(1, platform(s).demotions);
    Platform.demotionFailure = false;
    Platform.stopFailure = false;
    s.onTimeout(1, DATA);
    assertEquals(
        "framework timeout retains its independent teardown chance", 2, platform(s).demotions);
    assertFalse(platform(s).foreground);
    receive(original);
    assertEquals(2, platform(s).demotions);
  }

  static class Sender extends ContextWrapper {
    final List<Intent> starts = new ArrayList<>();
    RuntimeException failure;

    Sender(Context c) {
      super(c);
    }

    @Override
    public ComponentName startForegroundService(Intent i) {
      starts.add(new Intent(i));
      if (failure != null) throw failure;
      return i.getComponent();
    }
  }

  @Implements(AlarmManager.class)
  public static class Alarms extends ShadowAlarmManager {
    static int requested, type, cancelled;
    static long deadline;
    static boolean scheduleFailure, cancelFailure;
    static PendingIntent lastRequested;
    static Runnable afterSchedule, duringCancel;
    static List<PendingIntent> cancellations = new ArrayList<>();
    static List<PendingIntent> scheduled = new ArrayList<>();

    public static void reset() {
      requested = cancelled = 0;
      scheduled.clear();
      scheduleFailure = cancelFailure = false;
      lastRequested = null;
      afterSchedule = duringCancel = null;
      cancellations.clear();
    }

    @Implementation
    protected void setAndAllowWhileIdle(int t, long d, PendingIntent p) {
      requested++;
      type = t;
      deadline = d;
      lastRequested = p;
      if (scheduleFailure) throw new IllegalStateException("alarm unavailable");
      scheduled.add(p);
      if (afterSchedule != null) afterSchedule.run();
    }

    @Implementation
    protected void cancel(PendingIntent p) {
      cancelled++;
      cancellations.add(p);
      Runnable hook = duringCancel;
      duringCancel = null;
      if (hook != null) hook.run();
      if (cancelFailure) throw new IllegalStateException("cancel lost reply");
    }
  }

  @Implements(Service.class)
  public static class Platform extends ShadowService {

    int accepted, delivered, demotions, stopCalls, lastStopId;
    boolean foreground, stopped, stopResult = true;
    final List<String> order = new ArrayList<>();
    static RuntimeException promotionFailure;
    static boolean demotionFailure, stopFailure;
    static Runnable beforePromotion, duringDemotion, duringStop;

    static void reset() {
      promotionFailure = null;
      demotionFailure = stopFailure = false;
      beforePromotion = duringDemotion = duringStop = null;
    }

    @Implementation
    protected void startForeground(int id, Notification n, int type) {
      if (beforePromotion != null) beforePromotion.run();
      if (promotionFailure != null) throw promotionFailure;
      foreground = true;
    }

    @Implementation
    protected void stopForeground(int flags) {
      assertEquals(Service.STOP_FOREGROUND_REMOVE, flags);
      demotions++;
      order.add("demote");
      if (demotionFailure) throw new SecurityException("demotion denied");
      foreground = false;
      Runnable hook = duringDemotion;
      duringDemotion = null;
      if (hook != null) hook.run();
    }

    @Implementation
    protected boolean stopSelfResult(int id) {
      stopCalls++;
      lastStopId = id;
      order.add("stop:" + id);
      if (stopFailure) throw new IllegalStateException("stop failure");
      boolean result = stopResult && id == accepted;
      stopped = result;
      Runnable hook = duringStop;
      duringStop = null;
      if (hook != null) hook.run();
      return result;
    }
  }
}
