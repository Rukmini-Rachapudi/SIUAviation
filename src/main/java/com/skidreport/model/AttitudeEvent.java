package com.skidreport.model;

/**
 * One detected Bank / High-Pitch / Low-Pitch event.
 *
 * An event spans from the first trigger sample until the last trigger sample,
 * with the rule that any gap > 30 s between consecutive triggers closes the event.
 *
 * peakValue holds the signed extremum (most-positive for High Pitch / Right Bank,
 * most-negative for Low Pitch / Left Bank, largest absolute value for Bank).
 */
public class AttitudeEvent {
    public String tail;
    public String date;
    public String startTime;   // HH:MM:SS of first trigger
    public String endTime;     // HH:MM:SS of last trigger
    public long durationSeconds;
    public double peakValue;
    public int triggerCount;
}
