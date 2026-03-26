package com.skidreport.model;

import com.skidreport.util.DateUtils;

/**
 * Accumulates all qualifying skid seconds within one (date, HH:MM) bucket.
 * Converts to a SkidEvent once all seconds for that minute are processed.
 */
public class SkidMinuteAccumulator {

    public final String date;
    public final String minuteTime;

    private double sumPitch, sumRoll, sumLatAc, sumIas, sumAlt;
    private int count;
    private int lowAltCount;

    public SkidMinuteAccumulator(String date, String minuteTime) {
        this.date       = date;
        this.minuteTime = minuteTime;
    }

    public void add(double pitch, double roll, double latAc,
                    double ias, double alt, boolean isLowAlt) {
        sumPitch += pitch;
        sumRoll  += roll;
        sumLatAc += latAc;
        sumIas   += ias;
        sumAlt   += alt;
        count++;
        if (isLowAlt) lowAltCount++;
    }

    public SkidEvent toSkidEvent() {
        SkidEvent ev       = new SkidEvent();
        ev.date            = date;
        ev.minuteTime      = minuteTime;
        ev.month           = DateUtils.monthName(date);
        ev.skidFrequency   = count;
        ev.lowAltFrequency = lowAltCount;
        ev.avgPitch        = round2(sumPitch / count);
        ev.avgRoll         = round2(sumRoll  / count);
        ev.avgLatAc        = round2(sumLatAc / count);
        ev.avgIas          = round2(sumIas   / count);
        ev.avgAlt          = round2(sumAlt   / count);
        return ev;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
