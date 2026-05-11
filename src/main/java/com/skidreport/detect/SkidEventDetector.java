package com.skidreport.detect;

import com.skidreport.model.FlightRecord;
import com.skidreport.model.SkidEvent;
import com.skidreport.util.DateUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects skid events from a stream of FlightRecords.
 *
 * Skid condition (per record):
 *   (roll < -10 AND latAc >  0.2) OR (roll >  10 AND latAc < -0.2)
 *
 * Event rule:
 *   - Event opens at the first record where the condition holds.
 *   - Event extends only while consecutive records keep satisfying it.
 *   - Any single non-triggering record (or a date change) closes the event.
 *   - Missing CSV rows are transparent: only records that are actually present
 *     and fail the condition end an event.
 *
 * Low-altitude flag:
 *   The event is flagged low-altitude if ANY record inside the event window
 *   satisfies: alt < 1400 AND (rec.ias < prev.ias OR rec.pitch > prev.pitch),
 *   where prev is the record immediately preceding rec in the input stream
 *   (whether or not prev was itself a skid record).
 */
public class SkidEventDetector {

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String tail;
    private final List<SkidEvent> events = new ArrayList<>();

    private FlightRecord prevRec;

    private SkidEvent open;
    private LocalTime openLastTriggerLT;
    private double sumPitch, sumRoll, sumLatAc, sumIas, sumAlt;

    public SkidEventDetector(String tail) {
        this.tail = tail;
    }

    public static boolean isSkid(FlightRecord r) {
        return (r.roll < -10 && r.latAc >  0.2)
            || (r.roll >  10 && r.latAc < -0.2);
    }

    /** Feed one record. Records must be in time order. */
    public void accept(FlightRecord rec) {
        boolean fires  = isSkid(rec);
        boolean lowAlt = fires && prevRec != null
                      && rec.alt < 1400
                      && (rec.ias < prevRec.ias || rec.pitch > prevRec.pitch);

        if (open == null) {
            if (fires) {
                LocalTime lt = parseTime(rec.time);
                if (lt != null) startEvent(rec, lt, lowAlt);
            }
            prevRec = rec;
            return;
        }

        if (!fires) {
            closeEvent();
            prevRec = rec;
            return;
        }

        if (!rec.date.equals(open.date)) {
            closeEvent();
            LocalTime lt = parseTime(rec.time);
            if (lt != null) startEvent(rec, lt, lowAlt);
            prevRec = rec;
            return;
        }

        LocalTime lt = parseTime(rec.time);
        if (lt != null) {
            extendEvent(rec, lt, lowAlt);
        }
        prevRec = rec;
    }

    /** Close any open event and return the full list. Safe to call once at end. */
    public List<SkidEvent> flush() {
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

    private void startEvent(FlightRecord rec, LocalTime lt, boolean lowAlt) {
        open = new SkidEvent();
        open.tail        = tail;
        open.date        = rec.date;
        open.month       = DateUtils.monthName(rec.date);
        open.startTime   = DateUtils.normalizeTime(rec.time);
        open.endTime     = open.startTime;
        open.peakRoll    = rec.roll;
        open.peakLatAc   = rec.latAc;
        open.triggerCount = 1;
        open.isLowAlt    = lowAlt;

        sumPitch = rec.pitch;
        sumRoll  = rec.roll;
        sumLatAc = rec.latAc;
        sumIas   = rec.ias;
        sumAlt   = rec.alt;

        openLastTriggerLT = lt;
    }

    private void extendEvent(FlightRecord rec, LocalTime lt, boolean lowAlt) {
        open.endTime = DateUtils.normalizeTime(rec.time);
        open.triggerCount++;
        if (Math.abs(rec.roll)  > Math.abs(open.peakRoll))  open.peakRoll  = rec.roll;
        if (Math.abs(rec.latAc) > Math.abs(open.peakLatAc)) open.peakLatAc = rec.latAc;
        if (lowAlt) open.isLowAlt = true;

        sumPitch += rec.pitch;
        sumRoll  += rec.roll;
        sumLatAc += rec.latAc;
        sumIas   += rec.ias;
        sumAlt   += rec.alt;

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

        int n = open.triggerCount;
        open.avgPitch = round2(sumPitch / n);
        open.avgRoll  = round2(sumRoll  / n);
        open.avgLatAc = round2(sumLatAc / n);
        open.avgIas   = round2(sumIas   / n);
        open.avgAlt   = round2(sumAlt   / n);
        open.peakRoll  = round2(open.peakRoll);
        open.peakLatAc = round2(open.peakLatAc);

        events.add(open);
        open = null;
        openLastTriggerLT = null;
        sumPitch = sumRoll = sumLatAc = sumIas = sumAlt = 0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
