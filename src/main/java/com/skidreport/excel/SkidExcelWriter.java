package com.skidreport.excel;

import com.skidreport.model.SkidEvent;
import com.skidreport.util.DateUtils;
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
 * Output: Output/Skid_Reports_<tail>/Skids_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *
 * Columns:
 *   A  Local Date
 *   B  Local Time (HH:MM)
 *   C  Local Month
 *   D  Pitch (avg)
 *   E  Roll (avg)
 *   F  Lateral Acceleration (avg)
 *   G  Indicated Air Speed (avg)
 *   H  GPS Altitude (avg)
 *   I  Skid Count (seconds in this minute)
 *   J  Number of Skid Events (total this month, row 1 only)
 *   K  Low-Altitude-Skid-Events (1 if this minute had a low-alt skid)
 *   L  total-low-altitude-skid-event-count (total this month, row 1 only)
 *   M  Flight name
 */
public class SkidExcelWriter {

    private static final String[] HEADERS = {
            "Local Date", "Local Time (HH:MM:SS)", "Local Month",
            "Pitch", "Roll", "Lateral Acceleration",
            "Indicated Air Speed", "Gps Altitude", "Skid Count",
            "Number of Skid Events",
            "Low-Altitude-Skid-Events",
            "total-low-altitude-skid-event-count",
            "Flight name"
    };

    public static void write(String tail, String yearMonth,
                             List<SkidEvent> events, File outputDir) {

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = events.get(0).month;

        File reportDir = new File(outputDir, "Skid_Reports_" + tail);
        reportDir.mkdirs();

        String filename = String.format("Skids_%s_%s_%s_%s.xlsx",
                tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Skid Events");

            // Header row (dark blue)
            CellStyle headerStyle = buildHeaderStyle(wb, IndexedColors.DARK_BLUE);

            Row hRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = hRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            // Cell styles
            CellStyle numStyle    = buildNumStyle(wb, null);
            CellStyle centerStyle = buildCenterStyle(wb, null);
            CellStyle altNumStyle = buildNumStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
            CellStyle altStyle    = buildCenterStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);

            // Total low-alt events for this month
            int totalLowAltEvents = 0;
            for (SkidEvent ev : events) {
                if (ev.lowAltFrequency > 0) totalLowAltEvents++;
            }

            // Data rows
            int rowNum = 1;
            for (SkidEvent ev : events) {
                Row row      = sheet.createRow(rowNum);
                boolean isAlt = (rowNum % 2 == 0);
                CellStyle cs  = isAlt ? altStyle    : centerStyle;
                CellStyle csN = isAlt ? altNumStyle : numStyle;

                createStrCell(row, 0,  ev.date,           cs);
                createStrCell(row, 1,  ev.minuteTime,     cs);
                createStrCell(row, 2,  ev.month,          cs);
                createNumCell(row, 3,  ev.avgPitch,       csN);
                createNumCell(row, 4,  ev.avgRoll,        csN);
                createNumCell(row, 5,  ev.avgLatAc,       csN);
                createNumCell(row, 6,  ev.avgIas,         csN);
                createNumCell(row, 7,  ev.avgAlt,         csN);
                createIntCell(row, 8,  ev.skidFrequency,  cs);

                if (rowNum == 1) createIntCell(row, 9, events.size(), cs);

                if (ev.lowAltFrequency > 0) createIntCell(row, 10, 1, cs);

                if (rowNum == 1) createIntCell(row, 11, totalLowAltEvents, cs);

                createStrCell(row, 12, tail, cs);

                rowNum++;
            }

            autoSize(sheet, HEADERS.length);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            System.out.printf("    Written: %s  [%d skid-minute(s)]%n",
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
