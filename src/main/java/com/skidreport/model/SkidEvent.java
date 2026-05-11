package com.skidreport.model;

/**
 * One detected skid event.
 *
 * An event opens at the first record where the skid condition holds and
 * extends only while consecutive records keep satisfying it. Any single
 * non-triggering record (or a date change) closes the event. Missing CSV
 * rows are transparent.
 *
 * The "low altitude" flag is true if any record inside the event window
 * also satisfied: alt < 1400 AND (decreasing IAS OR increasing pitch) vs.
 * the immediately preceding record.
 */
public class SkidEvent {
    public String tail;
    public String date;
    public String month;
    public String startTime;   // HH:MM:SS of first trigger
    public String endTime;     // HH:MM:SS of last trigger
    public long durationSeconds;
    public int triggerCount;   // seconds in this event

    public double avgPitch;
    public double avgRoll;
    public double avgLatAc;
    public double avgIas;
    public double avgAlt;

    public double peakRoll;    // signed extremum (largest |roll|)
    public double peakLatAc;   // signed extremum (largest |latAc|)

    public boolean isLowAlt;
}
