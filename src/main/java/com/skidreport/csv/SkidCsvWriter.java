package com.skidreport.csv;

import com.skidreport.model.SkidEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Writes skid events to two CSV outputs:
 *
 *   1. Mirror file (one per aircraft per month) -- same shape as the Excel:
 *        <CSV_ROOT>\Skid_Reports_<tail>\Skids_<tail>_<YYYY>_<MM>_<Month>.csv
 *
 *   2. Consolidated Power BI file (one shared file across all aircraft/months):
 *        <POWERBI_ROOT>\skid_events.csv
 *
 * The Power BI file is rewritten from scratch each run via #beginConsolidated.
 * Every row carries tail and year_month so a single Power BI table can be
 * sliced by aircraft or month.
 */
public final class SkidCsvWriter {

    private static final String[] MIRROR_HEADERS = {
            "Local Date", "Local Time (HH:MM:SS)", "Local Month",
            "Pitch", "Roll", "Lateral Acceleration",
            "Indicated Air Speed", "Gps Altitude", "Skid Count",
            "Number of Skid Events",
            "Low-Altitude-Skid-Events",
            "total-low-altitude-skid-event-count",
            "Flight name"
    };

    private static final String[] CONSOLIDATED_HEADERS = {
            "tail", "year_month", "local_date", "local_time", "local_month_name",
            "avg_pitch", "avg_roll", "avg_lat_ac",
            "avg_ias", "avg_alt", "skid_count",
            "is_low_altitude_skid"
    };

    private SkidCsvWriter() {}

    // ------------------------------------------------------------------------
    // CONSOLIDATED (Power BI) -- one file across all aircraft/months
    // ------------------------------------------------------------------------
    private static BufferedWriter consolidatedWriter;

    /** Truncate the Power BI consolidated file and write its header. */
    public static void beginConsolidated() throws IOException {
        File f = CsvPaths.skidEventsConsolidated();
        consolidatedWriter = CsvWriterUtil.open(f);
        CsvWriterUtil.writeHeader(consolidatedWriter, CONSOLIDATED_HEADERS);
    }

    public static void endConsolidated() throws IOException {
        if (consolidatedWriter != null) {
            consolidatedWriter.flush();
            consolidatedWriter.close();
            consolidatedWriter = null;
        }
    }

    // ------------------------------------------------------------------------
    // WRITE: one (tail, yearMonth) batch -- writes mirror file AND appends to
    // the open consolidated writer. Excel writer continues to run separately.
    // ------------------------------------------------------------------------
    public static void write(String tail, String yearMonth, List<SkidEvent> events) {
        if (events == null || events.isEmpty()) return;

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = events.get(0).month;

        File reportDir = new File(CsvPaths.csvRoot(), "Skid_Reports_" + tail);
        reportDir.mkdirs();

        String filename = String.format("Skids_%s_%s_%s_%s.csv",
                tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        try (BufferedWriter w = CsvWriterUtil.open(outFile)) {
            CsvWriterUtil.writeHeader(w, MIRROR_HEADERS);

            int totalLowAltEvents = 0;
            for (SkidEvent ev : events) {
                if (ev.lowAltFrequency > 0) totalLowAltEvents++;
            }

            int rowNum = 1;
            for (SkidEvent ev : events) {
                Object numberOfSkidEvents = (rowNum == 1) ? Integer.valueOf(events.size()) : "";
                Object lowAltFlag         = (ev.lowAltFrequency > 0) ? Integer.valueOf(1) : "";
                Object totalLowAlt        = (rowNum == 1) ? Integer.valueOf(totalLowAltEvents) : "";

                CsvWriterUtil.writeRow(w, new Object[]{
                        ev.date,
                        ev.minuteTime,
                        ev.month,
                        ev.avgPitch,
                        ev.avgRoll,
                        ev.avgLatAc,
                        ev.avgIas,
                        ev.avgAlt,
                        ev.skidFrequency,
                        numberOfSkidEvents,
                        lowAltFlag,
                        totalLowAlt,
                        tail
                });

                if (consolidatedWriter != null) {
                    CsvWriterUtil.writeRow(consolidatedWriter, new Object[]{
                            tail,
                            yearMonth,
                            ev.date,
                            ev.minuteTime,
                            ev.month,
                            ev.avgPitch,
                            ev.avgRoll,
                            ev.avgLatAc,
                            ev.avgIas,
                            ev.avgAlt,
                            ev.skidFrequency,
                            (ev.lowAltFrequency > 0) ? 1 : 0
                    });
                }
                rowNum++;
            }

            System.out.printf("    CSV: %s  [%d skid-minute(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }
}
