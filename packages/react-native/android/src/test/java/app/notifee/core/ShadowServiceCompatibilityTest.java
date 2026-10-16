package app.notifee.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowService;

/** Exercises the real Robolectric superclass calls, including its checked exception contract. */
@RunWith(RobolectricTestRunner.class)
public class ShadowServiceCompatibilityTest {
  public static class TestService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }
  }

  @Test
  @Config(
      sdk = {29, 35, 36, 37},
      shadows = ForegroundServiceTest.SliceAShadowService.class)
  public void sliceA_typedForwardingPreservesFailures() {
    ForegroundServiceTest.SliceAShadowService.reset();
    assertForwarding(ForegroundServiceTest.SliceAShadowService.class, true);
  }

  @Test
  @Config(
      sdk = {24, 35, 36, 37},
      shadows = ForegroundServiceTest.Issue81ShadowService.class)
  public void issue81_untypedForwardingPreservesFailures() {
    resetIssue81();
    assertForwarding(ForegroundServiceTest.Issue81ShadowService.class, false);
  }

  @Test
  @Config(
      sdk = {29, 35, 36, 37},
      shadows = ForegroundServiceTest.Issue81ShadowService.class)
  public void issue81_typedForwardingResetsDelegationAfterFailure() {
    resetIssue81();
    TestService service = assertForwarding(ForegroundServiceTest.Issue81ShadowService.class, true);
    // One record for each outer typed call, including failed attempts, without double counting.
    assertEquals(4, ForegroundServiceTest.Issue81ShadowService.promotions.size());
    service.startForeground(101, notification());
    assertEquals(5, ForegroundServiceTest.Issue81ShadowService.promotions.size());
    assertEquals(
        Integer.valueOf(101), ForegroundServiceTest.Issue81ShadowService.promotions.get(4));
  }

  @Test
  @Config(
      sdk = {24, 35, 36, 37},
      shadows = ForegroundServiceTest.DenyOldNotificationShadowService.class)
  public void denyOld_untypedForwardingPreservesFailures() {
    assertForwarding(ForegroundServiceTest.DenyOldNotificationShadowService.class, false);
  }

  @Test
  @Config(
      sdk = {24, 35, 36, 37},
      shadows = ForegroundServiceTest.RejectBStartForegroundShadowService.class)
  public void rejectB_untypedForwardingPreservesFailures() {
    assertForwarding(ForegroundServiceTest.RejectBStartForegroundShadowService.class, false);
  }

  @Test
  @Config(
      sdk = {29, 35, 36, 37},
      shadows = ForegroundServiceTest.RejectBStartForegroundShadowService.class)
  public void rejectB_typedForwardingPreservesFailures() {
    assertForwarding(ForegroundServiceTest.RejectBStartForegroundShadowService.class, true);
  }

  private static void resetIssue81() {
    ForegroundServiceTest.Issue81ShadowService.failure = null;
    ForegroundServiceTest.Issue81ShadowService.beforePromotion = null;
    ForegroundServiceTest.Issue81ShadowService.rejectTitle = null;
    ForegroundServiceTest.Issue81ShadowService.promotions.clear();
    ForegroundServiceTest.Issue81ShadowService.types.clear();
    ForegroundServiceTest.Issue81ShadowService.order.clear();
  }

  private static Notification notification() {
    Context context = RuntimeEnvironment.getApplication();
    if (Build.VERSION.SDK_INT >= 26) {
      context
          .getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel("compat", "compat", NotificationManager.IMPORTANCE_LOW));
      return new Notification.Builder(context, "compat").setContentTitle("A").build();
    }
    return new Notification.Builder(context).setContentTitle("A").build();
  }

  private static TestService assertForwarding(
      Class<? extends ShadowService> expected, boolean typed) {
    assertEquals(RuntimeEnvironment.getApiLevel(), Build.VERSION.SDK_INT);
    System.out.println(
        "Shadow compatibility: sdk="
            + RuntimeEnvironment.getApiLevel()
            + " java="
            + System.getProperty("java.specification.version"));
    TestService service = Robolectric.buildService(TestService.class).create().get();
    ShadowService shadow = org.robolectric.Shadows.shadowOf(service);
    assertSame(expected, shadow.getClass());
    Notification notification = notification();
    Runnable promote =
        () -> {
          if (typed) {
            service.startForeground(
                100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
          } else {
            service.startForeground(100, notification);
          }
        };
    promote.run();
    assertSame(notification, shadow.getLastForegroundNotification());
    assertEquals(100, shadow.getLastForegroundNotificationId());
    assertTrue((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
    if (typed)
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.getForegroundServiceType());
    // Both a platform-style runtime failure and the newly declared checked failure must escape.
    for (Exception failure :
        new Exception[] {new SecurityException("denied"), new Exception("checked")}) {
      Notification rejected = notification();
      shadow.setThrowInStartForeground(failure);
      assertSame(
          failure,
          assertThrows(
              Exception.class,
              () -> {
                if (typed) {
                  service.startForeground(
                      200, rejected, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                  service.startForeground(200, rejected);
                }
              }));
      assertSame(notification, shadow.getLastForegroundNotification());
      assertEquals(100, shadow.getLastForegroundNotificationId());
      assertEquals(0, rejected.flags & Notification.FLAG_FOREGROUND_SERVICE);
    }
    shadow.setThrowInStartForeground(null);
    promote.run();
    assertSame(notification, shadow.getLastForegroundNotification());
    if (typed)
      assertEquals(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.getForegroundServiceType());
    return service;
  }
}
