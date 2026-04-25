package com.skidreport;

import com.skidreport.db.DatabaseManager;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MainRunner -- Single entry point for the full pipeline.
 *
 * PIPELINE:
 *   1. SkidReportGenerator.run()
 *      - Creates DB schema (IF NOT EXISTS)
 *      - Processes all aircraft CSV files
 *      - Writes Skid Excel reports per aircraft per month
 *      - Stores in-flight records in DB for near-miss analysis
 *
 *   2. NearMissReportGenerator.run()
 *      - Detects near-miss events using the DB (skips CSV re-loading)
 *      - Writes Near-Miss Excel reports per aircraft pair per month
 *      - Wipes the DB after all reports are written
 *
 * OUTPUT STRUCTURE:
 *   Output/
 *     Skid_Reports_<tail>/
 *       Skids_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *     NearMiss/
 *       <tail1>_vs_<tail2>/
 *         NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.xlsx
 */
public class MainRunner {

    public static void main(String[] args) throws Exception {

        String dayFolder = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d-MMM-yyyy"))
                .toLowerCase();  // e.g. "4-apr-2026"

        File rootDir   = new File(SkidReportGenerator.INPUT_PATH);
        File outputDir = new File(SkidReportGenerator.OUTPUT_PATH + "\\" + dayFolder + "\\Skid Reports");
        File nmDir     = new File(SkidReportGenerator.OUTPUT_PATH + "\\" + dayFolder + "\\NearMiss");

        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("ERROR: INPUT_PATH not found: " + SkidReportGenerator.INPUT_PATH);
            System.exit(1);
        }

        outputDir.mkdirs();
        nmDir.mkdirs();

        String dbPath = nmDir.getAbsolutePath() + "\\near_miss.db";

        System.out.println("==============================================");
        System.out.println("  SKID & NEAR-MISS REPORT GENERATOR");
        System.out.println("==============================================");
        System.out.println("Input    : " + rootDir.getAbsolutePath());
        System.out.println("Output   : " + outputDir.getAbsolutePath());
        System.out.println("Database : " + dbPath);
        System.out.println();

        Class.forName("org.sqlite.JDBC");

        // Single shared connection for the entire pipeline
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {

            SkidReportGenerator.applyPragmas(conn);
            conn.setAutoCommit(false);

            // Initialize schema once
            DatabaseManager.createTablesAndIndexes(conn);
            conn.commit();

            // ---- PHASE 1: Skid Reports + DB population ----
            System.out.println("==============================================");
            System.out.println("  STEP 1: SKID REPORT GENERATOR");
            System.out.println("==============================================");
            SkidReportGenerator.run(conn, rootDir, outputDir);

            // ---- PHASE 2: Near-Miss Detection + Reports ----
            System.out.println();
            System.out.println("==============================================");
            System.out.println("  STEP 2: NEAR-MISS REPORT GENERATOR");
            System.out.println("==============================================");
            NearMissReportGenerator.run(conn, rootDir, nmDir);
        }

        // Delete the DB file from disk now that the connection is fully closed
        File dbFile = new File(dbPath);
        if (dbFile.exists()) {
            if (dbFile.delete()) {
                System.out.println("  Database file deleted: " + dbPath);
            } else {
                System.err.println("  [WARN] Could not delete database file: " + dbPath);
            }
        }

        System.out.println();
        System.out.println("==============================================");
        System.out.println("  ALL DONE");
        System.out.println("  Skid reports  : " + outputDir.getAbsolutePath());
        System.out.println("  Near-miss     : " + nmDir.getAbsolutePath());
        System.out.println("  Database      : Deleted");
        System.out.println("==============================================");
    }
}
