package com.shadowphoenix.dietlog.discipline;

import android.content.*;

public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null && "ALARM".equals(intent.getAction()))
      ReminderScheduler.receive(this, context, () -> ReminderScheduler.fire(context, intent));
  }
}
