package com.shadowphoenix.dietlog.discipline;

import android.content.*;
import java.util.*;

public final class ReminderRescheduleReceiver extends BroadcastReceiver {
  private static final Set<String> ACTIONS =
      new HashSet<>(
          Arrays.asList(
              Intent.ACTION_BOOT_COMPLETED,
              Intent.ACTION_TIME_CHANGED,
              Intent.ACTION_TIMEZONE_CHANGED,
              Intent.ACTION_MY_PACKAGE_REPLACED,
              "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"));

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null && ACTIONS.contains(intent.getAction()))
      ReminderScheduler.receive(this, context, () -> ReminderScheduler.rescheduleAll(context));
  }
}
