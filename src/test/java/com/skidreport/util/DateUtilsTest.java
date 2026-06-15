package com.skidreport.util;

import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;

/**
 * R1 timezone-correction tests.
 *
 * Verifies that (Lcl Date, Lcl Time, UTCOfst) is re-expressed in true Central
 * (America/Chicago) local time, reproducing the hand-derived validation table
 * across all four DST boundary periods, plus the two worked examples and the
 * cross-offset alignment that is the whole point of the rework.
 */
public class DateUtilsTest {

    /** Corrects to Central and renders as "yyyy-MM-dd HH:mm:ss". */
    private static String corr(String date, String time, String offset) {
        LocalDateTime ldt = DateUtils.correctToCentral(date, time, offset);
        return ldt.toLocalDate() + " " + DateUtils.toHms(ldt.toLocalTime());
    }

    // -- The two worked examples from R1 -------------------------------------

    @Test
    public void workedExample_winter_utc() {
        // 2025-01-15 12:00:00 +00:00 -> CST (UTC-6) -> 06:00:00
        assertEquals("2025-01-15 06:00:00", corr("2025-01-15", "12:00:00", "+00:00"));
    }

    @Test
    public void workedExample_summer_minus6() {
        // 2025-07-01 12:00:00 -06:00 -> CDT (UTC-5) -> 13:00:00
        assertEquals("2025-07-01 13:00:00", corr("2025-07-01", "12:00:00", "-06:00"));
    }

    // -- Validation table: CST periods (offset 0 -> -6, -5 -> -1, -6 -> 0) ----

    @Test
    public void cstPeriod_2024_2025() {
        // 2024-11-03 -> before 2025-03-09 (CST)
        assertEquals("2024-12-15 06:00:00", corr("2024-12-15", "12:00:00", "+00:00"));
        assertEquals("2024-12-15 11:00:00", corr("2024-12-15", "12:00:00", "-05:00"));
        assertEquals("2024-12-15 12:00:00", corr("2024-12-15", "12:00:00", "-06:00"));
    }

    @Test
    public void cstPeriod_2025_2026() {
        // 2025-11-02 -> before 2026-03-08 (CST)
        assertEquals("2025-12-15 06:00:00", corr("2025-12-15", "12:00:00", "+00:00"));
        assertEquals("2025-12-15 11:00:00", corr("2025-12-15", "12:00:00", "-05:00"));
        assertEquals("2025-12-15 12:00:00", corr("2025-12-15", "12:00:00", "-06:00"));
    }

    // -- Validation table: CDT periods (offset 0 -> -5, -5 -> 0, -6 -> +1) ----

    @Test
    public void cdtPeriod_2025() {
        // 2025-03-09 onward (CDT)
        assertEquals("2025-06-15 07:00:00", corr("2025-06-15", "12:00:00", "+00:00"));
        assertEquals("2025-06-15 12:00:00", corr("2025-06-15", "12:00:00", "-05:00"));
        assertEquals("2025-06-15 13:00:00", corr("2025-06-15", "12:00:00", "-06:00"));
    }

    @Test
    public void cdtPeriod_2026() {
        // 2026-03-08 onward (CDT)
        assertEquals("2026-06-01 07:00:00", corr("2026-06-01", "12:00:00", "+00:00"));
        assertEquals("2026-06-01 12:00:00", corr("2026-06-01", "12:00:00", "-05:00"));
        assertEquals("2026-06-01 13:00:00", corr("2026-06-01", "12:00:00", "-06:00"));
    }

    // -- Pre-2024 data the table doesn't cover (America/Chicago still right) --

    @Test
    public void pre2024_stillCorrect() {
        // 2023-05-03 (CDT) 12:29:53 +00:00 -> 07:29:53 (matches the real sample log)
        assertEquals("2023-05-03 07:29:53", corr("2023-05-03", "12:29:53", "+00:00"));
    }

    // -- Date shift across midnight -----------------------------------------

    @Test
    public void midnightShiftsToPreviousDay() {
        // UTC 05:00 in winter -> CST 23:00 the previous Central day
        assertEquals("2025-01-14 23:00:00", corr("2025-01-15", "05:00:00", "+00:00"));
    }

    // -- Cross-offset alignment (the central bug the rework fixes) -----------

    @Test
    public void crossOffsetSameInstantAligns() {
        // Same real instant, two different UTCOfst labels with correspondingly
        // different Lcl Times -> identical corrected (date, second).
        String a = corr("2025-07-01", "12:00:00", "+00:00"); // 12:00Z
        String b = corr("2025-07-01", "07:00:00", "-05:00"); // also 12:00Z
        assertEquals(a, b);
        assertEquals("2025-07-01 07:00:00", a);
    }
}
