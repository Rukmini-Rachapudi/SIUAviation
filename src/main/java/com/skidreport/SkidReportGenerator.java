package com.skidreport;

import com.skidreport.csv.SkidCsvWriter;
import com.skidreport.detect.SkidEventDetector;
import com.skidreport.excel.SkidExcelWriter;
import com.skidreport.model.SkidEvent;
import com.skidreport.util.CsvParser;
import com.skidreport.util.DateUtils;
import com.skidreport.util.DuplicateRowFilter;
import com.skidreport.util.FleetLogIndex;

import java.io.File;
import java.io.IOException;
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
 * For each aircraft:
 *   1. Takes its CSV files from FleetLogIndex (assigned by the log's own
 *      avionics system_id, so misfiled logs land on the right aircraft)
 *   2. Processes in batches of BATCH_SIZE, dropping any row whose second was
 *      already read from another file (DuplicateRowFilter)
 *   3. Feeds every record into a SkidEventDetector (one event = run of
 *      consecutive seconds satisfying the skid condition; any single
 *      non-triggering record closes the event; missing rows transparent)
 *   4. Tags each event as low-altitude if any record inside the window
 *      satisfied alt < 1400 AND (decreasing IAS OR increasing pitch)
 *   5. Groups detected events by YYYY-MM and writes one Excel file per
 *      month per aircraft (one row per event)
 */
public class SkidReportGenerator {

    // -- EDIT THESE TWO PATHS ------------------------------------------------
    static final String INPUT_PATH  = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";
    // ------------------------------------------------------------------------

    private static final int BATCH_SIZE = 10;

    public static void main(String[] args) throws IOException {
        File rootDir   = new File(INPUT_PATH);
        File outputDir = new File(OUTPUT_PATH);

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        System.out.println("Input      : " + INPUT_PATH);
        System.out.println("Output     : " + outputDir.getAbsolutePath());
        System.out.println("Batch size : " + BATCH_SIZE);

        // Every log is assigned to the aircraft that recorded it (from its
        // system_id), not to the folder it happens to sit in.
        Map<String, List<File>> filesByTail = FleetLogIndex.filesByTail(rootDir);

        try {
            SkidCsvWriter.beginConsolidated();
        } catch (IOException e) {
            System.err.println("  [WARN] Could not open consolidated skid CSV: " + e.getMessage());
        }

        boolean anyProcessed = false;
        for (Map.Entry<String, List<File>> entry : filesByTail.entrySet()) {
            String tail = entry.getKey();
            System.out.println("\n--- Processing " + tail + " ---");
            processFlightFiles(tail, entry.getValue(), outputDir);
            anyProcessed = true;
        }

        try {
            SkidCsvWriter.endConsolidated();
        } catch (IOException e) {
            System.err.println("  [WARN] Could not close consolidated skid CSV: " + e.getMessage());
        }

        if (!anyProcessed) {
            System.out.println("No matching flight subfolders found.");
        }

        System.out.println("\nSkid reports written to: " + outputDir.getAbsolutePath());
    }

    // ------------------------------------------------------------------------
    // ONE AIRCRAFT
    // csvFiles come from FleetLogIndex already sorted by name (= time order,
    // with copies of the same log side by side for DuplicateRowFilter).
    // ------------------------------------------------------------------------
    private static void processFlightFiles(String tail, List<File> csvFiles, File outputDir) {
        System.out.println("  CSV files found: " + csvFiles.size());

        if (csvFiles.isEmpty()) {
            System.out.println("  Nothing to process for " + tail);
            return;
        }

        processBatches(tail, csvFiles, outputDir);
    }

    // ------------------------------------------------------------------------
    // BATCH PROCESSOR
    // Reads BATCH_SIZE files at a time, feeds them into a per-aircraft
    // SkidEventDetector (kept alive across batches so events spanning two CSVs
    // chain correctly), then groups detected events by YYYY-MM and writes one
    // Excel per month after all batches complete.
    // ------------------------------------------------------------------------
    private static void processBatches(String tail, List<File> allFiles, File outputDir) {

        SkidEventDetector detector = new SkidEventDetector(tail);
        DuplicateRowFilter dedup   = new DuplicateRowFilter();

        int totalBatches = (int) Math.ceil((double) allFiles.size() / BATCH_SIZE);
        int batchNumber  = 0;

        for (int start = 0; start < allFiles.size(); start += BATCH_SIZE) {
            batchNumber++;
            int end   = Math.min(start + BATCH_SIZE, allFiles.size());
            List<File> batch = allFiles.subList(start, end);

            System.out.printf("  [Batch %d/%d] Reading %d file(s)...%n",
                    batchNumber, totalBatches, batch.size());

            for (File csv : batch) {
                try {
                    dedup.startFile(csv);
                    CsvParser.streamSkidCsvFile(csv, rec -> {
                        if (dedup.firstSighting(rec)) detector.accept(rec);
                    });
                } catch (Exception e) {
                    System.err.println("    [WARN] Skipping " + csv.getName() + ": " + e.getMessage());
                }
            }

            System.out.printf("  [Batch %d/%d] Read complete.%n", batchNumber, totalBatches);
        }

        List<SkidEvent> allEvents = detector.flush();
        System.out.printf("  All batches done -- %d skid event(s) detected.%n", allEvents.size());
        if (dedup.skipped() > 0) {
            System.out.printf("  Skipped %d duplicate row(s) -- same flight logged in more than one file.%n",
                    dedup.skipped());
        }

        if (allEvents.isEmpty()) {
            System.out.println("  No skid events found for " + tail);
            return;
        }

        // Group by YYYY-MM (using the event's start date)
        Map<String, List<SkidEvent>> byMonth = new LinkedHashMap<>();
        for (SkidEvent ev : allEvents) {
            String monthKey = DateUtils.yearMonthKey(ev.date);
            byMonth.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(ev);
        }

        int totalEvents = 0;
        for (Map.Entry<String, List<SkidEvent>> entry : new TreeMap<>(byMonth).entrySet()) {
            SkidExcelWriter.write(tail, entry.getKey(), entry.getValue(), outputDir);
            SkidCsvWriter.write(tail, entry.getKey(), entry.getValue());
            totalEvents += entry.getValue().size();
        }

        System.out.printf("  Done: %d total skid event(s) across %d monthly file(s).%n",
                totalEvents, byMonth.size());
    }
}
