package com.shadowphoenix.dietlog.discipline;

import org.json.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable, bounded schedule settings. Date ranges include both endpoints. */
public final class ReminderPlan {
    public enum Mode {
        WEEKDAYS,
        INTERVAL,
        DATES
    }

    public final Mode mode;
    public final LocalDate start, end;
    public final int intervalDays, followupMinutes;
    public final SortedSet<LocalDate> dates;
    public final SortedSet<LocalTime> extraTimes;
    public final Map<LocalTime, String> timeIds;

    public ReminderPlan(
            Mode mode,
            LocalDate start,
            LocalDate end,
            int intervalDays,
            Collection<LocalDate> dates,
            Collection<LocalTime> extraTimes,
            int followupMinutes) {
        this(
                mode,
                start,
                end,
                intervalDays,
                dates,
                extraTimes,
                followupMinutes,
                Collections.emptyMap());
    }

    public ReminderPlan(
            Mode mode,
            LocalDate start,
            LocalDate end,
            int intervalDays,
            Collection<LocalDate> dates,
            Collection<LocalTime> extraTimes,
            int followupMinutes,
            Map<LocalTime, String> retainedIds) {
        this.mode = Objects.requireNonNull(mode);
        if (end != null && start != null && end.isBefore(start))
            throw new IllegalArgumentException("End date must be on or after start date");
        if (intervalDays < 1 || intervalDays > 365)
            throw new IllegalArgumentException("Use an interval of 1–365 days");
        if (mode == Mode.INTERVAL && start == null)
            throw new IllegalArgumentException("Choose a start date for an interval schedule");
        if (followupMinutes < 5 || followupMinutes > 1440)
            throw new IllegalArgumentException(
                    "Follow-up must be 5–1440 minutes after each occurrence");
        TreeSet<LocalDate> selected = new TreeSet<>(dates);
        if (selected.size() > 366 || (mode == Mode.DATES && selected.isEmpty()))
            throw new IllegalArgumentException("Choose 1–366 scheduled dates");
        for (LocalDate date : selected)
            if ((start != null && date.isBefore(start)) || (end != null && date.isAfter(end)))
                throw new IllegalArgumentException("Selected dates must be within the date range");
        TreeSet<LocalTime> times = new TreeSet<>(extraTimes);
        if (times.size() > 7)
            throw new IllegalArgumentException("Keep at most eight times per reminder");
        for (LocalTime time : times)
            if (time.getSecond() != 0 || time.getNano() != 0)
                throw new IllegalArgumentException("Use hour and minute times");
        this.start = start;
        this.end = end;
        this.intervalDays = intervalDays;
        this.followupMinutes = followupMinutes;
        this.dates = Collections.unmodifiableSortedSet(selected);
        this.extraTimes = Collections.unmodifiableSortedSet(times);
        Map<LocalTime, String> identities = new TreeMap<>();
        Set<String> used = new HashSet<>();
        for (LocalTime time : times)
            if (retainedIds.containsKey(time)) {
                String id = retainedIds.get(time);
                if (id == null || !id.matches("[a-z0-9]{1,8}") || !used.add(id))
                    throw new IllegalArgumentException("Invalid scheduled time identity");
                identities.put(time, id);
            }
        for (LocalTime time : times)
            if (!identities.containsKey(time)) {
                String id = String.valueOf(time.getHour() * 60 + time.getMinute());
                while (used.contains(id)) id = UUID.randomUUID().toString().substring(0, 8);
                used.add(id);
                identities.put(time, id);
            }
        this.timeIds = Collections.unmodifiableMap(identities);
    }

    public ReminderPlan moveExtraTime(LocalTime from, LocalTime to) {
        if (!extraTimes.contains(from) || extraTimes.contains(to))
            throw new IllegalArgumentException("That time is unavailable");
        TreeSet<LocalTime> times = new TreeSet<>(extraTimes);
        times.remove(from);
        times.add(to);
        Map<LocalTime, String> ids = new HashMap<>(timeIds);
        String id = ids.remove(from);
        ids.put(to, id);
        return new ReminderPlan(mode, start, end, intervalDays, dates, times, followupMinutes, ids);
    }

    public static ReminderPlan daily() {
        return new ReminderPlan(
                Mode.WEEKDAYS, null, null, 1, Collections.emptyList(), Collections.emptyList(), 60);
    }

    public boolean occurs(LocalDate date, int daysMask) {
        if ((start != null && date.isBefore(start)) || (end != null && date.isAfter(end)))
            return false;
        switch (mode) {
            case DATES:
                return dates.contains(date);
            case INTERVAL:
                return ChronoUnit.DAYS.between(start, date) % intervalDays == 0;
            default:
                return (daysMask & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
        }
    }

    JSONObject json() {
        JSONObject j = new JSONObject();
        ReminderStore.put(j, "mode", mode.name());
        ReminderStore.put(j, "start", start == null ? "" : start.toString());
        ReminderStore.put(j, "end", end == null ? "" : end.toString());
        ReminderStore.put(j, "interval", intervalDays);
        ReminderStore.put(j, "followup", followupMinutes);
        JSONArray selected = new JSONArray(), times = new JSONArray();
        for (LocalDate d : dates) selected.put(d.toString());
        for (LocalTime t : extraTimes) times.put(t.toString());
        ReminderStore.put(j, "dates", selected);
        ReminderStore.put(j, "times", times);
        JSONObject identities = new JSONObject();
        for (Map.Entry<LocalTime, String> entry : timeIds.entrySet())
            ReminderStore.put(identities, entry.getKey().toString(), entry.getValue());
        ReminderStore.put(j, "timeIds", identities);
        return j;
    }

    static ReminderPlan parse(JSONObject j) {
        if (j == null) return daily();
        List<LocalDate> dates = new ArrayList<>();
        List<LocalTime> times = new ArrayList<>();
        JSONArray ds = j.optJSONArray("dates"), ts = j.optJSONArray("times");
        if (ds != null)
            for (int i = 0; i < ds.length(); i++) dates.add(LocalDate.parse(ds.optString(i)));
        if (ts != null)
            for (int i = 0; i < ts.length(); i++) times.add(LocalTime.parse(ts.optString(i)));
        String start = j.optString("start"), end = j.optString("end");
        Map<LocalTime, String> ids = new HashMap<>();
        JSONObject encodedIds = j.optJSONObject("timeIds");
        if (encodedIds != null) {
            if (encodedIds.length() > 7 || encodedIds.length() != times.size())
                throw new IllegalArgumentException("Too many scheduled time identities");
            for (Iterator<String> it = encodedIds.keys(); it.hasNext(); ) {
                String key = it.next();
                LocalTime time = LocalTime.parse(key);
                if (!times.contains(time))
                    throw new IllegalArgumentException("Invalid scheduled time identity");
                ids.put(time, encodedIds.optString(key));
            }
        }
        return new ReminderPlan(
                Mode.valueOf(j.optString("mode", "WEEKDAYS")),
                start.isEmpty() ? null : LocalDate.parse(start),
                end.isEmpty() ? null : LocalDate.parse(end),
                j.optInt("interval", 1),
                dates,
                times,
                j.optInt("followup", 60),
                ids);
    }
}
