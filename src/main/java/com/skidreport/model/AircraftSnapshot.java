package com.skidreport.model;

/**
 * One aircraft's position and state at a specific second.
 * Used during near-miss pairwise comparison.
 */
public class AircraftSnapshot {
    public String tail;   // canonical tail (see TailNumbers)
    public double lat;
    public double lon;
    public double alt;    // AltMSL (ft) -- barometric MSL altitude, used for 3D separation distance
    public double ias;
}
