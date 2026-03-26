package com.skidreport.db;

import com.skidreport.model.AircraftSnapshot;
import com.skidreport.model.NearMissFlightRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FlightRecordDao
 *
 * All insert and fetch operations on the flight_records table.
 */
public class FlightRecordDao {

    private static final String INSERT_SQL =
        "INSERT INTO flight_records " +
        "  (tail, local_date, local_time, latitude, longitude, alt_msl, ias, e1_rpm) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    // ------------------------------------------------------------------------
    // INSERT: batch insert a list of NearMissFlightRecord rows
    // Flushes every 5000 rows to keep memory low
    // ------------------------------------------------------------------------
    public static void insertBatch(Connection conn,
                                   List<NearMissFlightRecord> records) throws Exception {
        if (records.isEmpty()) return;

        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            int count = 0;
            for (NearMissFlightRecord rec : records) {
                ps.setString(1, rec.tail);
                ps.setString(2, rec.date);
                ps.setString(3, rec.time);
                ps.setDouble(4, rec.lat);
                ps.setDouble(5, rec.lon);
                ps.setDouble(6, rec.alt);
                ps.setDouble(7, rec.ias);
                ps.setDouble(8, rec.rpm);
                ps.addBatch();
                count++;
                if (count % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch(); // flush remaining
        }
    }

    // ------------------------------------------------------------------------
    // FETCH: get all unique dates that have flight records
    // ------------------------------------------------------------------------
    public static List<String> getAllDates(Connection conn) throws Exception {
        List<String> dates = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT local_date FROM flight_records ORDER BY local_date")) {
            while (rs.next()) dates.add(rs.getString("local_date"));
        }
        return dates;
    }

    // ------------------------------------------------------------------------
    // FETCH: load all aircraft snapshots for a given date
    // Populates byTime map: HH:MM:SS -> list of AircraftSnapshot
    // Used by near-miss pairwise comparison
    // ------------------------------------------------------------------------
    public static void loadByDate(Connection conn, String date,
                                  Map<String, List<AircraftSnapshot>> byTime) throws Exception {
        String sql =
            "SELECT tail, local_time, latitude, longitude, alt_msl, ias " +
            "FROM flight_records " +
            "WHERE local_date = ? " +
            "ORDER BY local_time, tail";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AircraftSnapshot snap = new AircraftSnapshot();
                    snap.tail = rs.getString("tail");
                    snap.lat  = rs.getDouble("latitude");
                    snap.lon  = rs.getDouble("longitude");
                    snap.alt  = rs.getDouble("alt_msl");
                    snap.ias  = rs.getDouble("ias");
                    String time = rs.getString("local_time");
                    byTime.computeIfAbsent(time, k -> new ArrayList<>()).add(snap);
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // SUMMARY: print record count per aircraft
    // ------------------------------------------------------------------------
    public static void printSummary(Connection conn) throws Exception {
        String sql = "SELECT tail, COUNT(*) as cnt FROM flight_records GROUP BY tail ORDER BY tail";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int grandTotal = 0;
            while (rs.next()) {
                String tail = rs.getString("tail");
                int    cnt  = rs.getInt("cnt");
                System.out.printf("  %-15s : %,d records%n", tail, cnt);
                grandTotal += cnt;
            }
            System.out.printf("  %-15s : %,d total%n", "ALL AIRCRAFT", grandTotal);
        }
    }
}
