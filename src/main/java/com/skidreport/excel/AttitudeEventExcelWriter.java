package com.skidreport.excel;

import com.skidreport.model.AttitudeEvent;
import com.skidreport.util.DateUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Writes one workbook PER (aircraft, month) with three sheets:
 *   - "Bank Events"        (Roll < -60 OR Roll > 60)
 *   - "High Pitch Events"  (Pitch > 30)
 *   - "Low Pitch Events"   (Pitch < -30)
 *
 * Output:
 *   <outputDir>/BankPitch_Reports_<tail>/BankPitch_<tail>_<YYYY>_<MM>_<Month>.xlsx
 *
 * Mirrors SkidExcelWriter's layout so a partial run still yields complete files
 * for every aircraft processed so far.
 */
public class AttitudeEventExcelWriter {

    private static final String[] BANK_HEADERS = {
            "Tail", "Date", "Start Time", "End Time",
            "Duration (s)", "Peak Roll (deg)", "Trigger Count"
    };

    private static final String[] HIGH_PITCH_HEADERS = {
            "Tail", "Date", "Start Time", "End Time",
            "Duration (s)", "Peak Pitch (deg)", "Trigger Count"
    };

    private static final String[] LOW_PITCH_HEADERS = HIGH_PITCH_HEADERS;

    /**
     * Write one workbook for one (tail, yearMonth).
     * Caller passes only the events for that aircraft + month.
     */
    public static void write(String tail, String yearMonth, File outputDir,
                             List<AttitudeEvent> bankEvents,
                             List<AttitudeEvent> highPitchEvents,
                             List<AttitudeEvent> lowPitchEvents) {

        String[] parts      = yearMonth.split("-");
        String year         = parts[0];
        String monthNum     = parts[1];
        String monthNameStr = DateUtils.monthNameFromYearMonth(yearMonth);

        File reportDir = new File(outputDir, "BankPitch_Reports_" + tail);
        reportDir.mkdirs();

        String filename = String.format("BankPitch_%s_%s_%s_%s.xlsx",
                tail, year, monthNum, monthNameStr);
        File outFile = new File(reportDir, filename);

        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle headerStyle = buildHeaderStyle(wb);
            CellStyle numStyle    = buildNumStyle(wb, null);
            CellStyle centerStyle = buildCenterStyle(wb, null);
            CellStyle altNumStyle = buildNumStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
            CellStyle altStyle    = buildCenterStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);

            writeSheet(wb, "Bank Events",       BANK_HEADERS,       bankEvents,
                    headerStyle, centerStyle, numStyle, altStyle, altNumStyle);
            writeSheet(wb, "High Pitch Events", HIGH_PITCH_HEADERS, highPitchEvents,
                    headerStyle, centerStyle, numStyle, altStyle, altNumStyle);
            writeSheet(wb, "Low Pitch Events",  LOW_PITCH_HEADERS,  lowPitchEvents,
                    headerStyle, centerStyle, numStyle, altStyle, altNumStyle);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                wb.write(fos);
            }

            System.out.printf("    Written: %s  [bank=%d, highPitch=%d, lowPitch=%d]%n",
                    outFile.getName(), bankEvents.size(), highPitchEvents.size(), lowPitchEvents.size());

        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to write " + outFile.getName()
                    + ": " + e.getMessage());
        }
    }

    private static void writeSheet(Workbook wb, String sheetName, String[] headers,
                                   List<AttitudeEvent> events,
                                   CellStyle headerStyle, CellStyle centerStyle, CellStyle numStyle,
                                   CellStyle altStyle, CellStyle altNumStyle) {

        Sheet sheet = wb.createSheet(sheetName);
        Row hRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = hRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        for (AttitudeEvent ev : events) {
            Row row       = sheet.createRow(rowNum);
            boolean isAlt = (rowNum % 2 == 0);
            CellStyle cs  = isAlt ? altStyle    : centerStyle;
            CellStyle csN = isAlt ? altNumStyle : numStyle;

            createStrCell(row, 0, ev.tail,             cs);
            createStrCell(row, 1, ev.date,             cs);
            createStrCell(row, 2, ev.startTime,        cs);
            createStrCell(row, 3, ev.endTime,          cs);
            createIntCell(row, 4, (int) ev.durationSeconds, cs);
            createNumCell(row, 5, ev.peakValue,        csN);
            createIntCell(row, 6, ev.triggerCount,     cs);

            rowNum++;
        }

        autoSize(sheet, headers.length);
    }

    private static CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
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
