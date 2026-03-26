package com.skidreport;

/**
 * MainRunner
 *
 * Single entry point that runs both reports in sequence:
 *   1. SkidReportGenerator     -- skid + low-altitude skid Excel reports per aircraft per month
 *   2. NearMissReportGenerator -- near-miss Excel reports per aircraft pair per month
 *
 * OUTPUT STRUCTURE:
 *   Output/
 *     Skid_Reports_<tail>/
 *       Skids_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *     NearMiss/
 *       near_miss.db
 *       <tail1>_vs_<tail2>/
 *         NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.xlsx
 */
public class MainRunner {

    public static void main(String[] args) throws Exception {

        System.out.println("##############################################");
        System.out.println("#       SKID REPORT GENERATOR                #");
        System.out.println("##############################################");
        SkidReportGenerator.main(args);

        System.out.println();
        System.out.println("##############################################");
        System.out.println("#     NEAR MISS REPORT GENERATOR             #");
        System.out.println("##############################################");
        NearMissReportGenerator.main(args);

        System.out.println();
        System.out.println("##############################################");
        System.out.println("#              ALL DONE                      #");
        System.out.println("##############################################");
    }
}
