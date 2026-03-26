package com.skidreport.model;

/**
 * One output row in the skid Excel report.
 * Represents one unique (date, HH:MM) skid minute.
 */
public class SkidEvent {
    public String date;
    public String minuteTime;
    public String month;
    public double avgPitch;
    public double avgRoll;
    public double avgLatAc;
    public double avgIas;
    public double avgAlt;
    public int skidFrequency;    // number of qualifying seconds in this minute
    public int lowAltFrequency;  // number of qualifying low-alt seconds in this minute
}
