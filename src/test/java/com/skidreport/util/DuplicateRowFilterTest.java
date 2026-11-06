package com.skidreport.util;

import com.skidreport.model.FlightRecord;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The same flight logged in two CSVs (e.g. a log copied into two month folders)
 * must only reach the skid / bank-pitch detectors once.
 */
public class DuplicateRowFilterTest {

    private static FlightRecord rec(String date, String time) {
        FlightRecord r = new FlightRecord();
        r.date = date;
        r.time = time;
        return r;
    }

    @Test
    public void copiedLogIsDroppedEntirely() {
        DuplicateRowFilter f = new DuplicateRowFilter();

        f.startFile(new File("24-03/log_240305_193402_KMDH.csv"));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:34:02")));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:34:03")));

        f.startFile(new File("24-07/log_240305_193402_KMDH.csv"));
        assertFalse(f.firstSighting(rec("2024-03-05", "19:34:02")));
        assertFalse(f.firstSighting(rec("2024-03-05", "19:34:03")));
        assertEquals(2, f.skipped());
    }

    @Test
    public void overlappingLogsKeepOnlyTheNewSeconds() {
        DuplicateRowFilter f = new DuplicateRowFilter();

        f.startFile(new File("log_240305_193402_KMDH.csv"));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:40:00")));

        f.startFile(new File("log_240305_193955_KMDH.csv"));
        assertFalse(f.firstSighting(rec("2024-03-05", "19:40:00")));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:40:01")));
    }

    @Test
    public void hhmmAndHhmmssAreTheSameSecond() {
        DuplicateRowFilter f = new DuplicateRowFilter();
        f.startFile(new File("log_240305_193402_KMDH.csv"));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:40")));
        assertFalse(f.firstSighting(rec("2024-03-05", "19:40:00")));
    }

    @Test
    public void memoryResetsOnANewLogDay() {
        DuplicateRowFilter f = new DuplicateRowFilter();
        f.startFile(new File("log_240305_193402_KMDH.csv"));
        assertTrue(f.firstSighting(rec("2024-03-05", "19:40:00")));

        f.startFile(new File("log_240306_101500_KMDH.csv"));
        // Different log day: earlier seconds are forgotten (bounded memory).
        assertTrue(f.firstSighting(rec("2024-03-05", "19:40:00")));
    }

    @Test
    public void logDayFromFileName() {
        assertEquals("240305", DuplicateRowFilter.logDay("log_240305_193402_KMDH.csv"));
        assertEquals("odd.csv", DuplicateRowFilter.logDay("odd.csv"));
        assertEquals("log_abcdef_x.csv", DuplicateRowFilter.logDay("log_abcdef_x.csv"));
    }
}
