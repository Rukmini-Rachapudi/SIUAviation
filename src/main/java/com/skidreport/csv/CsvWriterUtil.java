package com.skidreport.csv;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Shared CSV writing helpers.
 *
 * Power BI ingests CSV best when:
 *   - UTF-8 (no BOM is fine for modern Power BI)
 *   - Comma-separated, CRLF line endings
 *   - Strings quoted only when they contain comma, quote, or newline
 *   - Numbers written as plain decimals (no thousands separators)
 *
 * All writers in com.skidreport.csv use this helper so the dialect stays
 * identical across mirror and consolidated outputs.
 */
public final class CsvWriterUtil {

    public static final String NEWLINE = "\r\n";

    private CsvWriterUtil() {}

    public static BufferedWriter open(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        return new BufferedWriter(new FileWriter(file, false));
    }

    public static void writeHeader(BufferedWriter w, String[] headers) throws IOException {
        writeRow(w, headers);
    }

    public static void writeRow(BufferedWriter w, Object[] values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(format(values[i]));
        }
        sb.append(NEWLINE);
        w.write(sb.toString());
    }

    private static String format(Object v) {
        if (v == null) return "";
        if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        if (v instanceof Float) {
            float f = (Float) v;
            if (Float.isNaN(f) || Float.isInfinite(f)) return "";
            return Float.toString(f);
        }
        if (v instanceof Number) {
            return v.toString();
        }
        return escape(v.toString());
    }

    private static String escape(String s) {
        boolean needsQuote = s.indexOf(',')  >= 0
                          || s.indexOf('"')  >= 0
                          || s.indexOf('\n') >= 0
                          || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
