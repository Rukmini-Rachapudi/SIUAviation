package com.skidreport.model;

/**
 * One aircraft's position and state at a specific second.
 * Used during near-miss pairwise comparison.
 */
public class AircraftSnapshot {
    public String tail;
    public double lat;
    public double lon;
    public double alt;
    public double ias;
}
