package com.skidreport.db;

import com.skidreport.model.AircraftSnapshot;
import com.skidreport.model.NearMissFlightRecord;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runtime exercise of the flight_records schema against real SQLite: proves
 * AltMSL round-trips end to end (Inserter stores alt_msl, loadByDate reads it
 * back into the snapshot used for distance), and that the (tail,
 * corrected-second) dedup for overlapping logs works (R5).
 */
public class FlightRecordDaoTest {

    private static NearMissFlightRecord rec(String tail, String date, String time,
                                            double altMsl, double ias) {
        NearMissFlightRecord r = new NearMissFlightRecord();
        r.tail = tail;
        r.date = date;
        r.time = time;
        r.lat = 37.78;
        r.lon = -89.25;
        r.altMsl = altMsl;          // this is what must round-trip into the snapshot
        r.ias = ias;
        r.rpm = 2000.0;
        return r;
    }

    @Test
    public void schemaRoundTripAndDedup() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseManager.createTablesAndIndexes(conn);

            try (FlightRecordDao.Inserter ins = FlightRecordDao.beginInsert(conn)) {
                ins.add(rec("82P", "2025-07-01", "07:00:00", 1450.0, 60.0));
                ins.add(rec("2JP", "2025-07-01", "07:00:00", 1550.0, 65.0));
                // duplicate (tail, second) for 82P from an overlapping log:
                ins.add(rec("82P", "2025-07-01", "07:00:00", 1460.0, 61.0));
                // a later second for 82P only:
                ins.add(rec("82P", "2025-07-01", "07:00:01", 1470.0, 62.0));
                assertEquals(4, ins.total());
            }

            Map<String, List<AircraftSnapshot>> byTime = new LinkedHashMap<>();
            int dup = FlightRecordDao.loadByDate(conn, "2025-07-01", byTime);

            assertEquals("one duplicate (82P, 07:00:00) row skipped", 1, dup);

            List<AircraftSnapshot> at7 = byTime.get("07:00:00");
            assertEquals("distinct tails 82P + 2JP at the same second", 2, at7.size());

            AircraftSnapshot p82 = at7.stream()
                    .filter(s -> s.tail.equals("82P")).findFirst().orElse(null);
            assertTrue(p82 != null);
            // First 82P row kept by id order -> AltMSL 1450 round-trips into the snapshot
            assertEquals(1450.0, p82.alt, 1e-9);

            assertEquals("second 07:00:01 has only 82P", 1, byTime.get("07:00:01").size());

            List<String> dates = FlightRecordDao.getAllDates(conn);
            assertEquals(1, dates.size());
            assertEquals("2025-07-01", dates.get(0));
        }
    }
}
