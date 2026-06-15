package com.skidreport.model;

/**
 * One row from a near-miss CSV file.
 * Used by NearMissReportGenerator.
 *
 * date/time are already corrected to true Central (America/Chicago) local time
 * by the parser -- the raw Lcl values and UTCOfst are dropped after correction.
 */
public class NearMissFlightRecord {
    public String tail;
    public String date;   // corrected local date, yyyy-MM-dd
    public String time;   // corrected local time, HH:MM:SS
    public double lat;
    public double lon;
    public double altMsl; // AltMSL (ft) -- barometric MSL, used only for the altitude floor filter
    public double altGps; // AltGPS (ft) -- WGS-84 GPS altitude, used for the 3D separation distance
    public double ias;    // knots
    public double rpm;    // E1 RPM
}
