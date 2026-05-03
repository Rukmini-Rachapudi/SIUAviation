package com.skidreport.csv;

import java.io.File;

/**
 * Hardcoded output paths for the CSV pipeline.
 *
 * The CSV pipeline runs in addition to the Excel pipeline, never replacing it.
 * It writes to its own root so the Excel output is untouched.
 *
 *   Excel root  : ...\Skids Monthly Reports\Output\                (existing, unchanged)
 *   CSV root    : ...\Skids Monthly Reports\Output\CSV\            (mirror layout)
 *   Power BI    : ...\Skids Monthly Reports\Output\PowerBI_CSV\    (consolidated, one file per event type)
 *
 * Mirror layout under CSV/:
 *   Skid_Reports_<tail>\Skids_<tail>_<YYYY>_<MM>_<Month>.csv
 *   NearMiss\<tail1>_vs_<tail2>\NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.csv
 *   <dayFolder>\Bank Pitch Events\BankPitch_Reports_<tail>\
 *       Bank_<tail>_<YYYY>_<MM>_<Month>.csv
 *       HighPitch_<tail>_<YYYY>_<MM>_<Month>.csv
 *       LowPitch_<tail>_<YYYY>_<MM>_<Month>.csv
 *
 * Consolidated layout under PowerBI_CSV/ (overwritten each run):
 *   skid_events.csv
 *   near_miss_events.csv
 *   bank_events.csv
 *   high_pitch_events.csv
 *   low_pitch_events.csv
 */
public final class CsvPaths {

    public static final String CSV_ROOT      =
            "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output\\CSV";

    public static final String POWERBI_ROOT  =
            "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output\\PowerBI_CSV";

    private CsvPaths() {}

    public static File csvRoot() {
        File f = new File(CSV_ROOT);
        f.mkdirs();
        return f;
    }

    public static File powerBiRoot() {
        File f = new File(POWERBI_ROOT);
        f.mkdirs();
        return f;
    }

    public static File skidEventsConsolidated()      { return new File(powerBiRoot(), "skid_events.csv"); }
    public static File nearMissEventsConsolidated()  { return new File(powerBiRoot(), "near_miss_events.csv"); }
    public static File bankEventsConsolidated()      { return new File(powerBiRoot(), "bank_events.csv"); }
    public static File highPitchEventsConsolidated() { return new File(powerBiRoot(), "high_pitch_events.csv"); }
    public static File lowPitchEventsConsolidated()  { return new File(powerBiRoot(), "low_pitch_events.csv"); }
}
