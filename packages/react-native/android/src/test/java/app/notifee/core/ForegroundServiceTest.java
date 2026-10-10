package app.notifee.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.InvalidForegroundServiceTypeException;
import android.app.MissingForegroundServiceTypeException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import app.notifee.core.event.ForegroundServiceEvent;
import app.notifee.core.event.NotificationEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowService;

@RunWith(RobolectricTestRunner.class)
public class ForegroundServiceTest {

  private final List<ServiceController<ForegroundService>> controllers = new ArrayList<>();

  @Before
  public void setUp() {
    // Initialize ContextHolder so the service can access the application context
    ContextHolder.setApplicationContext(RuntimeEnvironment.getApplication());
  }

  @After
  public void tearDown() throws Exception {
    for (int i = controllers.size() - 1; i >= 0; i--) {
      controllers.get(i).destroy();
    }
    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
    ContextHolder.setApplicationContext(RuntimeEnvironment.getApplication());
    // Reset public and private static fields to prevent cross-test pollution. The three private
    // statics are cleared via reflection here so that tests which seed them directly (to exercise
    // onTimeout without running the full START path) cannot leak state into neighbouring tests.
    ForegroundService.mCurrentNotificationId = null;
    ForegroundService.mCurrentForegroundServiceType = -1;
    setPrivateStatic("mCurrentNotificationBundle", null);
    setPrivateStatic("mCurrentNotification", null);
    setPrivateStatic("mCurrentHashCode", 0);
  }

  // Slice D uses the real UUID reservation/dispatch path. API 36/37 below are policy-boundary
  // simulations on the API 35 Robolectric runtime; they are not Android 16/17 framework proof.
  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_initialTrackedDenial_isSelectiveAndNonFatal() throws Exception {
    for (String message :
        new String[] {
          "Time limit already exhausted for foreground service type dataSync",
          "Background start not allowed",
          "opaque",
          ""
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Issue81ShadowService.failure = new ForegroundServiceStartNotAllowedException(message);
      issue81ObserveRecoveryStop(service);
      Intent a = issue82Dispatch(context, "A", 810);
      String id = a.getStringExtra(ISSUE81_REQUEST_ID);
      Object request = issue81Pending().get(id);
      FgsRunnerCapture capture = new FgsRunnerCapture();
      EventBus.register(capture);
      try {
        int result;
        try {
          result = service.onStartCommand(a, 0, 7);
        } catch (RuntimeException denial) {
          assertSame(Issue81ShadowService.failure, denial);
          assertEquals(java.util.Collections.singletonList(810), Issue81ShadowService.promotions);
          assertEquals(
              java.util.Collections.singletonList(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
              Issue81ShadowService.types);
          assertSame(request, issue81Pending().get(id));
          issue81AssertUnpromoted(service);
          throw denial; // causal RED: actual tracked initial platform promotion was attempted
        }
        assertEquals(Service.START_NOT_STICKY, result);
        assertTrue(issue81Pending().isEmpty());
        assertTrue((boolean) issue82RequestField(request, "accepted"));
        assertTrue((boolean) issue82RequestField(request, "promotionDenied"));
        issue81AssertUnpromoted(service);
        assertTrue(capture.events.isEmpty());
        assertEquals(java.util.Arrays.asList("promote:810", "stop:7"), Issue81ShadowService.order);
        issue82AssertDiagnostic(id, "accepted");
      } finally {
        EventBus.unregister(capture);
      }
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_api36And37_policyBoundarySimulation() throws Exception {
    try {
      for (int api : new int[] {36, 37}) {
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", api);
        RecordingServiceContext context = useRecordingContext();
        ForegroundService service = issue81Service();
        Intent a = issue82Dispatch(context, "A", 810);
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(a, 0, 7));
        issue81AssertUnpromoted(service);
        assertTrue(issue81Pending().isEmpty());
        assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
        issue82AssertDiagnostic(a.getStringExtra(ISSUE81_REQUEST_ID), "accepted");
        issue82DestroyServices();
      }
    } finally {
      org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 35);
    }
  }

  @Test
  @Config(
      sdk = {31, 32, 33, 34},
      shadows = Issue81ShadowService.class)
  public void issue82_api31Through34_trackedDenialRemainsFatal() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent a = issue82Dispatch(context, "A", 810);
    Object request = issue81Pending().get(a.getStringExtra(ISSUE81_REQUEST_ID));
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
    assertSame(request, issue81Pending().get(a.getStringExtra(ISSUE81_REQUEST_ID)));
    assertTrue(Issue81ShadowService.stops.isEmpty());
    issue81AssertUnpromoted(service);
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_otherPromotionExceptionsRemainFatal() throws Exception {
    for (RuntimeException failure :
        new RuntimeException[] {
          new SecurityException("permission"), new MissingForegroundServiceTypeException("missing"),
          new InvalidForegroundServiceTypeException("invalid"),
              new IllegalArgumentException("argument"),
          new IllegalStateException("state"), new RuntimeException("other")
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      Object request = issue81Pending().get(a.getStringExtra(ISSUE81_REQUEST_ID));
      Issue81ShadowService.failure = failure;
      assertSame(
          failure, assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
      assertSame(request, issue81Pending().get(a.getStringExtra(ISSUE81_REQUEST_ID)));
      assertTrue(Issue81ShadowService.stops.isEmpty());
      issue81AssertUnpromoted(service);
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_untrackedAndWrongActionRemainFatal_incompletePayloadDoesNotPromote()
      throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class, () -> service.onStartCommand(issue81UnknownStart(), 0, 1)));
    Intent a = issue82Dispatch(context, "A", 810);
    a.setAction("another.START");
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 2)));
    a.setAction(ForegroundService.START_FOREGROUND_SERVICE_ACTION);
    a.removeExtra("notification");
    service.onStartCommand(a, 0, 3);
    assertEquals(2, Issue81ShadowService.promotions.size());
    assertEquals(1, issue81Pending().size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_deniedA_preservesAcceptedAndDeliveredUnfulfilledB() throws Exception {
    for (boolean delivered : new boolean[] {false, true}) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      Intent b = issue82Dispatch(context, "B", 812);
      Object request = issue81Pending().get(b.getStringExtra(ISSUE81_REQUEST_ID));
      if (delivered) {
        Intent incomplete = new Intent(b);
        incomplete.removeExtra("notification");
        service.onStartCommand(incomplete, 0, 6);
      }
      assertEquals(Service.START_NOT_STICKY, service.onStartCommand(a, 0, 7));
      assertSame(request, issue81Pending().get(b.getStringExtra(ISSUE81_REQUEST_ID)));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      issue81AssertUnpromoted(service);
      assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
      issue82AssertDiagnostic(a.getStringExtra(ISSUE81_REQUEST_ID), "deferred");
      service.onStartCommand(b, 0, 8);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_pendingB_definiteRejectionReevaluatesRecovery_unknownDoesNot()
      throws Exception {
    for (RuntimeException reply :
        new RuntimeException[] {
          null, new SecurityException("reject"), new IllegalStateException("Binder UNKNOWN")
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      try (Issue81LateReply b = new Issue81LateReply(reply, false)) {
        b.awaitDispatch();
        Object request = issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID));
        assertFalse((boolean) issue82RequestField(request, "accepted"));
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(a, 0, 7));
        assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
        assertTrue(Issue81ShadowService.stops.isEmpty());
        issue82AssertDiagnostic(a.getStringExtra(ISSUE81_REQUEST_ID), "deferred");
        b.settle();
        assertSame(reply, b.failure.get());
        idleMain();
        if (reply instanceof IllegalStateException) {
          assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
          assertTrue(Issue81ShadowService.stops.isEmpty());
          assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
          service.onStartCommand(b.intent, 0, 8);
          assertCurrentState("B", 812, "B");
        } else {
          assertTrue(issue81Pending().isEmpty());
          assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
          issue82AssertDiagnostic(a.getStringExtra(ISSUE81_REQUEST_ID), "accepted");
        }
      }
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_pendingB_acceptanceInvalidatesRecovery() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent a = issue82Dispatch(context, "A", 810);
    try (Issue81LateReply b = new Issue81LateReply(null, true)) {
      b.awaitDispatch();
      service.onStartCommand(a, 0, 7);
      b.settle();
      idleMain();
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      service.onStartCommand(b.intent, 0, 8);
      assertCurrentState("B", 812, "B");
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_lateAReply_doesNotResurrectOrCancelLaterStop() throws Exception {
    for (RuntimeException reply :
        new RuntimeException[] {
          null, new SecurityException("late negative"), new IllegalStateException("late UNKNOWN")
        }) {
      for (boolean positive : new boolean[] {false, true}) {
        ForegroundService service = issue81Service();
        Issue81ShadowService.stopResult = false;
        try (Issue81LateReply a = new Issue81LateReply(reply, positive, "A", 810)) {
          a.awaitDispatch();
          String id = a.intent.getStringExtra(ISSUE81_REQUEST_ID);
          Object request = issue81Pending().get(id);
          service.onStartCommand(a.intent, 0, 7);
          assertTrue(issue81Pending().isEmpty());
          assertTrue((boolean) issue82RequestField(request, "accepted"));
          ForegroundService.stop();
          Object stop = getPrivateStatic("sStopRequest");
          a.settle();
          assertSame(reply, a.failure.get());
          assertTrue(issue81Pending().isEmpty());
          assertSame(stop, getPrivateStatic("sStopRequest"));
          idleMain();
          assertFalse((boolean) issue81Field(service, "mStopInitiated"));
        }
        issue82DestroyServices();
      }
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_lateAReply_doesNotInvalidateNewRecovery() throws Exception {
    for (RuntimeException reply :
        new RuntimeException[] {
          null, new SecurityException("late negative"), new IllegalStateException("late UNKNOWN")
        }) {
      for (boolean positive : new boolean[] {false, true}) {
        RecordingServiceContext context = useRecordingContext();
        ForegroundService service = issue81Service();
        Intent b = issue82Dispatch(context, "B", 812);
        try (Issue81LateReply a = new Issue81LateReply(reply, positive, "A", 810)) {
          a.awaitDispatch();
          service.onStartCommand(a.intent, 0, 7);
          Object first = issue81Field(service, "mDeniedStartRecovery");
          service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 8);
          Object newer = issue81Field(service, "mDeniedStartRecovery");
          assertNotNull(newer);
          assertFalse(first == newer);
          a.settle();
          assertSame(reply, a.failure.get());
          idleMain();
          assertSame(newer, issue81Field(service, "mDeniedStartRecovery"));
          assertEquals(1, issue81Pending().size());
          assertTrue(Issue81ShadowService.stops.isEmpty());
          service.onStartCommand(b, 0, 9);
          assertCurrentState("B", 812, "B");
        }
        issue82DestroyServices();
      }
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_stopRefusedOrThrows_doesNotCleanOrRetry() throws Exception {
    for (boolean throwsStop : new boolean[] {false, true}) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      Issue81ShadowService.stopResult = false;
      if (throwsStop) {
        Issue81ShadowService.stopFailure = new SecurityException("stop failed");
        assertSame(
            Issue81ShadowService.stopFailure,
            assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
      } else {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(a, 0, 7));
      }
      assertTrue(issue81Pending().isEmpty());
      issue81AssertUnpromoted(service);
      assertFalse((boolean) issue81Field(service, "mStopInitiated"));
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      issue82AssertDiagnostic(
          a.getStringExtra(ISSUE81_REQUEST_ID), throwsStop ? "failed" : "refused");
      idleMain();
      assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_failedReplacement_preservesPromotedBAndRunner() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      Intent b = issue82Dispatch(context, "B", 812);
      service.onStartCommand(b, 0, 1);
      Object runner = issue81Field(service, "mRunnerToken");
      Object bundle = getPrivateStatic("mCurrentNotificationBundle");
      ForegroundService.stop();
      Intent a = issue82Dispatch(context, "A", 810);
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 2)));
      assertCurrentState("B", 812, "B");
      assertSame(bundle, getPrivateStatic("mCurrentNotificationBundle"));
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          ForegroundService.mCurrentForegroundServiceType);
      assertSame(runner, issue81Field(service, "mRunnerToken"));
      assertTrue(ForegroundService.repostIfActive("B"));
      assertEquals(1, capture.events.size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("B", 812, "B");
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_postTimeoutSameInstance_isExplicitlyExcluded() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent b = issue82Dispatch(context, "B", 812);
    service.onStartCommand(b, 0, 1);
    service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    assertTrue((boolean) issue81Field(service, "mStartForegroundCalled"));
    assertEquals(1, issue81Field(service, "mRunFirstStartId"));
    Intent a = issue82Dispatch(context, "A", 810);
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 2)));
    assertEquals(java.util.Arrays.asList(812, 810), Issue81ShadowService.promotions);
    assertNull(issue81Field(service, "mDeniedStartRecovery"));
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_eligibilityRecheckedAfterPromotionAttempt() throws Exception {
    for (String fieldName :
        new String[] {
          "sInstance", "sStateOwner", "sRunGeneration", "mRunGeneration", "mLastStartId"
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      Field field = ForegroundService.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      Object owner = fieldName.startsWith("s") ? null : service;
      Object original = field.get(owner);
      Issue81ShadowService.beforePromotion =
          () -> {
            try {
              if (fieldName.endsWith("Generation")) field.set(owner, ((Long) original) + 1);
              else if (fieldName.equals("mLastStartId")) field.set(owner, 99);
              else field.set(owner, new ForegroundService());
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          };
      try {
        assertSame(
            Issue81ShadowService.failure,
            assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
        assertEquals(1, issue81Pending().size());
        assertTrue(Issue81ShadowService.stops.isEmpty());
      } finally {
        field.set(owner, original);
        Issue81ShadowService.beforePromotion = null;
      }
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_deferredCallback_revalidatesAllAuthority() throws Exception {
    for (String fieldName :
        new String[] {
          "sInstance", "sStateOwner", "sRunGeneration", "mRunGeneration", "mLastStartId"
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(a, 0, 7);
        b.settle(); // queues recovery with A's identity, before we revoke one fence
        Field field = ForegroundService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object owner = fieldName.startsWith("s") ? null : service;
        Object original = field.get(owner);
        try {
          if (fieldName.endsWith("Generation")) field.set(owner, ((Long) original) + 1);
          else if (fieldName.equals("mLastStartId")) field.set(owner, 99);
          else field.set(owner, new ForegroundService());
          idleMain();
          assertTrue(Issue81ShadowService.stops.isEmpty());
          assertNull(issue81Field(service, "mDeniedStartRecovery"));
        } finally {
          field.set(owner, original);
        }
      }
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_trackedFrameworkFlags_useSliceD_notUntrackedRedeliveryPolicy()
      throws Exception {
    for (int flags :
        new int[] {
          Service.START_FLAG_RETRY,
          Service.START_FLAG_REDELIVERY,
          Service.START_FLAG_RETRY | Service.START_FLAG_REDELIVERY
        }) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      service.onStartCommand(a, flags, 7);
      assertTrue(issue81Pending().isEmpty());
      issue81AssertUnpromoted(service);
      assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
      assertTrue(
          ShadowLog.getLogsForTag("NOTIFEE").stream()
              .anyMatch(
                  item ->
                      item.msg.contains("PROMOTION_DENIED")
                          && item.msg.contains("flags=" + flags)));
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_manifestAndNoneDenialsRemainOutsideCatch() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent a = issue82Dispatch(context, "A", 810);
    a.getBundleExtra("notificationBundle").getBundle("android").remove("foregroundServiceTypes");
    PackageManager manager = org.mockito.Mockito.mock(PackageManager.class);
    org.mockito.Mockito.when(
            manager.getServiceInfo(
                org.mockito.ArgumentMatchers.any(ComponentName.class),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(Issue81ShadowService.failure);
    ContextHolder.setApplicationContext(
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public PackageManager getPackageManager() {
            return manager;
          }
        });
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
    assertTrue(Issue81ShadowService.promotions.isEmpty());
    assertEquals(1, issue81Pending().size());
    // Actual resolved NONE reaches the defensive placeholder, not the initial real promotion.
    ContextHolder.setApplicationContext(null);
    RuntimeException denial =
        assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 8));
    assertSame(Issue81ShadowService.failure, denial.getCause());
    assertEquals(
        java.util.Collections.singletonList(Integer.MAX_VALUE - 1),
        Issue81ShadowService.promotions);
    assertTrue(Issue81ShadowService.stops.isEmpty());
    assertEquals(1, issue81Pending().size());
  }

  @Test
  @Config(
      sdk = 35,
      shadows = {Issue81ShadowService.class, Issue81NotificationShadowManager.class})
  public void issue82_trackedSameIdNotifyAndTypeChangeDenials_preserveState() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent b = issue82Dispatch(context, "B", 812);
    service.onStartCommand(b, 0, 1);
    Object runner = issue81Field(service, "mRunnerToken");
    Object bundle = getPrivateStatic("mCurrentNotificationBundle");
    for (int type :
        new int[] {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }) {
      Intent update = buildStartIntent("B", 812, "A", type);
      ForegroundService.start(
          812,
          update.getParcelableExtra("notification"),
          update.getBundleExtra("notificationBundle"));
      Intent delivered = context.starts.get(context.starts.size() - 1);
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(RuntimeException.class, () -> service.onStartCommand(delivered, 0, 2)));
      assertCurrentState("B", 812, "B");
      assertSame(bundle, getPrivateStatic("mCurrentNotificationBundle"));
      assertSame(runner, issue81Field(service, "mRunnerToken"));
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
          ForegroundService.mCurrentForegroundServiceType);
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
    }
    assertEquals(java.util.Arrays.asList(812, 812), Issue81ShadowService.promotions);
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_supersededTrackedDenial_isExcluded() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent a = issue82Dispatch(context, "A", 810);
    ForegroundService.stop();
    Intent b = issue82Dispatch(context, "B", 812);
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      service.onStartCommand(b, 0, 1);
      capture.events.get(0).setCompletionResult();
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 2)));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_requestIdentityAndOwnership_fencedBeforeAndAfterAttempt() throws Exception {
    for (boolean duringAttempt : new boolean[] {false, true}) {
      for (String authority : new String[] {"sInstance", "sStateOwner", "request"}) {
        RecordingServiceContext context = useRecordingContext();
        ForegroundService service = issue81Service();
        Intent a = issue82Dispatch(context, "A", 810);
        String id = a.getStringExtra(ISSUE81_REQUEST_ID);
        Field field =
            ForegroundService.class.getDeclaredField(
                authority.equals("request") ? "sPendingStarts" : authority);
        field.setAccessible(true);
        Object original = field.get(null);
        Runnable revoke =
            () -> {
              try {
                if (authority.equals("request")) issue81Pending().remove(id);
                else field.set(null, new ForegroundService());
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            };
        if (duringAttempt) Issue81ShadowService.beforePromotion = revoke;
        else revoke.run();
        try {
          assertSame(
              Issue81ShadowService.failure,
              assertThrows(RuntimeException.class, () -> service.onStartCommand(a, 0, 7)));
          assertTrue(Issue81ShadowService.stops.isEmpty());
        } finally {
          if (!authority.equals("request")) field.set(null, original);
          Issue81ShadowService.beforePromotion = null;
        }
        issue82DestroyServices();
      }
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_deferredRecovery_newAdmissionStopAndDestroyInvalidateStaleCallback()
      throws Exception {
    for (String action : new String[] {"admission", "stop", "destroy", "delivery"}) {
      RecordingServiceContext context = useRecordingContext();
      ForegroundService service = issue81Service();
      Intent a = issue82Dispatch(context, "A", 810);
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(a, 0, 7);
        Object recovery = issue81Field(service, "mDeniedStartRecovery");
        b.settle(); // queued callback carries recovery A; lifecycle changes happen before it runs
        if (action.equals("admission")) {
          Intent c = issue82Dispatch(useRecordingContext(), "C", 814);
          assertNull(issue81Field(service, "mDeniedStartRecovery"));
          idleMain();
          assertTrue(Issue81ShadowService.stops.isEmpty());
          service.onStartCommand(c, 0, 8);
          assertCurrentState("C", 814, "C");
        } else if (action.equals("stop")) {
          ForegroundService.stop();
          assertNull(issue81Field(service, "mDeniedStartRecovery"));
          idleMain();
          assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
          assertFalse(
              ShadowLog.getLogsForTag("NOTIFEE").stream()
                  .anyMatch(
                      item ->
                          item.msg.contains("PROMOTION_DENIED")
                              && item.msg.contains("stop=accepted")));
        } else if (action.equals("destroy")) {
          issue82DestroyServices();
          assertTrue(Issue81ShadowService.stops.isEmpty());
        } else {
          Intent incomplete = new Intent().setAction("incomplete");
          service.onStartCommand(incomplete, 0, 8);
          idleMain();
          assertTrue(Issue81ShadowService.stops.isEmpty());
        }
        assertNotNull(recovery);
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
      }
      issue82DestroyServices();
    }
  }

  @Test
  @Config(sdk = 35, shadows = Issue81ShadowService.class)
  public void issue82_queuedRecovery_newPendingReservationDefersUntilDefiniteRejection()
      throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Intent a = issue82Dispatch(context, "A", 810);
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(a, 0, 7);
      Object recovery = issue81Field(service, "mDeniedStartRecovery");
      b.settle();
      try (Issue81LateReply c = new Issue81LateReply(null, false, "C", 814)) {
        c.awaitDispatch();
        Object request = issue81Pending().get(c.intent.getStringExtra(ISSUE81_REQUEST_ID));
        idleMain();
        assertSame(recovery, issue81Field(service, "mDeniedStartRecovery"));
        assertSame(request, issue81Pending().get(c.intent.getStringExtra(ISSUE81_REQUEST_ID)));
        assertTrue(Issue81ShadowService.stops.isEmpty());
        c.settle();
        idleMain();
        assertTrue(issue81Pending().isEmpty());
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
        assertEquals(java.util.Collections.singletonList(7), Issue81ShadowService.stops);
        issue82AssertDiagnostic(a.getStringExtra(ISSUE81_REQUEST_ID), "accepted");
      }
    }
  }

  private static Object issue82RequestField(Object request, String name) throws Exception {
    Field field = request.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(request);
  }

  private static Intent issue82Dispatch(RecordingServiceContext context, String id, int hash) {
    Intent start = buildStartIntent(id, hash, id, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    ForegroundService.start(
        hash, start.getParcelableExtra("notification"), start.getBundleExtra("notificationBundle"));
    return context.starts.get(context.starts.size() - 1);
  }

  private void issue82DestroyServices() {
    for (int i = controllers.size() - 1; i >= 0; i--) controllers.get(i).destroy();
    controllers.clear();
    idleMain();
  }

  private static void issue82AssertDiagnostic(String id, String outcome) {
    assertTrue(
        ShadowLog.getLogsForTag("NOTIFEE").stream()
            .anyMatch(
                item ->
                    item.type == android.util.Log.WARN
                        && item.throwable == Issue81ShadowService.failure
                        && item.msg.contains("PROMOTION_DENIED")
                        && item.msg.contains("cause=UNKNOWN")
                        && item.msg.contains("requestId=" + id)
                        && item.msg.contains("notificationId=A")
                        && item.msg.contains("api=" + Build.VERSION.SDK_INT)
                        && item.msg.contains("type=1")
                        && item.msg.contains("startId=7")
                        && item.msg.contains("flags=0")
                        && item.msg.contains("generation=")
                        && item.msg.contains("stop=" + outcome)));
  }

  @Test
  @Config(
      sdk = {31, 33, 34, 35},
      shadows = Issue81ShadowService.class)
  public void issue81_retryFreshDenied_recoversWithoutClaimingPromotion() throws Exception {
    issue81AssertFreshRecovery(Service.START_FLAG_RETRY);
  }

  @Test
  @Config(
      sdk = {31, 33, 34, 35},
      shadows = Issue81ShadowService.class)
  public void issue81_redeliveryFreshDenied_recoversWithoutClaimingPromotion() throws Exception {
    issue81AssertFreshRecovery(Service.START_FLAG_REDELIVERY);
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_bothFlagsAndUppercaseUuid_recovers() throws Exception {
    ForegroundService service = issue81Service();
    Intent intent = issue81UnknownStart();
    intent.putExtra(
        ISSUE81_REQUEST_ID,
        intent.getStringExtra(ISSUE81_REQUEST_ID).toUpperCase(java.util.Locale.ROOT));
    assertEquals(
        Service.START_NOT_STICKY,
        service.onStartCommand(
            intent, Service.START_FLAG_RETRY | Service.START_FLAG_REDELIVERY, 1));
    issue81AssertUnpromoted(service);
    assertEquals(java.util.Collections.singletonList(1), Issue81ShadowService.stops);
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_trackedStartDenied_isFatalEvenWithFrameworkFlags() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Issue81ShadowService.rejectTitle = null;
    for (int flags :
        new int[] {
          0,
          Service.START_FLAG_RETRY,
          Service.START_FLAG_REDELIVERY,
          Service.START_FLAG_RETRY | Service.START_FLAG_REDELIVERY
        }) {
      ForegroundService.start(811, buildNotification(), buildNotificationBundle("tracked"));
      Intent intent = context.starts.get(context.starts.size() - 1);
      String requestId = intent.getStringExtra(ISSUE81_REQUEST_ID);
      Object request = issue81Pending().get(requestId);
      assertNotNull(request);
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(RuntimeException.class, () -> service.onStartCommand(intent, flags, 1)));
      assertSame(request, issue81Pending().get(requestId));
    }
    assertEquals(4, Issue81ShadowService.promotions.size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
    issue81AssertUnpromoted(service);
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_missingOrInvalidUuid_isFatal() throws Exception {
    ForegroundService service = issue81Service();
    for (String requestId :
        new String[] {
          null,
          "",
          "not-a-uuid",
          "1-1-1-1-1",
          "000000000-000-0000-0000-000000000000",
          " " + UUID.randomUUID(),
          "00000000-0000-0000-0000-00000000000z"
        }) {
      Intent intent = issue81UnknownStart();
      if (requestId == null) {
        intent.removeExtra(ISSUE81_REQUEST_ID);
      } else {
        intent.putExtra(ISSUE81_REQUEST_ID, requestId);
      }
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(
              RuntimeException.class,
              () -> service.onStartCommand(intent, Service.START_FLAG_RETRY, 1)));
    }
    Intent wrongType = issue81UnknownStart().putExtra(ISSUE81_REQUEST_ID, 81);
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(wrongType, Service.START_FLAG_RETRY, 1)));
    assertEquals(8, Issue81ShadowService.promotions.size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_missingFrameworkFlagsOrWrongAction_isFatal() throws Exception {
    ForegroundService service = issue81Service();
    Intent intentFlagsOnly = issue81UnknownStart().setFlags(0x4);
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(RuntimeException.class, () -> service.onStartCommand(intentFlagsOnly, 0, 1)));
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class, () -> service.onStartCommand(issue81UnknownStart(), 0x4, 2)));
    for (String action : new String[] {null, "another.START"}) {
      Intent intent = issue81UnknownStart().setAction(action);
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(
              RuntimeException.class,
              () -> service.onStartCommand(intent, Service.START_FLAG_RETRY, 3)));
    }
    assertEquals(4, Issue81ShadowService.promotions.size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(
      sdk = {24, 30, 31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_unrelatedPromotionFailures_remainFatal() throws Exception {
    ForegroundService service = issue81Service();
    List<RuntimeException> failures = new ArrayList<>();
    failures.add(new SecurityException("permission"));
    failures.add(new IllegalStateException("other denial"));
    failures.add(new RuntimeException("other failure"));
    if (Build.VERSION.SDK_INT >= 34) {
      failures.add(new MissingForegroundServiceTypeException("missing type"));
      failures.add(new InvalidForegroundServiceTypeException("invalid type"));
    }
    for (RuntimeException failure : failures) {
      Issue81ShadowService.failure = failure;
      assertSame(
          failure,
          assertThrows(
              RuntimeException.class,
              () -> service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1)));
    }
    assertEquals(failures.size(), Issue81ShadowService.promotions.size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
    issue81AssertUnpromoted(service);
  }

  @Test
  @Config(
      sdk = {33, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deniedA_preservesPendingBAndAllowsItsPromotion() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    issue81StartB();
    Intent b = context.starts.get(0);
    Object request = issue81Pending().get(b.getStringExtra(ISSUE81_REQUEST_ID));
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      assertEquals(
          Service.START_NOT_STICKY,
          service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1));
      assertSame(request, issue81Pending().get(b.getStringExtra(ISSUE81_REQUEST_ID)));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertTrue(capture.events.isEmpty());
      issue81AssertUnpromoted(service);
      assertFalse((boolean) issue81Field(service, "mStartCommandFailed"));
      service.onStartCommand(b, 0, 2);
      assertCurrentState("B", 812, "B");
      assertEquals(1, capture.events.size());
      assertTrue(issue81Pending().isEmpty());
      assertTrue(Issue81ShadowService.stops.isEmpty());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_deliveredUnfulfilledB_isNotSettledByDeniedA() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    issue81StartB();
    Intent b = context.starts.get(0);
    String requestId = b.getStringExtra(ISSUE81_REQUEST_ID);
    Object request = issue81Pending().get(requestId);
    Intent incomplete = new Intent(b);
    incomplete.removeExtra("notification");
    service.onStartCommand(incomplete, 0, 1);
    Field instance = request.getClass().getDeclaredField("instance");
    instance.setAccessible(true);
    assertSame(service, instance.get(request));
    assertTrue(Issue81ShadowService.promotions.isEmpty());
    assertEquals(
        Service.START_NOT_STICKY,
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 2));
    assertSame(request, issue81Pending().get(requestId));
    assertEquals(1, issue81Pending().size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
    issue81AssertUnpromoted(service);
    service.onStartCommand(b, 0, 3);
    assertCurrentState("B", 812, "B");
    assertTrue(issue81Pending().isEmpty());
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_deniedA_withQueuedStop_promotesBBeforeStopping() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    issue81StartB();
    ForegroundService.stop();
    Object stopRequest = getPrivateStatic("sStopRequest");
    assertEquals(
        Service.START_NOT_STICKY,
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1));
    idleMain();
    assertSame(stopRequest, getPrivateStatic("sStopRequest"));
    assertEquals(1, issue81Pending().size());
    assertTrue(Issue81ShadowService.stops.isEmpty());
    service.onStartCommand(context.starts.get(0), 0, 2);
    idleMain();
    assertEquals(
        java.util.Arrays.asList("promote:810", "promote:812", "stop:2"),
        Issue81ShadowService.order);
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(ForegroundService.mCurrentNotificationId);
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_refusedStop_preservesAdmissionForLaterStart() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    Issue81ShadowService.stopResult = false;
    issue81ObserveRecoveryStop(service);
    assertEquals(
        Service.START_NOT_STICKY,
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1));
    idleMain();
    assertFalse((boolean) issue81Field(service, "mStopInitiated"));
    issue81AssertUnpromoted(service);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertEquals(java.util.Collections.singletonList(1), Issue81ShadowService.stops);
    issue81StartB();
    service.onStartCommand(context.starts.get(0), 0, 2);
    assertCurrentState("B", 812, "B");
    assertTrue(issue81Pending().isEmpty());
    assertEquals(1, Issue81ShadowService.stops.size());
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_activeReplacementDenial_preservesStateAndFencesOldCompletion()
      throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = issue81Service();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      service.onStartCommand(buildStartIntent("live", 813, "live"), 0, 1);
      Object runner = issue81Field(service, "mRunnerToken");
      ForegroundService.stop();
      issue81StartB();
      Object request =
          issue81Pending().get(context.starts.get(0).getStringExtra(ISSUE81_REQUEST_ID));
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(
              RuntimeException.class,
              () -> service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 2)));
      assertCurrentState("live", 813, "live");
      assertSame(runner, issue81Field(service, "mRunnerToken"));
      assertSame(
          request, issue81Pending().get(context.starts.get(0).getStringExtra(ISSUE81_REQUEST_ID)));
      assertTrue(ForegroundService.repostIfActive("live"));
      assertTrue(Issue81ShadowService.stops.isEmpty());
      service.onStartCommand(context.starts.get(0), 0, 3);
      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("B", 812, "B");
      assertEquals(2, capture.events.size());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_priorPromotionAfterRunnerCompletion_isFatal() throws Exception {
    ForegroundService service = issue81Service();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      service.onStartCommand(buildStartIntent("live", 813, "live"), 0, 1);
      capture.events.get(0).setCompletionResult();
      assertNull(ForegroundService.mCurrentNotificationId);
      assertNull(issue81Field(service, "mRunnerToken"));
      assertSame(
          Issue81ShadowService.failure,
          assertThrows(
              RuntimeException.class,
              () -> service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 2)));
      assertTrue((boolean) issue81Field(service, "mStartForegroundCalled"));
      assertTrue(Issue81ShadowService.stops.isEmpty());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_staleInstanceDenial_preservesCurrentOwner() throws Exception {
    ForegroundService old = issue81Service();
    ForegroundService current = buildService().create().get();
    current.onStartCommand(buildStartIntent("live", 813, "live"), 0, 1);
    Object runner = issue81Field(current, "mRunnerToken");
    ForegroundService.stop();
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class,
            () -> old.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 2)));
    assertCurrentState("live", 813, "live");
    assertSame(current, getPrivateStatic("sInstance"));
    assertSame(current, getPrivateStatic("sStateOwner"));
    assertSame(runner, issue81Field(current, "mRunnerToken"));
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 34, shadows = Issue81ShadowService.class)
  public void issue81_sameIdTypeChangeDenial_isFatalAndPreservesCache() throws Exception {
    ForegroundService service = issue81Service();
    service.onStartCommand(
        buildStartIntent("live", 813, "live", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC), 0, 1);
    Object runner = issue81Field(service, "mRunnerToken");
    Intent update =
        buildStartIntent("live", 813, "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            .putExtra(ISSUE81_REQUEST_ID, UUID.randomUUID().toString());
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(update, Service.START_FLAG_RETRY, 2)));
    assertCurrentState("live", 813, "live");
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        ForegroundService.mCurrentForegroundServiceType);
    assertSame(runner, issue81Field(service, "mRunnerToken"));
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 34, shadows = Issue81ShadowService.class)
  public void issue81_noneDefensiveDenial_remainsFatal() throws Exception {
    ForegroundService service = issue81Service();
    Intent none = issue81UnknownStart();
    Bundle android = none.getBundleExtra("notificationBundle").getBundle("android");
    android.remove("foregroundServiceTypes");
    ContextHolder.setApplicationContext(null);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(none, Service.START_FLAG_RETRY, 1));
    assertSame(Issue81ShadowService.failure, thrown.getCause());
    assertEquals(
        java.util.Collections.singletonList(Integer.MAX_VALUE - 1),
        Issue81ShadowService.promotions);
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_manifestLookupDenial_remainsFatal() throws Exception {
    ForegroundService service = issue81Service();
    Intent intent = issue81UnknownStart();
    intent
        .getBundleExtra("notificationBundle")
        .getBundle("android")
        .remove("foregroundServiceTypes");
    PackageManager manager = org.mockito.Mockito.mock(PackageManager.class);
    org.mockito.Mockito.when(
            manager.getServiceInfo(
                org.mockito.ArgumentMatchers.any(ComponentName.class),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(Issue81ShadowService.failure);
    ContextHolder.setApplicationContext(
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public PackageManager getPackageManager() {
            return manager;
          }
        });
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(intent, Service.START_FLAG_RETRY, 1)));
    assertTrue(Issue81ShadowService.promotions.isEmpty());
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(
      sdk = 33,
      shadows = {Issue81ShadowService.class, Issue81NotificationShadowManager.class})
  public void issue81_sameIdNotifyDenial_remainsFatal() throws Exception {
    ForegroundService service = issue81Service();
    service.onStartCommand(buildStartIntent("live", 813, "live"), 0, 1);
    Intent update =
        buildStartIntent("live", 813, "A")
            .putExtra(ISSUE81_REQUEST_ID, UUID.randomUUID().toString());
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(update, Service.START_FLAG_RETRY, 2)));
    assertCurrentState("live", 813, "live");
    assertEquals(java.util.Collections.singletonList(813), Issue81ShadowService.promotions);
    assertTrue(Issue81ShadowService.stops.isEmpty());
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_stopOperationDenial_remainsFatal() throws Exception {
    ForegroundService service = issue81Service();
    Issue81ShadowService.stopFailure =
        new ForegroundServiceStartNotAllowedException("stop operation denied");
    assertSame(
        Issue81ShadowService.stopFailure,
        assertThrows(
            RuntimeException.class,
            () -> service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1)));
    assertEquals(java.util.Collections.singletonList(1), Issue81ShadowService.stops);
    issue81AssertUnpromoted(service);
    assertFalse((boolean) issue81Field(service, "mStopInitiated"));
    assertTrue(
        ShadowLog.getLogsForTag("NOTIFEE").stream()
            .anyMatch(
                item ->
                    item.type == android.util.Log.WARN
                        && item.throwable == Issue81ShadowService.failure
                        && item.msg.contains("stop=failed")));
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_pendingBLateReply_doesNotCancelStopAfterDelivery() throws Exception {
    CountDownLatch dispatched = new CountDownLatch(1);
    CountDownLatch reply = new CountDownLatch(1);
    List<Intent> starts = new ArrayList<>();
    ContextHolder.setApplicationContext(
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            starts.add(new Intent(intent));
            dispatched.countDown();
            try {
              if (!reply.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("reply timed out");
              }
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return intent.getComponent();
          }
        });
    ForegroundService service = issue81Service();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread sender =
        new Thread(
            () -> {
              try {
                issue81StartB();
              } catch (Throwable e) {
                failure.set(e);
              }
            });
    sender.start();
    try {
      assertTrue(dispatched.await(5, TimeUnit.SECONDS));
      Object request = issue81Pending().get(starts.get(0).getStringExtra(ISSUE81_REQUEST_ID));
      assertEquals(
          Service.START_NOT_STICKY,
          service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1));
      assertSame(request, issue81Pending().get(starts.get(0).getStringExtra(ISSUE81_REQUEST_ID)));
      assertTrue(Issue81ShadowService.stops.isEmpty());
      service.onStartCommand(starts.get(0), 0, 2);
      ForegroundService.stop();
      Object stop = getPrivateStatic("sStopRequest");
      reply.countDown();
      sender.join(5000);
      assertFalse(sender.isAlive());
      assertNull(failure.get());
      assertSame(stop, getPrivateStatic("sStopRequest"));
      idleMain();
      assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertEquals(java.util.Collections.singletonList(2), Issue81ShadowService.stops);
    } finally {
      reply.countDown();
      sender.join(5000);
    }
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_successfulRetry_usesNormalRunnerAndSameIdUpdate() throws Exception {
    ForegroundService service = issue81Service();
    Issue81ShadowService.failure = null;
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 1);
      assertCurrentState("A", 810, "A");
      assertTrue((boolean) issue81Field(service, "mStartForegroundCalled"));
      assertEquals(1, capture.events.size());
      service.onStartCommand(
          buildStartIntent("A", 810, "updated", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
          0,
          2);
      assertCurrentState("A", 810, "updated");
      assertEquals(java.util.Collections.singletonList(810), Issue81ShadowService.promotions);
      assertEquals(1, capture.events.size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33, shadows = Issue81ShadowService.class)
  public void issue81_retryWithoutPayload_doesNotAttemptRecovery() throws Exception {
    ForegroundService service = issue81Service();
    Intent intent = issue81UnknownStart();
    intent.removeExtra("notification");
    assertEquals(
        Service.START_NOT_STICKY, service.onStartCommand(intent, Service.START_FLAG_RETRY, 1));
    assertTrue(Issue81ShadowService.promotions.isEmpty());
    assertTrue(Issue81ShadowService.stops.isEmpty());
    issue81AssertUnpromoted(service);
  }

  private static final String ISSUE81_REQUEST_ID = "notifee.foregroundServiceStartRequestId";

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_lateSecurityRejection_stops() throws Exception {
    issue81AssertLateRejection(new SecurityException("B rejected"));
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_lateFgsRejection_stops() throws Exception {
    issue81AssertLateRejection(new ForegroundServiceStartNotAllowedException("B rejected"));
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_lateNullComponent_stops() throws Exception {
    issue81AssertLateRejection(null);
  }

  private void issue81AssertLateRejection(RuntimeException rejection) throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(rejection, false)) {
      b.awaitDispatch();
      Object request = issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID));
      assertNotNull(request);
      assertEquals(
          Service.START_NOT_STICKY,
          service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11));
      idleMain();
      assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
      assertTrue(Issue81ShadowService.stops.isEmpty());
      issue81AssertUnpromoted(service);
      assertSame(service, getPrivateStatic("sInstance"));
      assertEquals(11, issue81Field(service, "mLastStartId"));
      assertNull(getPrivateStatic("sStopRequest"));
      b.settle();
      assertSame(rejection, b.failure.get());
      assertTrue(issue81Pending().isEmpty());
      issue81ObserveRecoveryStop(service);
      idleMain();
      assertEquals(
          "The last definite rejection must re-evaluate denied A's stop",
          java.util.Collections.singletonList(11),
          Issue81ShadowService.stops);
      assertTrue((boolean) issue81Field(service, "mStopInitiated"));
      issue81AssertUnpromoted(service);
      assertNull(getPrivateStatic("sStopRequest"));
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_acceptedB_waitsForPromotion() throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, true)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      Object request = issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID));
      b.settle();
      assertNull(b.failure.get());
      idleMain();
      assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      service.onStartCommand(b.intent, 0, 12);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(issue81Pending().isEmpty());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(getPrivateStatic("sStopRequest"));
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_deliveredB_lateNegativeReplyPreservesRun() throws Exception {
    ForegroundService service = issue81Service();
    RuntimeException rejection = new SecurityException("late B rejection after delivery");
    try (Issue81LateReply b = new Issue81LateReply(rejection, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      service.onStartCommand(b.intent, 0, 12);
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      b.settle();
      assertSame(rejection, b.failure.get());
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue((boolean) issue81Field(service, "mForegroundActive"));
      assertTrue(issue81Pending().isEmpty());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(getPrivateStatic("sStopRequest"));
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_ambiguousReply_keepsObligation() throws Exception {
    ForegroundService service = issue81Service();
    RuntimeException unknown = new IllegalStateException("lost B reply");
    try (Issue81LateReply b = new Issue81LateReply(unknown, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      Object request = issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID));
      b.settle();
      assertSame(unknown, b.failure.get());
      idleMain();
      assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      issue81AssertUnpromoted(service);
      assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
      service.onStartCommand(b.intent, 0, 12);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(issue81Pending().isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      assertTrue(Issue81ShadowService.stops.isEmpty());
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_newAcceptedStart_invalidatesQueuedRecovery()
      throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      RecordingServiceContext context = useRecordingContext();
      issue81StartB();
      idleMain();
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertEquals(1, issue81Pending().size());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      assertNull(getPrivateStatic("sStopRequest"));
      service.onStartCommand(context.starts.get(0), 0, 12);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(Issue81ShadowService.stops.isEmpty());
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_newDelivery_invalidatesQueuedRecovery() throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      // Even an incomplete new delivery supersedes the denied startId; it grants no promotion.
      Intent later = issue81UnknownStart();
      later.removeExtra("notification");
      service.onStartCommand(later, 0, 12);
      idleMain();
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      issue81AssertUnpromoted(service);
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_refusedStop_isConsumedWithoutCleanupOrRetry()
      throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      Issue81ShadowService.stopResult = false;
      issue81ObserveRecoveryStop(service);
      idleMain();
      assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
      assertFalse((boolean) issue81Field(service, "mStopInitiated"));
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      issue81AssertUnpromoted(service);
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      issue81RejectAnotherDispatch();
      idleMain();
      assertEquals(1, Issue81ShadowService.stops.size());
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_throwingStop_preservesErrorAndDiagnostic() throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      Issue81ShadowService.stopFailure = new SecurityException("recovery stop failed");
      issue81ObserveRecoveryStop(service);
      assertSame(
          Issue81ShadowService.stopFailure, assertThrows(RuntimeException.class, () -> idleMain()));
      assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
      assertFalse((boolean) issue81Field(service, "mStopInitiated"));
      issue81AssertUnpromoted(service);
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      assertTrue(
          ShadowLog.getLogsForTag("NOTIFEE").stream()
              .anyMatch(
                  item ->
                      item.throwable == Issue81ShadowService.failure
                          && item.msg.contains("startId=11")
                          && item.msg.contains("stop=failed")));
      issue81RejectAnotherDispatch();
      idleMain();
      assertEquals(1, Issue81ShadowService.stops.size());
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_staleGenerations_doNotStop() throws Exception {
    ForegroundService service = issue81Service();
    for (String generation : new String[] {"sRunGeneration", "mRunGeneration"}) {
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
        assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
        // Isolate each immutable generation fence without introducing a separate client STOP.
        Field field = ForegroundService.class.getDeclaredField(generation);
        field.setAccessible(true);
        synchronized (getPrivateStatic("sLifecycleLock")) {
          Object owner = generation.startsWith("s") ? null : service;
          field.setLong(owner, field.getLong(owner) + 1);
        }
        b.settle();
        idleMain();
        assertTrue(Issue81ShadowService.stops.isEmpty());
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
      }
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_replacementAndOldDestroy_preserveNewOwner()
      throws Exception {
    ForegroundService old = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      old.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      ForegroundService current = buildService().create().get();
      current.onStartCommand(buildStartIntent("new", 814, "new"), 0, 12);
      controllers.get(0).destroy();
      controllers.remove(0);
      idleMain();
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertSame(current, getPrivateStatic("sInstance"));
      assertCurrentState("new", 814, "new");
      assertNull(issue81Field(old, "mDeniedStartRecovery"));
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_destroyBeforeLateAcceptance_preservesUndeliveredB()
      throws Exception {
    ForegroundService old = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, true)) {
      b.awaitDispatch();
      old.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      controllers.get(0).destroy();
      controllers.remove(0);
      assertNull(issue81Field(old, "mDeniedStartRecovery"));
      b.settle();
      idleMain();
      assertNull(b.failure.get());
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      ForegroundService current = buildService().create().get();
      current.onStartCommand(b.intent, 0, 12);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(issue81Pending().isEmpty());
      assertTrue(Issue81ShadowService.stops.isEmpty());
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_previousFailedCommand_doesNotSuppressNewRecovery()
      throws Exception {
    ForegroundService service = issue81Service();
    assertSame(
        Issue81ShadowService.failure,
        assertThrows(
            RuntimeException.class, () -> service.onStartCommand(issue81UnknownStart(), 0, 10)));
    assertTrue((boolean) issue81Field(service, "mStartCommandFailed"));
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      idleMain();
      assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
    }
  }

  private void issue81RejectAnotherDispatch() {
    RecordingServiceContext context = useRecordingContext();
    context.startFailure = new SecurityException("another definite rejection");
    assertSame(context.startFailure, assertThrows(RuntimeException.class, () -> issue81StartB()));
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_newPendingStart_defersUntilItsDefiniteRejection()
      throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      try (Issue81LateReply c = new Issue81LateReply(null, false)) {
        c.awaitDispatch();
        Object request = issue81Pending().get(c.intent.getStringExtra(ISSUE81_REQUEST_ID));
        idleMain();
        assertTrue(Issue81ShadowService.stops.isEmpty());
        assertSame(request, issue81Pending().get(c.intent.getStringExtra(ISSUE81_REQUEST_ID)));
        assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
        c.settle();
        idleMain();
        assertTrue(issue81Pending().isEmpty());
        assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
      }
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_terminalDelivery_replacesRecoveryWithExplicitStop()
      throws Exception {
    for (Intent terminal : new Intent[] {buildStopIntent(), null}) {
      ForegroundService service = issue81Service();
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
        service.onStartCommand(terminal, 0, 12);
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
        Object explicitStop = getPrivateStatic("sStopRequest");
        assertNotNull(explicitStop);
        idleMain();
        assertTrue(Issue81ShadowService.stops.isEmpty());
        assertEquals(1, issue81Pending().size());
        b.settle();
        assertSame(explicitStop, getPrivateStatic("sStopRequest"));
        idleMain();
        assertEquals(java.util.Collections.singletonList(12), Issue81ShadowService.stops);
        assertNull(getPrivateStatic("sStopRequest"));
      }
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_lateOldRunnerCompletion_doesNotCancelRecovery()
      throws Exception {
    ForegroundService old = issue81Service();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      old.onStartCommand(buildStartIntent("old", 814, "old"), 0, 1);
      ForegroundService service = buildService().create().get();
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
        Object recovery = issue81Field(service, "mDeniedStartRecovery");
        capture.events.get(0).setCompletionResult();
        assertSame(recovery, issue81Field(service, "mDeniedStartRecovery"));
        issue81AssertUnpromoted(service);
        b.settle();
        idleMain();
        assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
        assertNull(issue81Field(service, "mDeniedStartRecovery"));
      }
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_staleOwnershipOrStartId_rejectsQueuedCallback()
      throws Exception {
    for (String authority : new String[] {"sInstance", "sStateOwner", "mLastStartId"}) {
      ForegroundService service = issue81Service();
      try (Issue81LateReply b = new Issue81LateReply(null, false)) {
        b.awaitDispatch();
        service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
        b.settle();
        // Independently exercise callback revalidation after it has captured the original token.
        Field field = ForegroundService.class.getDeclaredField(authority);
        field.setAccessible(true);
        Object owner = authority.startsWith("s") ? null : service;
        Object original = field.get(owner);
        try {
          synchronized (getPrivateStatic("sLifecycleLock")) {
            field.set(owner, authority.equals("mLastStartId") ? 12 : new ForegroundService());
          }
          idleMain();
          assertTrue(Issue81ShadowService.stops.isEmpty());
          assertNull(issue81Field(service, "mDeniedStartRecovery"));
        } finally {
          field.set(owner, original);
        }
      }
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_deliveredUnfulfilledB_lateNullReplyKeepsObligation()
      throws Exception {
    issue81AssertUnfulfilledLateReply(null);
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_deliveredUnfulfilledB_lateSecurityReplyKeepsObligation()
      throws Exception {
    issue81AssertUnfulfilledLateReply(new SecurityException("B already delivered"));
  }

  private void issue81AssertUnfulfilledLateReply(RuntimeException rejection) throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(rejection, false)) {
      b.awaitDispatch();
      Intent incomplete = new Intent(b.intent);
      incomplete.removeExtra("notification");
      service.onStartCommand(incomplete, 0, 10);
      Object request = issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID));
      assertNotNull(request);
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      b.settle();
      assertSame(rejection, b.failure.get());
      idleMain();
      assertSame(request, issue81Pending().get(b.intent.getStringExtra(ISSUE81_REQUEST_ID)));
      assertEquals(1, issue81Pending().size());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      issue81AssertUnpromoted(service);
      service.onStartCommand(b.intent, 0, 12);
      idleMain();
      assertCurrentState("B", 812, "B");
      assertTrue(issue81Pending().isEmpty());
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      assertNull(getPrivateStatic("sStopRequest"));
    }
  }

  @Test
  @Config(
      sdk = {31, 34},
      shadows = Issue81ShadowService.class)
  public void issue81_deferredRecovery_explicitStop_invalidatesRecoveryWhileBPending()
      throws Exception {
    ForegroundService service = issue81Service();
    try (Issue81LateReply b = new Issue81LateReply(null, false)) {
      b.awaitDispatch();
      service.onStartCommand(issue81UnknownStart(), Service.START_FLAG_RETRY, 11);
      assertNotNull(issue81Field(service, "mDeniedStartRecovery"));
      ForegroundService.stop();
      assertNull(issue81Field(service, "mDeniedStartRecovery"));
      Object explicitStop = getPrivateStatic("sStopRequest");
      assertNotNull(explicitStop);
      idleMain();
      assertTrue(Issue81ShadowService.stops.isEmpty());
      assertEquals(1, issue81Pending().size());
      b.settle();
      assertSame(explicitStop, getPrivateStatic("sStopRequest"));
      idleMain();
      assertEquals(java.util.Collections.singletonList(11), Issue81ShadowService.stops);
      assertNull(getPrivateStatic("sStopRequest"));
    }
  }

  // Suspend the actual START sender's platform reply, without blocking lifecycle delivery.
  private static final class Issue81LateReply implements AutoCloseable {
    final CountDownLatch dispatched = new CountDownLatch(1);
    final CountDownLatch reply = new CountDownLatch(1);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread sender;
    Intent intent;

    Issue81LateReply(RuntimeException rejection, boolean accepted) {
      this(rejection, accepted, "B", 812);
    }

    Issue81LateReply(RuntimeException rejection, boolean accepted, String id, int hash) {
      ContextHolder.setApplicationContext(
          new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public ComponentName startForegroundService(Intent start) {
              intent = new Intent(start);
              dispatched.countDown();
              try {
                if (!reply.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("B reply timed out");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              if (rejection != null) {
                throw rejection;
              }
              return accepted ? start.getComponent() : null;
            }
          });
      sender =
          new Thread(
              () -> {
                try {
                  Intent start =
                      buildStartIntent(id, hash, id, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                  ForegroundService.start(
                      hash,
                      start.getParcelableExtra("notification"),
                      start.getBundleExtra("notificationBundle"));
                } catch (Throwable e) {
                  failure.set(e);
                }
              });
      sender.start();
    }

    void awaitDispatch() throws Exception {
      assertTrue(dispatched.await(5, TimeUnit.SECONDS));
    }

    void settle() throws Exception {
      reply.countDown();
      sender.join(5000);
      assertFalse(sender.isAlive());
    }

    @Override
    public void close() throws Exception {
      settle();
    }
  }

  private ForegroundService issue81Service() throws Exception {
    Issue81ShadowService.failure =
        Build.VERSION.SDK_INT >= 31
            ? new ForegroundServiceStartNotAllowedException("initial A promotion denied")
            : new IllegalStateException("initial A promotion denied");
    Issue81ShadowService.stopFailure = null;
    Issue81ShadowService.rejectTitle = "A";
    Issue81ShadowService.stopResult = true;
    Issue81ShadowService.beforeStop = null;
    Issue81ShadowService.beforePromotion = null;
    Issue81ShadowService.promotions.clear();
    Issue81ShadowService.types.clear();
    Issue81ShadowService.stops.clear();
    Issue81ShadowService.order.clear();
    ShadowLog.clear();
    if (Build.VERSION.SDK_INT >= 34) {
      declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    if (Build.VERSION.SDK_INT >= 26) {
      createChannel();
    }
    return buildService().create().get();
  }

  private static Intent issue81UnknownStart() {
    return buildStartIntent("A", 810, "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        .putExtra(ISSUE81_REQUEST_ID, UUID.randomUUID().toString());
  }

  private static void issue81StartB() {
    Intent b = buildStartIntent("B", 812, "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    ForegroundService.start(
        812, b.getParcelableExtra("notification"), b.getBundleExtra("notificationBundle"));
  }

  private void issue81AssertFreshRecovery(int flags) throws Exception {
    ForegroundService service = issue81Service();
    issue81ObserveRecoveryStop(service);
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      int result;
      try {
        result = service.onStartCommand(issue81UnknownStart(), flags, 7);
      } catch (RuntimeException denied) {
        // RED is admissible only after the real handler attempted the real notification/type.
        assertSame(Issue81ShadowService.failure, denied);
        assertEquals(java.util.Collections.singletonList(810), Issue81ShadowService.promotions);
        assertEquals(
            java.util.Collections.singletonList(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
            Issue81ShadowService.types);
        issue81AssertUnpromoted(service);
        throw denied;
      }
      assertEquals(Service.START_NOT_STICKY, result);
      assertEquals(java.util.Collections.singletonList(810), Issue81ShadowService.promotions);
      assertEquals(
          java.util.Collections.singletonList(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
          Issue81ShadowService.types);
      assertEquals(java.util.Arrays.asList("promote:810", "stop:7"), Issue81ShadowService.order);
      issue81AssertUnpromoted(service);
      assertTrue((boolean) issue81Field(service, "mStopInitiated"));
      assertTrue(capture.events.isEmpty());
      assertTrue(issue81Pending().isEmpty());
      assertTrue(
          ShadowLog.getLogsForTag("NOTIFEE").stream()
              .anyMatch(
                  item ->
                      item.type == android.util.Log.WARN
                          && item.throwable == Issue81ShadowService.failure
                          && item.msg.contains("startId=7")
                          && item.msg.contains("flags=" + flags)));
      ForegroundService current = buildService().create().get();
      current.onStartCommand(buildStartIntent("new", 814, "new"), 0, 8);
      controllers.get(0).destroy();
      controllers.remove(0);
      assertCurrentState("new", 814, "new");
      assertSame(current, getPrivateStatic("sInstance"));
    } finally {
      EventBus.unregister(capture);
    }
  }

  private static void issue81AssertUnpromoted(ForegroundService service) throws Exception {
    assertFalse((boolean) issue81Field(service, "mStartForegroundCalled"));
    assertFalse((boolean) issue81Field(service, "mForegroundActive"));
    assertFalse((boolean) issue81Field(service, "mStartCommandSatisfied"));
    assertNull(issue81Field(service, "mRunnerToken"));
    assertEquals(0, issue81Field(service, "mRunFirstStartId"));
    assertNull(ForegroundService.mCurrentNotificationId);
    assertEquals(-1, ForegroundService.mCurrentForegroundServiceType);
    assertNull(getPrivateStatic("mCurrentNotificationBundle"));
    assertNull(getPrivateStatic("mCurrentNotification"));
    assertEquals(0, getPrivateStatic("mCurrentHashCode"));
  }

  private static void issue81ObserveRecoveryStop(ForegroundService service) {
    Issue81ShadowService.beforeStop =
        () -> {
          try {
            assertTrue(Thread.holdsLock(getPrivateStatic("sLifecycleLock")));
            assertFalse(Thread.holdsLock(getPrivateStatic("sLock")));
            issue81AssertUnpromoted(service);
            assertFalse((boolean) issue81Field(service, "mStopInitiated"));
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
  }

  private static Object issue81Field(Object instance, String name) throws Exception {
    Field field = ForegroundService.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> issue81Pending() throws Exception {
    return (Map<String, ?>) getPrivateStatic("sPendingStarts");
  }

  @Implements(Service.class)
  public static class Issue81ShadowService extends ShadowService {
    static RuntimeException failure;
    static RuntimeException stopFailure;
    static String rejectTitle;
    static boolean stopResult;
    static Runnable beforeStop;
    static Runnable beforePromotion;
    static final List<Integer> promotions = new ArrayList<>();
    static final List<Integer> types = new ArrayList<>();
    static final List<Integer> stops = new ArrayList<>();
    static final List<String> order = new ArrayList<>();
    private boolean forwardingTyped;

    private void recordPromotion(int id, Notification notification, int type) {
      promotions.add(id);
      types.add(type);
      order.add("promote:" + id);
      if (beforePromotion != null) beforePromotion.run();
      if (failure != null
          && (id == Integer.MAX_VALUE - 1
              || rejectTitle == null
              || rejectTitle.equals(notification.extras.getString(Notification.EXTRA_TITLE)))) {
        throw failure;
      }
    }

    @Implementation
    protected void startForeground(int id, Notification notification) {
      if (!forwardingTyped) {
        recordPromotion(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
      }
      super.startForeground(id, notification);
    }

    @Implementation
    protected void startForeground(int id, Notification notification, int type) {
      recordPromotion(id, notification, type);
      // ShadowService's typed overload delegates to the two-argument shadow. Record only the
      // actual platform invocation so the witness cannot count that delegation as a second call.
      forwardingTyped = true;
      try {
        super.startForeground(id, notification, type);
      } finally {
        forwardingTyped = false;
      }
    }

    @Implementation
    protected boolean stopSelfResult(int startId) {
      if (beforeStop != null) {
        beforeStop.run();
      }
      stops.add(startId);
      order.add("stop:" + startId);
      if (stopFailure != null) {
        throw stopFailure;
      }
      return stopResult && super.stopSelfResult(startId);
    }
  }

  @Implements(NotificationManager.class)
  public static class Issue81NotificationShadowManager extends ShadowNotificationManager {
    @Implementation
    protected void notify(String tag, int id, Notification notification) {
      if ("A".equals(notification.extras.getString(Notification.EXTRA_TITLE))) {
        throw Issue81ShadowService.failure;
      }
      super.notify(tag, id, notification);
    }
  }

  @Test
  @Config(sdk = {24, 26, 30, 33, 34})
  public void stop_withoutInstance_doesNotStartServiceOrEmitRunner() {
    RecordingServiceContext context = useRecordingContext();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService.stop();
      idleMain();
      assertTrue(context.starts.isEmpty());
      assertEquals(0, context.stops);
      assertTrue(capture.events.isEmpty());
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void stop_startStopDestroySecondStop_doesNotRecreateService() {
    RecordingServiceContext context = useRecordingContext();
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();
    createChannel();
    service.onStartCommand(buildStartIntent("first", 101), 0, 1);

    ForegroundService.stop();
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    controller.destroy();
    controllers.remove(controller);
    ForegroundService.stop();
    idleMain();
    assertTrue(context.starts.isEmpty());
    assertNull(ForegroundService.mCurrentNotificationId);
  }

  @Test
  @Config(sdk = 33)
  public void stop_nullNotificationCache_stillStopsLiveInstance() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = buildService().create().get();
    createChannel();
    service.onStartCommand(buildStartIntent("live", 102), 0, 1);
    ForegroundService.mCurrentNotificationId = null;
    ForegroundService.mCurrentForegroundServiceType = -1;
    setPrivateStatic("mCurrentNotificationBundle", null);
    setPrivateStatic("mCurrentNotification", null);
    setPrivateStatic("mCurrentHashCode", 0);

    ForegroundService.stop();
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 33)
  public void stop_concurrentDestroy_doesNotDispatchNewGeneration() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();
    createChannel();
    service.onStartCommand(buildStartIntent("old", 103), 0, 1);
    CountDownLatch begin = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread stop =
        new Thread(
            () -> {
              try {
                assertTrue(begin.await(5, TimeUnit.SECONDS));
                ForegroundService.stop();
              } catch (Throwable e) {
                failure.set(e);
              }
            });
    stop.start();
    begin.countDown();
    controller.destroy();
    controllers.remove(controller);
    stop.join(5000);
    assertFalse(stop.isAlive());
    assertNull(failure.get());
    idleMain();
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 33)
  public void onDestroy_oldInstance_preservesNewInstanceAndAllNotificationState() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ServiceController<ForegroundService> oldController = buildService();
    ForegroundService old = oldController.create().get();
    createChannel();
    old.onStartCommand(buildStartIntent("old", 104), 0, 1);
    ForegroundService current = buildService().create().get();
    current.onStartCommand(buildStartIntent("new", 105, "new title"), 0, 2);
    oldController.destroy();
    controllers.remove(oldController);

    assertCurrentState("new", 105, "new title");
    ForegroundService.stop();
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(current).isStoppedBySelf());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 33)
  public void runnerCompletion_oldInstance_doesNotDemoteOrClearNewInstance() throws Exception {
    useRecordingContext();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService old = buildService().create().get();
      createChannel();
      old.onStartCommand(buildStartIntent("old", 106), 0, 1);
      ForegroundService current = buildService().create().get();
      current.onStartCommand(buildStartIntent("new", 107, "new title"), 0, 2);
      assertEquals(2, capture.events.size());

      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("new", 107, "new title");
      assertFalse(org.robolectric.Shadows.shadowOf(current).isForegroundStopped());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void runnerCompletion_oldRunOnSameInstance_preservesNewRun() throws Exception {
    useRecordingContext();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      createChannel();
      service.onStartCommand(buildStartIntent("old", 108), 0, 1);
      service.onStartCommand(buildStopIntent(), 0, 2);
      service.onStartCommand(buildStartIntent("new", 109, "new title"), 0, 3);
      assertEquals(2, capture.events.size());

      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("new", 109, "new title");
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      capture.events.get(1).setCompletionResult();
      idleMain();
      assertNull(ForegroundService.mCurrentNotificationId);
      assertTrue(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void stop_acceptedPendingStart_fulfillsRealContractBeforeStopping() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService.start(110, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService.stop();
    idleMain();
    assertEquals(1, context.starts.size());
    ForegroundService service = buildService().create().get();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    assertEquals(110, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(ForegroundService.mCurrentNotificationId);
    assertEquals(1, context.starts.size());
  }

  @Test
  @Config(sdk = 33)
  public void stop_betweenCreateAndPendingDelivery_doesNotStopBeforeRealPromotion() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService.start(111, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService service = buildService().create().get();
    ForegroundService.stop();
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());

    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    assertEquals(111, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertEquals(1, context.starts.size());
  }

  @Test
  @Config(sdk = 33)
  public void stop_newStartAcceptedBeforeQueuedStop_doesNotCancelLaterStart() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStartIntent("same", 112, "A"), 0, 1);
    ForegroundService.stop();
    Intent later = buildStartIntent("same", 112, "B");
    ForegroundService.start(
        112, later.getParcelableExtra("notification"), later.getBundleExtra("notificationBundle"));
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    service.onStartCommand(context.starts.get(0), 0, 2);
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertCurrentState("same", 112, "B");
  }

  @Test
  @Config(
      sdk = {28, 33, 34},
      shadows = {RejectBStartForegroundShadowService.class, RejectBNotificationShadowManager.class})
  public void stop_failedRestart_preservesPreviousSubmittedStateAndRunner() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    if (Build.VERSION.SDK_INT >= 34) {
      declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    createChannel();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      Intent a = buildStartIntent("same", 177, "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      ForegroundService.start(
          177, a.getParcelableExtra("notification"), a.getBundleExtra("notificationBundle"));
      service.onStartCommand(context.starts.get(0), 0, 1);
      Field tokenField = ForegroundService.class.getDeclaredField("mRunnerToken");
      tokenField.setAccessible(true);
      Object tokenA = tokenField.get(service);
      int typeA = ForegroundService.mCurrentForegroundServiceType;

      ForegroundService.stop();
      Intent b = buildStartIntent("same", 177, "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      ForegroundService.start(
          177, b.getParcelableExtra("notification"), b.getBundleExtra("notificationBundle"));
      SecurityException failure =
          assertThrows(
              SecurityException.class, () -> service.onStartCommand(context.starts.get(1), 0, 2));

      assertEquals("posting B rejected", failure.getMessage());
      assertEquals(
          "A",
          org.robolectric.Shadows.shadowOf(
                  (NotificationManager)
                      RuntimeEnvironment.getApplication()
                          .getSystemService(Context.NOTIFICATION_SERVICE))
              .getNotification(177)
              .extras
              .getString(Notification.EXTRA_TITLE));
      assertCurrentState("same", 177, "A");
      assertEquals(typeA, ForegroundService.mCurrentForegroundServiceType);
      assertTrue(tokenA == tokenField.get(service));
      assertEquals(1, capture.events.size());
      assertTrue(ForegroundService.repostIfActive("same"));
      idleMain();
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("same", 177, "A");
      assertTrue(tokenA == tokenField.get(service));
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());

      // A failed reservation must still be replaced by a fresh runner when C succeeds in the
      // reserved session, even if C uses a different notification ID.
      Intent c = buildStartIntent("new", 178, "C", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      ForegroundService.start(
          178, c.getParcelableExtra("notification"), c.getBundleExtra("notificationBundle"));
      service.onStartCommand(context.starts.get(2), 0, 3);
      assertCurrentState("new", 178, "C");
      assertFalse(tokenA == tokenField.get(service));
      assertEquals(2, capture.events.size());
      capture.events.get(0).setCompletionResult();
      idleMain();
      assertCurrentState("new", 178, "C");
      capture.events.get(1).setCompletionResult();
      idleMain();
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void onStartCommand_stopAndNullWithPendingStart_doNotSuppressRealStart() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService.start(113, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStopIntent(), 0, 1);
    service.onStartCommand(null, 0, 2);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());

    service.onStartCommand(context.starts.get(0), 0, 3);
    idleMain();
    assertEquals(113, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(
      sdk = {31, 33, 34, 35},
      shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_stopAndNullDenied_areNonFatalAndDoNotClaimPromotion()
      throws Exception {
    if (Build.VERSION.SDK_INT >= 34) {
      declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    DefensiveFailureShadowService.failure = new ForegroundServiceStartNotAllowedException("denied");
    DefensiveFailureShadowService.attempts = 0;
    ShadowLog.clear();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStopIntent(), 0, 1);
    service.onStartCommand(null, 0, 2);

    assertEquals(2, DefensiveFailureShadowService.attempts);
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertTrue(
        ShadowLog.getLogsForTag("NOTIFEE").stream()
            .anyMatch(item -> item.throwable == DefensiveFailureShadowService.failure));
  }

  @Test
  @Config(sdk = 31, shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_stopDeniedApi31_isNonFatal() {
    DefensiveFailureShadowService.failure = new ForegroundServiceStartNotAllowedException("denied");
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStopIntent(), 0, 1);
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(
      sdk = {24, 30, 33},
      shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_stopGenericIllegalState_remainsObservable() {
    assertTerminalFailureObservable(new IllegalStateException("unrelated ISE"));
  }

  @Test
  @Config(sdk = 34, shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_stopMissingType_remainsObservable() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    assertTerminalFailureObservable(new MissingForegroundServiceTypeException("missing type"));
  }

  @Test
  @Config(sdk = 34, shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_stopInvalidType_remainsObservable() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    assertTerminalFailureObservable(new InvalidForegroundServiceTypeException("invalid type"));
  }

  @Test
  @Config(sdk = 34, shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_noneDenied_isNotSoftSuppressed() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    DefensiveFailureShadowService.failure =
        new ForegroundServiceStartNotAllowedException("NONE denied");
    ForegroundService service = buildService().create().get();
    Intent none = buildStartIntent("none", 114, "none", ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
    // The model normalizes NONE to MANIFEST on API 34. Removing only the application context
    // makes manifest resolution return NONE; the Service still has its declared defensive type.
    ContextHolder.setApplicationContext(null);
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> service.onStartCommand(none, 0, 1));
    assertTrue(
        thrown == DefensiveFailureShadowService.failure
            || thrown.getCause() == DefensiveFailureShadowService.failure);
    assertEquals(Integer.MAX_VALUE - 1, DefensiveFailureShadowService.lastId);
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, DefensiveFailureShadowService.lastType);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(sdk = 33, shadows = SecurityDefensiveChannelShadowManager.class)
  public void onStartCommand_terminalChannelSecurityException_remainsObservable() {
    for (Intent terminal : new Intent[] {buildStopIntent(), null}) {
      ForegroundService service = buildService().create().get();
      SecurityException thrown =
          assertThrows(SecurityException.class, () -> service.onStartCommand(terminal, 0, 1));
      assertEquals("defensive channel failure", thrown.getMessage());
      assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    }
  }

  @Test
  @Config(sdk = 33, shadows = SecurityStopForegroundShadowService.class)
  public void onStartCommand_terminalTeardownSecurityException_remainsObservable() {
    for (Intent terminal : new Intent[] {buildStopIntent(), null}) {
      ForegroundService service = buildService().create().get();
      SecurityException thrown =
          assertThrows(SecurityException.class, () -> service.onStartCommand(terminal, 0, 1));
      assertEquals("defensive teardown failure", thrown.getMessage());
      assertEquals(
          Integer.MAX_VALUE - 1,
          org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    }
  }

  @Test
  @Config(sdk = 33, shadows = DefensiveFailureShadowService.class)
  public void onStartCommand_pendingStartDenied_isNotSoftSuppressedByStop() {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService.start(115, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService.stop();
    DefensiveFailureShadowService.failure =
        new ForegroundServiceStartNotAllowedException("real START denied");
    ForegroundService service = buildService().create().get();
    assertThrows(
        ForegroundServiceStartNotAllowedException.class,
        () -> service.onStartCommand(context.starts.get(0), 0, 1));
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  private void assertTerminalFailureObservable(RuntimeException failure) {
    DefensiveFailureShadowService.failure = failure;
    ForegroundService service = buildService().create().get();
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> service.onStartCommand(buildStopIntent(), 0, 1));
    assertTrue(thrown == failure || thrown.getCause() == failure);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(sdk = 33)
  public void runnerCompletion_stopSupersededByNewStart_preservesLaterRun() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      createChannel();
      service.onStartCommand(buildStartIntent("same", 116, "A"), 0, 1);
      ForegroundService.stop();
      Intent later = buildStartIntent("same", 116, "B");
      ForegroundService.start(
          116,
          later.getParcelableExtra("notification"),
          later.getBundleExtra("notificationBundle"));
      service.onStartCommand(context.starts.get(0), 0, 2);
      capture.events.get(0).setCompletionResult();
      idleMain();

      assertCurrentState("same", 116, "B");
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertEquals(2, capture.events.size());
      capture.events.get(1).setCompletionResult();
      idleMain();
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void stop_createdButUnpublishedStart_waitsForRealStartCommand() {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = buildService().create().get();
    ForegroundService.stop();
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());
    createChannel();
    service.onStartCommand(buildStartIntent("unknown", 117), 0, 1);
    idleMain();
    assertEquals(117, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 33)
  public void stop_multiplePendingStarts_waitsForAllRealCommands() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService.start(118, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService.start(118, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService.stop();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    service.onStartCommand(context.starts.get(1), 0, 2);
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertEquals(2, org.robolectric.Shadows.shadowOf(service).getStopSelfResultId());
    assertEquals(2, context.starts.size());
  }

  @Test
  @Config(sdk = 33)
  public void onStartCommand_pendingWithoutPromotion_doesNotAcquireStopAuthority() {
    RecordingServiceContext context = useRecordingContext();
    // Accepted START, but its delivery has no notification. STOP must not turn this into a
    // successful/absent START obligation, even though malformed START handling is out of scope.
    ForegroundService.start(119, null, buildNotificationBundle("pending"));
    ForegroundService.stop();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    ForegroundService.stop();
    service.onStartCommand(null, 0, 2);
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());
    assertEquals(1, context.starts.size());
  }

  @Test
  @Config(sdk = 33)
  public void start_dispatchFailure_doesNotCancelEarlierStop() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStartIntent("live", 120), 0, 1);
    ForegroundService.stop();
    context.startFailure = new ForegroundServiceStartNotAllowedException("dispatch rejected");
    assertThrows(
        ForegroundServiceStartNotAllowedException.class,
        () -> ForegroundService.start(121, buildNotification(), buildNotificationBundle("failed")));
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(ForegroundService.mCurrentNotificationId);
    assertEquals(1, context.starts.size());
  }

  @Test
  @Config(sdk = 33, shadows = RefuseStopSelfShadowService.class)
  public void stop_frameworkRefusesTokenStop_preservesCurrentState() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService service = buildService().create().get();
    createChannel();
    service.onStartCommand(buildStartIntent("live", 122, "live title"), 0, 1);
    ForegroundService.stop();
    idleMain();
    assertCurrentState("live", 122, "live title");
    assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 34)
  public void onTimeout_oldInstance_doesNotClearNewStateOrEmitNewInstanceEvent() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    createChannel();
    ForegroundService old = buildService().create().get();
    old.onStartCommand(
        buildStartIntent("old", 123, "old", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        1);
    ForegroundService current = buildService().create().get();
    current.onStartCommand(
        buildStartIntent(
            "new", 124, "new title", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        2);
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      old.onTimeout(1);
      assertCurrentState("new", 124, "new title");
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
          ForegroundService.mCurrentForegroundServiceType);
      assertTrue(capture.events.isEmpty());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33, shadows = FailStopForegroundShadowService.class)
  public void onStartCommand_denialOutsidePromotion_isStillFatal() {
    ForegroundService service = buildService().create().get();
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> service.onStartCommand(buildStopIntent(), 0, 1));
    assertTrue(
        thrown instanceof ForegroundServiceStartNotAllowedException
            || thrown.getCause() instanceof ForegroundServiceStartNotAllowedException);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(sdk = 33)
  public void stop_duringStartAcceptance_preservesAndThenStopsAcceptedStart() throws Exception {
    CountDownLatch dispatchEntered = new CountDownLatch(1);
    CountDownLatch releaseDispatch = new CountDownLatch(1);
    CountDownLatch stopAttempted = new CountDownLatch(1);
    RecordingServiceContext context =
        new RecordingServiceContext(RuntimeEnvironment.getApplication()) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            dispatchEntered.countDown();
            try {
              assertTrue(releaseDispatch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return super.startForegroundService(intent);
          }
        };
    ContextHolder.setApplicationContext(context);
    createChannel();
    Notification notification = buildNotification();
    Bundle bundle = buildNotificationBundle("pending");
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> start = workers.submit(() -> ForegroundService.start(125, notification, bundle));
      assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS));
      Future<?> stop =
          workers.submit(
              () -> {
                stopAttempted.countDown();
                ForegroundService.stop();
              });
      assertTrue(stopAttempted.await(5, TimeUnit.SECONDS));
      releaseDispatch.countDown();
      start.get(5, TimeUnit.SECONDS);
      stop.get(5, TimeUnit.SECONDS);

      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(0), 0, 1);
      idleMain();
      assertEquals(
          125, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
      assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertEquals(1, context.starts.size());
    } finally {
      releaseDispatch.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  @Config(sdk = 33)
  public void stop_queuedForDestroyedInstance_doesNotTouchLaterInstance() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ServiceController<ForegroundService> oldController = buildService();
    ForegroundService old = oldController.create().get();
    old.onStartCommand(buildStartIntent("old", 126), 0, 1);
    ForegroundService.stop();
    oldController.destroy();
    controllers.remove(oldController);
    ForegroundService current = buildService().create().get();
    current.onStartCommand(buildStartIntent("new", 127, "new title"), 0, 2);
    idleMain();
    assertCurrentState("new", 127, "new title");
    assertFalse(org.robolectric.Shadows.shadowOf(current).isStoppedBySelf());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 33)
  public void stop_liveInstanceWithUnavailableApplicationContext_stillReachesInstance() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStartIntent("live", 128), 0, 1);
    ContextHolder.setApplicationContext(null);
    ForegroundService.stop();
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertTrue(context.starts.isEmpty());
  }

  @Test
  @Config(sdk = 24)
  public void stop_pendingStartBeforeApi26_preservesPlainStartContract() {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService.start(129, buildNotification(), buildNotificationBundle("pending"));
    ForegroundService.stop();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    assertEquals(129, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertEquals(1, context.starts.size());
  }

  @Test
  @Config(sdk = 33)
  public void runnerCompletion_afterStopBeforeNewDelivery_doesNotDemotePendingRun() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      service.onStartCommand(buildStartIntent("same", 130), 0, 1);
      ForegroundService.stop();
      ForegroundService.start(130, buildNotification(), buildNotificationBundle("same"));
      capture.events.get(0).setCompletionResult();
      idleMain();
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      service.onStartCommand(context.starts.get(0), 0, 2);
      assertEquals(2, capture.events.size());
      assertEquals("same", ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void stop_laterRealPromotion_settlesEarlierDeliveredUnknownContract() {
    RecordingServiceContext context = useRecordingContext();
    ForegroundService.start(131, null, buildNotificationBundle("unknown"));
    ForegroundService.stop();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(context.starts.get(0), 0, 1);
    idleMain();
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    createChannel();
    ForegroundService.start(132, buildNotification(), buildNotificationBundle("valid"));
    service.onStartCommand(context.starts.get(1), 0, 2);
    ForegroundService.stop();
    idleMain();
    assertEquals(132, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(ForegroundService.mCurrentNotificationId);
    assertEquals(2, context.starts.size());
  }

  @Test
  @Config(sdk = 28, shadows = RejectBStartForegroundShadowService.class)
  public void stop_failedUpdateOnRealForegroundInstance_doesNotFenceStopForever() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(buildStartIntent("same", 133, "A"), 0, 1);
    Intent update = buildStartIntent("same", 133, "B");
    ForegroundService.start(
        133,
        update.getParcelableExtra("notification"),
        update.getBundleExtra("notificationBundle"));
    assertThrows(
        SecurityException.class, () -> service.onStartCommand(context.starts.get(0), 0, 2));
    assertCurrentState("same", 133, "A");
    ForegroundService.stop();
    idleMain();
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(ForegroundService.mCurrentNotificationId);
  }

  @Test
  @Config(sdk = 34)
  public void onTimeout_oldRunOnSameInstance_preservesNewRun() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(
        buildStartIntent("old", 134, "old", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        1);
    service.onStartCommand(buildStopIntent(), 0, 2);
    service.onStartCommand(
        buildStartIntent(
            "new", 135, "new title", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        3);
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      service.onTimeout(1);
      assertCurrentState("new", 135, "new title");
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
          ForegroundService.mCurrentForegroundServiceType);
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertTrue(capture.events.isEmpty());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 34)
  public void onTimeout_sameRunEarlierStartId_preservesLatestUpdateTimeoutBehavior()
      throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    createChannel();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(
        buildStartIntent("same", 136, "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        1);
    service.onStartCommand(
        buildStartIntent("same", 136, "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        2);
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      service.onTimeout(1);
      assertNull(ForegroundService.mCurrentNotificationId);
      assertTrue(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertEquals(1, capture.events.size());
      assertEquals("B", capture.events.get(0).getNotification().toBundle().getString("title"));
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void start_serviceDeliveryBeforeDispatchReturns_canFulfillRealContract() throws Exception {
    CountDownLatch dispatchEntered = new CountDownLatch(1);
    CountDownLatch promotionObserved = new CountDownLatch(1);
    AtomicReference<Boolean> blocked = new AtomicReference<>(false);
    RecordingServiceContext context =
        new RecordingServiceContext(RuntimeEnvironment.getApplication()) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            ComponentName result = super.startForegroundService(intent);
            dispatchEntered.countDown();
            try {
              // The timeout releases the simulated Binder reply even for the broken candidate.
              blocked.set(!promotionObserved.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return result;
          }
        };
    ContextHolder.setApplicationContext(context);
    createChannel();
    Notification notification = buildNotification();
    Bundle bundle = buildNotificationBundle("pending");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<?> start = worker.submit(() -> ForegroundService.start(137, notification, bundle));
      assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS));
      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(0), 0, 1);
      promotionObserved.countDown();
      start.get(5, TimeUnit.SECONDS);
      assertFalse("Service promotion waited for the dispatch caller", blocked.get());
      assertEquals(
          137, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    } finally {
      promotionObserved.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  @Config(sdk = 33)
  public void start_ambiguousDispatchFailure_retainsRealObligationUntilDelivery() {
    RecordingServiceContext context = useRecordingContext();
    context.startFailure = new IllegalStateException("Binder outcome unknown");
    assertThrows(
        IllegalStateException.class,
        () ->
            ForegroundService.start(138, buildNotification(), buildNotificationBundle("pending")));
    ForegroundService.stop();
    ForegroundService service = buildService().create().get();
    service.onStartCommand(null, 0, 1);
    assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
    assertNull(org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification());
    createChannel();
    service.onStartCommand(context.starts.get(0), 0, 2);
    idleMain();
    assertEquals(138, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  @Test
  @Config(sdk = 33)
  public void onStartCommand_oldAmbiguousDelivery_preservesNewForegroundRun() throws Exception {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    context.startFailure = new IllegalStateException("old reply unknown");
    assertThrows(
        IllegalStateException.class,
        () -> ForegroundService.start(139, buildNotification(), buildNotificationBundle("old")));
    ForegroundService.stop();
    context.startFailure = null;
    Intent fresh = buildStartIntent("new", 140, "new title");
    ForegroundService.start(
        140, fresh.getParcelableExtra("notification"), fresh.getBundleExtra("notificationBundle"));
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(1), 0, 1);
      service.onStartCommand(context.starts.get(0), 0, 2);
      idleMain();
      assertCurrentState("new", 140, "new title");
      assertEquals(1, capture.events.size());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertFalse(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      capture.events.get(0).setCompletionResult();
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void onStartCommand_oldAmbiguousDeliveryAfterNewRunnerEnds_fulfillsThenStopsOnlyOldWork() {
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    context.startFailure = new IllegalStateException("old reply unknown");
    assertThrows(
        IllegalStateException.class,
        () -> ForegroundService.start(141, buildNotification(), buildNotificationBundle("old")));
    ForegroundService.stop();
    context.startFailure = null;
    ForegroundService.start(142, buildNotification(), buildNotificationBundle("new"));
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(1), 0, 1);
      capture.events.get(0).setCompletionResult();
      service.onStartCommand(context.starts.get(0), 0, 2);
      idleMain();
      assertEquals(
          141, org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
      assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertTrue(org.robolectric.Shadows.shadowOf(service).isForegroundStopped());
      assertNull(ForegroundService.mCurrentNotificationId);
      assertEquals(1, capture.events.size());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void start_lateDispatchReply_doesNotCancelStopAfterRealDelivery() throws Exception {
    CountDownLatch dispatchEntered = new CountDownLatch(1);
    CountDownLatch releaseReply = new CountDownLatch(1);
    RecordingServiceContext context =
        new RecordingServiceContext(RuntimeEnvironment.getApplication()) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            ComponentName result = super.startForegroundService(intent);
            dispatchEntered.countDown();
            try {
              assertTrue(releaseReply.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return result;
          }
        };
    ContextHolder.setApplicationContext(context);
    createChannel();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    Notification notification = buildNotification();
    Bundle bundle = buildNotificationBundle("pending");
    try {
      Future<?> start = worker.submit(() -> ForegroundService.start(143, notification, bundle));
      assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS));
      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(0), 0, 1);
      ForegroundService.stop();
      releaseReply.countDown();
      start.get(5, TimeUnit.SECONDS);
      idleMain();
      assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      releaseReply.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  @Config(
      sdk = {33, 34},
      shadows = DenyOldNotificationShadowService.class)
  public void onStartCommand_supersededStartDenial_remainsARealStartFailure() throws Exception {
    if (Build.VERSION.SDK_INT >= 34) {
      declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
    RecordingServiceContext context = useRecordingContext();
    createChannel();
    Intent old = buildStartIntent("old", 144, "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    context.startFailure = new IllegalStateException("old reply unknown");
    assertThrows(
        IllegalStateException.class,
        () ->
            ForegroundService.start(
                144,
                old.getParcelableExtra("notification"),
                old.getBundleExtra("notificationBundle")));
    ForegroundService.stop();
    context.startFailure = null;
    ForegroundService.start(145, buildNotification(), buildNotificationBundle("new"));
    FgsRunnerCapture capture = new FgsRunnerCapture();
    EventBus.register(capture);
    try {
      ForegroundService service = buildService().create().get();
      service.onStartCommand(context.starts.get(1), 0, 1);
      capture.events.get(0).setCompletionResult();
      assertThrows(
          ForegroundServiceStartNotAllowedException.class,
          () -> service.onStartCommand(context.starts.get(0), 0, 2));
      idleMain();
      assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
      assertNull(ForegroundService.mCurrentNotificationId);
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Implements(Service.class)
  public static class DenyOldNotificationShadowService extends ShadowService {
    @Implementation
    protected void startForeground(int id, Notification notification) {
      if ("B".equals(notification.extras.getString(Notification.EXTRA_TITLE))) {
        throw new ForegroundServiceStartNotAllowedException("real old START denied");
      }
      super.startForeground(id, notification);
    }
  }

  @Implements(Service.class)
  public static class RefuseStopSelfShadowService extends ShadowService {
    @Implementation
    protected boolean stopSelfResult(int startId) {
      return false;
    }
  }

  @Implements(Service.class)
  public static class FailStopForegroundShadowService extends ShadowService {
    @Implementation
    protected void stopForeground(int flags) {
      throw new ForegroundServiceStartNotAllowedException("not from startForeground");
    }
  }

  private static void assertCurrentState(String id, int hash, String title) throws Exception {
    assertEquals(id, ForegroundService.mCurrentNotificationId);
    assertEquals(hash, getPrivateStatic("mCurrentHashCode"));
    assertEquals(
        title, ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    assertEquals(
        title,
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
  }

  private static void idleMain() {
    org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private RecordingServiceContext useRecordingContext() {
    RecordingServiceContext context =
        new RecordingServiceContext(RuntimeEnvironment.getApplication());
    ContextHolder.setApplicationContext(context);
    return context;
  }

  private ServiceController<ForegroundService> buildService() {
    ServiceController<ForegroundService> controller =
        Robolectric.buildService(ForegroundService.class);
    controllers.add(controller);
    return controller;
  }

  private static class RecordingServiceContext extends ContextWrapper {
    final List<Intent> starts = new ArrayList<>();
    int stops;
    RuntimeException startFailure;

    RecordingServiceContext(Context base) {
      super(base);
    }

    @Override
    public ComponentName startService(Intent intent) {
      starts.add(new Intent(intent));
      return intent.getComponent();
    }

    @Override
    public ComponentName startForegroundService(Intent intent) {
      starts.add(new Intent(intent));
      if (startFailure != null) {
        throw startFailure;
      }
      return intent.getComponent();
    }

    @Override
    public boolean stopService(Intent intent) {
      stops++;
      return true;
    }
  }

  @Implements(Service.class)
  public static class DefensiveFailureShadowService extends ShadowService {
    static RuntimeException failure;
    static int attempts;
    static int lastId;
    static int lastType;

    @Implementation
    protected void startForeground(int id, Notification notification) {
      attempts++;
      lastId = id;
      lastType = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
      throw failure;
    }

    @Implementation
    protected void startForeground(int id, Notification notification, int foregroundServiceType) {
      attempts++;
      lastId = id;
      lastType = foregroundServiceType;
      throw failure;
    }
  }

  @Implements(NotificationManager.class)
  public static class SecurityDefensiveChannelShadowManager extends ShadowNotificationManager {
    @Implementation
    protected void createNotificationChannel(NotificationChannel channel) {
      if ("notifee_fg_default".equals(channel.getId())) {
        throw new SecurityException("defensive channel failure");
      }
      super.createNotificationChannel(channel);
    }
  }

  @Implements(Service.class)
  public static class SecurityStopForegroundShadowService extends ShadowService {
    @Implementation
    protected void stopForeground(int flags) {
      throw new SecurityException("defensive teardown failure");
    }
  }

  /**
   * Regression test for Bug #1: a STOP intent arriving on a fresh service instance that has never
   * called startForeground() must not crash. The defensive startForeground() path should fire,
   * satisfying Android's contract, and the service should stop cleanly.
   */
  @Test
  public void onStartCommand_stopIntentBeforeStart_doesNotCrash() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);

    // This should not throw — the defensive startForeground() path handles the case
    int result = service.onStartCommand(stopIntent, 0, 1);
    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
  }

  /**
   * Regression test: a null intent (service recreation after process kill) must not crash. Android
   * may deliver a null intent when recreating a service.
   */
  @Test
  public void onStartCommand_nullIntent_doesNotCrash() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    // Null intent simulates service recreation after process kill
    int result = service.onStartCommand(null, 0, 1);
    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
  }

  /**
   * Verifies that after the STOP path, the public static state fields are reset to their initial
   * values. Only checks the two public static fields (mCurrentNotificationId and
   * mCurrentForegroundServiceType) — the three private static fields (mCurrentNotificationBundle,
   * mCurrentNotification, mCurrentHashCode) are not directly accessible from tests.
   */
  @Test
  public void onStartCommand_stopIntent_resetsPublicStaticState() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    // Set some stale state to simulate a prior invocation
    ForegroundService.mCurrentNotificationId = "test-id";
    ForegroundService.mCurrentForegroundServiceType = 42;

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);

    service.onStartCommand(stopIntent, 0, 1);

    // Public static state should be reset
    assertEquals(null, ForegroundService.mCurrentNotificationId);
    assertEquals(-1, ForegroundService.mCurrentForegroundServiceType);
  }

  /**
   * Bug A regression: on API 34+ with no foregroundServiceType declared in the manifest, the
   * defensive STOP path must throw a RuntimeException (causing a crash with an actionable message)
   * instead of silently catching and proceeding to stopSelf() — which would leave Android's
   * 5-second startForeground() contract unsatisfied and result in a cryptic ANR.
   */
  @Test(expected = RuntimeException.class)
  @Config(sdk = 34)
  public void onStartCommand_stopIntentApi34NoManifestType_throwsRuntimeException() {
    // Robolectric's default shadow PackageManager returns FOREGROUND_SERVICE_TYPE_NONE (0)
    // for services without an explicit foregroundServiceType in the test manifest.
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);
    service.onStartCommand(stopIntent, 0, 1);
  }

  /**
   * Bug A regression: the RuntimeException message must contain the documentation URL so the
   * developer debugging the crash can find the fix immediately.
   */
  @Test
  @Config(sdk = 34)
  public void onStartCommand_stopIntentApi34NoManifestType_messageContainsDocUrl() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);
    try {
      service.onStartCommand(stopIntent, 0, 1);
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertTrue(
          "Message should contain documentation URL",
          e.getMessage().contains("foreground-service-setup-android-14"));
    }
  }

  /**
   * Backward compatibility: on API levels below 34, the defensive path should run normally without
   * the proactive manifest check. No foregroundServiceType declaration is required pre-API 34.
   */
  @Test
  @Config(sdk = 33)
  public void onStartCommand_stopIntentApiBelow34_defensivePathRunsNormally() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);

    // Should not throw on API 33, regardless of manifest
    int result = service.onStartCommand(stopIntent, 0, 1);
    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
  }

  /**
   * Idempotency: when mStartForegroundCalled is already true (the service has previously called
   * startForeground() successfully), the helper must be a no-op. Verified by sending two
   * consecutive STOP intents — the second should not crash even on API 33 where the first succeeded
   * via the defensive path.
   */
  @Test
  @Config(sdk = 33)
  public void onStartCommand_stopIntentAfterSuccessfulStart_skipsDefensivePath() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    Intent stopIntent = new Intent();
    stopIntent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);

    // First STOP — triggers defensive startForeground
    service.onStartCommand(stopIntent, 0, 1);

    // Second STOP — helper should be no-op since mStartForegroundCalled is now true
    int result = service.onStartCommand(stopIntent, 0, 2);
    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
  }

  @Test
  @Config(sdk = 34)
  public void onStartCommand_stopIntentApi34MicrophoneAndDataSync_usesDataSyncDefensiveType()
      throws Exception {
    declareForegroundServiceTypes(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    int result = service.onStartCommand(buildStopIntent(), 0, 1);

    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, getLastForegroundServiceType(service));
  }

  @Test
  @Config(sdk = 34)
  public void onStartCommand_stopIntentApi34CameraAndDataSync_usesDataSyncDefensiveType()
      throws Exception {
    declareForegroundServiceTypes(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    int result = service.onStartCommand(buildStopIntent(), 0, 1);

    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, getLastForegroundServiceType(service));
  }

  @Test
  @Config(sdk = 34)
  public void
      onStartCommand_stopIntentApi34ShortServiceAndMicrophone_usesShortServiceDefensiveType()
          throws Exception {
    declareForegroundServiceTypes(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    int result = service.onStartCommand(buildStopIntent(), 0, 1);

    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE, getLastForegroundServiceType(service));
  }

  @Test
  @Config(sdk = 34)
  public void onStartCommand_stopIntentApi34MicrophoneOnly_doesNotUseUndeclaredShortService()
      throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    int result = service.onStartCommand(buildStopIntent(), 0, 1);

    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, getLastForegroundServiceType(service));
  }

  @Test
  @Config(sdk = 34, shadows = ThrowingStartForegroundShadowService.class)
  public void onStartCommand_stopIntentApi34SecurityException_doesNotCrash() throws Exception {
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    int result = service.onStartCommand(buildStopIntent(), 0, 1);

    assertEquals(Service.START_STICKY_COMPATIBILITY, result);
    assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf());
  }

  // ──────────────────────────────────────────────────────────────────────────
  // START path and onTimeout event emission (regression guards for 9.1.13)
  // ──────────────────────────────────────────────────────────────────────────

  private static final String TEST_CHANNEL_ID = "fgs-test-channel";

  /**
   * START happy path: a valid intent with a notification payload must drive the service through
   * {@code startForeground()} and leave {@code mCurrentNotificationId} populated so a subsequent
   * STOP/onTimeout can reference it. Runs on SDK 33 to avoid the API 34+ manifest-type check, which
   * would require a test-specific {@code foregroundServiceType} declaration.
   */
  @Test
  @Config(sdk = 33)
  public void onStartCommand_startIntent_setsCurrentNotificationIdAndCallsStartForeground()
      throws Exception {
    createChannel();
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    String id = "fgs-start-happy";
    int result =
        service.onStartCommand(
            buildStartIntent(id, id.hashCode()), /* flags= */ 0, /* startId= */ 1);

    assertEquals(Service.START_NOT_STICKY, result);
    assertEquals(id, ForegroundService.mCurrentNotificationId);
    // Robolectric's ShadowService records the most recent Notification passed to
    // startForeground(); a non-null result proves the 3-arg startForeground() overload on the
    // API 33 branch of onStartCommand was exercised and Android's contract was satisfied.
    Notification posted = org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification();
    assertNotNull("startForeground() must have been called during the START path", posted);
    // The private static mCurrentHashCode tracks the caller-supplied hash; verifying it via
    // reflection proves the START branch fully ran (not just the early-return path).
    Field hashField = ForegroundService.class.getDeclaredField("mCurrentHashCode");
    hashField.setAccessible(true);
    assertEquals(id.hashCode(), hashField.getInt(null));
  }

  @Test
  @Config(sdk = 28)
  public void onStartCommand_sameIdUpdate_refreshesRegisteredNotificationAndCachedState()
      throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    FgsRunnerCapture runnerCapture = new FgsRunnerCapture();
    EventBus.register(runnerCapture);
    try {
      service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);
      service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 2);

      Notification registered =
          org.robolectric.Shadows.shadowOf(service).getLastForegroundNotification();
      NotificationManager manager =
          (NotificationManager)
              RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
      Notification visible =
          org.robolectric.Shadows.shadowOf(manager).getNotification(id.hashCode());
      assertEquals("B", visible.extras.getString(Notification.EXTRA_TITLE));
      assertEquals(1, org.robolectric.Shadows.shadowOf(manager).size());
      assertEquals("B", registered.extras.getString(Notification.EXTRA_TITLE));
      assertTrue((registered.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
      assertEquals(
          id.hashCode(),
          org.robolectric.Shadows.shadowOf(service).getLastForegroundNotificationId());
      assertEquals(
          "B",
          ((Notification) getPrivateStatic("mCurrentNotification"))
              .extras.getString(Notification.EXTRA_TITLE));
      assertEquals(
          "B", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
      assertEquals(id, ForegroundService.mCurrentNotificationId);
      assertEquals(id.hashCode(), getPrivateStatic("mCurrentHashCode"));
      assertEquals(1, runnerCapture.events.size());
    } finally {
      EventBus.unregister(runnerCapture);
    }
  }

  @Test
  @Config(sdk = 29)
  public void onStartCommand_sameIdUpdateApi29_preservesExplicitNoneTypeAndRegistration()
      throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";

    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE), 0, 1);
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE), 0, 2);

    assertEquals(
        "B",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));
    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, getLastForegroundServiceType(service));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, ForegroundService.mCurrentForegroundServiceType);
    assertEquals("B", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
  }

  @Test
  @Config(sdk = 30)
  public void onStartCommand_sameIdUpdateApi30_keepsTypedForegroundService() throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        0,
        1);
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        0,
        2);

    assertEquals(
        "B",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, getLastForegroundServiceType(service));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        ForegroundService.mCurrentForegroundServiceType);
  }

  @Test
  @Config(sdk = 29)
  public void onStartCommand_sameIdUpdateApi29_preservesManifestFallbackType() throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 2);

    assertEquals(
        "B",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST, getLastForegroundServiceType(service));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
        ForegroundService.mCurrentForegroundServiceType);
  }

  @Test
  @Config(sdk = 34)
  public void onStartCommand_sameIdTypeChange_usesLatestTypedNotificationAndCache()
      throws Exception {
    createChannel();
    declareForegroundServiceTypes(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";

    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        0,
        1);
    service.onStartCommand(
        buildStartIntent(
            id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK),
        0,
        2);

    assertEquals(
        "B",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, getLastForegroundServiceType(service));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        ForegroundService.mCurrentForegroundServiceType);
    assertEquals(
        "B",
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
    assertEquals("B", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
  }

  @Test
  @Config(sdk = 33)
  public void receiverDeleteAfterSameIdUpdate_repostsLatestNotificationWithoutDismissEvent()
      throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 2);

    NotificationManager manager =
        (NotificationManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
    manager.cancel(id.hashCode());
    ReceiverService receiver = Robolectric.buildService(ReceiverService.class).create().get();
    Intent delete = new Intent();
    delete.setAction(ReceiverService.DELETE_INTENT);
    delete.putExtra("notification", buildNotificationBundle(id));
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      receiver.onStartCommand(delete, 0, 3);
      Notification reposted =
          org.robolectric.Shadows.shadowOf(manager).getNotification(id.hashCode());
      assertNotNull(reposted);
      assertEquals("B", reposted.extras.getString(Notification.EXTRA_TITLE));
      assertEquals(1, org.robolectric.Shadows.shadowOf(manager).size());
      assertEquals(0, capture.events.size());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 34)
  public void onTimeout_afterShortServiceSameIdUpdate_emitsLatestBundleWithoutRestart()
      throws Exception {
    createChannel();
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        1);
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        2);

    NotificationManager manager =
        (NotificationManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
    assertEquals(
        "B",
        org.robolectric.Shadows.shadowOf(manager)
            .getNotification(id.hashCode())
            .extras
            .getString(Notification.EXTRA_TITLE));
    // Robolectric does not model Android 12+ notify-to-FGS synchronization. Its service shadow
    // remains at A here, which also guards against resetting shortService's timer via a repeat
    // startForeground call.
    assertEquals(
        "A",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      service.onTimeout(42);
      assertEquals(1, capture.events.size());
      assertEquals(NotificationEvent.TYPE_FG_TIMEOUT, capture.events.get(0).getType());
      assertEquals("B", capture.events.get(0).getNotification().getTitle());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 33)
  public void onStartCommand_differentId_emitsAlreadyExistsAndKeepsActiveState() throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      service.onStartCommand(buildStartIntent("other", "other".hashCode(), "B"), 0, 2);
      assertEquals(1, capture.events.size());
      assertEquals(NotificationEvent.TYPE_FG_ALREADY_EXIST, capture.events.get(0).getType());
      assertEquals(id, ForegroundService.mCurrentNotificationId);
      assertEquals(
          "A", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
      NotificationManager manager =
          (NotificationManager)
              RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
      assertEquals(1, org.robolectric.Shadows.shadowOf(manager).size());
    } finally {
      EventBus.unregister(capture);
    }
  }

  @Test
  @Config(sdk = 28, shadows = RejectBStartForegroundShadowService.class)
  public void onStartCommand_sameTypeStartForegroundFailure_keepsPreviousCache() throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);

    SecurityException failure =
        assertThrows(
            SecurityException.class,
            () -> service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 2));
    assertEquals("posting B rejected", failure.getMessage());

    assertEquals(id, ForegroundService.mCurrentNotificationId);
    assertEquals(id.hashCode(), getPrivateStatic("mCurrentHashCode"));
    assertEquals("A", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    assertEquals(
        "A",
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
  }

  @Test
  @Config(sdk = 28, shadows = RejectBStartForegroundShadowService.class)
  public void onStartCommand_initialStartForegroundFailure_doesNotClaimActiveService()
      throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    FgsRunnerCapture runnerCapture = new FgsRunnerCapture();
    EventBus.register(runnerCapture);
    try {
      SecurityException failure =
          assertThrows(
              SecurityException.class,
              () -> service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 1));
      assertEquals("posting B rejected", failure.getMessage());
      assertNull(ForegroundService.mCurrentNotificationId);
      assertNull(getPrivateStatic("mCurrentNotification"));
      assertNull(getPrivateStatic("mCurrentNotificationBundle"));
      assertEquals(0, getPrivateStatic("mCurrentHashCode"));
      assertEquals(0, runnerCapture.events.size());

      service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 2);
      assertEquals(1, runnerCapture.events.size());
      assertEquals(
          "A", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    } finally {
      EventBus.unregister(runnerCapture);
    }
  }

  @Test
  @Config(sdk = 34, shadows = RejectBStartForegroundShadowService.class)
  public void onStartCommand_typeChangeStartForegroundFailure_keepsPreviousTypeAndCache()
      throws Exception {
    createChannel();
    declareForegroundServiceTypes(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        0,
        1);

    SecurityException failure =
        assertThrows(
            SecurityException.class,
            () ->
                service.onStartCommand(
                    buildStartIntent(
                        id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK),
                    0,
                    2));
    assertEquals("posting B rejected", failure.getMessage());

    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        ForegroundService.mCurrentForegroundServiceType);
    assertEquals("A", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    assertEquals(
        "A",
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
  }

  @Test
  @Config(sdk = 33, shadows = RejectBNotificationShadowManager.class)
  public void onStartCommand_notifyFailure_keepsPreviousCache() throws Exception {
    createChannel();
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(buildStartIntent(id, id.hashCode(), "A"), 0, 1);

    SecurityException failure =
        assertThrows(
            SecurityException.class,
            () -> service.onStartCommand(buildStartIntent(id, id.hashCode(), "B"), 0, 2));
    assertEquals("posting B rejected", failure.getMessage());

    assertEquals(id, ForegroundService.mCurrentNotificationId);
    assertEquals(id.hashCode(), getPrivateStatic("mCurrentHashCode"));
    assertEquals("A", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    assertEquals(
        "A",
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
  }

  @Test
  @Config(sdk = 34, shadows = SilentlyRejectMissingChannelShadowManager.class)
  public void onStartCommand_notifyReturnsNormallyWithoutPosting_keepsLatestSubmittedState()
      throws Exception {
    createChannel();
    declareForegroundServiceTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    ForegroundService service = Robolectric.buildService(ForegroundService.class).create().get();
    String id = "live";
    service.onStartCommand(
        buildStartIntent(id, id.hashCode(), "A", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE),
        0,
        1);

    Intent update =
        buildStartIntent(id, id.hashCode(), "B", ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    String missingChannelId = "missing-channel";
    Notification rejected =
        new NotificationCompat.Builder(RuntimeEnvironment.getApplication(), missingChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("B")
            .setOnlyAlertOnce(true)
            .build();
    update.putExtra("notification", rejected);
    update
        .getBundleExtra("notificationBundle")
        .getBundle("android")
        .putString("channelId", missingChannelId);

    NotificationManager manager =
        (NotificationManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE);
    assertNull(manager.getNotificationChannel(missingChannelId));
    SilentlyRejectMissingChannelShadowManager.rejectedPosts = 0;
    SilentlyRejectMissingChannelShadowManager.lastRejectedNotification = null;
    service.onStartCommand(update, 0, 2);

    // This shadow models the platform's normal-return, no-post outcome. Robolectric does not
    // reproduce the separate Android foreground-service record update in this sequence.
    assertEquals(1, SilentlyRejectMissingChannelShadowManager.rejectedPosts);
    assertEquals(
        "B",
        SilentlyRejectMissingChannelShadowManager.lastRejectedNotification.extras.getString(
            Notification.EXTRA_TITLE));
    assertEquals(
        "A",
        org.robolectric.Shadows.shadowOf(manager)
            .getNotification(id.hashCode())
            .extras
            .getString(Notification.EXTRA_TITLE));
    // The simulated visible card remains A while NotifyKit's latest submitted state is B.
    assertEquals("B", ((Bundle) getPrivateStatic("mCurrentNotificationBundle")).getString("title"));
    assertEquals(
        "B",
        ((Notification) getPrivateStatic("mCurrentNotification"))
            .extras.getString(Notification.EXTRA_TITLE));
    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        ForegroundService.mCurrentForegroundServiceType);
    assertEquals(id, ForegroundService.mCurrentNotificationId);
    assertEquals(id.hashCode(), getPrivateStatic("mCurrentHashCode"));
    // The service shadow remains at A because the unchanged-type path did not call
    // startForeground() again, which could extend a shortService timeout on Android.
    assertEquals(
        "A",
        org.robolectric.Shadows.shadowOf(service)
            .getLastForegroundNotification()
            .extras
            .getString(Notification.EXTRA_TITLE));

    manager.cancel(id.hashCode());
    ReceiverService receiver = Robolectric.buildService(ReceiverService.class).create().get();
    Intent delete = new Intent();
    delete.setAction(ReceiverService.DELETE_INTENT);
    delete.putExtra("notification", buildNotificationBundle(id));
    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      receiver.onStartCommand(delete, 0, 3);
      assertEquals(2, SilentlyRejectMissingChannelShadowManager.rejectedPosts);
      assertEquals(
          "B",
          SilentlyRejectMissingChannelShadowManager.lastRejectedNotification.extras.getString(
              Notification.EXTRA_TITLE));
      assertNull(org.robolectric.Shadows.shadowOf(manager).getNotification(id.hashCode()));
      assertEquals(0, capture.events.size());

      service.onTimeout(42);
      assertEquals(1, capture.events.size());
      assertEquals(NotificationEvent.TYPE_FG_TIMEOUT, capture.events.get(0).getType());
      assertEquals("B", capture.events.get(0).getNotification().getTitle());
    } finally {
      EventBus.unregister(capture);
    }
  }

  /**
   * Regression guard for the 9.1.13 {@code onTimeout(int)} fix (upstream invertase/notifee#703). On
   * API 34, Android's single-argument {@code onTimeout} fires when a {@code shortService} FGS
   * exceeds its 3-minute budget. The handler must:
   *
   * <ol>
   *   <li>emit a {@link NotificationEvent} with type {@link NotificationEvent#TYPE_FG_TIMEOUT},
   *   <li>carry the originating notification model so JS can correlate the event,
   *   <li>populate {@code startId} and {@code fgsType} extras (the latter is {@code -1} as a
   *       sentinel on the single-argument variant), and
   *   <li>reset the service's static tracking state.
   * </ol>
   */
  @Test
  @Config(sdk = 34)
  public void onTimeout_api34_emitsFgTimeoutEventWithStartIdAndSentinelFgsType() throws Exception {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    String id = "fgs-timeout-api34";
    seedActiveForegroundServiceState(id);

    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      int startId = 42;
      service.onTimeout(startId);

      assertEquals(
          "exactly one NotificationEvent should be emitted on timeout", 1, capture.events.size());
      NotificationEvent event = capture.events.get(0);
      assertEquals(NotificationEvent.TYPE_FG_TIMEOUT, event.getType());
      assertNotNull(
          "timeout event must carry the originating notification", event.getNotification());
      assertEquals(id, event.getNotification().getId());
      assertNotNull("timeout event must carry startId/fgsType extras", event.getExtras());
      assertEquals(startId, event.getExtras().getInt("startId"));
      // handleTimeout is called with -1 as the fgsType sentinel from the single-argument overload.
      assertEquals(-1, event.getExtras().getInt("fgsType"));
    } finally {
      EventBus.unregister(capture);
    }

    assertNull(
        "mCurrentNotificationId must be cleared after onTimeout",
        ForegroundService.mCurrentNotificationId);
    assertEquals(-1, ForegroundService.mCurrentForegroundServiceType);
  }

  /**
   * Regression guard for the API 35+ {@code onTimeout(int, int)} overload, which supersedes the
   * single-argument variant and surfaces the type-specific timeout cause (e.g. {@code
   * FOREGROUND_SERVICE_TYPE_DATA_SYNC}'s new Android 15 cumulative cap). The emitted event must
   * carry the explicit {@code fgsType} value, not the sentinel.
   */
  @Test
  @Config(sdk = 35)
  public void onTimeout_api35_emitsFgTimeoutEventWithExplicitFgsType() throws Exception {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    String id = "fgs-timeout-api35";
    seedActiveForegroundServiceState(id);

    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      int startId = 7;
      int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
      service.onTimeout(startId, fgsType);

      assertEquals(1, capture.events.size());
      NotificationEvent event = capture.events.get(0);
      assertEquals(NotificationEvent.TYPE_FG_TIMEOUT, event.getType());
      assertEquals(id, event.getNotification().getId());
      assertNotNull(event.getExtras());
      assertEquals(startId, event.getExtras().getInt("startId"));
      assertEquals(fgsType, event.getExtras().getInt("fgsType"));
    } finally {
      EventBus.unregister(capture);
    }

    assertNull(ForegroundService.mCurrentNotificationId);
    assertEquals(-1, ForegroundService.mCurrentForegroundServiceType);
  }

  /**
   * Defensive behaviour: if onTimeout fires on a service instance whose static state has already
   * been cleared (a race between STOP and the Android system delivering a delayed timeout), the
   * handler must not crash and must not post a stray event.
   */
  @Test
  @Config(sdk = 34)
  public void onTimeout_withNoActiveState_doesNotCrashOrEmitEvent() {
    ServiceController<ForegroundService> controller = buildService();
    ForegroundService service = controller.create().get();

    FgsEventCapture capture = new FgsEventCapture();
    EventBus.register(capture);
    try {
      service.onTimeout(/* startId= */ 99);
      assertEquals(0, capture.events.size());
    } finally {
      EventBus.unregister(capture);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  private static void createChannel() {
    Context context = RuntimeEnvironment.getApplication();
    NotificationManager nm =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm != null && nm.getNotificationChannel(TEST_CHANNEL_ID) == null) {
      NotificationChannel channel =
          new NotificationChannel(
              TEST_CHANNEL_ID, "FGS test channel", NotificationManager.IMPORTANCE_LOW);
      nm.createNotificationChannel(channel);
    }
  }

  private static Bundle buildNotificationBundle(String id) {
    Bundle bundle = new Bundle();
    bundle.putString("id", id);
    bundle.putString("title", "FGS test " + id);
    Bundle androidBundle = new Bundle();
    androidBundle.putString("channelId", TEST_CHANNEL_ID);
    bundle.putBundle("android", androidBundle);
    return bundle;
  }

  private static Notification buildNotification() {
    Context context = RuntimeEnvironment.getApplication();
    return new NotificationCompat.Builder(context, TEST_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("FGS test")
        .build();
  }

  private static Intent buildStartIntent(String id, int hashCode) {
    return buildStartIntent(id, hashCode, "FGS test");
  }

  private static Intent buildStartIntent(String id, int hashCode, String title) {
    Intent intent = new Intent();
    intent.setAction(ForegroundService.START_FOREGROUND_SERVICE_ACTION);
    intent.putExtra("hashCode", hashCode);
    Notification notification =
        new NotificationCompat.Builder(RuntimeEnvironment.getApplication(), TEST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .build();
    intent.putExtra("notification", notification);
    Bundle bundle = buildNotificationBundle(id);
    bundle.putString("title", title);
    intent.putExtra("notificationBundle", bundle);
    return intent;
  }

  private static Intent buildStartIntent(
      String id, int hashCode, String title, int foregroundServiceType) {
    Intent intent = buildStartIntent(id, hashCode, title);
    Bundle bundle = intent.getBundleExtra("notificationBundle");
    Bundle android = bundle.getBundle("android");
    ArrayList<Integer> types = new ArrayList<>();
    types.add(foregroundServiceType);
    android.putIntegerArrayList("foregroundServiceTypes", types);
    intent.putExtra("notificationBundle", bundle);
    return intent;
  }

  private static Intent buildStopIntent() {
    Intent intent = new Intent();
    intent.setAction(ForegroundService.STOP_FOREGROUND_SERVICE_ACTION);
    return intent;
  }

  private static void declareForegroundServiceTypes(int foregroundServiceTypes) throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ComponentName component = new ComponentName(context, ForegroundService.class);
    ServiceInfo serviceInfo;
    try {
      serviceInfo =
          context.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
      serviceInfo = new ServiceInfo();
    }
    serviceInfo.packageName = component.getPackageName();
    serviceInfo.name = component.getClassName();
    setForegroundServiceType(serviceInfo, foregroundServiceTypes);
    org.robolectric.Shadows.shadowOf(context.getPackageManager()).addOrUpdateService(serviceInfo);
  }

  private static void setForegroundServiceType(ServiceInfo serviceInfo, int foregroundServiceTypes)
      throws Exception {
    Field field = ServiceInfo.class.getDeclaredField("mForegroundServiceType");
    field.setAccessible(true);
    field.setInt(serviceInfo, foregroundServiceTypes);
  }

  private static int getLastForegroundServiceType(Service service) throws Exception {
    Object shadowService = org.robolectric.Shadows.shadowOf(service);
    Method method = ShadowService.class.getDeclaredMethod("getForegroundServiceType");
    method.setAccessible(true);
    return (int) method.invoke(shadowService);
  }

  /**
   * Populates the service's private static tracking fields directly, simulating the post-START
   * state that onTimeout expects to observe. Using reflection here (rather than running a full
   * START first) keeps the onTimeout tests SDK-independent — the START path would trip the API 34+
   * manifest-type check, but onTimeout itself is sdk-agnostic because its body only uses pre-API-34
   * primitives.
   */
  private static void seedActiveForegroundServiceState(String id) throws Exception {
    ForegroundService.mCurrentNotificationId = id;
    ForegroundService.mCurrentForegroundServiceType = 1;
    setPrivateStatic("mCurrentNotificationBundle", buildNotificationBundle(id));
    setPrivateStatic("mCurrentNotification", buildNotification());
    setPrivateStatic("mCurrentHashCode", id.hashCode());
  }

  private static void setPrivateStatic(String name, Object value) throws Exception {
    Field field = ForegroundService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object getPrivateStatic(String name) throws Exception {
    Field field = ForegroundService.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  public static class FgsRunnerCapture {
    final List<ForegroundServiceEvent> events = new ArrayList<>();

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onForegroundServiceEvent(ForegroundServiceEvent event) {
      events.add(event);
    }
  }

  @Implements(Service.class)
  public static class RejectBStartForegroundShadowService extends ShadowService {
    @Implementation
    protected void startForeground(int id, Notification notification) {
      if ("B".equals(notification.extras.getString(Notification.EXTRA_TITLE))) {
        throw new SecurityException("posting B rejected");
      }
      super.startForeground(id, notification);
    }

    @Implementation
    protected void startForeground(int id, Notification notification, int foregroundServiceType) {
      if ("B".equals(notification.extras.getString(Notification.EXTRA_TITLE))) {
        throw new SecurityException("posting B rejected");
      }
      super.startForeground(id, notification, foregroundServiceType);
    }
  }

  @Implements(NotificationManager.class)
  public static class RejectBNotificationShadowManager extends ShadowNotificationManager {
    @Implementation
    protected void notify(String tag, int id, Notification notification) {
      if ("B".equals(notification.extras.getString(Notification.EXTRA_TITLE))) {
        throw new SecurityException("posting B rejected");
      }
      super.notify(tag, id, notification);
    }
  }

  @Implements(NotificationManager.class)
  public static class SilentlyRejectMissingChannelShadowManager extends ShadowNotificationManager {
    static int rejectedPosts;
    static Notification lastRejectedNotification;

    @Implementation
    protected void notify(String tag, int id, Notification notification) {
      if ("missing-channel".equals(notification.getChannelId())) {
        rejectedPosts++;
        lastRejectedNotification = notification;
        return;
      }
      super.notify(tag, id, notification);
    }
  }

  /**
   * greenrobot EventBus subscriber that records every {@link NotificationEvent} posted during a
   * test. {@link ThreadMode#POSTING} fires the subscriber synchronously on the caller thread, so
   * the test can inspect {@code events} immediately after {@code post()} returns without any looper
   * advancement.
   */
  public static class FgsEventCapture {
    final List<NotificationEvent> events = new ArrayList<>();

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onNotificationEvent(NotificationEvent event) {
      events.add(event);
    }
  }

  @Implements(Service.class)
  public static class ThrowingStartForegroundShadowService extends ShadowService {
    @Implementation
    protected void startForeground(int id, Notification notification, int foregroundServiceType) {
      throw new SecurityException("while-in-use foreground service type denied");
    }
  }
}
