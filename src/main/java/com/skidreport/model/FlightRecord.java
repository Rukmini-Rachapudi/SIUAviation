package com.skidreport.model;

/**
 * One row from a skid CSV file.
 * Used by SkidReportGenerator.
 */
public class FlightRecord {
    public String date;
    public String time;
    public double pitch;
    public double roll;
    public double latAc;
    public double ias;
    public double alt;
}
