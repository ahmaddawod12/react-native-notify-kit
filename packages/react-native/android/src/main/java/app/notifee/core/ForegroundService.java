package app.notifee.core;

/*
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this library except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import android.annotation.SuppressLint;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import app.notifee.core.event.ForegroundServiceEvent;
import app.notifee.core.event.NotificationEvent;
import app.notifee.core.interfaces.MethodCallResult;
import app.notifee.core.model.NotificationModel;
import app.notifee.core.utility.ParcelableCompatReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ForegroundService extends Service {
  private static final String TAG = "ForegroundService";
  public static final String START_FOREGROUND_SERVICE_ACTION =
      "app.notifee.core.ForegroundService.START";
  public static final String STOP_FOREGROUND_SERVICE_ACTION =
      "app.notifee.core.ForegroundService.STOP";

  private static final Object sLock = new Object();
  // Serializes client START acceptance with instance-targeted STOP. Notification cache fields
  // are not lifecycle authority. Lock order is sLifecycleLock, then sLock.
  private static final Object sLifecycleLock = new Object();
  // Only START senders take this lock (the notification pipeline runs on its executor). Service
  // lifecycle callbacks must never wait for a sender's Binder reply before promoting themselves.
  private static final Object sStartDispatchLock = new Object();
  private static final Map<String, StartRequest> sPendingStarts = new HashMap<>();
  private static final String START_REQUEST_ID = "notifee.foregroundServiceStartRequestId";
  private static long sRunGeneration;
  private static long sStartAdmissionRevision;
  private static ForegroundService sInstance;
  private static Object sStopRequest;
  private static ForegroundService sStateOwner;
  private int mLastStartId;
  private boolean mStopInitiated;
  private boolean mStartCommandFailed;
  private boolean mForegroundActive;
  private boolean mStartCommandSatisfied;
  private long mRunGeneration;
  // The admission fence may advance before posting. Cached state belongs to its last successful
  // generation until the replacement platform call returns normally.
  private long mStateGeneration;
  private int mRunFirstStartId;
  private Object mRunnerToken;
  private ForegroundEpoch mForegroundEpoch;
  private ForegroundServiceRunLimitScheduler.Ticket mAlarmPoc;
  private long mAlarmPocAttemptedRun = Long.MIN_VALUE;
  private String mAlarmPocInstanceId;
  // Previously admitted metadata can collide with a successor even across run generations.
  // These IDs only disqualify stop authority; they never establish Android epoch provenance.
  private final Set<Integer> mTimeoutStartIds = new HashSet<>();
  // A reentrant platform callback may still inherit an outer lifecycle monitor. Drain these
  // publications synchronously at that call's unlocked boundary, without scheduling or waiting.
  private static final ThreadLocal<List<Runnable>> sTimeoutPublications = new ThreadLocal<>();

  private static void publishTimeoutEventsIfUnlocked() {
    if (Thread.holdsLock(sLifecycleLock) || Thread.holdsLock(sLock)) {
      return;
    }
    List<Runnable> publications = sTimeoutPublications.get();
    if (publications != null) {
      sTimeoutPublications.remove();
      for (Runnable publication : publications) {
        publication.run();
      }
    }
  }

  // Local publication identity, never provenance supplied by Android's timeout callback.
  // Guarded by sLifecycleLock then sLock. Retiring consumes timeout authority even on failure.
  private static final class ForegroundEpoch {
    final long runGeneration;
    final long promotedElapsed = SystemClock.elapsedRealtime();
    final boolean ambiguousPromotion;
    final Set<Integer> satisfiedStartIds = new HashSet<>();
    int lastSatisfiedStartId;
    boolean retiring;
    // A consumed voluntary alarm must not suppress a later genuine framework timeout
    // when both native teardown attempts failed and the FGS is still active.
    boolean voluntaryRetirement;
    boolean retired;

    ForegroundEpoch(long runGeneration, boolean ambiguousPromotion, int startId) {
      this.runGeneration = runGeneration;
      this.ambiguousPromotion = ambiguousPromotion;
      satisfiedStartIds.add(startId);
      lastSatisfiedStartId = startId;
    }
  }

  // Caller holds both locks, after the actual promotion returned normally.
  private boolean commitForegroundEpoch() {
    ForegroundEpoch previous = mForegroundEpoch;
    long generation = sRunGeneration;
    long runGeneration = mRunGeneration;
    int startId = mLastStartId;
    if (mAlarmPoc != null) {
      ForegroundServiceRunLimitScheduler.marker(
          "ARM_RETIRED", mAlarmPoc, "promotion epoch replaced; no automatic rearm");
    }
    invalidateAlarmPoc();
    // Cancellation crosses IPC/storage. Neither this commit nor its caller may overwrite
    // a successor admitted while the old ticket was being cancelled.
    if (sInstance != this
        || sStateOwner != this
        || mForegroundEpoch != previous
        || sRunGeneration != generation
        || mRunGeneration != runGeneration
        || mLastStartId != startId) return false;
    mForegroundEpoch =
        new ForegroundEpoch(
            mRunGeneration,
            previous != null && previous.runGeneration == mRunGeneration,
            mLastStartId);
    return true;
  }

  // Snapshot before platform IPC; a reply cannot acquire authority over a reentrant successor.
  private static final class StopAuthority {
    final ForegroundService owner;
    final ForegroundEpoch epoch;
    final long generation;
    final long runGeneration;
    final int startId;
    final Object request;

    StopAuthority(ForegroundService service) {
      owner = sStateOwner;
      epoch = service.mForegroundEpoch;
      generation = sRunGeneration;
      runGeneration = service.mRunGeneration;
      startId = service.mLastStartId;
      request = sStopRequest;
    }
  }

  // Caller holds sLifecycleLock. Follow the lifecycle -> state lock order for both snapshots.
  private StopAuthority captureStopAuthority() {
    synchronized (sLock) {
      return new StopAuthority(this);
    }
  }

  private boolean ownsStopAuthority(StopAuthority authority) {
    synchronized (sLock) {
      return sInstance == this
          && sStateOwner == authority.owner
          && mForegroundEpoch == authority.epoch
          && sRunGeneration == authority.generation
          && mRunGeneration == authority.runGeneration
          && mLastStartId == authority.startId
          && sStopRequest == authority.request
          && sPendingStarts.isEmpty();
    }
  }

  // A delivered command can be physically satisfied by the existing foreground publication,
  // even if its notification update throws. A future/unfulfilled delivery gains no authority.
  private void recordSatisfiedStart(int startId) {
    synchronized (sLock) {
      ForegroundEpoch epoch = mForegroundEpoch;
      if (sInstance == this
          && sStateOwner == this
          && epoch != null
          && !epoch.retiring
          && !epoch.retired
          && mForegroundActive
          && mStartCommandSatisfied
          && mLastStartId == startId
          && epoch.runGeneration == mRunGeneration) {
        epoch.satisfiedStartIds.add(startId);
        epoch.lastSatisfiedStartId = startId;
      }
    }
  }

  // A denied initial promotion is not a client STOP. Its recovery cannot acquire authority
  // over a later accepted/promoted run, and is consumed by one eligible stop attempt.
  private DeniedStartRecovery mDeniedStartRecovery;
  private static final String DEFENSIVE_CHANNEL_ID = "notifee_fg_default";
  private static final int DEFENSIVE_NOTIFICATION_ID = Integer.MAX_VALUE - 1;

  private static final class StartRequest {
    final long generation;
    final Object precedingStop;
    boolean accepted;
    // Dispatch acceptance is distinct from the promotion outcome and the physical instance's
    // obligation. A denied delivered request is terminal even while its sender's reply is pending.
    boolean promotionDenied;
    ForegroundServiceRunLimitScheduler.Request alarmPoc;
    ForegroundEpoch alarmPocEpoch;
    // Null until delivery. An unfulfilled delivered request belongs to that physical instance.
    ForegroundService instance;

    StartRequest(long generation, Object precedingStop) {
      this.generation = generation;
      this.precedingStop = precedingStop;
    }
  }

  private static final class DeniedStartRecovery {
    final long generation;
    final long runGeneration;
    final int startId;
    final int flags;
    final RuntimeException denial;
    final String requestId;
    final String notificationId;
    final int foregroundServiceType;

    DeniedStartRecovery(
        long generation,
        long runGeneration,
        int startId,
        int flags,
        RuntimeException denial,
        String requestId,
        String notificationId,
        int foregroundServiceType) {
      this.generation = generation;
      this.runGeneration = runGeneration;
      this.startId = startId;
      this.flags = flags;
      this.denial = denial;
      this.requestId = requestId;
      this.notificationId = notificationId;
      this.foregroundServiceType = foregroundServiceType;
    }
  }

  /**
   * Tracks whether startForeground() has been called on THIS service instance. This is an instance
   * field (not static) because the static fields below track cross-invocation notification state
   * shared across the process lifetime, but mStartForegroundCalled must track whether this specific
   * service instance — which may be a fresh recreation after process death — has fulfilled
   * Android's startForeground() contract. If the process is killed and Android recreates the
   * service, all statics reset AND a new instance is created; we need per-instance tracking to know
   * whether the recreated instance has called startForeground() before attempting stopSelf().
   */
  private volatile boolean mStartForegroundCalled = false;

  // While active, mCurrent* tracks the latest state submitted by a platform call that returned
  // normally. Android does not acknowledge that the notification was displayed.
  public static String mCurrentNotificationId = null;

  public static int mCurrentForegroundServiceType = -1;

  private static Bundle mCurrentNotificationBundle = null;
  private static Notification mCurrentNotification = null;
  private static int mCurrentHashCode = 0;

  @Override
  public void onCreate() {
    super.onCreate();
    synchronized (sLifecycleLock) {
      if (sInstance != null) {
        sInstance.mDeniedStartRecovery = null;
        sInstance.invalidateAlarmPoc();
      }
      sInstance = this;
      mRunGeneration = sRunGeneration;
      synchronized (sLock) {
        sStateOwner = this;
        clearCurrentState();
      }
    }
  }

  @Override
  public void onDestroy() {
    synchronized (sLifecycleLock) {
      invalidateAlarmPoc();
      mDeniedStartRecovery = null;
      sPendingStarts.values().removeIf(request -> request.instance == this);
      if (sInstance == this) {
        sInstance = null;
        if (sPendingStarts.isEmpty()) {
          sStopRequest = null;
        }
      }
      clearOwnedState();
      mForegroundActive = false;
      synchronized (sLock) {
        if (sStateOwner == this) {
          sStateOwner = null;
        }
      }
    }
    super.onDestroy();
  }

  // Caller holds sLock. Ownership is checked by clearOwnedState before any terminal cleanup.
  private static void clearCurrentState() {
    mCurrentNotificationId = null;
    mCurrentForegroundServiceType = -1;
    mCurrentNotificationBundle = null;
    mCurrentNotification = null;
    mCurrentHashCode = 0;
  }

  private void clearOwnedState() {
    ForegroundServiceRunLimitScheduler.Ticket ticket = null;
    synchronized (sLock) {
      if (sStateOwner == this) {
        ticket = revokeAlarmPoc();
        mDeniedStartRecovery = null;
        clearCurrentState();
        mRunnerToken = null;
        if (mForegroundEpoch != null) {
          mForegroundEpoch.retired = true;
        }
      }
    }
    // Finish A's local retirement before cancellation can synchronously admit a successor.
    if (ticket != null) ForegroundServiceRunLimitScheduler.cancel(ticket);
  }

  /**
   * Submits the latest foreground service notification again if the given notification ID matches
   * the active foreground service. On Android 14+, users can dismiss ongoing foreground service
   * notifications for most service types.
   *
   * @return true if a re-post was submitted without a synchronous exception, false otherwise
   */
  @SuppressLint("MissingPermission")
  static boolean repostIfActive(String notificationId) {
    try {
      synchronized (sLifecycleLock) {
        synchronized (sLock) {
          if (mCurrentNotificationId == null
              || (sStateOwner != null
                  && sStateOwner.mForegroundEpoch != null
                  && sStateOwner.mForegroundEpoch.retiring)
              || !mCurrentNotificationId.equals(notificationId)
              || mCurrentNotification == null) {
            return false;
          }
          Logger.w(TAG, "Submitting dismissed foreground service notification for re-post");
          NotificationManagerCompat.from(ContextHolder.getApplicationContext())
              .notify(mCurrentHashCode, mCurrentNotification);
          return true;
        }
      }
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  // Internal smoke entrypoint, deliberately absent from Notifee/React Native public APIs.
  static void startAlarmPoc(
      int hashCode,
      Notification notification,
      Bundle notificationBundle,
      String run,
      long requestedDurationMs) {
    start(
        hashCode,
        notification,
        notificationBundle,
        new ForegroundServiceRunLimitScheduler.Request(
            ContextHolder.getApplicationContext(), run, requestedDurationMs));
  }

  static void start(int hashCode, Notification notification, Bundle notificationBundle) {
    app.notifee.core.model.NotificationAndroidModel android =
        NotificationModel.fromBundle(notificationBundle).getAndroid();
    Long duration = android.getForegroundServiceBestEffortStopAfterMs();
    ForegroundServiceRunLimitScheduler.Request request = null;
    if (duration != null) {
      // The model rejects this too; keep the platform guard explicit at API-specific calls.
      if (Build.VERSION.SDK_INT < 35) {
        throw new IllegalArgumentException(
            "notification.android.foregroundServiceBestEffortStopAfterMs requires API 35+");
      }
      int type = android.getForegroundServiceType();
      if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST) type = resolveManifestServiceType();
      request =
          ForegroundServiceRunLimitScheduler.publicRequest(
              ContextHolder.getApplicationContext(), duration, type);
    }
    start(hashCode, notification, notificationBundle, request);
  }

  private static void start(
      int hashCode,
      Notification notification,
      Bundle notificationBundle,
      ForegroundServiceRunLimitScheduler.Request alarmPoc) {
    Context context = ContextHolder.getApplicationContext();
    if (context == null) {
      Logger.e(TAG, "Application context is null; cannot start ForegroundService.");
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      try {
        ComponentName component = new ComponentName(context, ForegroundService.class);
        ServiceInfo info =
            context.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
        if (info.getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
          Logger.e(
              TAG,
              "No foregroundServiceType declared for app.notifee.core.ForegroundService in"
                  + " your AndroidManifest.xml. Android 14+ requires an explicit"
                  + " foregroundServiceType. Add <service"
                  + " android:name=\"app.notifee.core.ForegroundService\""
                  + " android:foregroundServiceType=\"yourType\" /> to your app manifest."
                  + " Aborting foreground service start.");
          return;
        }
      } catch (PackageManager.NameNotFoundException e) {
        Logger.e(TAG, "ForegroundService not found in manifest", e);
        return;
      }
    }

    Intent intent = new Intent(context, ForegroundService.class);
    intent.setAction(START_FOREGROUND_SERVICE_ACTION);
    intent.putExtra("hashCode", hashCode);
    intent.putExtra("notification", notification);
    intent.putExtra("notificationBundle", notificationBundle);

    synchronized (sStartDispatchLock) {
      // A retry/redelivery from a previous process must not alias a new pending request.
      String requestId = UUID.randomUUID().toString();
      intent.putExtra(START_REQUEST_ID, requestId);
      StartRequest request;
      synchronized (sLifecycleLock) {
        if (alarmPoc != null
            && (!sPendingStarts.isEmpty()
                || (sInstance != null
                    && (sInstance.mForegroundActive || sInstance.mRunnerToken != null)))) {
          if (!alarmPoc.publicOption)
            throw new IllegalStateException("Diagnostic arming requires a new quiescent run");
          // Repeating the same option on an owned same-ID update is accepted without rearming.
          // Enabling/changing it during an existing run or unresolved START is rejected
          // pre-dispatch.
          synchronized (sLock) {
            ForegroundService owner = sInstance;
            String id = NotificationModel.fromBundle(notificationBundle).getId();
            if (!sPendingStarts.isEmpty()
                || owner == null
                || owner.mAlarmPoc == null
                || !owner.ownsAlarmPoc(owner.mAlarmPoc)
                || !id.equals(mCurrentNotificationId)
                || alarmPoc.duration != owner.mAlarmPoc.duration) {
              throw new IllegalArgumentException(
                  "notification.android.foregroundServiceBestEffortStopAfterMs cannot be enabled or"
                      + " changed during an active/pending run; stop and start a new run");
            }
          }
          alarmPoc = null;
        }
        request = new StartRequest(sRunGeneration, sStopRequest);
        request.alarmPoc = alarmPoc;
        sPendingStarts.put(requestId, request);
        sStartAdmissionRevision++;
      }
      ComponentName component;
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          component = context.startForegroundService(intent);
        } else {
          component = context.startService(intent);
        }
      } catch (RuntimeException e) {
        synchronized (sLifecycleLock) {
          // Only a definite platform rejection releases an undelivered obligation. An unrelated
          // exception can be a lost Binder reply: UNKNOWN must remain held until actual delivery.
          if (!request.accepted
              && (e instanceof SecurityException
                  || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                      && e instanceof ForegroundServiceStartNotAllowedException))) {
            sPendingStarts.remove(requestId);
          }
          scheduleStop();
        }
        throw e;
      }
      synchronized (sLifecycleLock) {
        if (component != null) {
          acceptStart(request);
        } else if (!request.accepted) {
          sPendingStarts.remove(requestId);
        }
        scheduleStop();
      }
    }
  }

  // Caller holds sLifecycleLock. Delivery itself can prove acceptance before Context returns.
  // A late reply never cancels a STOP ordered after this START was reserved/delivered.
  private static void acceptStart(StartRequest request) {
    if (request.promotionDenied) {
      return;
    }
    if (!request.accepted) {
      request.accepted = true;
      if (sInstance != null) {
        sInstance.mDeniedStartRecovery = null;
      }
      if (sStopRequest == request.precedingStop) {
        sStopRequest = null;
      }
    }
  }

  static void stop() {
    synchronized (sLifecycleLock) {
      if (sInstance == null && sPendingStarts.isEmpty()) {
        return;
      }
      if (sInstance != null) {
        sInstance.mDeniedStartRecovery = null;
      }
      // Order STOP before cancellation IPC/storage: a reentrant later START must observe
      // and be able to supersede this request, rather than inherit an outer stale STOP.
      sStopRequest = new Object();
      sRunGeneration++;
      if (sInstance != null) sInstance.invalidateAlarmPoc();
      scheduleStop();
    }
  }

  // Caller holds sLifecycleLock. No START/STOP intent is dispatched here, so a stale reference
  // cannot cold-start a service. onCreate alone is UNKNOWN: wait for a delivered command.
  private static void scheduleStop() {
    ForegroundService instance = sInstance;
    Object request = sStopRequest;
    if (instance != null && request != null) {
      new Handler(Looper.getMainLooper()).post(() -> instance.stopIfRequested(request));
    }
    if (instance != null && instance.mDeniedStartRecovery != null && sPendingStarts.isEmpty()) {
      DeniedStartRecovery recovery = instance.mDeniedStartRecovery;
      new Handler(Looper.getMainLooper()).post(() -> instance.stopDeniedStartIfCurrent(recovery));
    }
  }

  private void stopDeniedStartIfCurrent(DeniedStartRecovery recovery) {
    try {
      stopDeniedStartIfCurrentLocked(recovery);
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  private void stopDeniedStartIfCurrentLocked(DeniedStartRecovery recovery) {
    synchronized (sLifecycleLock) {
      if (mDeniedStartRecovery != recovery) {
        return;
      }
      synchronized (sLock) {
        if (!ownsUnpromotedState()
            || sRunGeneration != recovery.generation
            || mRunGeneration != recovery.runGeneration
            || mLastStartId != recovery.startId
            || mStopInitiated) {
          mDeniedStartRecovery = null;
          return;
        }
      }
      String stopOutcome = "deferred";
      try {
        if (sPendingStarts.isEmpty()) {
          StopAuthority authority = captureStopAuthority();
          // Consume before Binder IPC: refusal or failure grants no automatic retry authority.
          mDeniedStartRecovery = null;
          stopOutcome = "failed";
          boolean stopped = stopSelfResult(recovery.startId);
          stopOutcome = stopped ? "accepted" : "refused";
          synchronized (sLock) {
            if (stopped && ownsStopAuthority(authority) && ownsUnpromotedState()) {
              mStopInitiated = true;
            }
          }
        }
      } finally {
        // Preserve the promotion diagnostic even if the separate stop operation throws.
        Logger.w(
            TAG,
            (recovery.requestId == null
                    ? "Framework redelivered START promotion denied (startId="
                    : "Tracked START PROMOTION_DENIED (cause=UNKNOWN, requestId="
                        + recovery.requestId
                        + ", notificationId="
                        + recovery.notificationId
                        + ", api="
                        + Build.VERSION.SDK_INT
                        + ", type="
                        + recovery.foregroundServiceType
                        + ", generation="
                        + recovery.generation
                        + ", runGeneration="
                        + recovery.runGeneration
                        + ", startId=")
                + recovery.startId
                + ", flags="
                + recovery.flags
                + ", stop="
                + stopOutcome
                + ").",
            recovery.denial);
      }
    }
  }

  private void stopIfRequested(Object request) {
    try {
      stopIfRequestedLocked(request);
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  private void stopIfRequestedLocked(Object request) {
    synchronized (sLifecycleLock) {
      if (sInstance != this
          || sStopRequest != request
          || !sPendingStarts.isEmpty()
          || mLastStartId == 0
          || mStopInitiated
          || mStartCommandFailed) {
        return;
      }
      // Consume this request before IPC so a reentrant START cannot schedule it against its
      // successor. Refusal/failure restores it only while the original admission still owns it.
      sStopRequest = null;
      StopAuthority authority = captureStopAuthority();
      boolean stopped = false;
      try {
        // The framework token and startId also protect an accepted, not yet delivered START.
        stopped = stopSelfResult(authority.startId);
        if (stopped && ownsStopAuthority(authority)) {
          mStopInitiated = true;
          mForegroundActive = false;
          clearOwnedState();
        }
      } finally {
        if (!stopped && ownsStopAuthority(authority)) {
          sStopRequest = request;
        }
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    try {
      return onStartCommandLocked(intent, flags, startId);
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  private int onStartCommandLocked(Intent intent, int flags, int startId) {
    synchronized (sLifecycleLock) {
      // Any later delivery supersedes the recovery startId, including terminal commands.
      mDeniedStartRecovery = null;
      mLastStartId = startId;
      boolean terminal =
          intent == null || STOP_FOREGROUND_SERVICE_ACTION.equals(intent.getAction());
      if (terminal) {
        long generation = ++sRunGeneration;
        long admission = sStartAdmissionRevision;
        long runGeneration = mRunGeneration;
        ForegroundEpoch epoch = mForegroundEpoch;
        invalidateAlarmPoc();
        // This terminal delivery predates every START accepted during cancellation, including
        // an undelivered obligation whose startId/epoch cannot yet reveal the new admission.
        if (sInstance != this
            || sStateOwner != this
            || sRunGeneration != generation
            || sStartAdmissionRevision != admission
            || mRunGeneration != runGeneration
            || mForegroundEpoch != epoch
            || mLastStartId != startId) {
          return START_STICKY_COMPATIBILITY;
        }
      }
      if (terminal && !sPendingStarts.isEmpty()) {
        // The earlier accepted START must promote with its real notification/type first.
        sStopRequest = new Object();
        return START_STICKY_COMPATIBILITY;
      }
      if (!terminal) {
        mStopInitiated = false;
      }
      String requestId = intent == null ? null : intent.getStringExtra(START_REQUEST_ID);
      StartRequest request = sPendingStarts.get(requestId);
      boolean frameworkRedelivery =
          intent != null
              && START_FOREGROUND_SERVICE_ACTION.equals(intent.getAction())
              && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
              && (flags & (START_FLAG_RETRY | START_FLAG_REDELIVERY)) != 0
              && isValidStartRequestId(requestId)
              && request == null;
      boolean superseded = false;
      if (!terminal) {
        long generation = request == null ? sRunGeneration : request.generation;
        if (request != null) {
          request.instance = this;
          acceptStart(request);
        }
        superseded = mRunFirstStartId != 0 && generation < mRunGeneration;
        if (!superseded && mRunGeneration != generation) {
          // Reserve the new session without retiring the previous submitted state or runner.
          // A failed promotion must preserve them, while older deliveries remain fenced.
          invalidateAlarmPoc();
          mRunGeneration = generation;
        }
      }
      mStartCommandSatisfied = mForegroundActive;
      try {
        int result;
        if (superseded) {
          // A lost-reply START can arrive after a newer run. Preserve the new cache/runner. If
          // that run already ended, still perform the old request's real promotion before teardown.
          result = mForegroundActive ? START_NOT_STICKY : fulfillSupersededStart(intent);
        } else {
          result =
              handleStartCommand(intent, flags, startId, frameworkRedelivery, requestId, request);
        }
        mStartCommandFailed = false;
        if (mStartCommandSatisfied) {
          if (!terminal && !superseded) {
            recordSatisfiedStart(startId);
          }
          settleDeliveredStarts();
          if (!terminal && !superseded) maybeArmAlarmPoc(request);
        }
        if (superseded
            && mStartCommandSatisfied
            && !mForegroundActive
            && mRunnerToken == null
            && sPendingStarts.isEmpty()
            && sStopRequest == null) {
          sStopRequest = new Object();
        }
        if (!terminal) {
          scheduleStop();
        }
        return result;
      } catch (RuntimeException e) {
        mStartCommandFailed = !mStartCommandSatisfied;
        if (mStartCommandSatisfied) {
          if (!terminal && !superseded) {
            recordSatisfiedStart(startId);
          }
          settleDeliveredStarts();
        }
        throw e;
      }
    }
  }

  private static boolean isValidStartRequestId(@Nullable String requestId) {
    if (requestId == null || requestId.length() != 36) {
      return false;
    }
    try {
      return UUID.fromString(requestId).toString().equalsIgnoreCase(requestId);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // Caller holds sLifecycleLock and sLock. Recovery cannot retire an acquired run or another owner.
  private boolean ownsUnpromotedState() {
    return sInstance == this
        && sStateOwner == this
        && !mStartForegroundCalled
        && !mForegroundActive
        && !mStartCommandSatisfied
        && mRunnerToken == null
        && mRunFirstStartId == 0
        && mCurrentNotificationId == null
        && mCurrentForegroundServiceType == -1
        && mCurrentNotificationBundle == null
        && mCurrentNotification == null
        && mCurrentHashCode == 0;
  }

  // Caller holds both lifecycle/state locks. Revalidate the original admission after the platform
  // attempt: a synchronous authority change must not authorize a stale request's recovery.
  private boolean canRecoverTrackedDenial(
      Intent intent,
      String requestId,
      StartRequest request,
      long generation,
      long runGeneration,
      int startId) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        && START_FOREGROUND_SERVICE_ACTION.equals(intent.getAction())
        && isValidStartRequestId(requestId)
        && request != null
        && sPendingStarts.get(requestId) == request
        && request.instance == this
        && request.accepted
        && !request.promotionDenied
        && request.generation == runGeneration
        && sRunGeneration == generation
        && mRunGeneration == runGeneration
        && mLastStartId == startId
        && !mStopInitiated
        && ownsUnpromotedState();
  }

  // A real promotion (or an already foreground instance) satisfies the physical instance's
  // delivered obligations, including a prior ambiguous delivery. Undelivered STARTs remain held.
  // This does not turn a posting/type exception into success: onStartCommand still rethrows it.
  private void settleDeliveredStarts() {
    mDeniedStartRecovery = null;
    sPendingStarts.values().removeIf(request -> request.instance == this);
  }

  @SuppressLint({"ForegroundServiceType", "MissingPermission"})
  private int fulfillSupersededStart(Intent intent) {
    Bundle extras = intent.getExtras();
    if (extras == null) {
      return START_NOT_STICKY;
    }
    Notification notification =
        ParcelableCompatReader.getParcelable(extras, "notification", Notification.class);
    Bundle bundle = extras.getBundle("notificationBundle");
    if (notification == null || bundle == null) {
      return START_NOT_STICKY;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      int type = NotificationModel.fromBundle(bundle).getAndroid().getForegroundServiceType();
      if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST) {
        type = resolveManifestServiceType();
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
          && type == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
        ensureStartForegroundContractSatisfied(false);
        return START_NOT_STICKY;
      }
      startForeground(extras.getInt("hashCode"), notification, type);
    } else {
      startForeground(extras.getInt("hashCode"), notification);
    }
    mStartForegroundCalled = true;
    mForegroundActive = true;
    mStartCommandSatisfied = true;
    stopForegroundCompat();
    return START_NOT_STICKY;
  }

  @SuppressLint({"ForegroundServiceType", "MissingPermission"})
  private int handleStartCommand(
      Intent intent,
      int flags,
      int startId,
      boolean frameworkRedelivery,
      String requestId,
      StartRequest request) {
    Trace.beginSection("notifee:ForegroundService.onStartCommand");
    try {
      // Check if action is to stop the foreground service
      if (intent == null || STOP_FOREGROUND_SERVICE_ACTION.equals(intent.getAction())) {
        StopAuthority authority = captureStopAuthority();
        ensureStartForegroundContractSatisfied(true);
        if (ownsStopAuthority(authority)
            && stopSelfResult(authority.startId)
            && ownsStopAuthority(authority)) {
          mStopInitiated = true;
          mForegroundActive = false;
          clearOwnedState();
        }
        return Service.START_STICKY_COMPATIBILITY;
      }

      Bundle extras = intent.getExtras();

      if (extras != null) {
        // Hash code is sent to service to ensure it is kept the same
        int hashCode = extras.getInt("hashCode");
        Notification notification =
            ParcelableCompatReader.getParcelable(extras, "notification", Notification.class);
        Bundle bundle = extras.getBundle("notificationBundle");

        if (notification != null && bundle != null) {
          NotificationModel notificationModel = NotificationModel.fromBundle(bundle);
          String notificationId = notificationModel.getId();

          Object pendingEvent = null;
          boolean noneEarlyReturn = false;
          RuntimeException deniedInitialPromotion = null;
          int deniedForegroundServiceType = -1;
          boolean deniedTrackedStart = false;

          synchronized (sLock) {
            if (mCurrentNotificationId == null
                || mStateGeneration != mRunGeneration
                || (mForegroundEpoch != null && mForegroundEpoch.retiring)) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int foregroundServiceType =
                    notificationModel.getAndroid().getForegroundServiceType();
                if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST) {
                  foregroundServiceType = resolveManifestServiceType();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                  Logger.e(
                      TAG,
                      "Resolved foreground service type is NONE on API 34+; aborting"
                          + " startForeground to avoid InvalidForegroundServiceTypeException.");
                  // Set flag to call the helper AFTER releasing sLock — it performs
                  // Binder IPC (PackageManager + startForeground) and must not hold sLock.
                  noneEarlyReturn = true;
                } else {
                  boolean recoverInitialDenial = frameworkRedelivery && ownsUnpromotedState();
                  long generation = sRunGeneration;
                  long runGeneration = mRunGeneration;
                  boolean recoverTrackedDenial =
                      canRecoverTrackedDenial(
                          intent, requestId, request, generation, runGeneration, startId);
                  Trace.beginSection("notifee:startForeground");
                  try {
                    startForeground(hashCode, notification, foregroundServiceType);
                  } catch (RuntimeException e) {
                    // Only the real initial promotion is exempt. Type resolution, NONE, posting,
                    // updates and teardown retain their existing failure boundaries.
                    boolean trackedDenial =
                        recoverTrackedDenial
                            && canRecoverTrackedDenial(
                                intent, requestId, request, generation, runGeneration, startId);
                    if (!(trackedDenial || (recoverInitialDenial && ownsUnpromotedState()))
                        || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || e.getClass() != ForegroundServiceStartNotAllowedException.class) {
                      throw e;
                    }
                    deniedInitialPromotion = e;
                    deniedForegroundServiceType = foregroundServiceType;
                    deniedTrackedStart = trackedDenial;
                  } finally {
                    Trace.endSection();
                  }
                  if (deniedInitialPromotion == null) {
                    mStartForegroundCalled = true;
                    mForegroundActive = true;
                    mStartCommandSatisfied = true;
                    mCurrentForegroundServiceType = foregroundServiceType;
                  }
                }
              } else {
                Trace.beginSection("notifee:startForeground");
                try {
                  startForeground(hashCode, notification);
                } finally {
                  Trace.endSection();
                }
                mStartForegroundCalled = true;
                mForegroundActive = true;
                mStartCommandSatisfied = true;
              }

              if (!noneEarlyReturn && deniedInitialPromotion == null) {
                if (!commitForegroundEpoch()) return START_NOT_STICKY;
                if (request != null && request.alarmPoc != null)
                  request.alarmPocEpoch = mForegroundEpoch;
                mCurrentNotificationId = notificationId;
                mCurrentNotificationBundle = bundle;
                mCurrentNotification = notification;
                mCurrentHashCode = hashCode;
                mStateGeneration = mRunGeneration;
                mRunFirstStartId = mLastStartId;
                final Object runnerToken = new Object();
                final long runnerGeneration = mRunGeneration;
                mRunnerToken = runnerToken;
                // On headless task complete
                final MethodCallResult<Void> methodCallResult =
                    (e, aVoid) -> {
                      Runnable complete =
                          () -> {
                            try {
                              synchronized (sLifecycleLock) {
                                if (sInstance == this
                                    && mRunnerToken == runnerToken
                                    && sRunGeneration == runnerGeneration
                                    && mForegroundEpoch != null
                                    && !mForegroundEpoch.retiring
                                    && !mForegroundEpoch.retired) {
                                  ForegroundEpoch epoch = mForegroundEpoch;
                                  stopForegroundCompat();
                                  if (sInstance == this
                                      && mForegroundEpoch == epoch
                                      && mRunnerToken == runnerToken) {
                                    clearOwnedState();
                                  }
                                }
                              }
                            } finally {
                              publishTimeoutEventsIfUnlocked();
                            }
                          };
                      if (Looper.myLooper() == Looper.getMainLooper()) {
                        complete.run();
                      } else {
                        new Handler(Looper.getMainLooper()).post(complete);
                      }
                    };

                pendingEvent = new ForegroundServiceEvent(notificationModel, methodCallResult);
              }
            } else {
              if (mCurrentNotificationId.equals(notificationId)) {
                ForegroundEpoch updateEpoch = mForegroundEpoch;
                long updateGeneration = sRunGeneration;
                // Before API 31, updating the service's registered notification requires
                // startForeground(). API 31+ processes a same-ID notify() against the foreground
                // service record. Keep that path for an unchanged type: repeating
                // startForeground() can extend a shortService timeout on API 34+.
                boolean shouldStartForeground = Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
                int foregroundServiceType = mCurrentForegroundServiceType;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  int requestedType = notificationModel.getAndroid().getForegroundServiceType();
                  if (requestedType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST) {
                    requestedType = resolveManifestServiceType();
                  }
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                      && requestedType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                    Logger.e(
                        TAG,
                        "Resolved foreground service type is NONE on API 34+; skipping type"
                            + " change.");
                  } else if (requestedType != mCurrentForegroundServiceType) {
                    foregroundServiceType = requestedType;
                    shouldStartForeground = true;
                  }
                }
                if (shouldStartForeground) {
                  Trace.beginSection("notifee:startForeground");
                  try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                      startForeground(mCurrentHashCode, notification, foregroundServiceType);
                    } else {
                      startForeground(mCurrentHashCode, notification);
                    }
                  } finally {
                    Trace.endSection();
                  }
                } else {
                  NotificationManagerCompat.from(ContextHolder.getApplicationContext())
                      .notify(mCurrentHashCode, notification);
                }
                // A timeout or successor may have retired/replaced the publication during IPC.
                // A normal return cannot reacquire its cache, runner or promotion authority.
                if (!ownsTimeoutEpoch(updateEpoch)
                    || updateEpoch == null
                    || updateEpoch.retiring
                    || updateEpoch.retired
                    || mLastStartId != startId
                    || mRunGeneration != updateEpoch.runGeneration
                    || sRunGeneration != updateGeneration) {
                  return START_NOT_STICKY;
                }
                if (shouldStartForeground) {
                  mStartForegroundCalled = true;
                  mForegroundActive = true;
                  mStartCommandSatisfied = true;
                  if (!commitForegroundEpoch()) return START_NOT_STICKY;
                }
                // The platform call returned normally, so cache the latest submitted state for
                // re-post and timeout. A normal return does not acknowledge display.
                mCurrentForegroundServiceType = foregroundServiceType;
                mCurrentNotification = notification;
                mCurrentNotificationBundle = bundle;
              } else {
                pendingEvent =
                    new NotificationEvent(
                        NotificationEvent.TYPE_FG_ALREADY_EXIST, notificationModel);
              }
            }
          }

          if (deniedInitialPromotion != null) {
            // Release sLock before stop Binder IPC. Terminalize only the exact denied request;
            // this is not a successful promotion and cannot fulfill any other delivered START.
            if (deniedTrackedStart) {
              request.promotionDenied = true;
              sPendingStarts.remove(requestId, request);
            }
            DeniedStartRecovery recovery =
                new DeniedStartRecovery(
                    sRunGeneration,
                    mRunGeneration,
                    startId,
                    flags,
                    deniedInitialPromotion,
                    deniedTrackedStart ? requestId : null,
                    notificationId,
                    deniedForegroundServiceType);
            mDeniedStartRecovery = recovery;
            stopDeniedStartIfCurrent(recovery);
            return START_NOT_STICKY;
          }

          // Handle NONE early return outside the lock — the helper performs Binder IPC
          // (PackageManager query + startForeground) that must not hold sLock.
          if (noneEarlyReturn) {
            ensureStartForegroundContractSatisfied(false);
            return START_NOT_STICKY;
          }

          if (pendingEvent != null) {
            EventBus.post(pendingEvent);
          }
        }
      }

      return START_NOT_STICKY;
    } finally {
      Trace.endSection();
    }
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /**
   * Called by the system on API 34 (Android 14) when a foreground service of type {@code
   * shortService} exceeds its timeout. Stops the service gracefully to prevent ANR.
   */
  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  @Override
  public void onTimeout(int startId) {
    try {
      handleTimeout(startId, -1, true);
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  /**
   * Called by the system on API 35+ (Android 15+) when a foreground service exceeds its
   * type-specific timeout. Supersedes the single-parameter variant on these API levels.
   */
  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  @Override
  public void onTimeout(int startId, int fgsType) {
    try {
      handleTimeout(startId, fgsType, false);
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private static int resolveManifestServiceType() {
    try {
      Context context = ContextHolder.getApplicationContext();
      if (context == null) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
      }
      ComponentName component = new ComponentName(context, ForegroundService.class);
      ServiceInfo info =
          context.getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
      int type = info.getForegroundServiceType();
      if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
        return type;
      }
    } catch (PackageManager.NameNotFoundException e) {
      Logger.e(TAG, "ForegroundService not found in manifest", e);
    }
    // On API 34+ returning MANIFEST (-1) would crash in startForeground(), so return NONE.
    // On API 29-33 MANIFEST is accepted by the framework and resolves at runtime.
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        : ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
  }

  /**
   * Ensures the defensive notification channel exists. Required for the placeholder notification
   * used on the STOP path when startForeground() was never called on this instance.
   */
  private void ensureDefensiveChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      if (nm != null && nm.getNotificationChannel(DEFENSIVE_CHANNEL_ID) == null) {
        NotificationChannel channel =
            new NotificationChannel(
                DEFENSIVE_CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
      }
    }
  }

  @SuppressWarnings("deprecation")
  private void stopForegroundCompat() {
    ForegroundEpoch epoch = mForegroundEpoch;
    removeForegroundNotification();
    if (sInstance == this && sStateOwner == this && mForegroundEpoch == epoch) {
      mForegroundActive = false;
    }
  }

  @SuppressWarnings("deprecation")
  private void removeForegroundNotification() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      stopForeground(true);
    }
  }

  /**
   * Queries the PackageManager for the {@code foregroundServiceType} attribute declared on this
   * service's {@code <service>} element in AndroidManifest.xml.
   *
   * <p>This is intentionally separate from {@link #resolveManifestServiceType()} (which resolves
   * the effective type for the normal startForeground path). That method is static, uses {@link
   * ContextHolder#getApplicationContext()} (which may be null during early recreation), and has
   * different return semantics — it maps missing types to {@code FOREGROUND_SERVICE_TYPE_MANIFEST}
   * on API 29-33 and to {@code FOREGROUND_SERVICE_TYPE_NONE} on API 34+. This method is an instance
   * method that uses {@code this} as context (always valid inside a running Service) and returns
   * the raw declared value without any API-level mapping, which is what the proactive manifest
   * check in {@link #ensureStartForegroundContractSatisfied(boolean)} needs.
   *
   * @return the raw {@code foregroundServiceType} bitmask from the manifest, or 0 if the service is
   *     not declared or has no explicit type
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  private int getDeclaredForegroundServiceType() {
    try {
      ComponentName component = new ComponentName(this, ForegroundService.class);
      ServiceInfo info =
          getPackageManager().getServiceInfo(component, PackageManager.GET_META_DATA);
      return info.getForegroundServiceType();
    } catch (PackageManager.NameNotFoundException e) {
      return 0;
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private static int selectDefensiveForegroundServiceType(int declaredTypes) {
    int[] preferredTypes = {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
    };

    for (int type : preferredTypes) {
      if (isDeclared(declaredTypes, type)) {
        return type;
      }
    }

    return declaredTypes;
  }

  private static boolean isDeclared(int declaredTypes, int type) {
    return (declaredTypes & type) == type;
  }

  private static boolean hasAnyForegroundServiceType(int foregroundServiceTypes) {
    return foregroundServiceTypes != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        && foregroundServiceTypes != ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
  }

  /**
   * Ensures Android's 5-second {@code startForeground()} contract is satisfied before an early
   * return from {@link #onStartCommand(Intent, int, int)}.
   *
   * <p>This method is idempotent: if {@link #mStartForegroundCalled} is already {@code true} (i.e.,
   * this service instance has previously called {@code startForeground()} successfully), this
   * method returns immediately without side effects.
   *
   * <p>On API 34+ (Android 14), this method performs a proactive manifest check via {@link
   * #getDeclaredForegroundServiceType()} before attempting the defensive {@code startForeground()}
   * call. If no {@code foregroundServiceType} is declared in the manifest, the method throws a
   * {@link RuntimeException} with a clear error message and documentation URL instead of proceeding
   * to a call that would fail with a cryptic {@code SecurityException}.
   *
   * <p>On API levels below 34, the manifest check is skipped and the defensive {@code
   * startForeground()} is called directly, as no {@code foregroundServiceType} declaration is
   * required.
   *
   * @throws RuntimeException if the manifest is missing required {@code foregroundServiceType}
   *     declarations on API 34+, or if the defensive {@code startForeground()} call fails for a
   *     non-platform-permission reason. {@link SecurityException} is handled as a best-effort stop
   *     because API 34+ can deny while-in-use types when the app is backgrounded. A terminal
   *     STOP/null also logs and tolerates the API 31+ promotion denial specifically; that policy
   *     does not apply to NONE or to an earlier unfulfilled accepted START.
   */
  @SuppressLint({"ForegroundServiceType", "MissingPermission"})
  private void ensureStartForegroundContractSatisfied(boolean terminalStop) {
    if (mStartForegroundCalled) {
      return;
    }

    // Proactive manifest check on API 34+: fail fast with actionable message.
    // Also caches the declared type for use in the 3-param startForeground() call below.
    int declaredTypes = 0;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      declaredTypes = getDeclaredForegroundServiceType();
      if (!hasAnyForegroundServiceType(declaredTypes)) {
        String msg =
            "react-native-notify-kit: ForegroundService cannot start — "
                + "no foregroundServiceType declared in AndroidManifest.xml on API 34+. "
                + "See https://github.com/marcocrupi/react-native-notify-kit"
                + "#foreground-service-setup-android-14";
        Logger.e(TAG, msg);
        // Intentional crash: a RuntimeException here terminates the process with a clear,
        // actionable crash report within milliseconds — well inside the 5-second ANR budget.
        // The alternative (catching and calling stopSelf()) leaves Android's startForeground()
        // contract unsatisfied, which produces a ForegroundServiceDidNotStartInTimeException
        // ANR with a framework-only stack trace that gives no indication of the root cause.
        // Do NOT suppress this throw without also providing an alternative mechanism to satisfy
        // the startForeground() contract or terminate the process before the ANR fires.
        throw new RuntimeException(msg);
      }
    }

    ensureDefensiveChannel();
    Notification placeholder =
        new NotificationCompat.Builder(this, DEFENSIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build();
    // Resolve the safe declared type before entering the promotion-only exception boundary.
    int defensiveTypes =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ? selectDefensiveForegroundServiceType(declaredTypes)
            : 0;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(DEFENSIVE_NOTIFICATION_ID, placeholder, defensiveTypes);
      } else {
        startForeground(DEFENSIVE_NOTIFICATION_ID, placeholder);
      }
    } catch (SecurityException e) {
      Logger.w(
          TAG,
          "Defensive startForeground() was denied by Android while stopping the service; "
              + "continuing with stopSelf() to avoid a fatal app crash.",
          e);
      return;
    } catch (Exception e) {
      // NONE and unrelated IllegalStateException/type failures remain observable.
      if (terminalStop
          && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
          && e instanceof ForegroundServiceStartNotAllowedException) {
        Logger.w(
            TAG,
            "Defensive startForeground() was not allowed by Android while stopping; "
                + "continuing with stopSelf().",
            e);
        return;
      }
      String msg =
          "react-native-notify-kit: defensive startForeground() failed. "
              + "This indicates an inconsistent service state and the process cannot recover.";
      Logger.e(TAG, msg, e);
      throw new RuntimeException(msg, e);
    }
    mStartForegroundCalled = true;
    mForegroundActive = true;
    mStartCommandSatisfied = true;
    stopForegroundCompat();
  }

  // Caller holds sLifecycleLock. Platform calls may synchronously reenter lifecycle callbacks.
  private boolean ownsTimeoutEpoch(ForegroundEpoch epoch) {
    synchronized (sLock) {
      return sInstance == this && sStateOwner == this && mForegroundEpoch == epoch;
    }
  }

  private void retireTimeoutEpoch(ForegroundEpoch epoch) {
    synchronized (sLock) {
      if (ownsTimeoutEpoch(epoch)) {
        mForegroundActive = false;
        clearOwnedState();
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private void handleTimeout(int startId, int fgsType, boolean shortServiceCallback) {
    Bundle notificationBundle;
    ForegroundEpoch epoch;
    ForegroundServiceRunLimitScheduler.Ticket timeoutAlarm;
    RuntimeException primary = null;
    String demotionOutcome = "failed";
    String stopOutcome = "deferred";
    synchronized (sLifecycleLock) {
      final boolean stopAuthorized;
      final int stopId;
      final Object stopRequest = sStopRequest;
      synchronized (sLock) {
        epoch = mForegroundEpoch;
        int callbackType =
            shortServiceCallback ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE : fgsType;
        if (sInstance != this
            || sStateOwner != this
            || epoch == null
            || (epoch.retiring && !epoch.voluntaryRetirement)
            || epoch.retired
            || !mForegroundActive
            || (mRunFirstStartId != 0 && startId < mRunFirstStartId)
            || callbackType <= 0
            || mCurrentForegroundServiceType <= 0
            || (callbackType & mCurrentForegroundServiceType) == 0) {
          return;
        }
        // Matching IDs are library authority only. A repeated platform promotion within the
        // same run can expose coincident metadata, so it never upgrades ambiguous provenance.
        stopAuthorized =
            !epoch.ambiguousPromotion
                && !mTimeoutStartIds.contains(startId)
                && epoch.satisfiedStartIds.contains(startId);
        stopId = epoch.lastSatisfiedStartId;
        notificationBundle =
            mCurrentNotificationBundle == null ? null : new Bundle(mCurrentNotificationBundle);
        mTimeoutStartIds.add(startId);
        epoch.retiring = true;
        epoch.voluntaryRetirement = false;
        timeoutAlarm = revokeAlarmPoc();
      }

      // Nothing that posts an event, converts a model or retires owned evidence precedes this.
      try {
        removeForegroundNotification();
        demotionOutcome = "returned"; // Normal Binder return is not an AMS acknowledgement.
        retireTimeoutEpoch(epoch);
      } catch (RuntimeException failure) {
        primary = failure;
      }

      if (stopAuthorized
          && ownsTimeoutEpoch(epoch)
          && epoch.runGeneration == mRunGeneration
          && epoch.runGeneration == sRunGeneration
          && sStopRequest == stopRequest
          && sPendingStarts.isEmpty()
          && mLastStartId == stopId
          && stopId != 0
          && !mStartCommandFailed
          && !mStopInitiated) {
        try {
          stopOutcome = "failed";
          StopAuthority authority = captureStopAuthority();
          boolean stopped = stopSelfResult(stopId);
          stopOutcome = stopped ? "accepted" : "refused";
          if (stopped && ownsTimeoutEpoch(epoch) && ownsStopAuthority(authority)) {
            mStopInitiated = true;
            retireTimeoutEpoch(epoch);
          }
        } catch (RuntimeException failure) {
          if (primary == null) primary = failure;
          else if (primary != failure) primary.addSuppressed(failure);
        }
      }
    }

    if (timeoutAlarm != null) ForegroundServiceRunLimitScheduler.cancel(timeoutAlarm);

    // Consume publication authority once, including when a reentrant caller still owns locks.
    final RuntimeException timeoutFailure = primary;
    final String diagnostic =
        "Foreground timeout (startId="
            + startId
            + ", type="
            + fgsType
            + ", runGeneration="
            + epoch.runGeneration
            + ", demotion="
            + demotionOutcome
            + ", stop="
            + stopOutcome
            + ").";
    List<Runnable> publications = sTimeoutPublications.get();
    if (publications == null) {
      publications = new ArrayList<>();
      sTimeoutPublications.set(publications);
    }
    publications.add(
        () -> {
          if (notificationBundle != null) {
            try {
              NotificationModel model = NotificationModel.fromBundle(notificationBundle);
              Bundle extras = new Bundle();
              extras.putInt("startId", startId);
              extras.putInt("fgsType", fgsType);
              EventBus.post(
                  new NotificationEvent(NotificationEvent.TYPE_FG_TIMEOUT, model, extras));
            } catch (RuntimeException failure) {
              if (timeoutFailure != null && timeoutFailure != failure)
                timeoutFailure.addSuppressed(failure);
              Logger.w(TAG, "Timeout event publication failed", failure);
            }
          }
          // Preserve the existing error LogEvent, but only after the essential teardown and
          // timeout publication. Its failure cannot replace a primary platform exception.
          try {
            if (timeoutFailure == null) Logger.e(TAG, diagnostic);
            else Logger.e(TAG, diagnostic, timeoutFailure);
          } catch (RuntimeException failure) {
            if (timeoutFailure != null && timeoutFailure != failure)
              timeoutFailure.addSuppressed(failure);
            Logger.w(TAG, "Timeout diagnostic publication failed", failure);
          }
        });
    publishTimeoutEventsIfUnlocked();
    if (primary != null) throw primary;
  }

  // All diagnostic authority is subordinate to the existing lifecycle/epoch registry.
  // Caller holds sLifecycleLock; nested state checks always take sLock second.
  private void invalidateAlarmPoc() {
    ForegroundServiceRunLimitScheduler.Ticket ticket = revokeAlarmPoc();
    if (ticket != null) ForegroundServiceRunLimitScheduler.cancel(ticket);
  }

  private ForegroundServiceRunLimitScheduler.Ticket revokeAlarmPoc() {
    ForegroundServiceRunLimitScheduler.Ticket ticket = mAlarmPoc;
    mAlarmPoc = null;
    if (ticket != null) ticket.valid = false;
    return ticket;
  }

  private void maybeArmAlarmPoc(StartRequest request) {
    if (request == null || request.alarmPoc == null || Build.VERSION.SDK_INT < 35) return;
    synchronized (sLock) {
      ForegroundEpoch epoch = mForegroundEpoch;
      if ((!request.alarmPoc.publicOption && mAlarmPocAttemptedRun == mRunGeneration)
          || request.alarmPocEpoch != epoch) {
        ForegroundServiceRunLimitScheduler.marker(
            "ARM_REJECTED", null, "no new diagnostic promotion/run");
        return;
      }
      mAlarmPocAttemptedRun = mRunGeneration;
      if (sInstance != this
          || sStateOwner != this
          || epoch == null
          || epoch.retiring
          || epoch.retired
          || !mForegroundActive
          || !mStartCommandSatisfied
          || epoch.runGeneration != mRunGeneration
          || sRunGeneration != mRunGeneration
          || epoch.lastSatisfiedStartId != mLastStartId
          || mStopInitiated
          || mCurrentForegroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
        ForegroundServiceRunLimitScheduler.marker(
            "ARM_REJECTED", null, "requires current single dataSync epoch");
        return;
      }
      ForegroundServiceRunLimitScheduler.Ticket ticket = null;
      try {
        // Promotion has already returned; no ledger/GC I/O is added to default START latency.
        if (request.alarmPoc.publicOption) ForegroundServiceRunLimitScheduler.collectOrphans(this);
        ticket =
            new ForegroundServiceRunLimitScheduler.Ticket(
                this,
                epoch,
                sRunGeneration,
                mRunGeneration,
                request.alarmPoc,
                epoch.promotedElapsed);
        // Ticket construction reads boot identity through a platform API. Revalidate after it.
        if (sInstance != this
            || sStateOwner != this
            || mForegroundEpoch != epoch
            || epoch.retiring
            || epoch.retired
            || sRunGeneration != ticket.generation
            || mRunGeneration != ticket.runGeneration) {
          ForegroundServiceRunLimitScheduler.cancel(ticket);
          return;
        }
        mAlarmPoc = ticket;
        if (!ForegroundServiceRunLimitScheduler.arm(ticket) || !ownsAlarmPoc(ticket)) {
          if (mAlarmPoc == ticket) mAlarmPoc = null;
          ForegroundServiceRunLimitScheduler.cancel(ticket);
        }
      } catch (org.json.JSONException | RuntimeException failure) {
        // Construction/storage may have admitted B reentrantly. Cleanup belongs only to A.
        if (ticket != null) {
          if (mAlarmPoc == ticket) mAlarmPoc = null;
          ForegroundServiceRunLimitScheduler.cancel(ticket);
        }
        ForegroundServiceRunLimitScheduler.marker("ARM_FAILED", ticket, failure.toString());
      }
    }
  }

  String alarmPocInstanceId() {
    if (mAlarmPocInstanceId == null) mAlarmPocInstanceId = UUID.randomUUID().toString();
    return mAlarmPocInstanceId;
  }

  private boolean ownsAlarmPoc(ForegroundServiceRunLimitScheduler.Ticket ticket) {
    return Build.VERSION.SDK_INT >= 35
        && sInstance == this
        && sStateOwner == this
        && ticket.owner == this
        && mAlarmPoc == ticket
        && ticket.valid
        && ticket.epoch == mForegroundEpoch
        && sRunGeneration == ticket.generation
        && mRunGeneration == ticket.runGeneration
        && mForegroundEpoch != null
        && mForegroundEpoch.runGeneration == ticket.runGeneration
        && !mForegroundEpoch.retiring
        && !mForegroundEpoch.retired
        && mForegroundActive
        && mCurrentForegroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        && mStartCommandSatisfied
        && mLastStartId != 0
        && mLastStartId == mForegroundEpoch.lastSatisfiedStartId
        && !mStartCommandFailed
        && !mStopInitiated;
  }

  static void receiveAlarmPoc(Intent intent) {
    try {
      synchronized (sLifecycleLock) {
        ForegroundService owner = sInstance;
        if (owner == null) {
          ForegroundServiceRunLimitScheduler.marker(
              "NO_MATCHING_LIVE_INSTANCE", null, "cold delivery");
          return;
        }
        owner.expireAlarmPoc(intent);
      }
    } finally {
      publishTimeoutEventsIfUnlocked();
    }
  }

  private void expireAlarmPoc(Intent intent) {
    final ForegroundServiceRunLimitScheduler.Ticket ticket;
    final ForegroundEpoch epoch;
    final StopAuthority stop;
    final Bundle notificationBundle;
    final long deliveredElapsed;
    String demotionOutcome = "failed";
    String stopOutcome = "deferred";
    synchronized (sLock) {
      ticket = mAlarmPoc;
      if (ticket == null
          || !ownsAlarmPoc(ticket)
          || !ForegroundServiceRunLimitScheduler.matches(ticket, intent)) {
        ForegroundServiceRunLimitScheduler.marker(
            "ALARM_STALE", null, "no matching live authority");
        return;
      }
      ForegroundServiceRunLimitScheduler.marker("ALARM_DELIVERED", ticket, "native receiver");
      if (!ForegroundServiceRunLimitScheduler.ledgerMatches(ticket)) {
        if (mAlarmPoc == ticket) invalidateAlarmPoc();
        ForegroundServiceRunLimitScheduler.marker(
            "LEDGER_INVALID", ticket, "no teardown authority");
        return;
      }
      long now = SystemClock.elapsedRealtime();
      if (now < ticket.deadline) {
        ForegroundServiceRunLimitScheduler.marker("ALARM_EARLY", ticket, "no demotion");
        return;
      }
      if (now > ticket.deadline) {
        ForegroundServiceRunLimitScheduler.marker(
            "ALARM_LATE", ticket, "delayMs=" + (now - ticket.deadline));
      }
      // Boot/ledger validation crosses platform APIs. It cannot transfer the old alarm's
      // authority to a successor that appeared while those calls were in progress.
      if (!ownsAlarmPoc(ticket)) {
        ForegroundServiceRunLimitScheduler.marker(
            "ALARM_STALE", ticket, "owner changed during validation");
        return;
      }
      epoch = mForegroundEpoch;
      stop = captureStopAuthority();
      deliveredElapsed = now;
      notificationBundle =
          mCurrentNotificationBundle == null ? null : new Bundle(mCurrentNotificationBundle);
      mAlarmPoc = null;
      ticket.valid = false;
      epoch.retiring = true;
      epoch.voluntaryRetirement = true;
    }
    try {
      ForegroundServiceRunLimitScheduler.marker(
          "DEMOTION_ATTEMPTED",
          ticket,
          "REMOVE notificationId="
              + mCurrentNotificationId
              + " startId="
              + stop.startId
              + " satisfiedStartId="
              + epoch.lastSatisfiedStartId
              + " pendingStarts="
              + sPendingStarts.size());
      try {
        removeForegroundNotification();
        demotionOutcome = "returned";
        ForegroundServiceRunLimitScheduler.marker(
            "DEMOTION_RETURNED", ticket, "not an AMS acknowledgement");
        retireTimeoutEpoch(epoch);
      } catch (RuntimeException failure) {
        ForegroundServiceRunLimitScheduler.marker("DEMOTION_FAILED", ticket, failure.toString());
      }
      if (ownsTimeoutEpoch(epoch)
          && epoch.runGeneration == mRunGeneration
          && epoch.runGeneration == sRunGeneration
          && ownsStopAuthority(stop)
          && mLastStartId == epoch.lastSatisfiedStartId
          && mLastStartId != 0
          && !mStartCommandFailed
          && !mStopInitiated) {
        try {
          stopOutcome = "failed";
          boolean stopped = stopSelfResult(stop.startId);
          stopOutcome = stopped ? "accepted" : "refused";
          ForegroundServiceRunLimitScheduler.marker(
              stopped ? "STOP_ACCEPTED" : "STOP_REFUSED", ticket, "startId=" + stop.startId);
          if (stopped && ownsTimeoutEpoch(epoch) && ownsStopAuthority(stop)) {
            mStopInitiated = true;
            retireTimeoutEpoch(epoch);
          }
        } catch (RuntimeException failure) {
          ForegroundServiceRunLimitScheduler.marker("STOP_FAILED", ticket, failure.toString());
        }
      } else {
        ForegroundServiceRunLimitScheduler.marker(
            "STOP_DEFERRED", ticket, "pending/unknown START or ownership changed");
      }
      ForegroundServiceRunLimitScheduler.marker(
          "NATIVE_FINAL_STATE",
          ticket,
          "sameEpoch="
              + ownsTimeoutEpoch(epoch)
              + " foregroundActive="
              + mForegroundActive
              + " stopInitiated="
              + mStopInitiated
              + " pendingStarts="
              + sPendingStarts.size());
    } finally {
      ForegroundServiceRunLimitScheduler.cancel(ticket);
    }
    if (ticket.publicOption && notificationBundle != null) {
      Bundle metadata = new Bundle();
      metadata.putLong("requestedDurationMs", ticket.duration);
      metadata.putLong("deadlineElapsed", ticket.deadline);
      metadata.putLong("deliveredElapsed", deliveredElapsed);
      metadata.putString("demotion", demotionOutcome);
      metadata.putString("conditionalStop", stopOutcome);
      List<Runnable> publications = sTimeoutPublications.get();
      if (publications == null) {
        publications = new ArrayList<>();
        sTimeoutPublications.set(publications);
      }
      publications.add(
          () -> {
            try {
              Bundle extras = new Bundle();
              extras.putBundle("foregroundServiceRunLimit", metadata);
              EventBus.post(
                  new NotificationEvent(
                      NotificationEvent.TYPE_FG_RUN_LIMIT_REACHED,
                      NotificationModel.fromBundle(notificationBundle),
                      extras));
            } catch (RuntimeException failure) {
              ForegroundServiceRunLimitScheduler.marker("EVENT_FAILED", ticket, failure.toString());
            }
          });
    }
  }
}
