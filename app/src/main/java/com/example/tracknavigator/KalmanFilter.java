package com.example.tracknavigator;

import android.location.Location;

/**
 * Enhanced Kalman Filter for GPS coordinates smoothing.
 */
public class KalmanFilter {
    private double lat;
    private double lng;
    private float variance; // Error covariance
    private long lastTimeMs;
    private final float minAccuracy;

    public KalmanFilter(float minAccuracy) {
        this.minAccuracy = minAccuracy;
        this.variance = -1;
    }

    /**
     * Resets the filter state. Use this when starting a new session.
     */
    public void reset() {
        this.variance = -1;
    }

    /**
     * Processes a raw GPS location and returns a smoothed one.
     * @param location Raw location from GPS sensor.
     * @return Smoothed location.
     */
    public Location process(Location location) {
        double newLat = location.getLatitude();
        double newLng = location.getLongitude();
        float accuracy = location.getAccuracy();
        long timeMs = location.getTime();

        if (accuracy < minAccuracy) accuracy = minAccuracy;

        if (variance < 0) {
            // Initialize filter with the first fix
            this.lat = newLat;
            this.lng = newLng;
            this.variance = accuracy * accuracy;
            this.lastTimeMs = timeMs;
        } else {
            long durationMs = timeMs - lastTimeMs;
            if (durationMs > 0) {
                // Apply process noise (predict how much we moved since last fix)
                // Assuming average movement speed ~ 5 m/s for process variance
                this.variance += durationMs * 0.025; 
                this.lastTimeMs = timeMs;
            }

            // Kalman Gain Calculation
            float k = variance / (variance + accuracy * accuracy);

            // Correct prediction with actual measurement
            this.lat += k * (newLat - this.lat);
            this.lng += k * (newLng - this.lng);

            // Update error covariance
            this.variance = (1 - k) * variance;
        }

        // Create a new location object to return smoothed values
        Location filtered = new Location(location);
        filtered.setLatitude(this.lat);
        filtered.setLongitude(this.lng);
        // Accuracy is now theoretically better (square root of variance)
        filtered.setAccuracy((float) Math.sqrt(this.variance));
        
        return filtered;
    }
}
