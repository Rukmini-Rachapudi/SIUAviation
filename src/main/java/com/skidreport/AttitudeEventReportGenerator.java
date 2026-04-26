package com.skidreport;

import com.skidreport.detect.AttitudeEventDetector;
import com.skidreport.excel.AttitudeEventExcelWriter;
import com.skidreport.model.AttitudeEvent;
import com.skidreport.model.FlightRecord;
import com.skidreport.util.CsvParser;
import com.skidreport.util.DateUtils;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AttitudeEventReportGenerator
 *
 * Detects three event types from the flight CSVs and writes one Excel workbook
 * PER aircraft PER month, mirroring the SkidReportGenerator pattern.
 *
 *   Bank        : Roll < -60 OR Roll > 60
 *   High Pitch  : Pitch > 30
 *   Low Pitch   : Pitch < -30
 *
 * Window rule (all three): event opens at first trigger, stays alive while
 * gap-since-last-trigger <= 30 s, closes when 30 s pass with no trigger.
 *
 * RESILIENCE / MEMORY:
 *   - CSVs processed in batches of BATCH_SIZE per aircraft (progress feedback).
 *   - One detector per aircraft, kept alive across batches so an event spanning
 *     two CSVs still chains correctly.
 *   - When an aircraft finishes, its monthly workbooks are written to disk and
 *     in-memory state is dropped before moving to the next aircraft.
 *   - Ctrl+C mid-run: every aircraft already finished still has full output.
 *
 * NO in-flight filter -- every CSV row is evaluated.
 *
 * OUTPUT:
 *   Output/<dayFolder>/Bank Pitch Events/
 *     BankPitch_Reports_<tail>/
 *       BankPitch_<tail>_<YYYY>_<MM>_<Month>.xlsx
 */
public class AttitudeEventReportGenerator {

    static final String INPUT_PATH  = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Input";
    static final String OUTPUT_PATH = "C:\\Users\\SIU950304093\\Documents\\Skids Monthly Reports\\Output";

    private static final int BATCH_SIZE = 10;

    private static final String[] ALL_TAIL_NUMBERS = {
            "41E","3FS","4FS","5FS","46A","47E","48A","49A",
            "13N","31T","41K","61J","83H","2JP",
            "06H","97B","59Y","78K","49K","29E","82P"
    };

    public static void main(String[] args) throws IOException {
        String dayFolder = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();

        File rootDir   = new File(INPUT_PATH);
        File outputDir = new File(OUTPUT_PATH + "\\" + dayFolder + "\\Bank Pitch Events");

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        System.out.println("==============================================");
        System.out.println("  BANK / PITCH EVENT GENERATOR");
        System.out.println("==============================================");
        System.out.println("Input      : " + rootDir.getAbsolutePath());
        System.out.println("Output     : " + outputDir.getAbsolutePath());
        System.out.println("Batch size : " + BATCH_SIZE);

        File[] subdirs = rootDir.listFiles(File::isDirectory);
        if (subdirs == null || subdirs.length == 0) {
            System.out.println("No subdirectories found.");
            return;
        }

        int totalBank = 0, totalHigh = 0, totalLow = 0;
        boolean anyProcessed = false;

        for (File dir : subdirs) {
            String tail = detectTailNumber(dir.getName());
            if (tail == null) continue;
            anyProcessed = true;

            System.out.println("\n--- Processing " + tail + " ---");
            int[] counts = processFlightFolder(tail, dir, outputDir);
            totalBank += counts[0];
            totalHigh += counts[1];
            totalLow  += counts[2];
        }

        if (!anyProcessed) {
            System.out.println("No matching flight subfolders found.");
            return;
        }

        System.out.println("\n==============================================");
        System.out.println("  DONE");
        System.out.printf ("  Bank events       : %d%n", totalBank);
        System.out.printf ("  High-pitch events : %d%n", totalHigh);
        System.out.printf ("  Low-pitch events  : %d%n", totalLow);
        System.out.println("  Output            : " + outputDir.getAbsolutePath());
        System.out.println("==============================================");
    }

    // ------------------------------------------------------------------------
    // PER-AIRCRAFT
    // Returns int[3]: total bank, high-pitch, low-pitch events written.
    // ------------------------------------------------------------------------
    private static int[] processFlightFolder(String tail, File flightDir, File outputDir) {
        List<File> csvFiles = new ArrayList<>();
        CsvParser.collectCsvFiles(flightDir, csvFiles);
        csvFiles.sort((a, b) -> a.getName().compareTo(b.getName()));
        System.out.println("  CSV files found: " + csvFiles.size());

        if (csvFiles.isEmpty()) {
            System.out.println("  Nothing to process for " + tail);
            return new int[]{0, 0, 0};
        }

        // ONE detector instance per type, kept alive across all batches
        AttitudeEventDetector bank = new AttitudeEventDetector(
                tail, AttitudeEventDetector.BANK_TRIGGER, r -> r.roll);
        AttitudeEventDetector high = new AttitudeEventDetector(
                tail, AttitudeEventDetector.HIGH_PITCH_TRIGGER, r -> r.pitch);
        AttitudeEventDetector low  = new AttitudeEventDetector(
                tail, AttitudeEventDetector.LOW_PITCH_TRIGGER, r -> r.pitch);

        int totalBatches = (int) Math.ceil((double) csvFiles.size() / BATCH_SIZE);
        int batchNumber  = 0;

        for (int start = 0; start < csvFiles.size(); start += BATCH_SIZE) {
            batchNumber++;
            int end          = Math.min(start + BATCH_SIZE, csvFiles.size());
            List<File> batch = csvFiles.subList(start, end);

            System.out.printf("  [Batch %d/%d] Reading %d file(s)...%n",
                    batchNumber, totalBatches, batch.size());

            for (File csv : batch) {
                try {
                    List<FlightRecord> records = CsvParser.parseAttitudeCsvFile(csv);
                    for (FlightRecord rec : records) {
                        bank.accept(rec);
                        high.accept(rec);
                        low.accept(rec);
                    }
                } catch (Exception e) {
                    System.err.println("    [WARN] Skipping " + csv.getName() + ": " + e.getMessage());
                }
            }
        }

        // Flush all open events
        List<AttitudeEvent> bankEvents = bank.flush();
        List<AttitudeEvent> highEvents = high.flush();
        List<AttitudeEvent> lowEvents  = low.flush();

        System.out.printf("  All batches done -- bank=%d, highPitch=%d, lowPitch=%d%n",
                bankEvents.size(), highEvents.size(), lowEvents.size());

        // Group by YYYY-MM and write one workbook per month
        Map<String, List<AttitudeEvent>> bankByMonth = groupByMonth(bankEvents);
        Map<String, List<AttitudeEvent>> highByMonth = groupByMonth(highEvents);
        Map<String, List<AttitudeEvent>> lowByMonth  = groupByMonth(lowEvents);

        // Union of all months that had at least one event of any type
        TreeMap<String, Boolean> monthsToWrite = new TreeMap<>();
        for (String m : bankByMonth.keySet()) monthsToWrite.put(m, Boolean.TRUE);
        for (String m : highByMonth.keySet()) monthsToWrite.put(m, Boolean.TRUE);
        for (String m : lowByMonth.keySet())  monthsToWrite.put(m, Boolean.TRUE);

        if (monthsToWrite.isEmpty()) {
            System.out.println("  No events found for " + tail);
            return new int[]{0, 0, 0};
        }

        for (String yearMonth : monthsToWrite.keySet()) {
            List<AttitudeEvent> b = bankByMonth.getOrDefault(yearMonth, java.util.Collections.emptyList());
            List<AttitudeEvent> h = highByMonth.getOrDefault(yearMonth, java.util.Collections.emptyList());
            List<AttitudeEvent> l = lowByMonth.getOrDefault(yearMonth, java.util.Collections.emptyList());
            AttitudeEventExcelWriter.write(tail, yearMonth, outputDir, b, h, l);
        }

        System.out.printf("  Wrote %d monthly workbook(s) for %s.%n",
                monthsToWrite.size(), tail);

        return new int[]{ bankEvents.size(), highEvents.size(), lowEvents.size() };
    }

    private static Map<String, List<AttitudeEvent>> groupByMonth(List<AttitudeEvent> events) {
        Map<String, List<AttitudeEvent>> byMonth = new LinkedHashMap<>();
        for (AttitudeEvent ev : events) {
            String key = DateUtils.yearMonthKey(ev.date);
            byMonth.computeIfAbsent(key, k -> new ArrayList<>()).add(ev);
        }
        return byMonth;
    }

    private static String detectTailNumber(String folderName) {
        for (String tail : ALL_TAIL_NUMBERS) {
            if (folderName.contains(tail)) return tail;
        }
        return null;
    }
}
