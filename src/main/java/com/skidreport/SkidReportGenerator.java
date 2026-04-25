package com.skidreport;

import com.skidreport.db.DatabaseManager;
import com.skidreport.db.FlightRecordDao;
import com.skidreport.excel.SkidExcelWriter;
import com.skidreport.model.FlightRecord;
import com.skidreport.model.SkidEvent;
import com.skidreport.model.SkidMinuteAccumulator;
import com.skidreport.util.CsvParser;
import com.skidreport.util.DateUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SkidReportGenerator
 *
 * Orchestrates skid report generation for all aircraft.
 *
 * For each aircraft folder:
 *   1. Collects all CSV files
 *   2. Processes in batches of BATCH_SIZE
 *   3. Applies skid + low-altitude skid detection
 *   4. Groups by (date, HH:MM) -- one row per minute
 *   5. Writes one Excel file per month per aircraft
 */
public class SkidReportGenerator {

    // -- EDIT THESE TWO PATHS ------------------------------------------------
    static final String INPUT_PATH  = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";
    // ------------------------------------------------------------------------

    private static final int BATCH_SIZE = 10;

    private static final String[] ALL_TAIL_NUMBERS = {
            "41E","3FS","4FS","5FS","46A","47E","48A","49A",
            "13N","31T","41K","61J","83H","2JP",
            "06H","97B","59Y","78K","49K","29E","82P"
    };

    public static void main(String[] args) throws Exception {
        String dayFolder = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();  // e.g. "4-apr-2026"

        File rootDir   = new File(INPUT_PATH);
        File outputDir = new File(OUTPUT_PATH + "\\" + dayFolder + "\\Skid Reports");

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        File nmDir = new File(OUTPUT_PATH + "\\" + dayFolder + "\\NearMiss");
        nmDir.mkdirs();
        String dbPath = nmDir.getAbsolutePath() + "\\near_miss.db";

        System.out.println("Input      : " + INPUT_PATH);
        System.out.println("Output     : " + outputDir.getAbsolutePath());
        System.out.println("Database   : " + dbPath);
        System.out.println("Batch size : " + BATCH_SIZE);

        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            applyPragmas(conn);
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
     * Core processing logic - called by main() and by MainRunner (shared connection).
     */
    public static void run(Connection conn, File rootDir, File outputDir) throws Exception {
        outputDir.mkdirs();

        System.out.println("\nInitializing database...");
        DatabaseManager.createTablesIfAbsent(conn);
        conn.commit();

        File[] subdirs = rootDir.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) {
            System.out.println("No subdirectories found.");
            return;
        }

        boolean anyProcessed = false;
        for (File dir : subdirs) {
            String tail = detectTailNumber(dir.getName());
            if (tail != null) {
                System.out.println("\n--- Processing " + tail + " ---");
                processFlightFolder(conn, tail, dir, outputDir);
                anyProcessed = true;
            }
        }

        if (!anyProcessed) {
            System.out.println("No matching flight subfolders found.");
        }

        System.out.println("\nSkid reports written to: " + outputDir.getAbsolutePath());
    }

    static void applyPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA cache_size=10000");
        }
    }

    // ------------------------------------------------------------------------
    // FLIGHT FOLDER
    // ------------------------------------------------------------------------
    private static void processFlightFolder(Connection conn, String tail, File flightDir, File outputDir) {
        List<File> csvFiles = new ArrayList<>();
        CsvParser.collectCsvFiles(flightDir, csvFiles);
        System.out.println("  CSV files found: " + csvFiles.size());

        if (csvFiles.isEmpty()) {
            System.out.println("  Nothing to process for " + tail);
            return;
        }

        processBatches(conn, tail, csvFiles, outputDir);
    }

    // ------------------------------------------------------------------------
    // BATCH PROCESSOR
    // Reads BATCH_SIZE files at a time, accumulates skid events into byMonth,
    // then writes one Excel per month after all batches complete.
    // ------------------------------------------------------------------------
    private static void processBatches(Connection conn, String tail, List<File> allFiles, File outputDir) {

        // key: YYYY-MM
        Map<String, List<SkidEvent>> byMonth = new LinkedHashMap<>();

        int totalBatches = (int) Math.ceil((double) allFiles.size() / BATCH_SIZE);
        int batchNumber  = 0;

        for (int start = 0; start < allFiles.size(); start += BATCH_SIZE) {
            batchNumber++;
            int end   = Math.min(start + BATCH_SIZE, allFiles.size());
            List<File> batch = allFiles.subList(start, end);

            System.out.printf("  [Batch %d/%d] Reading %d file(s)...%n",
                    batchNumber, totalBatches, batch.size());

            // date|HH:MM -> accumulator
            Map<String, SkidMinuteAccumulator> minuteMap = new LinkedHashMap<>();

            for (File csv : batch) {
                try {
                    List<FlightRecord> records = CsvParser.parseSkidCsvFile(csv, tail);
                    
                    // SAVE RELEVANT RECORDS TO DATABASE (for near-miss comparison later)
                    // We filter for "In Flight" data to keep DB size small and analysis fast
                    List<FlightRecord> flightOnly = new ArrayList<>();
                    for (FlightRecord r : records) {
                        if (r.ias > 30 && r.alt > 100 && r.rpm > 1700) {
                            flightOnly.add(r);
                        }
                    }
                    FlightRecordDao.insertBatch(conn, flightOnly);
                    conn.commit();

                    FlightRecord prevRec = null;
                    for (FlightRecord rec : records) {

                        // SKID CONDITION
                        boolean isSkid = (rec.roll < -10 && rec.latAc > 0.2)
                                      || (rec.roll >  10 && rec.latAc < -0.2);

                        // LOW-ALTITUDE SKID CONDITION
                        boolean isLowAlt = false;
                        if (isSkid && prevRec != null) {
                            boolean decreasingIas   = rec.ias   < prevRec.ias;
                            boolean increasingPitch = rec.pitch > prevRec.pitch;
                            if (rec.alt < 1400 && (decreasingIas || increasingPitch)) {
                                isLowAlt = true;
                            }
                        }

                        prevRec = rec;

                        if (!isSkid) continue;

                        String hhmm = DateUtils.toHHMM(rec.time);
                        String key  = rec.date + "|" + hhmm;

                        SkidMinuteAccumulator acc = minuteMap.get(key);
                        if (acc == null) {
                            acc = new SkidMinuteAccumulator(rec.date, hhmm);
                            minuteMap.put(key, acc);
                        }
                        acc.add(rec.pitch, rec.roll, rec.latAc, rec.ias, rec.alt, isLowAlt);
                    }

                } catch (Exception e) {
                    System.err.println("    [WARN] Skipping " + csv.getName() + ": " + e.getMessage());
                }
            }

            int eventsInBatch = 0;
            for (SkidMinuteAccumulator acc : minuteMap.values()) {
                String monthKey = DateUtils.yearMonthKey(acc.date);
                byMonth.computeIfAbsent(monthKey, k -> new ArrayList<>())
                       .add(acc.toSkidEvent());
                eventsInBatch++;
            }

            System.out.printf("  [Batch %d/%d] Complete -- %d skid-minute(s) found.%n",
                    batchNumber, totalBatches, eventsInBatch);
        }

        System.out.println("  All batches done. Writing Excel files...");

        if (byMonth.isEmpty()) {
            System.out.println("  No skid events found for " + tail);
            return;
        }

        int totalEvents = 0;
        for (Map.Entry<String, List<SkidEvent>> entry : new TreeMap<>(byMonth).entrySet()) {
            SkidExcelWriter.write(tail, entry.getKey(), entry.getValue(), outputDir);
            totalEvents += entry.getValue().size();
        }

        System.out.printf("  Done: %d total skid-minute(s) across %d monthly file(s).%n",
                totalEvents, byMonth.size());
    }

    // ------------------------------------------------------------------------
    // DETECT TAIL NUMBER FROM FOLDER NAME
    // ------------------------------------------------------------------------
    private static String detectTailNumber(String folderName) {
        for (String tail : ALL_TAIL_NUMBERS) {
            if (folderName.contains(tail)) return tail;
        }
        return null;
    }
}
