package com.skidreport.excel;

import com.skidreport.model.SkidEvent;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * SkidExcelWriter
 *
 * Writes one Excel file per aircraft per month.
 * Output: <outputDir>/Skids/<YYYY>/<MonthName>/Skids_<tail>_<YYYY>_<MM>_<Month>.xlsx
 * where outputDir = Output/<dayFolder>/ (e.g. Output/11-may-2026/).
 * The year layer separates same-month data from different years
 * (e.g. April 2025 vs April 2026).
 *
 * One row per detected skid event (a run of consecutive seconds satisfying the
 * skid condition; any single non-triggering record closes the event).
 *
 * Columns:
 *   A  Tail
 *   B  Local Date
 *   C  Local Month
 *   D  Start Time (HH:MM:SS)
 *   E  End Time (HH:MM:SS)
 *   F  Duration (s)
 *   G  Trigger Count (seconds in event)
 *   H  Avg Pitch
 *   I  Avg Roll
 *   J  Peak Roll
 *   K  Avg Lateral Acceleration
 *   L  Peak Lateral Acceleration
 *   M  Avg Indicated Air Speed
 *   N  Avg GPS Altitude
 *   O  Low-Altitude Skid (1/0)
 *   P  Number of Skid Events (total this month, row 1 only)
 *   Q  Total Low-Altitude Skid Events (total this month, row 1 only)
 */
public class SkidExcelWriter {

    private static final String[] HEADERS = {
            "Tail", "Local Date", "Local Month",
            "Start Time", "End Time", "Duration (s)",
            "Trigger Count",
            "Avg Pitch", "Avg Roll", "Peak Roll",
            "Avg Lateral Acceleration", "Peak Lateral Acceleration",
            "Avg Indicated Air Speed", "Avg Gps Altitude",
            "Low-Altitude Skid",
            "Number of Skid Events",
            "Total Low-Altitude Skid Events"
    };

    public static void write(String tail, String yearMonth,
                             List<SkidEvent> events, File outputDir) {

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = events.get(0).month;

        File reportDir = new File(outputDir,
                "Skids" + File.separator + year + File.separator + monthNameStr);
        reportDir.mkdirs();

        String filename = String.format("Skids_%s_%s_%s_%s.xlsx",
                tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Skid Events");

            CellStyle headerStyle = buildHeaderStyle(wb, IndexedColors.DARK_BLUE);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            CellStyle numStyle    = buildNumStyle(wb, null);
            CellStyle centerStyle = buildCenterStyle(wb, null);
            CellStyle altNumStyle = buildNumStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
            CellStyle altStyle    = buildCenterStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);

            int totalLowAltEvents = 0;
            for (SkidEvent ev : events) {
                if (ev.isLowAlt) totalLowAltEvents++;
            }

            int rowNum = 1;
            for (SkidEvent ev : events) {
                Row row      = sheet.createRow(rowNum);
                boolean isAlt = (rowNum % 2 == 0);
                CellStyle cs  = isAlt ? altStyle    : centerStyle;
                CellStyle csN = isAlt ? altNumStyle : numStyle;

                createStrCell(row, 0,  tail,              cs);
                createStrCell(row, 1,  ev.date,           cs);
                createStrCell(row, 2,  ev.month,          cs);
                createStrCell(row, 3,  ev.startTime,      cs);
                createStrCell(row, 4,  ev.endTime,        cs);
                createIntCell(row, 5,  (int) ev.durationSeconds, cs);
                createIntCell(row, 6,  ev.triggerCount,   cs);
                createNumCell(row, 7,  ev.avgPitch,       csN);
                createNumCell(row, 8,  ev.avgRoll,        csN);
                createNumCell(row, 9,  ev.peakRoll,       csN);
                createNumCell(row, 10, ev.avgLatAc,       csN);
                createNumCell(row, 11, ev.peakLatAc,      csN);
                createNumCell(row, 12, ev.avgIas,         csN);
                createNumCell(row, 13, ev.avgAlt,         csN);
                createIntCell(row, 14, ev.isLowAlt ? 1 : 0, cs);

                if (rowNum == 1) {
                    createIntCell(row, 15, events.size(),     cs);
                    createIntCell(row, 16, totalLowAltEvents, cs);
                }

                rowNum++;
            }

            autoSize(sheet, HEADERS.length);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            System.out.printf("    Written: %s  [%d skid event(s)]%n",
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

    private static CellStyle buildCenterStyle(Workbook wb, IndexedColors bg) {
        CellStyle style = wb.createCellStyle();
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
