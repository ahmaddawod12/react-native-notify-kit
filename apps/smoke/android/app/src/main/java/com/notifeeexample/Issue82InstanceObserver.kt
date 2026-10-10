package com.notifeeexample

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import org.json.JSONObject

// Smoke-only passive identity observation of an app-owned class field.
// No Android hidden API, field writes, core callbacks, Service/Activity start,
// FGS exemption or synthetic timeout. Absence here is never an AMS proof.
object Issue82InstanceObserver {
  fun start() {
    val run = UUID.randomUUID().toString()
    Thread({
      try {
        val klass = Class.forName("app.notifee.core.ForegroundService")
        val field = klass.getDeclaredField("sInstance").apply { isAccessible = true }
        var previous = WeakReference<Any>(null)
        var present = false
        var sequence = 0
        while (!Thread.currentThread().isInterrupted) {
          val instance = field.get(null)
          if (instance != null && instance.javaClass == klass && previous.get() !== instance) {
            sequence++
            previous = WeakReference(instance)
            present = true
            Log.i("Issue82Instance", "INSTANCE_PRESENT " + JSONObject().apply {
              put("observerRunId", run)
              put("instanceId", sequence)
              put("className", klass.name)
              put("pid", Process.myPid())
              put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
            }.toString())
          } else if (instance == null && present) {
            present = false
            Log.i("Issue82Instance", "INSTANCE_ABSENT " + JSONObject().apply {
              put("observerRunId", run)
              put("instanceId", sequence)
              put("pid", Process.myPid())
              put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
            }.toString())
          }
          Thread.sleep(1)
        }
      } catch (error: Throwable) {
        Log.e("Issue82Instance", "OBSERVER_UNAVAILABLE", error)
      }
    }, "Issue82InstanceObserver").apply { isDaemon = true; start() }
  }
}
