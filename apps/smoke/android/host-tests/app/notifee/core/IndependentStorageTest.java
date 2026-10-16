package app.notifee.core;

// Historical independent PoC witnesses retained as regressions; not a current review verdict.

import static org.junit.Assert.*;

import android.content.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 35,
    shadows = {
      ForegroundServiceRunLimitTest.Platform.class,
      ForegroundServiceRunLimitTest.Alarms.class
    })
public class IndependentStorageTest extends ForegroundServiceRunLimitTest {
  static int indexReads;
  static boolean uncertainCommit;
  static Runnable afterApplied;

  static SharedPreferences observed(SharedPreferences real, String name) {
    if (!ForegroundServiceRunLimitScheduler.PREFS.equals(name)) return real;
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getString") && ((String) args[0]).startsWith("gc/slot/"))
                indexReads++;
              if (method.getName().equals("edit")) {
                SharedPreferences.Editor editor = real.edit();
                boolean[] removes = {false};
                return Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class},
                    (editorProxy, operation, values) -> {
                      if (operation.getName().equals("remove")) removes[0] = true;
                      Object result = operation.invoke(editor, values);
                      if (operation.getName().equals("commit") && removes[0] && uncertainCommit) {
                        uncertainCommit = false;
                        Runnable callback = afterApplied;
                        afterApplied = null;
                        if (callback != null) callback.run();
                        return false; // Actual memory merge already occurred; disk result is
                        // uncertain.
                      }
                      return result instanceof SharedPreferences.Editor ? editorProxy : result;
                    });
              }
              return method.invoke(real, args);
            });
  }

  public static class CountingService extends ForegroundService {
    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
      return observed(super.getSharedPreferences(name, mode), name);
    }
  }

  @Before
  public void resetObservation() {
    indexReads = 0;
    uncertainCommit = false;
    afterApplied = null;
  }

  @Test
  public void RV11_totalIndexReadsAreEightGcPlusThirtyTwoReservation() throws Exception {
    for (int i = 0; i < 32; i++) gcSeed(i, UUID.randomUUID().toString(), 42, "UNKNOWN");
    Context counter =
        new ContextWrapper(app) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return observed(super.getSharedPreferences(name, mode), name);
          }
        };
    sender = new Sender(counter);
    ContextHolder.setApplicationContext(sender);
    request("a", true);
    assertEquals("GC visits 8 metadata slots before dispatch", 8, indexReads);
    org.robolectric.android.controller.ServiceController<CountingService> c =
        Robolectric.buildService(CountingService.class);
    controllers.add(c);
    ForegroundService service = c.create().get();
    deliver(service, sender.starts.get(0), 1);
    assertEquals("reservation adds 32 more slot reads", 40, indexReads);
    assertNull(ticket(service));
    noTeardown(service);
    assertTrue(platform(service).foreground);
  }

  @Test
  public void RV12_commitFalseAfterMemoryMergeAndReentrantBIsSafe() throws Exception {
    org.robolectric.android.controller.ServiceController<CountingService> c =
        Robolectric.buildService(CountingService.class);
    controllers.add(c);
    ForegroundService service = c.create().get();
    request("a", true);
    deliver(service, sender.starts.get(0), 1);
    ForegroundServiceRunLimitScheduler.Ticket a = ticket(service);
    String victim = gcOrphan(1);
    uncertainCommit = true;
    afterApplied = promoteSuccessor(service);
    collectCycle(service);
    assertFalse(uncertainCommit);
    assertNull(afterApplied);
    assertFalse("false does not imply memory rollback", store().contains(victim));
    assertTrue(marker("failures=1"));
    assertSuccessorExpires(service, a);
  }
}
