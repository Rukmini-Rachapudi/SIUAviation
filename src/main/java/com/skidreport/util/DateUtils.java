package com.skidreport.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * DateUtils
 *
 * All date and time helper methods shared across skid and near-miss processing.
 */
public class DateUtils {

    // ------------------------------------------------------------------------
    // TIMEZONE CORRECTION (near-miss)
    //
    // Garmin logs record (Lcl Date, Lcl Time) in whatever zone the row's
    // UTCOfst claims -- and that offset varies between flights (some logs are
    // labelled +00:00 i.e. UTC, others -05:00 / -06:00). To compare two
    // aircraft that were at the same real instant we must first re-express
    // every timestamp in the airport's true local zone.
    //
    // We use ZoneId.of("America/Chicago") rather than a hardcoded offset so
    // the conversion stays correct across DST transitions and for any year,
    // including pre-2024 logs.
    // ------------------------------------------------------------------------
    private static final ZoneId CENTRAL = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Corrects a recorded (Lcl Date, Lcl Time) stamped with {@code utcOfst} to
     * the true local wall-clock time at the airport (America/Chicago).
     *
     * Steps: parse the offset, combine date+time+offset into a single instant,
     * then re-express that instant in Central time. Correcting to Central can
     * legitimately shift the date near midnight -- callers must bucket by the
     * returned (corrected) date.
     *
     * @param lclDate recorded local date, "yyyy-MM-dd"
     * @param lclTime recorded local time, "hh:mm:ss" (or "hh:mm")
     * @param utcOfst the offset the row claims, "+hh:mm" / "-hh:mm" (or "Z")
     * @return the corrected date+time in America/Chicago
     */
    public static LocalDateTime correctToCentral(String lclDate, String lclTime, String utcOfst) {
        ZoneOffset offset = parseOffset(utcOfst);
        LocalDate date = LocalDate.parse(lclDate.trim());          // ISO yyyy-MM-dd
        LocalTime time = LocalTime.parse(normalizeTime(lclTime));  // ISO HH:mm:ss
        return OffsetDateTime.of(date, time, offset)
                .atZoneSameInstant(CENTRAL)
                .toLocalDateTime();
    }

    /** Formats a LocalTime as "HH:mm:ss" (zero-padded), matching stored keys. */
    public static String toHms(LocalTime time) {
        return time.format(HMS);
    }

    /**
     * Parses a Garmin UTCOfst field ("+hh:mm", "-hh:mm", or "Z") into a
     * ZoneOffset. Throws if the string is blank or malformed so the caller's
     * per-row guard skips the (un-correctable) row.
     */
    public static ZoneOffset parseOffset(String utcOfst) {
        if (utcOfst == null) throw new IllegalArgumentException("null UTCOfst");
        String s = utcOfst.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("blank UTCOfst");
        if (s.equalsIgnoreCase("Z")) return ZoneOffset.UTC;

        int sign = 1;
        char c0 = s.charAt(0);
        if (c0 == '+') { s = s.substring(1); }
        else if (c0 == '-') { sign = -1; s = s.substring(1); }

        String[] p = s.split(":");
        int hh = Integer.parseInt(p[0].trim());
        int mm = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        int ss = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
        return ZoneOffset.ofHoursMinutesSeconds(sign * hh, sign * mm, sign * ss);
    }

    /**
     * Today's run-date folder name, e.g. "11-may-2026".
     * Shared by every generator so a single MainRunner invocation drops all
     * reports into the same Output/<dayFolder>/... tree.
     */
    public static String todayDayFolder() {
        return LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();
    }


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
    // secondsOfDay: "HH:MM:SS" -> int seconds since 00:00:00
    // Returns -1 if the input is malformed. Used to detect consecutive seconds
    // when aggregating near-miss snapshots into duration-based events.
    // ------------------------------------------------------------------------
    public static int secondsOfDay(String time) {
        if (time == null || time.length() < 8) return -1;
        try {
            int h = Integer.parseInt(time.substring(0, 2));
            int m = Integer.parseInt(time.substring(3, 5));
            int s = Integer.parseInt(time.substring(6, 8));
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
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
