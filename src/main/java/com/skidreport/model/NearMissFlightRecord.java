package com.skidreport.model;

/**
 * One row from a near-miss CSV file.
 * Used by NearMissReportGenerator.
 */
public class NearMissFlightRecord {
    public String tail;
    public String date;
    public String time;   // normalized to HH:MM:SS
    public double lat;
    public double lon;
    public double alt;    // AltMSL in feet
    public double ias;    // knots
    public double rpm;    // E1 RPM
}
