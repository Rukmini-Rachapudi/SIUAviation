package com.skidreport.db;

import com.skidreport.model.AircraftSnapshot;
import com.skidreport.model.NearMissFlightRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    private static final int FLUSH_EVERY = 5000;

    // ------------------------------------------------------------------------
    // STREAMING INSERT
    //
    // Callers consume CSV files one record at a time and push each record
    // through Inserter.add(...). The Inserter wraps a PreparedStatement,
    // adds rows to a JDBC batch, and flushes every FLUSH_EVERY rows. close()
    // flushes the remaining rows. Use in a try-with-resources block so the
    // tail flush and statement close are guaranteed.
    //
    // This lets the entire near-miss load run without ever materializing a
    // full file's worth of records in Java memory.
    // ------------------------------------------------------------------------
    public static Inserter beginInsert(Connection conn) throws SQLException {
        return new Inserter(conn);
    }

    public static class Inserter implements AutoCloseable {

        private final PreparedStatement ps;
        private int pending = 0;
        private int total   = 0;

        private Inserter(Connection conn) throws SQLException {
            this.ps = conn.prepareStatement(INSERT_SQL);
        }

        public void add(NearMissFlightRecord rec) {
            try {
                ps.setString(1, rec.tail);
                ps.setString(2, rec.date);
                ps.setString(3, rec.time);
                ps.setDouble(4, rec.lat);
                ps.setDouble(5, rec.lon);
                ps.setDouble(6, rec.alt);
                ps.setDouble(7, rec.ias);
                ps.setDouble(8, rec.rpm);
                ps.addBatch();
                pending++;
                total++;
                if (pending >= FLUSH_EVERY) {
                    ps.executeBatch();
                    pending = 0;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Insert into flight_records failed", e);
            }
        }

        public int total() { return total; }

        @Override
        public void close() {
            try (PreparedStatement p = ps) {
                if (pending > 0) {
                    p.executeBatch();
                    pending = 0;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Final flush to flight_records failed", e);
            }
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
    // Populates byTime map: HH:MM:SS -> list of AircraftSnapshot.
    // Deduplicates so each (tail, second) appears at most once -- prevents
    // the near-miss detector from comparing an aircraft against itself when
    // overlapping flight log files produce duplicate same-second rows.
    // Returns the number of duplicate (tail, second) rows skipped.
    // ------------------------------------------------------------------------
    public static int loadByDate(Connection conn, String date,
                                 Map<String, List<AircraftSnapshot>> byTime) throws Exception {
        String sql =
            "SELECT tail, local_time, latitude, longitude, alt_msl, ias " +
            "FROM flight_records " +
            "WHERE local_date = ? " +
            "ORDER BY local_time, tail, id";

        int dupSkipped = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tail = rs.getString("tail");
                    String time = rs.getString("local_time");
                    List<AircraftSnapshot> list =
                            byTime.computeIfAbsent(time, k -> new ArrayList<>());

                    boolean dup = false;
                    for (AircraftSnapshot existing : list) {
                        if (existing.tail.equals(tail)) { dup = true; break; }
                    }
                    if (dup) { dupSkipped++; continue; }

                    AircraftSnapshot snap = new AircraftSnapshot();
                    snap.tail = tail;
                    snap.lat  = rs.getDouble("latitude");
                    snap.lon  = rs.getDouble("longitude");
                    snap.alt  = rs.getDouble("alt_msl");
                    snap.ias  = rs.getDouble("ias");
                    list.add(snap);
                }
            }
        }
        return dupSkipped;
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
