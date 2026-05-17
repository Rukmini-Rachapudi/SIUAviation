package com.skidreport.model;

/**
 * One aggregated near-miss event.
 *
 * An event opens on the first second a pair of aircraft is within 500 ft (3D)
 * and extends only while consecutive seconds (t, t+1, t+2, ...) keep
 * qualifying. Any gap -- a non-consecutive second, a second where the pair is
 * no longer within 500 ft, or a date boundary -- closes the event.
 *
 * The lat/lon/alt/ias fields capture the snapshot of each aircraft at the
 * second of closest approach during the event window. minDistanceFt is the
 * smallest 3D distance observed inside that window.
 */
public class NearMissEvent {
    public String date;
    public String startTime;        // HH:MM:SS of first qualifying second
    public long   durationSeconds;  // count of consecutive qualifying seconds
    public String tail1;
    public String tail2;

    // Closest-approach snapshot (the second within the event with smallest distance)
    public double minDistanceFt;
    public double lat1;
    public double lon1;
    public double alt1;
    public double ias1;
    public double lat2;
    public double lon2;
    public double alt2;
    public double ias2;

    public String yearMonth;        // YYYY-MM for grouping
}
