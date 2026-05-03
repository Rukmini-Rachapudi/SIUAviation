package com.skidreport.csv;

import com.skidreport.db.NearMissEventDao;
import com.skidreport.model.NearMissEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.List;

/**
 * Writes near-miss events to two CSV outputs:
 *
 *   1. Mirror file (one per aircraft pair per month):
 *        <CSV_ROOT>\NearMiss\<tail1>_vs_<tail2>\
 *            NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.csv
 *
 *   2. Consolidated Power BI file (one across all pairs/months):
 *        <POWERBI_ROOT>\near_miss_events.csv
 *
 * Power BI file is rewritten from scratch each run.
 */
public final class NearMissCsvWriter {

    private static final String[] MIRROR_HEADERS = {
            "Local Date", "Local Time",
            "Aircraft 1", "Latitude 1", "Longitude 1", "Altitude 1 (ft)", "IAS 1 (kts)",
            "Aircraft 2", "Latitude 2", "Longitude 2", "Altitude 2 (ft)", "IAS 2 (kts)",
            "Distance (ft)", "Total Near-Miss Events This Month"
    };

    private static final String[] CONSOLIDATED_HEADERS = {
            "year_month", "local_date", "local_time",
            "tail1", "lat1", "lon1", "alt1_ft", "ias1_kts",
            "tail2", "lat2", "lon2", "alt2_ft", "ias2_kts",
            "distance_ft", "pair_key"
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

        List<NearMissEvent> events =
                NearMissEventDao.getEventsForPairAndMonth(conn, tail1, tail2, yearMonth);
        if (events.isEmpty()) return;

        String[] parts   = yearMonth.split("-");
        String year      = parts[0];
        String monthNum  = parts[1];
        String monthName = com.skidreport.util.DateUtils.monthNameFromYearMonth(yearMonth);

        File rootNm  = new File(CsvPaths.csvRoot(), "NearMiss");
        File pairDir = new File(rootNm, tail1 + "_vs_" + tail2);
        pairDir.mkdirs();

        String filename = String.format("NearMiss_%s_%s_%s_%s_%s.csv",
                tail1, tail2, year, monthNum, monthName);
        File outFile = new File(pairDir, filename);

        try (BufferedWriter w = CsvWriterUtil.open(outFile)) {
            CsvWriterUtil.writeHeader(w, MIRROR_HEADERS);

            int rowNum = 1;
            String pairKey = tail1 + "_vs_" + tail2;
            for (NearMissEvent ev : events) {
                Object totalThisMonth = (rowNum == 1) ? Integer.valueOf(events.size()) : "";

                CsvWriterUtil.writeRow(w, new Object[]{
                        ev.date, ev.time,
                        tail1, ev.lat1, ev.lon1, ev.alt1, ev.ias1,
                        tail2, ev.lat2, ev.lon2, ev.alt2, ev.ias2,
                        ev.distanceFt, totalThisMonth
                });

                CsvWriterUtil.writeRow(pbi, new Object[]{
                        yearMonth, ev.date, ev.time,
                        tail1, ev.lat1, ev.lon1, ev.alt1, ev.ias1,
                        tail2, ev.lat2, ev.lon2, ev.alt2, ev.ias2,
                        ev.distanceFt, pairKey
                });
                rowNum++;
            }

            System.out.printf("    CSV: %s  [%d event(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }
}
