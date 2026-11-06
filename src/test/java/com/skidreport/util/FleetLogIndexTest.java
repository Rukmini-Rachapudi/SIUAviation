package com.skidreport.util;

import com.skidreport.util.FleetLogIndex.LogFile;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Logs are assigned to the aircraft whose avionics recorded them, not to the
 * folder they were copied into. Unit IDs below are the real fleet values.
 */
public class FleetLogIndexTest {

    private static final String UNIT_46A = "2DE35495F";
    private static final String UNIT_49A = "24D7DD08A";

    private static LogFile log(String name, String folderTail, String unit, String ident) {
        return new LogFile(new File(name), folderTail, unit, ident);
    }

    /** A folder of n logs from its own unit, so that unit is recognised as its owner. */
    private static List<LogFile> ownLogs(String tail, String unit, int n) {
        List<LogFile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(log(tail + "_own_" + i + ".csv", tail, unit, null));
        return out;
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    @Test
    public void misfiledLogMovesToTheAircraftThatRecordedIt() {
        List<LogFile> logs = new ArrayList<>();
        logs.addAll(ownLogs("46A", UNIT_46A, 3));
        logs.addAll(ownLogs("49A", UNIT_49A, 3));
        // N546A flight sitting in the N549A folder (real case: 23-11\log_231011_121341_KMDH.csv)
        logs.add(log("log_231011_121341_KMDH.csv", "49A", UNIT_46A, null));

        Map<String, List<File>> byTail = FleetLogIndex.assign(logs);

        assertTrue(names(byTail.get("46A")).contains("log_231011_121341_KMDH.csv"));
        assertFalse(names(byTail.get("49A")).contains("log_231011_121341_KMDH.csv"));
        assertEquals(4, byTail.get("46A").size());
        assertEquals(3, byTail.get("49A").size());
    }

    @Test
    public void logFromAircraftOutsideTheFleetIsSkipped() {
        List<LogFile> logs = new ArrayList<>(ownLogs("2JP", "20527ED5C", 3));
        // Real case: G3X logs from N5330U sitting in the N192JP folder
        logs.add(log("log_20250327_085808_KMDH.csv", "2JP", "6000265C23311", "N5330U"));

        Map<String, List<File>> byTail = FleetLogIndex.assign(logs);

        assertEquals(3, byTail.get("2JP").size());
        assertFalse(names(byTail.get("2JP")).contains("log_20250327_085808_KMDH.csv"));
    }

    @Test
    public void unknownUnitNamingAFleetTailGoesToThatTail() {
        List<LogFile> logs = new ArrayList<>();
        logs.addAll(ownLogs("46A", UNIT_46A, 2));
        logs.addAll(ownLogs("49A", UNIT_49A, 2));
        logs.add(log("new_unit.csv", "49A", "NEWUNIT1", "N546A"));

        Map<String, List<File>> byTail = FleetLogIndex.assign(logs);

        assertTrue(names(byTail.get("46A")).contains("new_unit.csv"));
    }

    @Test
    public void logWithNoIdentityStaysInItsFolder() {
        List<LogFile> logs = new ArrayList<>(ownLogs("49A", UNIT_49A, 2));
        logs.add(log("no_header.csv", "49A", null, null));
        logs.add(log("swapped_unit.csv", "49A", "UNLISTED", null));

        Map<String, List<File>> byTail = FleetLogIndex.assign(logs);

        assertTrue(names(byTail.get("49A")).containsAll(Arrays.asList("no_header.csv", "swapped_unit.csv")));
    }

    @Test
    public void filesAreSortedByName() {
        List<LogFile> logs = new ArrayList<>();
        logs.add(log("log_240306_100000_KMDH.csv", "49A", UNIT_49A, null));
        logs.add(log("log_240305_100000_KMDH.csv", "49A", UNIT_49A, null));

        List<String> order = names(FleetLogIndex.assign(logs).get("49A"));

        assertEquals(Arrays.asList("log_240305_100000_KMDH.csv", "log_240306_100000_KMDH.csv"), order);
    }
}
