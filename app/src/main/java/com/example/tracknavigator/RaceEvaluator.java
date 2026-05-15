package com.example.tracknavigator;

import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Pure logic for segment advance and cross-track deviation.
 */
final class RaceEvaluator {

    static final double DEVIATION_THRESHOLD_M = 8; // Slightly increased for better tolerance
    static final double PASS_CHECKPOINT_M = 5.0; // User requested 5 meters
    static final float MAX_FIX_ACCURACY_M = 10f;

    static final int BG_NONE = 0;
    static final int BG_WARNING = 1;
    static final int BG_SUCCESS = 2;

    static final class StepResult {
        final int newSegmentStartIndex;
        final boolean raceFinished;
        final boolean checkpointJustPassed;
        @NonNull
        final RaceUiState ui;

        StepResult(int newSegmentStartIndex, boolean raceFinished, boolean checkpointJustPassed, @NonNull RaceUiState ui) {
            this.newSegmentStartIndex = newSegmentStartIndex;
            this.raceFinished = raceFinished;
            this.checkpointJustPassed = checkpointJustPassed;
            this.ui = ui;
        }
    }

    @NonNull
    static StepResult evaluateStep(
            @NonNull Context ctx,
            @NonNull List<LatLngPoint> track,
            int segmentStartIndex,
            @Nullable Location lastFix) {
        int n = track.size();

        if (lastFix == null) {
            RaceUiState ui = new RaceUiState(
                    "—",
                    ctx.getString(R.string.gps_waiting),
                    "",
                    "",
                    BG_NONE,
                    false,
                    false);
            return new StepResult(segmentStartIndex, false, false, ui);
        }

        String coords = ctx.getString(R.string.coords_label, lastFix.getLatitude(), lastFix.getLongitude());
        String acc = lastFix.hasAccuracy()
                ? ctx.getString(R.string.accuracy_label, String.format(Locale.US, "%.1f", lastFix.getAccuracy()))
                : ctx.getString(R.string.accuracy_label, "—");

        if (!lastFix.hasAccuracy() || lastFix.getAccuracy() > MAX_FIX_ACCURACY_M) {
            RaceUiState ui = new RaceUiState(
                    ctx.getString(R.string.checkpoint_progress, Math.max(0, segmentStartIndex), n - 1),
                    ctx.getString(R.string.accuracy_low_race),
                    coords,
                    acc,
                    BG_NONE,
                    false,
                    false);
            return new StepResult(segmentStartIndex, false, false, ui);
        }

        LatLngPoint p = new LatLngPoint(lastFix.getLatitude(), lastFix.getLongitude());
        boolean checkpointPassed = false;

        // 1. Advance segments
        int idx = segmentStartIndex;
        while (idx <= n - 2
                && GeoUtils.distanceMeters(p, track.get(idx + 1)) <= PASS_CHECKPOINT_M) {
            idx++;
            checkpointPassed = true;
        }

        // 2. Check for finish
        if (idx >= n - 1) {
            RaceUiState ui = new RaceUiState(
                    ctx.getString(R.string.checkpoint_progress, n - 1, n - 1),
                    ctx.getString(R.string.race_finished),
                    coords,
                    acc,
                    BG_SUCCESS,
                    true,
                    true);
            return new StepResult(idx, true, true, ui);
        }

        // 3. Normal progress
        String checkpoint = ctx.getString(R.string.checkpoint_progress, idx, n - 1);
        LatLngPoint a = track.get(idx);
        LatLngPoint b = track.get(idx + 1);
        double crossDist = GeoUtils.distancePointToSegmentMeters(a, b, p);

        if (crossDist <= DEVIATION_THRESHOLD_M) {
            RaceUiState ui = new RaceUiState(
                    checkpoint,
                    ctx.getString(R.string.on_track),
                    coords,
                    acc,
                    BG_NONE,
                    false,
                    checkpointPassed);
            return new StepResult(idx, false, checkpointPassed, ui);
        }

        double cross = GeoUtils.crossTrackSign(a, b, p);
        int bg = BG_WARNING;
        String devText;
        if (cross > 0) {
            devText = ctx.getString(R.string.deviation_left);
        } else if (cross < 0) {
            devText = ctx.getString(R.string.deviation_right);
        } else {
            devText = ctx.getString(R.string.on_track);
            bg = BG_NONE;
        }
        RaceUiState ui = new RaceUiState(checkpoint, devText, coords, acc, bg, false, checkpointPassed);
        return new StepResult(idx, false, checkpointPassed, ui);
    }

    private RaceEvaluator() {
    }
}
