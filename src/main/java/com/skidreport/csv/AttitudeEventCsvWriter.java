package com.skidreport.csv;

import com.skidreport.model.AttitudeEvent;
import com.skidreport.util.DateUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes bank / high-pitch / low-pitch attitude events to CSV outputs.
 *
 * The Excel writer packs all three event types into one workbook (3 sheets).
 * CSV cannot hold multiple sheets, so the mirror layout splits into 3 files:
 *
 *   <CSV_ROOT>\<dayFolder>\Bank Pitch Events\BankPitch_Reports_<tail>\
 *       Bank_<tail>_<YYYY>_<MM>_<Month>.csv
 *       HighPitch_<tail>_<YYYY>_<MM>_<Month>.csv
 *       LowPitch_<tail>_<YYYY>_<MM>_<Month>.csv
 *
 * Consolidated Power BI files (one per type, rewritten each run):
 *   <POWERBI_ROOT>\bank_events.csv
 *   <POWERBI_ROOT>\high_pitch_events.csv
 *   <POWERBI_ROOT>\low_pitch_events.csv
 */
public final class AttitudeEventCsvWriter {

    public enum Type {
        BANK("Bank",       "bank_events.csv",       "Peak Roll (deg)"),
        HIGH("HighPitch",  "high_pitch_events.csv", "Peak Pitch (deg)"),
        LOW ("LowPitch",   "low_pitch_events.csv",  "Peak Pitch (deg)");

        public final String filePrefix;
        public final String pbiFileName;
        public final String peakHeader;

        Type(String filePrefix, String pbiFileName, String peakHeader) {
            this.filePrefix  = filePrefix;
            this.pbiFileName = pbiFileName;
            this.peakHeader  = peakHeader;
        }
    }

    private static final String[] CONSOLIDATED_HEADERS = {
            "tail", "year_month", "event_type",
            "local_date", "start_time", "end_time",
            "duration_seconds", "peak_value", "trigger_count"
    };

    private AttitudeEventCsvWriter() {}

    // ------------------------------------------------------------------------
    // CONSOLIDATED writers -- one shared writer per type, opened once per run.
    // ------------------------------------------------------------------------
    private static BufferedWriter bankPbi;
    private static BufferedWriter highPbi;
    private static BufferedWriter lowPbi;

    public static void beginConsolidated() throws IOException {
        bankPbi = openPbi(CsvPaths.bankEventsConsolidated());
        highPbi = openPbi(CsvPaths.highPitchEventsConsolidated());
        lowPbi  = openPbi(CsvPaths.lowPitchEventsConsolidated());
    }

    private static BufferedWriter openPbi(File f) throws IOException {
        BufferedWriter w = CsvWriterUtil.open(f);
        CsvWriterUtil.writeHeader(w, CONSOLIDATED_HEADERS);
        return w;
    }

    public static void endConsolidated() throws IOException {
        if (bankPbi != null) { bankPbi.flush(); bankPbi.close(); bankPbi = null; }
        if (highPbi != null) { highPbi.flush(); highPbi.close(); highPbi = null; }
        if (lowPbi  != null) { lowPbi.flush();  lowPbi.close();  lowPbi  = null; }
    }

    // ------------------------------------------------------------------------
    // WRITE: one (tail, yearMonth) bundle. Writes the 3 mirror files (skips
    // empty ones) and appends to the corresponding Power BI files.
    //
    // dayFolder mirrors AttitudeEventReportGenerator's run-day folder (e.g.
    // "26-apr-2026") so the CSV mirror sits next to the Excel mirror under
    // the CSV root. The generator passes its computed dayFolder string in.
    // ------------------------------------------------------------------------
    public static void write(String tail, String yearMonth, String dayFolder,
                             List<AttitudeEvent> bankEvents,
                             List<AttitudeEvent> highEvents,
                             List<AttitudeEvent> lowEvents) {

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = DateUtils.monthNameFromYearMonth(yearMonth);

        File reportDir = new File(CsvPaths.csvRoot(),
                dayFolder + File.separator + "Bank Pitch Events"
                          + File.separator + "BankPitch_Reports_" + tail);
        reportDir.mkdirs();

        writeOne(Type.BANK, bankEvents, tail, yearMonth, year, monthNum, monthNameStr, reportDir, bankPbi);
        writeOne(Type.HIGH, highEvents, tail, yearMonth, year, monthNum, monthNameStr, reportDir, highPbi);
        writeOne(Type.LOW,  lowEvents,  tail, yearMonth, year, monthNum, monthNameStr, reportDir, lowPbi);
    }

    private static void writeOne(Type type, List<AttitudeEvent> events,
                                 String tail, String yearMonth,
                                 String year, String monthNum, String monthNameStr,
                                 File reportDir, BufferedWriter pbi) {
        if (events == null || events.isEmpty()) return;

        String filename = String.format("%s_%s_%s_%s_%s.csv",
                type.filePrefix, tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        String[] mirrorHeaders = {
                "Tail", "Date", "Start Time", "End Time",
                "Duration (s)", type.peakHeader, "Trigger Count"
        };

        try (BufferedWriter w = CsvWriterUtil.open(outFile)) {
            CsvWriterUtil.writeHeader(w, mirrorHeaders);

            for (AttitudeEvent ev : events) {
                CsvWriterUtil.writeRow(w, new Object[]{
                        ev.tail, ev.date, ev.startTime, ev.endTime,
                        (int) ev.durationSeconds, ev.peakValue, ev.triggerCount
                });

                if (pbi != null) {
                    CsvWriterUtil.writeRow(pbi, new Object[]{
                            ev.tail, yearMonth, type.filePrefix,
                            ev.date, ev.startTime, ev.endTime,
                            (int) ev.durationSeconds, ev.peakValue, ev.triggerCount
                    });
                }
            }

            System.out.printf("    CSV: %s  [%d event(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }

    /** Helper for callers that want today's mirror-layout day folder. */
    public static String todayDayFolder() {
        return LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();
    }
}
