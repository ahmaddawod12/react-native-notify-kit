package app.notifee.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Internal explicit receiver. Does not start a service or require React. */
public final class ForegroundServiceRunLimitReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ForegroundServiceRunLimitScheduler.ACTION.equals(intent.getAction()))
      return;
    ForegroundService.receiveAlarmPoc(intent);
  }
}
