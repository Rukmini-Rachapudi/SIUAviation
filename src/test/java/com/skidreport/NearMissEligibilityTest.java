package com.skidreport;

import com.skidreport.model.NearMissFlightRecord;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R2 eligibility: a row qualifies only if IAS > 45, E1 RPM > 0, and
 * AltMSL > 1000. In particular, ground/runway rows must never qualify so they
 * can never appear in any near-miss event (ground-exclusion acceptance test).
 */
public class NearMissEligibilityTest {

    private static NearMissFlightRecord rec(double ias, double rpm, double altMsl) {
        NearMissFlightRecord r = new NearMissFlightRecord();
        r.ias = ias;
        r.rpm = rpm;
        r.altMsl = altMsl;
        r.altGps = altMsl;   // value irrelevant to eligibility
        r.lat = 37.78;
        r.lon = -89.25;
        return r;
    }

    @Test
    public void airborneFastEngineOn_qualifies() {
        assertTrue(NearMissReportGenerator.qualifies(rec(60.0, 2000.0, 1500.0)));
    }

    @Test
    public void runwayAltitude_excluded() {
        // KMDH field ~411 ft MSL: taxi / takeoff roll never qualifies
        assertFalse(NearMissReportGenerator.qualifies(rec(60.0, 2000.0, 411.0)));
    }

    @Test
    public void altMslFloorIsStrict_at1000_excluded() {
        assertFalse(NearMissReportGenerator.qualifies(rec(60.0, 2000.0, 1000.0)));
        assertTrue(NearMissReportGenerator.qualifies(rec(60.0, 2000.0, 1000.1)));
    }

    @Test
    public void slowOrEngineOff_excluded() {
        assertFalse("IAS == 45 is not > 45", NearMissReportGenerator.qualifies(rec(45.0, 2000.0, 1500.0)));
        assertFalse("engine off", NearMissReportGenerator.qualifies(rec(60.0, 0.0, 1500.0)));
    }
}
