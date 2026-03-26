package com.skidreport.util;

/**
 * DateUtils
 *
 * All date and time helper methods shared across skid and near-miss processing.
 */
public class DateUtils {

    private static final String[] MONTH_NAMES = {
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
    };

    // ------------------------------------------------------------------------
    // toHHMM: "HH:MM:SS" -> "HH:MM"
    // Used in skid reports to group by minute
    // ------------------------------------------------------------------------
    public static String toHHMM(String time) {
        if (time == null || time.length() < 5) return time;
        return time.substring(0, 5);
    }

    // ------------------------------------------------------------------------
    // normalizeTime: ensures time is always "HH:MM:SS"
    // Used in near-miss processing so joining on time works correctly
    // ------------------------------------------------------------------------
    public static String normalizeTime(String time) {
        if (time == null) return "";
        time = time.trim();
        if (time.length() >= 8) return time.substring(0, 8); // already HH:MM:SS
        if (time.length() == 5) return time + ":00";          // HH:MM -> HH:MM:00
        return time;
    }

    // ------------------------------------------------------------------------
    // yearMonthKey: extracts YYYY-MM from a date string
    // Handles both YYYY-MM-DD and MM/DD/YYYY formats
    // ------------------------------------------------------------------------
    public static String yearMonthKey(String date) {
        if (date == null || date.length() < 7) return "0000-00";
        if (date.charAt(4) == '-') return date.substring(0, 7);
        if (date.contains("/")) {
            String[] p = date.split("/");
            if (p.length >= 3)
                return p[2] + "-" + String.format("%02d", Integer.parseInt(p[0]));
        }
        return date.substring(0, 7);
    }

    // ------------------------------------------------------------------------
    // monthName: returns full month name from a date string
    // e.g. "2023-05-03" -> "May"
    // ------------------------------------------------------------------------
    public static String monthName(String date) {
        try {
            String ym = yearMonthKey(date);
            int m = Integer.parseInt(ym.substring(5));
            if (m >= 1 && m <= 12) return MONTH_NAMES[m - 1];
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // ------------------------------------------------------------------------
    // monthNameFromYearMonth: returns full month name from YYYY-MM string
    // e.g. "2023-05" -> "May"
    // ------------------------------------------------------------------------
    public static String monthNameFromYearMonth(String yearMonth) {
        try {
            int m = Integer.parseInt(yearMonth.substring(5));
            if (m >= 1 && m <= 12) return MONTH_NAMES[m - 1];
        } catch (Exception ignored) {}
        return "Unknown";
    }
}
