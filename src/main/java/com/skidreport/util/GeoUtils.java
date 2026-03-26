package com.skidreport.util;

/**
 * GeoUtils
 *
 * Geographic calculation utilities for near-miss distance detection.
 */
public class GeoUtils {

    private static final double EARTH_RADIUS_FT = 20902231.0; // Earth radius in feet

    // ------------------------------------------------------------------------
    // distance3dFeet
    //
    // Computes the 3D distance between two aircraft positions in feet.
    //
    // Steps:
    //   1. Haversine formula for horizontal (great-circle) distance in feet
    //   2. Absolute altitude difference in feet
    //   3. 3D distance = sqrt(horizontal^2 + vertical^2)
    //
    // This is the same approach described in the system documentation:
    //   "derive absolute 3d distance: SQRT((x2-x1)^2+(y2-y1)^2+(z2-z1)^2)"
    // ------------------------------------------------------------------------
    public static double distance3dFeet(double lat1, double lon1, double alt1,
                                         double lat2, double lon2, double alt2) {

        // Haversine for horizontal distance
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1))
                 * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double horizontalFt = EARTH_RADIUS_FT * c;

        // Vertical distance
        double verticalFt = Math.abs(alt2 - alt1);

        // 3D Euclidean distance
        return Math.sqrt(horizontalFt * horizontalFt + verticalFt * verticalFt);
    }
}
