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
 *        <CSV_ROOT>\Skids\<YYYY>\<MonthName>\Skids_<tail>_<YYYY>_<MM>_<Month>.csv
 *
 *   2. Consolidated Power BI file (one shared file across all aircraft/months):
 *        <POWERBI_ROOT>\skid_events.csv
 *
 * Each row is one detected skid event (a run of consecutive seconds satisfying
 * the skid condition; any single non-triggering record closes the event).
 *
 * The Power BI file is rewritten from scratch each run via #beginConsolidated.
 * Every row carries tail and year_month so a single Power BI table can be
 * sliced by aircraft or month.
 */
public final class SkidCsvWriter {

    private static final String[] MIRROR_HEADERS = {
            "Tail", "Local Date", "Local Month",
            "Start Time", "End Time", "Duration (s)",
            "Trigger Count",
            "Avg Pitch", "Avg Roll", "Peak Roll",
            "Avg Lateral Acceleration", "Peak Lateral Acceleration",
            "Avg Indicated Air Speed", "Avg Gps Altitude",
            "Low-Altitude Skid",
            "Number of Skid Events",
            "Total Low-Altitude Skid Events"
    };

    private static final String[] CONSOLIDATED_HEADERS = {
            "tail", "year_month", "local_date", "local_month_name",
            "start_time", "end_time", "duration_seconds", "trigger_count",
            "avg_pitch", "avg_roll", "peak_roll",
            "avg_lat_ac", "peak_lat_ac",
            "avg_ias", "avg_alt",
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

        File reportDir = new File(CsvPaths.csvRoot(),
                "Skids" + File.separator + year + File.separator + monthNameStr);
        reportDir.mkdirs();

        String filename = String.format("Skids_%s_%s_%s_%s.csv",
                tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        try (BufferedWriter w = CsvWriterUtil.open(outFile)) {
            CsvWriterUtil.writeHeader(w, MIRROR_HEADERS);

            int totalLowAltEvents = 0;
            for (SkidEvent ev : events) {
                if (ev.isLowAlt) totalLowAltEvents++;
            }

            int rowNum = 1;
            for (SkidEvent ev : events) {
                Object numberOfSkidEvents = (rowNum == 1) ? Integer.valueOf(events.size()) : "";
                Object totalLowAlt        = (rowNum == 1) ? Integer.valueOf(totalLowAltEvents) : "";

                CsvWriterUtil.writeRow(w, new Object[]{
                        tail,
                        ev.date,
                        ev.month,
                        ev.startTime,
                        ev.endTime,
                        ev.durationSeconds,
                        ev.triggerCount,
                        ev.avgPitch,
                        ev.avgRoll,
                        ev.peakRoll,
                        ev.avgLatAc,
                        ev.peakLatAc,
                        ev.avgIas,
                        ev.avgAlt,
                        ev.isLowAlt ? 1 : 0,
                        numberOfSkidEvents,
                        totalLowAlt
                });

                if (consolidatedWriter != null) {
                    CsvWriterUtil.writeRow(consolidatedWriter, new Object[]{
                            tail,
                            yearMonth,
                            ev.date,
                            ev.month,
                            ev.startTime,
                            ev.endTime,
                            ev.durationSeconds,
                            ev.triggerCount,
                            ev.avgPitch,
                            ev.avgRoll,
                            ev.peakRoll,
                            ev.avgLatAc,
                            ev.peakLatAc,
                            ev.avgIas,
                            ev.avgAlt,
                            ev.isLowAlt ? 1 : 0
                    });
                }
                rowNum++;
            }

            System.out.printf("    CSV: %s  [%d skid event(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }
}
