package com.skidreport.model;

/**
 * One near-miss event row stored in SQLite and written to Excel.
 * Represents one second where two aircraft were within 500 feet of each other.
 */
public class NearMissEvent {
    public String date;
    public String time;
    public String tail1;
    public String tail2;
    public double lat1;
    public double lon1;
    public double alt1;
    public double ias1;
    public double lat2;
    public double lon2;
    public double alt2;
    public double ias2;
    public double distanceFt;
    public String yearMonth;   // YYYY-MM for grouping
}
