package com.skidreport.util;

import com.skidreport.model.FlightRecord;
import com.skidreport.model.NearMissFlightRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CsvParser
 *
 * All CSV file collection and parsing logic.
 *
 * CSV structure (same for all files):
 *   Row 0 = metadata
 *   Row 1 = units
 *   Row 2 = column headers
 *   Row 3+ = data rows
 *   Last row = footer (skipped)
 *
 * Parsing is streaming: each file is read one line at a time via BufferedReader
 * and records are emitted to the caller's Consumer as they are parsed. The full
 * file is never held in memory.
 */
public class CsvParser {

    // -- Skid report column names --------------------------------------------
    private static final String H_DATE  = "Lcl Date";
    private static final String H_TIME  = "Lcl Time";
    private static final String H_PITCH = "Pitch";
    private static final String H_ROLL  = "Roll";
    private static final String H_LATAC = "LatAc";
    private static final String H_IAS   = "IAS";
    private static final String H_ALT   = "AltMSL";

    private static final String[] SKID_REQUIRED_COLS = {
            H_DATE, H_TIME, H_PITCH, H_ROLL, H_LATAC, H_IAS, H_ALT
    };

    // Bank/pitch event detection only needs date, time, pitch, roll.
    // AltMSL / IAS / LatAc are NOT required so we can read older logs that omit them.
    private static final String[] ATTITUDE_REQUIRED_COLS = {
            H_DATE, H_TIME, H_PITCH, H_ROLL
    };

    // -- Near miss column names ----------------------------------------------
    private static final String H_LAT     = "Latitude";
    private static final String H_LON     = "Longitude";
    private static final String H_RPM     = "E1 RPM";
    private static final String H_UTCOFST = "UTCOfst";  // row's claimed offset, ±hh:mm

    // AltMSL is both the altitude floor (R2) and the separation altitude (R3);
    // UTCOfst is required so every timestamp can be corrected to Central time (R1).
    private static final String[] NEAR_MISS_REQUIRED_COLS = {
            H_DATE, H_TIME, H_UTCOFST, H_LAT, H_LON, H_ALT, H_IAS, H_RPM
    };

    // ========================================================================
    // FILE COLLECTION
    // Recursively walks a directory tree collecting qualifying CSV files.
    // Skips: files containing "master" or "skid" in name, and "000" files.
    // ========================================================================
    public static void collectCsvFiles(File dir, List<File> result) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                collectCsvFiles(f, result);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".csv")
                        && !name.contains("master")
                        && !name.contains("skid")) {
                    if (f.getName().length() > 7 && f.getName().substring(4, 7).equals("000")) {
                        continue;
                    }
                    result.add(f);
                }
            }
        }
    }

    // ========================================================================
    // STREAM SKID CSV
    // Emits one FlightRecord per qualifying data row.
    // ========================================================================
    public static void streamSkidCsvFile(File file, Consumer<FlightRecord> sink)
            throws IOException {

        try (BufferedReader br = openReader(file)) {
            Map<String, Integer> colIndex =
                    readHeaderAndValidate(br, file, SKID_REQUIRED_COLS);
            if (colIndex == null) return;

            streamDataRows(br, line -> {
                String[] cols = splitCsv(line);
                try {
                    String date = getCol(cols, colIndex, H_DATE).trim();
                    String time = getCol(cols, colIndex, H_TIME).trim();
                    if (date.isEmpty() || time.isEmpty()) return;

                    FlightRecord rec = new FlightRecord();
                    rec.date  = date;
                    rec.time  = time;
                    rec.pitch = parseDouble(getCol(cols, colIndex, H_PITCH));
                    rec.roll  = parseDouble(getCol(cols, colIndex, H_ROLL));
                    rec.latAc = parseDouble(getCol(cols, colIndex, H_LATAC));
                    rec.ias   = parseDouble(getCol(cols, colIndex, H_IAS));
                    rec.alt   = parseDouble(getCol(cols, colIndex, H_ALT));

                    if (!Double.isNaN(rec.roll) && !Double.isNaN(rec.latAc)) {
                        sink.accept(rec);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    // ========================================================================
    // STREAM ATTITUDE CSV
    // Emits one FlightRecord (date/time/pitch/roll) per qualifying row.
    // AltMSL / IAS / LatAc are optional so older logs that omit them still work.
    // ========================================================================
    public static void streamAttitudeCsvFile(File file, Consumer<FlightRecord> sink)
            throws IOException {

        try (BufferedReader br = openReader(file)) {
            Map<String, Integer> colIndex =
                    readHeaderAndValidate(br, file, ATTITUDE_REQUIRED_COLS);
            if (colIndex == null) return;

            streamDataRows(br, line -> {
                String[] cols = splitCsv(line);
                try {
                    String date = getCol(cols, colIndex, H_DATE).trim();
                    String time = getCol(cols, colIndex, H_TIME).trim();
                    if (date.isEmpty() || time.isEmpty()) return;

                    FlightRecord rec = new FlightRecord();
                    rec.date  = date;
                    rec.time  = time;
                    rec.pitch = parseDouble(getCol(cols, colIndex, H_PITCH));
                    rec.roll  = parseDouble(getCol(cols, colIndex, H_ROLL));

                    if (!Double.isNaN(rec.pitch) && !Double.isNaN(rec.roll)) {
                        sink.accept(rec);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    // ========================================================================
    // STREAM NEAR MISS CSV
    // Emits one NearMissFlightRecord per qualifying row.
    // ========================================================================
    public static void streamNearMissCsvFile(File file, String tail,
                                             Consumer<NearMissFlightRecord> sink)
            throws IOException {

        try (BufferedReader br = openReader(file)) {
            Map<String, Integer> colIndex =
                    readHeaderAndValidate(br, file, NEAR_MISS_REQUIRED_COLS);
            if (colIndex == null) return;

            streamDataRows(br, line -> {
                String[] cols = splitCsv(line);
                try {
                    String date   = getCol(cols, colIndex, H_DATE).trim();
                    String time   = getCol(cols, colIndex, H_TIME).trim();
                    String offset = getCol(cols, colIndex, H_UTCOFST).trim();
                    // Pre-GPS / NoSoln rows blank these out -- skip; they must
                    // never reach the detector (and can't be time-corrected).
                    if (date.isEmpty() || time.isEmpty() || offset.isEmpty()) return;

                    NearMissFlightRecord rec = new NearMissFlightRecord();
                    rec.tail = tail;

                    // R1: correct the recorded timestamp to true Central local
                    // time before anything downstream uses date/time.
                    java.time.LocalDateTime corrected =
                            DateUtils.correctToCentral(date, time, offset);
                    rec.date = corrected.toLocalDate().toString();        // yyyy-MM-dd
                    rec.time = DateUtils.toHms(corrected.toLocalTime());  // HH:MM:SS

                    rec.lat    = parseDouble(getCol(cols, colIndex, H_LAT));
                    rec.lon    = parseDouble(getCol(cols, colIndex, H_LON));
                    rec.altMsl = parseDouble(getCol(cols, colIndex, H_ALT));      // floor (R2) + distance (R3)
                    rec.ias    = parseDouble(getCol(cols, colIndex, H_IAS));
                    rec.rpm    = parseDouble(getCol(cols, colIndex, H_RPM));

                    if (Double.isNaN(rec.lat) || Double.isNaN(rec.lon)
                            || Double.isNaN(rec.altMsl)
                            || Double.isNaN(rec.ias) || Double.isNaN(rec.rpm)) return;

                    sink.accept(rec);
                } catch (Exception ignored) {}
            });
        }
    }

    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================

    private static BufferedReader openReader(File file) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "ISO-8859-1"));
    }

    /**
     * Skips the metadata and units rows, parses the header row, and verifies
     * every required column is present. Returns the column-index map on
     * success, or {@code null} if a required column is missing (a warning is
     * printed in that case).
     */
    private static Map<String, Integer> readHeaderAndValidate(
            BufferedReader br, File file, String[] requiredCols) throws IOException {

        br.readLine(); // row 0: metadata
        br.readLine(); // row 1: units
        String headerLine = br.readLine();
        if (headerLine == null) return null;

        Map<String, Integer> colIndex = buildColIndex(headerLine);
        for (String req : requiredCols) {
            if (!colIndex.containsKey(req)) {
                System.out.println("    Skipping " + file.getName()
                        + " -- missing column: " + req);
                return null;
            }
        }
        return colIndex;
    }

    /**
     * Reads data rows from the current reader position to EOF, calling
     * {@code rowSink} for every non-blank line EXCEPT the final non-blank one.
     * The last data line of a Garmin log is a footer (and is frequently a row
     * truncated mid-write), so it is always dropped -- and dropped correctly
     * even when the file ends with one or more trailing blank lines, by holding
     * each non-blank line back until we know it is not the last. At most two
     * lines are held in memory at a time.
     */
    private static void streamDataRows(BufferedReader br, Consumer<String> rowSink)
            throws IOException {

        String pending = null;   // last non-blank line seen, held back as the footer candidate
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;            // skip blank lines entirely
            if (pending != null) rowSink.accept(pending);
            pending = trimmed;
        }
        // 'pending' is now the final non-blank line (footer/partial) -- dropped.
    }

    private static Map<String, Integer> buildColIndex(String headerLine) {
        String[] headers = splitCsv(headerLine);
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].trim(), i);
        }
        return colIndex;
    }

    private static String[] splitCsv(String line) {
        return line.split("\\s*,\\s*", -1);
    }

    private static String getCol(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        return (i != null && i < cols.length) ? cols[i] : "";
    }

    private static double parseDouble(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }
}
