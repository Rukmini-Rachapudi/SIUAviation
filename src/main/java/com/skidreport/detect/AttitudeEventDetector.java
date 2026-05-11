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
 * Single-field event detector for attitude triggers (Bank, High Pitch, Low Pitch).
 *
 * RULES:
 *   - A trigger is any record where {@link Trigger#isTriggered(double)} returns true.
 *   - An event opens at the first triggering record.
 *   - The event extends only while consecutive records keep triggering.
 *   - Any single non-triggering record closes the current event.
 *   - Records missing from the CSV are transparent: the next record present in
 *     the file decides whether the event continues. A non-trigger present in the
 *     file is what ends an event, never an absent timestamp.
 *   - A change of date also closes the open event.
 *
 *   peakValue is the signed sample value with the largest absolute magnitude
 *   inside the event window. For Bank this captures the worst left- or
 *   right-bank; for High Pitch this is the largest positive pitch; for Low
 *   Pitch the most-negative.
 */
public class AttitudeEventDetector {

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

    /** Feed one record into the detector. Order matters; records must be in time order. */
    public void accept(FlightRecord rec) {
        double v = field.applyAsDouble(rec);
        boolean fires = trigger.isTriggered(v);

        if (open == null) {
            if (fires) {
                LocalTime lt = parseTime(rec.time);
                if (lt != null) startEvent(rec, DateUtils.normalizeTime(rec.time), lt, v);
            }
            return;
        }

        if (!fires) {
            closeEvent();
            return;
        }

        if (!rec.date.equals(openDate)) {
            closeEvent();
            LocalTime lt = parseTime(rec.time);
            if (lt != null) startEvent(rec, DateUtils.normalizeTime(rec.time), lt, v);
            return;
        }

        LocalTime lt = parseTime(rec.time);
        if (lt == null) return;

        open.endTime = DateUtils.normalizeTime(rec.time);
        open.triggerCount++;
        if (Math.abs(v) > Math.abs(open.peakValue)) open.peakValue = v;
        openLastTriggerLT = lt;
    }

    /** Closes any event still open and returns the full list. Safe to call once at end. */
    public List<AttitudeEvent> flush() {
        if (open != null) closeEvent();
        return events;
    }

    private LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(DateUtils.normalizeTime(time), HMS);
        } catch (Exception e) {
            return null;
        }
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
            // Wall-clock span inclusive of both endpoints: a single-second
            // event spans 1 s, a 5-second event spans 5 s.
            open.durationSeconds = Math.max(1, Duration.between(start, openLastTriggerLT).getSeconds() + 1);
        } catch (Exception e) {
            open.durationSeconds = open.triggerCount;
        }
        events.add(open);
        open = null;
        openDate = null;
        openLastTriggerLT = null;
    }
}
