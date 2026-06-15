package com.skidreport;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R3 separation: a reportable near-miss is closer than 500 ft AND no closer
 * than the 1 ft duplicate-track floor. The floor answers "how can the minimum
 * distance be 0 feet?" -- a 0 ft reading is the same GPS track duplicated
 * across two tails, never two real aircraft, so it must be excluded.
 */
public class NearMissSeparationTest {

    @Test
    public void typicalNearMiss_included() {
        assertTrue(NearMissReportGenerator.isNearMissSeparation(250.0));
        assertTrue(NearMissReportGenerator.isNearMissSeparation(1.0));   // floor is inclusive
        assertTrue(NearMissReportGenerator.isNearMissSeparation(499.99));
    }

    @Test
    public void zeroFeetDuplicateTrack_excluded() {
        assertFalse("0 ft is a duplicate-track artifact, not a near miss",
                NearMissReportGenerator.isNearMissSeparation(0.0));
        assertFalse("sub-foot reading excluded",
                NearMissReportGenerator.isNearMissSeparation(0.5));
    }

    @Test
    public void atOrBeyondThreshold_excluded() {
        assertFalse("500 ft is not < 500", NearMissReportGenerator.isNearMissSeparation(500.0));
        assertFalse(NearMissReportGenerator.isNearMissSeparation(1200.0));
    }
}
