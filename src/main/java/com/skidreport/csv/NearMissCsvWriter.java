package com.skidreport.csv;

import com.skidreport.db.NearMissEventDao;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.List;

/**
 * Writes near-miss events to two CSV outputs:
 *
 *   1. Mirror file (one per aircraft pair per month):
 *        <CSV_ROOT>\Near Miss\<YYYY>\<MonthName>\
 *            NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.csv
 *
 *   2. Consolidated Power BI file (one across all pairs/months):
 *        <POWERBI_ROOT>\near_miss_events.csv
 *
 * Power BI file is rewritten from scratch each run.
 */
public final class NearMissCsvWriter {

    private static final String[] MIRROR_HEADERS = {
            "Local Date", "Start Time", "Duration (sec)",
            "Aircraft 1", "Latitude 1", "Longitude 1", "Altitude 1 (ft)", "IAS 1 (kts)",
            "Aircraft 2", "Latitude 2", "Longitude 2", "Altitude 2 (ft)", "IAS 2 (kts)",
            "Min Distance (ft)", "Total Near-Miss Events This Month"
    };

    private static final String[] CONSOLIDATED_HEADERS = {
            "year_month", "local_date", "start_time", "duration_seconds",
            "tail1", "lat1", "lon1", "alt1_ft", "ias1_kts",
            "tail2", "lat2", "lon2", "alt2_ft", "ias2_kts",
            "min_distance_ft", "pair_key"
    };

    private NearMissCsvWriter() {}

    public static void writeAll(Connection conn) throws Exception {
        List<String[]> pairs = NearMissEventDao.getAllPairsAndMonths(conn);
        if (pairs.isEmpty()) {
            System.out.println("  No near-miss events to write to CSV.");
            return;
        }

        File consolidated = CsvPaths.nearMissEventsConsolidated();
        try (BufferedWriter pbi = CsvWriterUtil.open(consolidated)) {
            CsvWriterUtil.writeHeader(pbi, CONSOLIDATED_HEADERS);

            System.out.println("  " + pairs.size() + " CSV file(s) to write...");
            for (String[] pair : pairs) {
                writeOne(conn, pair[0], pair[1], pair[2], pbi);
            }
        }
    }

    private static void writeOne(Connection conn,
                                 String tail1, String tail2, String yearMonth,
                                 BufferedWriter pbi) throws Exception {

        // Count first so the "total this month" cell can go on row 1 without
        // holding the whole result set in memory; then stream the rows.
        int total = NearMissEventDao.countEventsForPairAndMonth(conn, tail1, tail2, yearMonth);
        if (total == 0) return;

        String[] parts   = yearMonth.split("-");
        String year      = parts[0];
        String monthNum  = parts[1];
        String monthName = com.skidreport.util.DateUtils.monthNameFromYearMonth(yearMonth);

        File monthDir = new File(CsvPaths.csvRoot(),
                "Near Miss" + File.separator + year + File.separator + monthName);
        monthDir.mkdirs();

        String filename = String.format("NearMiss_%s_%s_%s_%s_%s.csv",
                tail1, tail2, year, monthNum, monthName);
        File outFile = new File(monthDir, filename);
        String pairKey = tail1 + "_vs_" + tail2;

        try (BufferedWriter w = CsvWriterUtil.open(outFile)) {
            CsvWriterUtil.writeHeader(w, MIRROR_HEADERS);

            // Mutable counter usable from the streaming lambda.
            final int[] rowNum = {1};
            NearMissEventDao.streamEventsForPairAndMonth(conn, tail1, tail2, yearMonth, ev -> {
                try {
                    Object totalThisMonth = (rowNum[0] == 1) ? Integer.valueOf(total) : "";

                    CsvWriterUtil.writeRow(w, new Object[]{
                            ev.date, ev.startTime, ev.durationSeconds,
                            tail1, ev.lat1, ev.lon1, ev.alt1, ev.ias1,
                            tail2, ev.lat2, ev.lon2, ev.alt2, ev.ias2,
                            ev.minDistanceFt, totalThisMonth
                    });

                    CsvWriterUtil.writeRow(pbi, new Object[]{
                            yearMonth, ev.date, ev.startTime, ev.durationSeconds,
                            tail1, ev.lat1, ev.lon1, ev.alt1, ev.ias1,
                            tail2, ev.lat2, ev.lon2, ev.alt2, ev.ias2,
                            ev.minDistanceFt, pairKey
                    });
                    rowNum[0]++;
                } catch (IOException e) {
                    throw new RuntimeException("Failed writing row for " + outFile.getName(), e);
                }
            });

            System.out.printf("    CSV: %s  [%d event(s)]%n", outFile.getName(), total);

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }
}
