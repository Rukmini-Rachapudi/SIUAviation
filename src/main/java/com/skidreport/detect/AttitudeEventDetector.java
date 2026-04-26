package com.skidreport.detect;

import com.skidreport.model.AttitudeEvent;
import com.skidreport.model.FlightRecord;
import com.skidreport.util.DateUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Generic 30-second-gap event detector for attitude triggers
 * (Bank, High Pitch, Low Pitch).
 *
 * USAGE:
 *   AttitudeEventDetector det = new AttitudeEventDetector(
 *       AttitudeEventDetector.BANK_TRIGGER,
 *       r -> r.roll);
 *   for (FlightRecord r : records) det.accept(r);
 *   List<AttitudeEvent> events = det.flush();
 *
 * RULES:
 *   - A trigger is any sample where {@link Trigger#isTriggered(double)} returns true.
 *   - An event opens at the first trigger sample.
 *   - The event stays alive while gap-since-last-trigger <= 30 seconds.
 *   - The event closes when 30 s pass with no further trigger; its endTime is the
 *     time of the most-recent trigger sample (not last_trigger + 30 s).
 *   - peakValue is the sample value with the largest absolute magnitude inside the
 *     event window (signed). For Bank this captures the worst left- or right-bank;
 *     for High Pitch this is the largest positive pitch; for Low Pitch the most-negative.
 */
public class AttitudeEventDetector {

    private static final long GAP_THRESHOLD_SECONDS = 30;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public interface Trigger {
        boolean isTriggered(double value);
    }

    public static final Trigger BANK_TRIGGER       = v -> v < -60.0 || v > 60.0;
    public static final Trigger HIGH_PITCH_TRIGGER = v ->                v >  30.0;
    public static final Trigger LOW_PITCH_TRIGGER  = v -> v < -30.0;

    private final String tail;
    private final Trigger trigger;
    private final ToDoubleFunction<FlightRecord> field;
    private final List<AttitudeEvent> events = new ArrayList<>();

    private AttitudeEvent open;
    private String openDate;
    private LocalTime openLastTriggerLT;

    public AttitudeEventDetector(String tail, Trigger trigger, ToDoubleFunction<FlightRecord> field) {
        this.tail = tail;
        this.trigger = trigger;
        this.field = field;
    }

    /** Feed one record into the detector. Order matters; records should be in time order. */
    public void accept(FlightRecord rec) {
        double v = field.applyAsDouble(rec);
        boolean fires = trigger.isTriggered(v);
        if (!fires) return;

        String time = DateUtils.normalizeTime(rec.time);
        LocalTime lt;
        try {
            lt = LocalTime.parse(time, HMS);
        } catch (Exception e) {
            return;
        }

        if (open == null) {
            startEvent(rec, time, lt, v);
            return;
        }

        if (!rec.date.equals(openDate)) {
            closeEvent();
            startEvent(rec, time, lt, v);
            return;
        }

        long gap = Duration.between(openLastTriggerLT, lt).getSeconds();
        if (gap < 0 || gap > GAP_THRESHOLD_SECONDS) {
            closeEvent();
            startEvent(rec, time, lt, v);
            return;
        }

        open.endTime = time;
        open.triggerCount++;
        if (Math.abs(v) > Math.abs(open.peakValue)) open.peakValue = v;
        openLastTriggerLT = lt;
    }

    /** Closes any event still open and returns the full list. Safe to call once at end. */
    public List<AttitudeEvent> flush() {
        if (open != null) closeEvent();
        return events;
    }

    private void startEvent(FlightRecord rec, String time, LocalTime lt, double v) {
        open = new AttitudeEvent();
        open.tail = tail;
        open.date = rec.date;
        open.startTime = time;
        open.endTime = time;
        open.peakValue = v;
        open.triggerCount = 1;
        openDate = rec.date;
        openLastTriggerLT = lt;
    }

    private void closeEvent() {
        try {
            LocalTime start = LocalTime.parse(open.startTime, HMS);
            open.durationSeconds = Math.max(0, Duration.between(start, openLastTriggerLT).getSeconds());
        } catch (Exception e) {
            open.durationSeconds = 0;
        }
        events.add(open);
        open = null;
        openDate = null;
        openLastTriggerLT = null;
    }
}
