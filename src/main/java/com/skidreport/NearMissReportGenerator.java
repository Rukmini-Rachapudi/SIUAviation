package com.skidreport;

import com.skidreport.db.DatabaseManager;
import com.skidreport.db.FlightRecordDao;
import com.skidreport.db.NearMissEventDao;
import com.skidreport.excel.NearMissExcelWriter;
import com.skidreport.model.AircraftSnapshot;
import com.skidreport.model.NearMissEvent;
import com.skidreport.model.FlightRecord;
import com.skidreport.util.CsvParser;
import com.skidreport.util.DateUtils;
import com.skidreport.util.GeoUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * IAS > 45 kts AND AltMSL > 1000 ft AND E1 RPM > 0
 *
 * NEAR-MISS: 3D Haversine distance < 500 feet
 */
public class NearMissReportGenerator {

    // -- EDIT THESE TWO PATHS ------------------------------------------------
    static final String INPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";
    // ------------------------------------------------------------------------

    static final double NEAR_MISS_FEET = 500.0;

    // Trigger filters applied before inserting into DB
    // These MUST match SkidReportGenerator's in-flight filter thresholds
    static final double MIN_IAS = 45.0;
    static final double MIN_ALT = 1000.0;
    static final double MIN_RPM = 0.0;

    public static void main(String[] args) throws Exception {
        String dayFolder = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();  // e.g. "4-apr-2026"

        File rootDir   = new File(INPUT_PATH);
        File outputDir = new File(OUTPUT_PATH + "\\" + dayFolder + "\\NearMiss");

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        String dbPath = outputDir.getAbsolutePath() + "\\near_miss.db";
        System.out.println("Input    : " + INPUT_PATH);
        System.out.println("Output   : " + outputDir.getAbsolutePath());
        System.out.println("Database : " + dbPath);

        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            SkidReportGenerator.applyPragmas(conn);
            conn.setAutoCommit(false);
            run(conn, rootDir, outputDir);
        }

        // Delete the DB file from disk now that the connection is fully closed
        File dbFile = new File(dbPath);
        if (dbFile.exists()) {
            if (dbFile.delete()) {
                System.out.println("Database file deleted: " + dbPath);
            } else {
                System.err.println("[WARN] Could not delete database file: " + dbPath);
            }
        }
    }

    /**
     * Core near-miss logic — called by main() standalone or by MainRunner via
     * shared connection.
     * If the DB already has flight records (loaded by SkidReportGenerator), Phase 2
     * is skipped.
     * Always wipes the DB at the end after all Excel files are written.
     */
    public static void run(Connection conn, File rootDir, File outputDir) throws Exception {
        outputDir.mkdirs();

        // PHASE 1: Ensure schema exists
        System.out.println("\n[PHASE 1] Ensuring database schema...");
        DatabaseManager.createTablesIfAbsent(conn);
        conn.commit();
        System.out.println("[PHASE 1] Complete.");

        // PHASE 2: Load data only if not already populated by SkidReportGenerator
        long existingRows = FlightRecordDao.countRecords(conn);
        if (existingRows > 0) {
            System.out.println("\n[PHASE 2] Skipped -- DB already contains "
                    + String.format("%,d", existingRows) + " flight records from SkidReportGenerator.");
        } else {
            System.out.println("\n[PHASE 2] Loading all aircraft data (standalone mode)...");
            File[] tailDirs = rootDir.listFiles(File::isDirectory);
            if (tailDirs == null || tailDirs.length == 0) {
                System.out.println("  No aircraft folders found.");
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
        }

        // PHASE 3: Detect near misses and write Excel
        System.out.println("\n[PHASE 3] Detecting near-miss events...");
        int eventCount = detectNearMisses(conn);
        conn.commit();
        System.out.println("[PHASE 3] Detection complete: " + eventCount + " event(s) stored.");

        System.out.println("\n[PHASE 3] Writing Excel reports...");
        NearMissExcelWriter.writeAll(conn, outputDir);
        System.out.println("[PHASE 3] Reports written.");

        // CLEANUP: Wipe DB after all reports are generated
        System.out.println("\nCleaning up database...");
        DatabaseManager.wipeDatabase(conn);
        conn.commit();
        System.out.println("Database wiped successfully.");
    }

    // ------------------------------------------------------------------------
    // PHASE 2 HELPER: Load one aircraft's CSV files into SQLite
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

        int totalInserted = 0;
        int skipped = 0;

        for (File csv : csvFiles) {
            try {
                List<FlightRecord> records = CsvParser.parseNearMissCsvFile(csv, tail);
                List<FlightRecord> filtered = new ArrayList<>();

                for (FlightRecord rec : records) {
                    if (rec.ias <= MIN_IAS)
                        continue;
                    if (rec.alt <= MIN_ALT)
                        continue;
                    if (rec.rpm <= MIN_RPM)
                        continue;
                    filtered.add(rec);
                }

                FlightRecordDao.insertBatch(conn, filtered);
                totalInserted += filtered.size();

            } catch (Exception e) {
                skipped++;
                System.err.println("    [WARN] Skipping " + csv.getName() + ": " + e.getMessage());
            }
        }

        if (skipped > 0)
            System.out.println("    Skipped " + skipped + " file(s).");
        return totalInserted;
    }

    // ------------------------------------------------------------------------
    // PHASE 3 HELPER: Detect near misses across all aircraft for every date
    // ------------------------------------------------------------------------
    private static int detectNearMisses(Connection conn) throws Exception {

        List<String> dates = FlightRecordDao.getAllDates(conn);
        System.out.println("  Processing " + dates.size() + " date(s)...");

        int totalStoredEvents = 0;

        for (String date : dates) {
            System.out.println("  Analyzing Date: " + date);

            // RAW POINTS: HH:MM:SS -> List of Events (each representing a pair at that
            // second)
            List<NearMissEvent> rawPoints = new ArrayList<>();

            // CHUNKING: process in 2-hour increments to avoid OOM
            for (int hour = 0; hour < 24; hour += 2) {
                String start = String.format("%02d:00:00", hour);
                String end = String.format("%02d:00:00", hour + 2);

                Map<String, List<AircraftSnapshot>> byTime = new LinkedHashMap<>();
                FlightRecordDao.loadByTimeRange(conn, date, start, end, byTime);

                if (byTime.isEmpty())
                    continue;

                for (Map.Entry<String, List<AircraftSnapshot>> entry : byTime.entrySet()) {
                    String time = entry.getKey();
                    List<AircraftSnapshot> snaps = entry.getValue();

                    if (snaps.size() < 2)
                        continue;

                    for (int i = 0; i < snaps.size(); i++) {
                        for (int j = i + 1; j < snaps.size(); j++) {
                            AircraftSnapshot a = snaps.get(i);
                            AircraftSnapshot b = snaps.get(j);

                            // FIX: Self-comparison guard
                            if (a.tail.equals(b.tail))
                                continue;

                            // Both aircraft must be above MIN_ALT and above MIN_IAS
                            // (SkidReportGenerator inserts with alt>100, so we re-enforce here)
                            if (a.alt <= MIN_ALT || b.alt <= MIN_ALT)
                                continue;
                            if (a.ias <= MIN_IAS || b.ias <= MIN_IAS)
                                continue;

                            // Normalize tail order
                            String t1, t2;
                            AircraftSnapshot s1, s2;
                            if (a.tail.compareTo(b.tail) < 0) {
                                t1 = a.tail;
                                s1 = a;
                                t2 = b.tail;
                                s2 = b;
                            } else {
                                t1 = b.tail;
                                s1 = b;
                                t2 = a.tail;
                                s2 = a;
                            }

                            double dist = GeoUtils.distance3dFeet(s1.lat, s1.lon, s1.alt, s2.lat, s2.lon, s2.alt);

                            // Skip duplicate data: two distinct aircraft cannot
                            // occupy the exact same position (same CSV in multiple folders)
                            if (dist < 1.0)
                                continue;

                            if (dist < NEAR_MISS_FEET) {
                                NearMissEvent point = new NearMissEvent();
                                point.date = date;
                                point.startTime = time;
                                point.endTime = time;
                                point.tail1 = t1;
                                point.tail2 = t2;
                                point.lat1 = s1.lat;
                                point.lon1 = s1.lon;
                                point.alt1 = s1.alt;
                                point.ias1 = s1.ias;
                                point.lat2 = s2.lat;
                                point.lon2 = s2.lon;
                                point.alt2 = s2.alt;
                                point.ias2 = s2.ias;
                                point.minDistanceFt = dist;
                                point.yearMonth = DateUtils.yearMonthKey(date);
                                rawPoints.add(point);
                            }
                        }
                    }
                }
            }

            if (!rawPoints.isEmpty()) {
                List<NearMissEvent> groupedEvents = groupPoints(rawPoints);
                NearMissEventDao.insertBatch(conn, groupedEvents);
                totalStoredEvents += groupedEvents.size();
                System.out.printf("    %s -- %d raw points grouped into %d event(s).%n", date, rawPoints.size(),
                        groupedEvents.size());
            }
        }
        return totalStoredEvents;
    }

    /**
     * Grouping logic:
     * 1. Partition points by (Tail1, Tail2).
     * 2. Sort points by time.
     * 3. Group continuous points (gap <= 10s).
     * 4. Cap each group to 60s max duration.
     */
    private static List<NearMissEvent> groupPoints(List<NearMissEvent> points) {
        if (points.isEmpty())
            return Collections.emptyList();

        Map<String, List<NearMissEvent>> byPair = new HashMap<>();
        for (NearMissEvent p : points) {
            String key = p.tail1 + "|" + p.tail2;
            byPair.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<NearMissEvent> finalEvents = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");

        for (List<NearMissEvent> pairPoints : byPair.values()) {
            pairPoints.sort(Comparator.comparing(p -> p.startTime));

            NearMissEvent currentGroup = null;
            LocalTime groupStart = null;

            for (NearMissEvent p : pairPoints) {
                LocalTime pTime = LocalTime.parse(p.startTime, dtf);

                if (currentGroup == null) {
                    currentGroup = p;
                    groupStart = pTime;
                    continue;
                }

                LocalTime lastTime = LocalTime.parse(currentGroup.endTime, dtf);
                long gapSeconds = java.time.Duration.between(lastTime, pTime).getSeconds();
                long durationSeconds = java.time.Duration.between(groupStart, pTime).getSeconds();

                if (gapSeconds <= 10 && durationSeconds < 60) {
                    currentGroup.endTime = p.startTime;
                    if (p.minDistanceFt < currentGroup.minDistanceFt) {
                        currentGroup.minDistanceFt = p.minDistanceFt;
                        currentGroup.lat1 = p.lat1;
                        currentGroup.lon1 = p.lon1;
                        currentGroup.alt1 = p.alt1;
                        currentGroup.ias1 = p.ias1;
                        currentGroup.lat2 = p.lat2;
                        currentGroup.lon2 = p.lon2;
                        currentGroup.alt2 = p.alt2;
                        currentGroup.ias2 = p.ias2;
                    }
                } else {
                    finalEvents.add(currentGroup);
                    currentGroup = p;
                    groupStart = pTime;
                }
            }
            if (currentGroup != null)
                finalEvents.add(currentGroup);
        }
        return finalEvents;
    }
}
