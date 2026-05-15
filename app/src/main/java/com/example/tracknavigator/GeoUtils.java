package com.example.tracknavigator;

/**
 * Track geometry: closest point on segment in local ENU; cross-track distance is Haversine(P, C).
 * Left/right uses cross product in ENU.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_M = 6371000.0;

    private GeoUtils() {}

    /** East (x) and north (y) meters relative to origin (lat0, lon0). */
    public static double[] toEnMeters(double lat0, double lon0, double lat, double lon) {
        double dLat = Math.toRadians(lat - lat0);
        double dLon = Math.toRadians(lon - lon0);
        double phi = Math.toRadians(lat0);
        double x = EARTH_RADIUS_M * Math.cos(phi) * dLon;
        double y = EARTH_RADIUS_M * dLat;
        return new double[]{x, y};
    }

    /** Lat/lon of point A offset by (eastM, northM) in local ENU from A. */
    private static LatLngPoint enuOffsetToLatLng(double lat0, double lon0, double eastM, double northM) {
        double lat = lat0 + Math.toDegrees(northM / EARTH_RADIUS_M);
        double cosLat = Math.cos(Math.toRadians(lat0));
        if (Math.abs(cosLat) < 1e-10) {
            cosLat = cosLat >= 0 ? 1e-10 : -1e-10;
        }
        double lon = lon0 + Math.toDegrees(eastM / (EARTH_RADIUS_M * cosLat));
        return new LatLngPoint(lat, lon);
    }

    /**
     * Cross-track distance to segment AB: closest point C on AB (ENU projection), then
     * Haversine distance from P to C ({@link #distanceMeters}).
     */
    public static double distancePointToSegmentMeters(
            LatLngPoint a, LatLngPoint b, LatLngPoint p) {
        double[] bEn = toEnMeters(a.latitude, a.longitude, b.latitude, b.longitude);
        double[] pEn = toEnMeters(a.latitude, a.longitude, p.latitude, p.longitude);
        double abx = bEn[0];
        double aby = bEn[1];
        double apx = pEn[0];
        double apy = pEn[1];
        double ab2 = abx * abx + aby * aby;
        if (ab2 < 1e-4) {
            return distanceMeters(a, p);
        }
        double t = (apx * abx + apy * aby) / ab2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = abx * t;
        double cy = aby * t;
        LatLngPoint c = enuOffsetToLatLng(a.latitude, a.longitude, cx, cy);
        return distanceMeters(p, c);
    }

    /**
     * Vector AB is the track direction; z-component of (B−A)×(P−A) in local ENU tangent plane.
     * Positive: P is left of forward A→B; negative: right.
     */
    public static double crossTrackSign(LatLngPoint a, LatLngPoint b, LatLngPoint p) {
        double[] bEn = toEnMeters(a.latitude, a.longitude, b.latitude, b.longitude);
        double[] pEn = toEnMeters(a.latitude, a.longitude, p.latitude, p.longitude);
        return bEn[0] * pEn[1] - bEn[1] * pEn[0];
    }

    /** Great-circle distance between two points (Haversine), meters. */
    public static double distanceMeters(LatLngPoint a, LatLngPoint b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
