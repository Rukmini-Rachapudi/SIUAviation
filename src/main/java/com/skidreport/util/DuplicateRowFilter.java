package com.skidreport.util;

import com.skidreport.model.FlightRecord;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * DuplicateRowFilter
 *
 * Drops rows an aircraft has already logged, so a flight that appears in more
 * than one CSV -- the same log copied into two month folders, or two logs that
 * overlap -- is only fed to the skid and bank/pitch detectors once.
 *
 * A row is a repeat when its (Lcl Date, Lcl Time) second was already seen for
 * this aircraft. To keep memory flat, seen seconds are only remembered for the
 * current log day (the yymmdd in log_yymmdd_hhmmss_*.csv), so callers must feed
 * files sorted by name: copies of one log then arrive back to back.
 *
 * Use one instance per aircraft.
 */
public class DuplicateRowFilter {

    private final Set<String> seen = new HashSet<>();
    private String currentDay;
    private long skipped;

    /** Call before streaming each file; resets memory when the log day changes. */
    public void startFile(File csv) {
        String day = logDay(csv.getName());
        if (!day.equals(currentDay)) {
            seen.clear();
            currentDay = day;
        }
    }

    /** True the first time this (date, second) is seen; false for a repeat. */
    public boolean firstSighting(FlightRecord rec) {
        if (seen.add(rec.date + " " + DateUtils.normalizeTime(rec.time))) return true;
        skipped++;
        return false;
    }

    /** Number of repeated rows dropped so far. */
    public long skipped() {
        return skipped;
    }

    // ------------------------------------------------------------------------
    // logDay: "log_240305_193402_KMDH.csv" -> "240305"
    // Names that don't follow the Garmin pattern are their own scope, so they
    // are only de-duplicated against themselves.
    // ------------------------------------------------------------------------
    static String logDay(String fileName) {
        if (fileName.length() >= 10 && fileName.startsWith("log_")) {
            String day = fileName.substring(4, 10);
            if (day.chars().allMatch(Character::isDigit)) return day;
        }
        return fileName;
    }
}
