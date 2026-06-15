package com.skidreport.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * R3 geometry tests: the near-miss test is a 500-ft SPHERE (true straight-line
 * 3D distance), not a per-axis box. Direction must not matter.
 */
public class GeoUtilsTest {

    private static final double LAT = 37.7764626;   // KMDH area
    private static final double LON = -89.2536926;
    private static final double NEAR_MISS_FEET = 500.0;

    /** Pure horizontal distance (ft) for a north/south latitude delta. */
    private static double horiz(double dLatDeg) {
        return GeoUtils.distance3dFeet(LAT, LON, 0.0, LAT + dLatDeg, LON, 0.0);
    }

    @Test
    public void pureVertical_480_triggers() {
        double d = GeoUtils.distance3dFeet(LAT, LON, 1000.0, LAT, LON, 1480.0);
        assertEquals(480.0, d, 1e-6);
        assertTrue("pure vertical 480 ft must be a near-miss", d < NEAR_MISS_FEET);
    }

    @Test
    public void pureHorizontal_480_triggers() {
        double dLat = 480.0 / 364813.0;            // ~480 ft north
        double h = horiz(dLat);
        assertEquals(480.0, h, 1.0);
        double d = GeoUtils.distance3dFeet(LAT, LON, 1200.0, LAT + dLat, LON, 1200.0);
        assertTrue("pure horizontal 480 ft must be a near-miss", d < NEAR_MISS_FEET);
    }

    @Test
    public void boxNotSphere_450h_450v_doesNotTrigger() {
        // Each axis < 500 (a box test would fire) but the true 3D distance
        // (~636 ft) is >= 500, so a SPHERE test must NOT fire.
        double dLat = 450.0 / 364813.0;
        double h = horiz(dLat);                      // ~450 ft horizontal
        double d = GeoUtils.distance3dFeet(LAT, LON, 1000.0, LAT + dLat, LON, 1450.0);
        assertEquals(Math.sqrt(h * h + 450.0 * 450.0), d, 1e-6);
        assertTrue("450h + 450v (~636 ft) must NOT be a near-miss", d >= NEAR_MISS_FEET);
    }

    @Test
    public void pureDiagonal_480_triggers() {
        // Horizontal 375 ft + vertical 300 ft -> true ~480 ft on a diagonal.
        double dLat = 375.0 / 364813.0;
        double h = horiz(dLat);
        double d = GeoUtils.distance3dFeet(LAT, LON, 1000.0, LAT + dLat, LON, 1300.0);
        assertEquals(Math.sqrt(h * h + 300.0 * 300.0), d, 1e-6);
        assertTrue("true diagonal 480 ft must be a near-miss", d < NEAR_MISS_FEET);
    }
}
