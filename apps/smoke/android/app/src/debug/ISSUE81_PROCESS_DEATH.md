# Issue 81 Debug process-death probe

Only the `debug` source set declares/compiles the receiver. Main, Release,
benchmark, library core, public API and the existing six-file candidate are
unchanged. This probe deliberately kills its own process; it never takes a
caller-selected PID into `killProcess` and does not stop the service cleanly.

The receiver has no intent filter or separate process and requires the existing
platform `android.permission.DUMP` held by ADB shell. The app requests no new
permission. A privileged diagnostic caller may arm it; ordinary apps cannot.

After proving a persistent FGS and native background notification updates,
discover the app PID, UID, `/proc/<pid>/stat` field 22 and current correlation.
Generate a fresh cryptographically random 32-byte token, represented by 64
lowercase hex characters. Send these identical extras on two explicit commands:

```sh
adb -s "$serial" shell am broadcast \
  -n com.notifeeexample/.Issue81ProcessDeathReceiver \
  -a com.notifeeexample.ISSUE81_PROCESS_DEATH \
  --es operation arm --es token "$token" --es correlationId "$correlation" \
  --ei expectedPid "$pid" --ei expectedUid "$uid" --el expectedTicks "$ticks"
# Within 10 seconds, after the native ARMED marker and renewed identity checks:
adb -s "$serial" shell am broadcast \
  -n com.notifeeexample/.Issue81ProcessDeathReceiver \
  -a com.notifeeexample.ISSUE81_PROCESS_DEATH \
  --es operation fire --es token "$token" --es correlationId "$correlation" \
  --ei expectedPid "$pid" --ei expectedUid "$uid" --el expectedTicks "$ticks"
```

Commands without a matching active foreground notification are rejected. The
first accepted arm owns this process for its lifetime; a duplicate arm cannot
replace the token or extend expiry. Fire checks identity and correlation again,
consumes the authorization before the syscall, and emits `ISSUE81_DEATH
KILL_SELF` without exposing the token. Expired, duplicate, mismatched or old
process commands are rejected. Restarted processes inherit no authorization.

A successful broadcast return or marker alone is not proof of death. Preserve
full logcat, PID/start ticks, AMS service records and ExitInfo. A JS update
request or native notification readback does not prove a native START is still
unacknowledged. Do not count a receiver-triggered cold start as a framework
restart. Do not infer onStartCommand flags from Activity Intent flags.

Host checks: `python3 apps/smoke/__tests__/issue81DebugProcessDeath.test.py`.
Also inspect the built manifests and all DEX files: Debug must include the two
classes; Release must include neither. Debug requires Metro and differs from
production timing. No production recovery claim follows from this probe alone.
