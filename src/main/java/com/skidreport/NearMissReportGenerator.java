package com.skidreport;

import com.skidreport.csv.NearMissCsvWriter;
import com.skidreport.db.DatabaseManager;
import com.skidreport.db.FlightRecordDao;
import com.skidreport.db.NearMissEventDao;
import com.skidreport.excel.NearMissExcelWriter;
import com.skidreport.model.AircraftSnapshot;
import com.skidreport.model.NearMissEvent;
import com.skidreport.util.CsvParser;
import com.skidreport.util.DateUtils;
import com.skidreport.util.GeoUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NearMissReportGenerator
 *
 * Orchestrates near-miss report generation across all aircraft.
 *
 * PHASE 1 -- Create SQLite database + tables + indexes
 * PHASE 2 -- Load all aircraft CSV data into SQLite (filtered by triggers)
 * PHASE 3 -- Detect near-miss events per date, store results, write Excel
 *
 * TRIGGERS (all must be true for both aircraft):
 *   IAS > 45 kts   AND   AltMSL > 500 ft   AND   E1 RPM > 0
 *
 * NEAR-MISS: 3D Haversine distance < 500 feet
 */
public class NearMissReportGenerator {

    // -- EDIT THESE TWO PATHS ------------------------------------------------
    static final String INPUT_PATH  = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";
    // ------------------------------------------------------------------------

    static final double NEAR_MISS_FEET = 500.0;

    // Trigger filters applied before inserting into DB
    static final double MIN_IAS = 45.0;
    static final double MIN_ALT = 500.0;
    static final double MIN_RPM = 0.0;

    public static void main(String[] args) throws Exception {

        String dayFolder = DateUtils.todayDayFolder();

        File rootDir = new File(INPUT_PATH);

        // .db stays at a stable root path so subsequent runs can reuse it as a cache.
        File dbDir = new File(OUTPUT_PATH + File.separator + "NearMiss");
        // Report files land inside the per-run day folder, matching skid + bank/pitch.
        File outputDir = new File(OUTPUT_PATH + File.separator + dayFolder
                + File.separator + "Near Miss");

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + INPUT_PATH);
            System.exit(1);
        }

        dbDir.mkdirs();
        outputDir.mkdirs();

        String dbPath = dbDir.getAbsolutePath() + File.separator + "near_miss.db";
        System.out.println("Input    : " + INPUT_PATH);
        System.out.println("Output   : " + outputDir.getAbsolutePath());
        System.out.println("Database : " + dbPath);

        Class.forName("org.sqlite.JDBC");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {

            // SQLite performance tuning
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA cache_size=10000");
            }

            conn.setAutoCommit(false);

            // ------------------------------------------------------------------
            // PHASE 1: Create database tables and indexes
            // ------------------------------------------------------------------
            System.out.println("\n[PHASE 1] Creating database tables...");
            DatabaseManager.createTablesAndIndexes(conn);
            conn.commit();
            System.out.println("[PHASE 1] Complete.");

            // ------------------------------------------------------------------
            // PHASE 2: Load all aircraft into SQLite
            // ------------------------------------------------------------------
            System.out.println("\n[PHASE 2] Loading all aircraft data...");

            File[] tailDirs = rootDir.listFiles(File::isDirectory);
            if (tailDirs == null || tailDirs.length == 0) {
                System.out.println("No aircraft folders found.");
                return;
            }

            Arrays.sort(tailDirs, Comparator.comparing(File::getName));

            for (File tailDir : tailDirs) {
                String tail = tailDir.getName().trim();
                System.out.println("\n  Loading: " + tail);
                int rows = loadAircraft(conn, tail, tailDir);
                System.out.println("  Inserted " + rows + " qualifying records for " + tail);
            }

            conn.commit();
            System.out.println("\n[PHASE 2] Complete.");
            FlightRecordDao.printSummary(conn);

            // ------------------------------------------------------------------
            // PHASE 3: Detect near misses and write Excel
            // ------------------------------------------------------------------
            System.out.println("\n[PHASE 3] Detecting near-miss events...");
            int eventCount = detectNearMisses(conn);
            conn.commit();
            System.out.println("[PHASE 3] Detection complete: " + eventCount + " event(s) stored.");

            System.out.println("\n[PHASE 3] Writing Excel reports...");
            NearMissExcelWriter.writeAll(conn, outputDir);
            System.out.println("[PHASE 3] Complete.");

            System.out.println("\n[PHASE 3] Writing CSV reports (mirror + Power BI)...");
            NearMissCsvWriter.writeAll(conn);
            System.out.println("[PHASE 3] CSV complete.");
        }
    }

    // ------------------------------------------------------------------------
    // PHASE 2 HELPER: Load one aircraft's CSV files into SQLite
    //
    // Streams each CSV record straight into a single shared Inserter: parse
    // -> trigger filter -> JDBC batch. No List<NearMissFlightRecord> is ever
    // held, so memory stays flat regardless of how large any one file is.
    // ------------------------------------------------------------------------
    private static int loadAircraft(Connection conn, String tail, File tailDir)
            throws Exception {

        List<File> csvFiles = new ArrayList<>();
        CsvParser.collectCsvFiles(tailDir, csvFiles);

        if (csvFiles.isEmpty()) {
            System.out.println("    No CSV files found.");
            return 0;
        }

        System.out.println("    CSV files: " + csvFiles.size());

        int skipped = 0;

        try (FlightRecordDao.Inserter inserter = FlightRecordDao.beginInsert(conn)) {
            for (File csv : csvFiles) {
                try {
                    CsvParser.streamNearMissCsvFile(csv, tail, rec -> {
                        if (rec.ias <= MIN_IAS) return;
                        if (rec.alt <= MIN_ALT) return;
                        if (rec.rpm <= MIN_RPM) return;
                        inserter.add(rec);
                    });
                } catch (Exception e) {
                    skipped++;
                    System.err.println("    [WARN] Skipping " + csv.getName()
                            + ": " + e.getMessage());
                }
            }

            if (skipped > 0) System.out.println("    Skipped " + skipped + " file(s).");
            return inserter.total();
        }
    }

    // ------------------------------------------------------------------------
    // PHASE 3 HELPER: Detect near-miss events across all aircraft for every date
    //
    // For each pair of aircraft, a single "event" spans one or more consecutive
    // seconds (t, t+1, t+2, ...) where the pair is within NEAR_MISS_FEET. Any
    // gap -- a non-consecutive second, a second where the pair is no longer
    // within range, or a date boundary -- closes the event. The output row
    // captures duration and the closest-approach snapshot inside that window.
    // ------------------------------------------------------------------------
    private static int detectNearMisses(Connection conn) throws Exception {

        List<String> dates = FlightRecordDao.getAllDates(conn);
        System.out.println("  Processing " + dates.size() + " date(s)...");

        int totalEvents = 0;

        for (String date : dates) {

            Map<String, List<AircraftSnapshot>> byTime = new LinkedHashMap<>();
            int dupRowsSkipped = FlightRecordDao.loadByDate(conn, date, byTime);
            if (dupRowsSkipped > 0) {
                System.out.printf("  Date %s -- %d duplicate (tail, second) row(s) "
                        + "skipped at load (overlapping flight logs).%n",
                        date, dupRowsSkipped);
            }

            // pairKey ("tail1|tail2", tails already alphabetized) -> in-progress event
            Map<String, ActiveEvent> active = new LinkedHashMap<>();
            List<NearMissEvent> finalized = new ArrayList<>();

            for (Map.Entry<String, List<AircraftSnapshot>> entry : byTime.entrySet()) {
                String time = entry.getKey();
                int    currentSec = DateUtils.secondsOfDay(time);
                List<AircraftSnapshot> snaps = entry.getValue();

                if (snaps.size() < 2) continue;

                for (int i = 0; i < snaps.size(); i++) {
                    for (int j = i + 1; j < snaps.size(); j++) {
                        AircraftSnapshot a = snaps.get(i);
                        AircraftSnapshot b = snaps.get(j);

                        // Near-miss is between TWO DIFFERENT aircraft. Skip
                        // any same-tail pair defensively -- loadByDate already
                        // dedupes (tail, second), but a guard here keeps the
                        // detector correct if that ever regresses.
                        if (a.tail.equals(b.tail)) continue;

                        AircraftSnapshot s1; AircraftSnapshot s2;
                        String tail1; String tail2;
                        if (a.tail.compareTo(b.tail) <= 0) {
                            tail1 = a.tail; s1 = a;
                            tail2 = b.tail; s2 = b;
                        } else {
                            tail1 = b.tail; s1 = b;
                            tail2 = a.tail; s2 = a;
                        }

                        double distFt = GeoUtils.distance3dFeet(
                                s1.lat, s1.lon, s1.alt,
                                s2.lat, s2.lon, s2.alt);

                        if (distFt >= NEAR_MISS_FEET) continue;

                        String pairKey = tail1 + "|" + tail2;
                        ActiveEvent ae = active.get(pairKey);

                        boolean canExtend = ae != null
                                && currentSec >= 0
                                && ae.lastSec >= 0
                                && currentSec == ae.lastSec + 1;

                        if (canExtend) {
                            ae.lastSec       = currentSec;
                            ae.durationSecs++;
                            if (distFt < ae.minDist) {
                                ae.minDist = distFt;
                                ae.lat1 = s1.lat; ae.lon1 = s1.lon; ae.alt1 = s1.alt; ae.ias1 = s1.ias;
                                ae.lat2 = s2.lat; ae.lon2 = s2.lon; ae.alt2 = s2.alt; ae.ias2 = s2.ias;
                            }
                        } else {
                            // gap or first sighting: close any previous event, start fresh
                            if (ae != null) finalized.add(toEvent(ae, date));

                            ActiveEvent fresh = new ActiveEvent();
                            fresh.tail1        = tail1;
                            fresh.tail2        = tail2;
                            fresh.startTime    = time;
                            fresh.lastSec      = currentSec;
                            fresh.durationSecs = 1;
                            fresh.minDist      = distFt;
                            fresh.lat1 = s1.lat; fresh.lon1 = s1.lon; fresh.alt1 = s1.alt; fresh.ias1 = s1.ias;
                            fresh.lat2 = s2.lat; fresh.lon2 = s2.lon; fresh.alt2 = s2.alt; fresh.ias2 = s2.ias;
                            active.put(pairKey, fresh);
                        }
                    }
                }
            }

            // Flush events still in progress at end of date
            for (ActiveEvent ae : active.values()) {
                finalized.add(toEvent(ae, date));
            }

            if (!finalized.isEmpty()) {
                NearMissEventDao.insertBatch(conn, finalized);
                totalEvents += finalized.size();
            }

            System.out.printf("  Date %s -- %d event(s) found so far.%n", date, totalEvents);
        }

        return totalEvents;
    }

    private static NearMissEvent toEvent(ActiveEvent ae, String date) {
        NearMissEvent ev = new NearMissEvent();
        ev.date            = date;
        ev.startTime       = ae.startTime;
        ev.durationSeconds = ae.durationSecs;
        ev.tail1           = ae.tail1;
        ev.tail2           = ae.tail2;
        ev.lat1            = ae.lat1;
        ev.lon1            = ae.lon1;
        ev.alt1            = ae.alt1;
        ev.ias1            = ae.ias1;
        ev.lat2            = ae.lat2;
        ev.lon2            = ae.lon2;
        ev.alt2            = ae.alt2;
        ev.ias2            = ae.ias2;
        ev.minDistanceFt   = ae.minDist;
        ev.yearMonth       = DateUtils.yearMonthKey(date);
        return ev;
    }

    private static class ActiveEvent {
        String tail1;
        String tail2;
        String startTime;      // HH:MM:SS at event start
        int    lastSec;        // seconds-of-day of most recent qualifying second
        long   durationSecs;
        double minDist;
        double lat1, lon1, alt1, ias1;
        double lat2, lon2, alt2, ias2;
    }
}
