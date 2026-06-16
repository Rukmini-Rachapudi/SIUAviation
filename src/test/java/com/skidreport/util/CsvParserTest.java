package com.skidreport.util;

import com.skidreport.model.NearMissFlightRecord;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * End-to-end CsvParser tests for the near-miss path: footer/trailing-blank
 * handling (R5), pre-GPS/NoSoln skip, column mapping, and R1 time correction.
 */
public class CsvParserTest {

    /** Writes a Garmin-shaped CSV (3 header lines + body) to a temp file. */
    private static File writeCsv(String body) throws IOException {
        String content =
                "#airframe_info, log_version=\"1.00\", airframe_name=\"Cessna 172S\",\n" +
                "#yyy-mm-dd, hh:mm:ss, hh:mm, degrees, degrees, ft msl, ft wgs, kt, rpm\n" +
                "Lcl Date, Lcl Time, UTCOfst, Latitude, Longitude, AltMSL, AltGPS, IAS, E1 RPM\n" +
                body;
        File f = File.createTempFile("nm_test_", ".csv");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.ISO_8859_1));
        return f;
    }

    private static List<NearMissFlightRecord> parse(File f) throws IOException {
        List<NearMissFlightRecord> out = new ArrayList<>();
        CsvParser.streamNearMissCsvFile(f, "82P", out::add);
        return out;
    }

    @Test
    public void dropsFooterEvenWithTrailingBlankLines() throws IOException {
        // A NoSoln row (skip), two real rows, a footer that LOOKS like data, then
        // trailing blank lines. The footer must be dropped despite the blanks.
        String body =
                "2025-07-01, 11:59:59, +00:00, , , , , , \n" +                       // NoSoln -> skipped
                "2025-07-01, 12:00:00, +00:00, 37.78, -89.25, 1500.0, 1450.0, 60.0, 2000.0\n" +
                "2025-07-01, 12:00:01, +00:00, 37.79, -89.26, 1600.0, 1550.0, 65.0, 2100.0\n" +
                "2025-07-01, 12:00:02, +00:00, 37.80, -89.27, 1700.0, 1650.0, 70.0, 2200.0\n" + // footer
                "\n" +
                "   \n";
        List<NearMissFlightRecord> recs = parse(writeCsv(body));

        assertEquals("NoSoln row and footer both excluded", 2, recs.size());
        for (NearMissFlightRecord r : recs) {
            assertFalse("footer (12:00:02 -> 07:00:02) must not leak", "07:00:02".equals(r.time));
        }
    }

    @Test
    public void mapsColumnsAndCorrectsTimeToCentral() throws IOException {
        // No trailing blank: last row is the footer and is dropped, leaving one record.
        String body =
                "2025-07-01, 12:00:00, +00:00, 37.78, -89.25, 1500.0, 1450.0, 60.0, 2000.0\n" +
                "2025-07-01, 12:00:01, +00:00, 37.79, -89.26, 1600.0, 1550.0, 65.0, 2100.0\n"; // footer (dropped)
        List<NearMissFlightRecord> recs = parse(writeCsv(body));

        assertEquals(1, recs.size());
        NearMissFlightRecord r = recs.get(0);
        assertEquals("82P", r.tail);
        assertEquals("2025-07-01", r.date);
        assertEquals("07:00:00", r.time);     // +00:00 in CDT -> -5h
        assertEquals(37.78, r.lat, 1e-9);
        assertEquals(-89.25, r.lon, 1e-9);
        assertEquals(1500.0, r.altMsl, 1e-9); // AltMSL -> floor filter + separation distance
        assertEquals(60.0, r.ias, 1e-9);
        assertEquals(2000.0, r.rpm, 1e-9);
    }
}
