package com.skidreport.excel;

import com.skidreport.db.NearMissEventDao;
import com.skidreport.model.NearMissEvent;
import com.skidreport.util.DateUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.util.List;

/**
 * NearMissExcelWriter
 *
 * Writes one Excel file per aircraft pair per month. One row per aggregated
 * event (a run of consecutive seconds the pair was within 500 ft); the
 * aircraft snapshot columns are taken at the second of closest approach.
 * Output: <outputDir>/<YYYY>/<MonthName>/NearMiss_<tail1>_<tail2>_<YYYY>_<MM>_<Month>.xlsx
 * where outputDir = Output/<dayFolder>/Near Miss/.
 *
 * Columns:
 *   A  Local Date
 *   B  Start Time
 *   C  Duration (sec)
 *   D  Aircraft 1
 *   E  Latitude 1
 *   F  Longitude 1
 *   G  Altitude 1 (ft)
 *   H  IAS 1 (kts)
 *   I  Aircraft 2
 *   J  Latitude 2
 *   K  Longitude 2
 *   L  Altitude 2 (ft)
 *   M  IAS 2 (kts)
 *   N  Min Distance (ft)
 *   O  Total Near-Miss Events This Month (row 1 only)
 */
public class NearMissExcelWriter {

    private static final String[] HEADERS = {
            "Local Date",
            "Start Time",
            "Duration (sec)",
            "Aircraft 1",
            "Latitude 1",
            "Longitude 1",
            "Altitude 1 (ft)",
            "IAS 1 (kts)",
            "Aircraft 2",
            "Latitude 2",
            "Longitude 2",
            "Altitude 2 (ft)",
            "IAS 2 (kts)",
            "Min Distance (ft)",
            "Total Near-Miss Events This Month"
    };

    // ------------------------------------------------------------------------
    // WRITE ALL: iterates all unique pair+month combinations from DB
    // ------------------------------------------------------------------------
    public static void writeAll(Connection conn, File outputDir)
            throws Exception {

        List<String[]> pairs = NearMissEventDao.getAllPairsAndMonths(conn);

        if (pairs.isEmpty()) {
            System.out.println("  No near-miss events found. No Excel files written.");
            return;
        }

        System.out.println("  " + pairs.size() + " Excel file(s) to write...");

        for (String[] pair : pairs) {
            writeOne(conn, outputDir, pair[0], pair[1], pair[2]);
        }
    }

    // ------------------------------------------------------------------------
    // WRITE ONE: fetches events from DB, writes one Excel file
    // ------------------------------------------------------------------------
    private static void writeOne(Connection conn, File outputDir,
                                  String tail1, String tail2,
                                  String yearMonth) throws Exception {

        List<NearMissEvent> events =
                NearMissEventDao.getEventsForPairAndMonth(conn, tail1, tail2, yearMonth);

        if (events.isEmpty()) return;

        String[] parts   = yearMonth.split("-");
        String year      = parts[0];
        String monthNum  = parts[1];
        String monthName = DateUtils.monthNameFromYearMonth(yearMonth);

        File monthDir = new File(outputDir, year + java.io.File.separator + monthName);
        monthDir.mkdirs();

        String filename = String.format("NearMiss_%s_%s_%s_%s_%s.xlsx",
                tail1, tail2, year, monthNum, monthName);
        File outFile = new File(monthDir, filename);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Near Miss Events");

            // Header row (orange -- distinct from blue skid reports)
            CellStyle headerStyle = buildHeaderStyle(wb, IndexedColors.ORANGE);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // Cell styles
            CellStyle centerStyle = buildCenterStyle(wb, null);
            CellStyle latLonStyle = buildLatLonStyle(wb, null);
            CellStyle numStyle    = buildNumStyle(wb, null);
            CellStyle altCenter   = buildCenterStyle(wb, IndexedColors.LIGHT_YELLOW);
            CellStyle altLatLon   = buildLatLonStyle(wb, IndexedColors.LIGHT_YELLOW);
            CellStyle altNum      = buildNumStyle(wb, IndexedColors.LIGHT_YELLOW);

            // Data rows -- one row per aggregated near-miss event
            int rowNum = 1;
            for (NearMissEvent ev : events) {
                Row row      = sheet.createRow(rowNum);
                boolean isAlt = (rowNum % 2 == 0);
                CellStyle cs   = isAlt ? altCenter : centerStyle;
                CellStyle csLL = isAlt ? altLatLon : latLonStyle;
                CellStyle csN  = isAlt ? altNum    : numStyle;

                createStrCell(row, 0,  ev.date,              cs);
                createStrCell(row, 1,  ev.startTime,         cs);
                createIntCell(row, 2,  (int) ev.durationSeconds, cs);
                createStrCell(row, 3,  tail1,                cs);
                createNumCell(row, 4,  ev.lat1,              csLL);
                createNumCell(row, 5,  ev.lon1,              csLL);
                createNumCell(row, 6,  ev.alt1,              csN);
                createNumCell(row, 7,  ev.ias1,              csN);
                createStrCell(row, 8,  tail2,                cs);
                createNumCell(row, 9,  ev.lat2,              csLL);
                createNumCell(row, 10, ev.lon2,              csLL);
                createNumCell(row, 11, ev.alt2,              csN);
                createNumCell(row, 12, ev.ias2,              csN);
                createNumCell(row, 13, ev.minDistanceFt,     csN);

                // Col O: total events this month -- row 1 only
                if (rowNum == 1) createIntCell(row, 14, events.size(), cs);

                rowNum++;
            }

            autoSize(sheet, HEADERS.length);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            System.out.printf("    Written: %s  [%d event(s)]%n",
                    outFile.getName(), events.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // STYLE BUILDERS
    // ------------------------------------------------------------------------
    private static CellStyle buildHeaderStyle(Workbook wb, IndexedColors color) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        return style;
    }

    private static CellStyle buildCenterStyle(Workbook wb, IndexedColors bg) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        if (bg != null) {
            style.setFillForegroundColor(bg.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }

    private static CellStyle buildLatLonStyle(Workbook wb, IndexedColors bg) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("0.000000"));
        style.setAlignment(HorizontalAlignment.CENTER);
        if (bg != null) {
            style.setFillForegroundColor(bg.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }

    private static CellStyle buildNumStyle(Workbook wb, IndexedColors bg) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("0.00"));
        style.setAlignment(HorizontalAlignment.CENTER);
        if (bg != null) {
            style.setFillForegroundColor(bg.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }

    private static void autoSize(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
            sheet.setColumnWidth(i, sheet.getColumnWidth(i) + 512);
        }
    }

    // ------------------------------------------------------------------------
    // CELL HELPERS
    // ------------------------------------------------------------------------
    private static void createStrCell(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val != null ? val : "");
        cell.setCellStyle(style);
    }

    private static void createNumCell(Row row, int col, double val, CellStyle style) {
        Cell cell = row.createCell(col);
        if (!Double.isNaN(val)) cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private static void createIntCell(Row row, int col, int val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }
}
