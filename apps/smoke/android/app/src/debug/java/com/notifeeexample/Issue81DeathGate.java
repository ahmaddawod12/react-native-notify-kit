package com.notifeeexample;

/** In-memory, process-lifetime admission. Never persists/re-arms after death. */
final class Issue81DeathGate {
  static final class Identity {
    final int pid, uid;
    final long ticks;
    final String process;
    Identity(int pid, int uid, long ticks, String process) {
      this.pid = pid; this.uid = uid; this.ticks = ticks; this.process = process;
    }
    boolean valid() {
      return pid > 1 && uid > 0 && ticks > 0 && "com.notifeeexample".equals(process);
    }
    boolean same(Identity other) {
      return other != null && valid() && other.valid() && pid == other.pid
          && uid == other.uid && ticks == other.ticks && process.equals(other.process);
    }
  }
  private String token;
  private Identity owner;
  private long armedAt, expiresAt;
  private boolean consumed;

  synchronized boolean arm(String supplied, Identity expected, Identity actual, long now, long ttl) {
    if (token != null || consumed || supplied == null || !supplied.matches("[a-f0-9]{64}")
        || expected == null || !expected.same(actual) || now < 0 || ttl <= 0 || ttl > 10000
        || now > Long.MAX_VALUE - ttl) return false;
    token = supplied; owner = actual; armedAt = now; expiresAt = now + ttl;
    return true;
  }

  synchronized boolean consume(String supplied, Identity actual, long now) {
    if (consumed || token == null || !token.equals(supplied) || !owner.same(actual)
        || now < armedAt || now >= expiresAt) return false;
    consumed = true; // Claim before returning, even with competing callers.
    return true;
  }
}
