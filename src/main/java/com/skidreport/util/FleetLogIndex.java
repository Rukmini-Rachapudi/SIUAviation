package com.skidreport.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FleetLogIndex
 *
 * Decides which aircraft every log belongs to from the log itself, not from the
 * folder it was copied into. Logs are regularly misfiled -- e.g. N546A flights
 * sitting in the N549A folder -- which credits events to the wrong aircraft and
 * makes one aircraft appear in two places at the same second.
 *
 * Line 1 of every Garmin log carries system_id="..." (the avionics unit's serial)
 * and, on newer units, aircraft_ident="N....". Each fleet folder's most common
 * system_id is taken to be that aircraft's own unit. Then, per file:
 *   1. system_id is a fleet aircraft's unit     -> that aircraft
 *   2. else aircraft_ident names a fleet tail   -> that aircraft
 *      else aircraft_ident names another plane  -> skipped (not our fleet)
 *   3. no identifying info                      -> the folder's aircraft
 *
 * The scan reads only the first line of each file and is cached per input root,
 * so the three generators share one pass.
 */
public final class FleetLogIndex {

    private static final Pattern SYSTEM_ID      = Pattern.compile("system_id=\"([^\"]*)\"");
    private static final Pattern AIRCRAFT_IDENT = Pattern.compile("aircraft_ident=\"([^\"]*)\"");

    private static final Map<String, Map<String, List<File>>> CACHE = new HashMap<>();

    private FleetLogIndex() {}

    /** One log file and the identity fields from its first line. */
    static final class LogFile {
        final File   file;
        final String folderTail;
        final String systemId;   // null if absent
        final String ident;      // null if absent

        LogFile(File file, String folderTail, String systemId, String ident) {
            this.file = file;
            this.folderTail = folderTail;
            this.systemId = systemId;
            this.ident = ident;
        }
    }

    /**
     * Returns canonical tail -> that aircraft's log files (sorted by name), with
     * every file assigned to the aircraft that actually recorded it.
     */
    public static synchronized Map<String, List<File>> filesByTail(File inputRoot) {
        String key = inputRoot.getAbsolutePath();
        Map<String, List<File>> cached = CACHE.get(key);
        if (cached != null) return cached;

        List<LogFile> logs = new ArrayList<>();
        File[] dirs = inputRoot.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String tail = TailNumbers.detect(dir.getName());
                if (tail == null) continue;
                List<File> csvs = new ArrayList<>();
                CsvParser.collectCsvFiles(dir, csvs);
                for (File f : csvs) logs.add(readHeader(f, tail));
            }
        }

        Map<String, List<File>> result = assign(logs);
        CACHE.put(key, result);
        return result;
    }

    // ------------------------------------------------------------------------
    // assign: applies the three rules above and prints what moved or was skipped.
    // ------------------------------------------------------------------------
    static Map<String, List<File>> assign(List<LogFile> logs) {
        Map<String, String> unitOwner = ownUnits(logs);

        Map<String, List<File>> byTail = new TreeMap<>();
        Map<String, Integer> moved   = new TreeMap<>();   // "folder -> owner (unit)" -> count
        Map<String, Integer> skipped = new TreeMap<>();   // "folder: ident (unit)"   -> count

        for (LogFile lf : logs) {
            String owner = lf.systemId == null ? null : unitOwner.get(lf.systemId);
            if (owner == null && lf.ident != null && !lf.ident.isEmpty()) {
                owner = TailNumbers.detect(lf.ident);
                if (owner == null) {
                    skipped.merge(lf.folderTail + " folder: " + lf.ident
                            + " (unit " + lf.systemId + ")", 1, Integer::sum);
                    continue;
                }
            }
            if (owner == null) owner = lf.folderTail;

            if (!owner.equals(lf.folderTail)) {
                moved.merge(lf.folderTail + " folder -> " + owner
                        + " (unit " + lf.systemId + ")", 1, Integer::sum);
            }
            byTail.computeIfAbsent(owner, k -> new ArrayList<>()).add(lf.file);
        }

        // Name order = time order; also puts copies of one log side by side.
        for (List<File> files : byTail.values()) {
            files.sort((a, b) -> a.getName().compareTo(b.getName()));
        }

        if (!moved.isEmpty()) {
            System.out.println("  Misfiled logs reassigned to the aircraft that recorded them:");
            moved.forEach((k, n) -> System.out.printf("    %4d file(s)  %s%n", n, k));
        }
        if (!skipped.isEmpty()) {
            System.out.println("  Logs from aircraft outside the fleet (skipped):");
            skipped.forEach((k, n) -> System.out.printf("    %4d file(s)  %s%n", n, k));
        }
        return byTail;
    }

    // ------------------------------------------------------------------------
    // ownUnits: each folder's most common system_id is that aircraft's unit.
    // ------------------------------------------------------------------------
    static Map<String, String> ownUnits(List<LogFile> logs) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (LogFile lf : logs) {
            if (lf.systemId == null) continue;
            counts.computeIfAbsent(lf.folderTail, k -> new HashMap<>())
                  .merge(lf.systemId, 1, Integer::sum);
        }
        Map<String, String> unitOwner = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : counts.entrySet()) {
            String best = null;
            int bestN = 0;
            for (Map.Entry<String, Integer> u : e.getValue().entrySet()) {
                if (u.getValue() > bestN) { best = u.getKey(); bestN = u.getValue(); }
            }
            if (best != null) unitOwner.put(best, e.getKey());
        }
        return unitOwner;
    }

    private static LogFile readHeader(File f, String folderTail) {
        String line = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "ISO-8859-1"))) {
            line = br.readLine();
        } catch (IOException ignored) {}
        return new LogFile(f, folderTail, match(SYSTEM_ID, line), match(AIRCRAFT_IDENT, line));
    }

    private static String match(Pattern p, String line) {
        if (line == null) return null;
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1).trim() : null;
    }
}
