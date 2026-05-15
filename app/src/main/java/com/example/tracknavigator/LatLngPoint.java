package com.example.tracknavigator;

import java.io.Serializable;

/** Immutable geographic point for track control points. */
public final class LatLngPoint implements Serializable {
    private static final long serialVersionUID = 1L;
    public final double latitude;
    public final double longitude;

    public LatLngPoint(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
