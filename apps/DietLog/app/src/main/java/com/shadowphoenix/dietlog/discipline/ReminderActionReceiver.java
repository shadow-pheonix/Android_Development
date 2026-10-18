package com.shadowphoenix.dietlog.discipline;

import android.content.*;

public final class ReminderActionReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null
        && ("DONE".equals(intent.getAction()) || "SNOOZE".equals(intent.getAction())))
      ReminderScheduler.receive(this, context, () -> ReminderScheduler.act(context, intent));
  }
}
