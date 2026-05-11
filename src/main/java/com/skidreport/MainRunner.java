package com.skidreport;

/**
 * MainRunner
 *
 * Single entry point that runs reports in sequence:
 *   1. SkidReportGenerator          -- skid + low-altitude skid Excel reports
 *                                      per aircraft per month
 *   2. AttitudeEventReportGenerator -- bank, high-pitch, low-pitch Excel reports
 *                                      per aircraft per month
 *   3. NearMissReportGenerator      -- (CURRENTLY SKIPPED) near-miss Excel reports
 *                                      per aircraft pair per month
 *
 * OUTPUT STRUCTURE:
 *   Output/
 *     <dayFolder>/                            (e.g. 11-may-2026, one per run)
 *       Skids/<YYYY>/<MonthName>/
 *         Skids_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *       Bank Pitch Events/<YYYY>/<MonthName>/
 *         BankPitch_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *       Near Miss/<YYYY>/<MonthName>/         (when near-miss is re-enabled)
 *         NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.xlsx
 *     NearMiss/
 *       near_miss.db                          (stable cache path, reused across runs)
 *     CSV/                                    (Power BI mirror, stable root)
 *       Skids/<YYYY>/<MonthName>/...
 *       Bank Pitch Events/<YYYY>/<MonthName>/...
 *       Near Miss/<YYYY>/<MonthName>/...
 *     PowerBI_CSV/                            (Power BI consolidated, stable root)
 *       skid_events.csv, near_miss_events.csv, bank_events.csv,
 *       high_pitch_events.csv, low_pitch_events.csv
 */
public class MainRunner {

    public static void main(String[] args) throws Exception {

        System.out.println("##############################################");
        System.out.println("#       SKID REPORT GENERATOR                #");
        System.out.println("##############################################");
        SkidReportGenerator.main(args);

        System.out.println();
        System.out.println("##############################################");
        System.out.println("#  BANK / HIGH-PITCH / LOW-PITCH GENERATOR   #");
        System.out.println("##############################################");
        AttitudeEventReportGenerator.main(args);

        // Near-miss is currently SKIPPED while the Friday-deadline reports
        // (skid + bank/pitch) are being iterated on. To re-enable, uncomment
        // the block below or run NearMissReportGenerator.main(...) directly.
        //
        // System.out.println();
        // System.out.println("##############################################");
        // System.out.println("#     NEAR MISS REPORT GENERATOR             #");
        // System.out.println("##############################################");
        // NearMissReportGenerator.main(args);

        System.out.println();
        System.out.println("##############################################");
        System.out.println("#              ALL DONE                      #");
        System.out.println("##############################################");
    }
}
