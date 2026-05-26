package com.example.tracknavigator;

/**
 * Enhanced geometry utils using Haversine for point-to-point distance
 * and n-vector approach for cross-track deviation logic.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_M = 6371001.0;

    private GeoUtils() {}

    /**
     * Converts Lat/Lng to an n-vector (unit vector pointing from Earth center to the point).
     * Coordinate system: X=0,0; Y=0,90E; Z=North Pole.
     */
    private static double[] toNVector(LatLngPoint p) {
        double latRad = Math.toRadians(p.latitude);
        double lonRad = Math.toRadians(p.longitude);
        double cosLat = Math.cos(latRad);
        return new double[] {
            cosLat * Math.cos(lonRad),
            cosLat * Math.sin(lonRad),
            Math.sin(latRad)
        };
    }

    /**
     * Vector cross product: a x b
     */
    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    /**
     * Vector dot product: a . b
     */
    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Calculates distance between two points using the Haversine formula.
     * Used for distance to checkpoints and total path length.
     */
    public static double distanceMeters(LatLngPoint p1, LatLngPoint p2) {
        double lat1 = Math.toRadians(p1.latitude);
        double lat2 = Math.toRadians(p2.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(p2.longitude - p1.longitude);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_M * c;
    }

    /**
     * Calculates the side of the point P relative to the great circle path A -> B.
     * Uses n-vector cross track error logic.
     * @return Positive if P is to the LEFT of A->B, negative if to the RIGHT.
     */
    public static double crossTrackSign(LatLngPoint a, LatLngPoint b, LatLngPoint p) {
        double[] nA = toNVector(a);
        double[] nB = toNVector(b);
        double[] nP = toNVector(p);

        // Path normal vector
        double[] pathNormal = cross(nA, nB);
        
        // The dot product of nP and pathNormal gives the direction and sin of cross-track angle
        return dot(nP, pathNormal);
    }

    /**
     * Cross-track distance from P to the great circle path A-B in meters.
     * Uses n-vector projection for high accuracy.
     */
    public static double distancePointToSegmentMeters(LatLngPoint a, LatLngPoint b, LatLngPoint p) {
        double[] nA = toNVector(a);
        double[] nB = toNVector(b);
        double[] nP = toNVector(p);

        double[] pathNormal = cross(nA, nB);
        double normLen = Math.sqrt(dot(pathNormal, pathNormal));
        
        if (normLen < 1e-10) {
            return distanceMeters(a, p); // A and B are the same point
        }

        // Normalize the path normal
        pathNormal[0] /= normLen;
        pathNormal[1] /= normLen;
        pathNormal[2] /= normLen;

        // Angle to path is asin of (nP . pathNormal)
        double angleToPath = Math.asin(Math.max(-1.0, Math.min(1.0, dot(nP, pathNormal))));
        double crossTrackDist = Math.abs(angleToPath) * EARTH_RADIUS_M;

        // Check if the closest point is actually within the segment AB
        // We use dot products to check if P is between the planes defined by nA and nB
        double[] planeA = cross(pathNormal, nA);
        double[] planeB = cross(pathNormal, nB);

        if (dot(nP, planeA) >= 0 && dot(nP, planeB) <= 0) {
            return crossTrackDist;
        } else {
            // Closest point is one of the endpoints
            return Math.min(distanceMeters(a, p), distanceMeters(b, p));
        }
    }
}
