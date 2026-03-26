package com.skidreport.util;

import com.skidreport.model.FlightRecord;
import com.skidreport.model.NearMissFlightRecord;

import java.io.*;
import java.util.*;

/**
 * CsvParser
 *
 * All CSV file collection and parsing logic.
 * CSV structure (same for all files):
 *   Row 0 = metadata
 *   Row 1 = units
 *   Row 2 = column headers
 *   Row 3+ = data rows
 *   Last row = footer (skipped)
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

    // -- Near miss column names ----------------------------------------------
    private static final String H_LAT = "Latitude";
    private static final String H_LON = "Longitude";
    private static final String H_RPM = "E1 RPM";

    private static final String[] NEAR_MISS_REQUIRED_COLS = {
            H_DATE, H_TIME, H_LAT, H_LON, H_ALT, H_IAS, H_RPM
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
    // PARSE SKID CSV
    // Returns list of FlightRecord for skid report processing.
    // ========================================================================
    public static List<FlightRecord> parseSkidCsvFile(File file) throws IOException {
        List<String> lines = readLines(file);
        if (lines.size() < 4) return Collections.emptyList();

        Map<String, Integer> colIndex = buildColIndex(lines.get(2));

        for (String req : SKID_REQUIRED_COLS) {
            if (!colIndex.containsKey(req)) {
                System.out.println("    Skipping " + file.getName() + " -- missing column: " + req);
                return Collections.emptyList();
            }
        }

        List<FlightRecord> records = new ArrayList<>();
        int lastDataLine = lines.size() - 1;
        for (int i = 3; i < lastDataLine; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] cols = splitCsv(line);
            try {
                String date = getCol(cols, colIndex, H_DATE).trim();
                String time = getCol(cols, colIndex, H_TIME).trim();
                if (date.isEmpty() || time.isEmpty()) continue;

                FlightRecord rec = new FlightRecord();
                rec.date  = date;
                rec.time  = time;
                rec.pitch = parseDouble(getCol(cols, colIndex, H_PITCH));
                rec.roll  = parseDouble(getCol(cols, colIndex, H_ROLL));
                rec.latAc = parseDouble(getCol(cols, colIndex, H_LATAC));
                rec.ias   = parseDouble(getCol(cols, colIndex, H_IAS));
                rec.alt   = parseDouble(getCol(cols, colIndex, H_ALT));

                if (!Double.isNaN(rec.roll) && !Double.isNaN(rec.latAc)) {
                    records.add(rec);
                }
            } catch (Exception ignored) {}
        }
        return records;
    }

    // ========================================================================
    // PARSE NEAR MISS CSV
    // Returns list of NearMissFlightRecord for near-miss processing.
    // ========================================================================
    public static List<NearMissFlightRecord> parseNearMissCsvFile(File file, String tail)
            throws IOException {

        List<String> lines = readLines(file);
        if (lines.size() < 4) return Collections.emptyList();

        Map<String, Integer> colIndex = buildColIndex(lines.get(2));

        for (String req : NEAR_MISS_REQUIRED_COLS) {
            if (!colIndex.containsKey(req)) {
                System.out.println("    Skipping " + file.getName() + " -- missing column: " + req);
                return Collections.emptyList();
            }
        }

        List<NearMissFlightRecord> records = new ArrayList<>();
        int lastDataLine = lines.size() - 1;
        for (int i = 3; i < lastDataLine; i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] cols = splitCsv(line);
            try {
                String date = getCol(cols, colIndex, H_DATE).trim();
                String time = getCol(cols, colIndex, H_TIME).trim();
                if (date.isEmpty() || time.isEmpty()) continue;

                NearMissFlightRecord rec = new NearMissFlightRecord();
                rec.tail = tail;
                rec.date = date;
                rec.time = DateUtils.normalizeTime(time);
                rec.lat  = parseDouble(getCol(cols, colIndex, H_LAT));
                rec.lon  = parseDouble(getCol(cols, colIndex, H_LON));
                rec.alt  = parseDouble(getCol(cols, colIndex, H_ALT));
                rec.ias  = parseDouble(getCol(cols, colIndex, H_IAS));
                rec.rpm  = parseDouble(getCol(cols, colIndex, H_RPM));

                if (Double.isNaN(rec.lat) || Double.isNaN(rec.lon)
                        || Double.isNaN(rec.alt) || Double.isNaN(rec.ias)
                        || Double.isNaN(rec.rpm)) continue;

                records.add(rec);
            } catch (Exception ignored) {}
        }
        return records;
    }

    // ========================================================================
    // INTERNAL HELPERS
    // ========================================================================

    private static Map<String, Integer> buildColIndex(String headerLine) {
        String[] headers = splitCsv(headerLine);
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colIndex.put(headers[i].trim(), i);
        }
        return colIndex;
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "ISO-8859-1"))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
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
