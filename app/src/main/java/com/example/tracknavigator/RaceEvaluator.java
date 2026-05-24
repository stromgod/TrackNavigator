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
        int segmentTotal = Math.max(1, n - 1);

        if (lastFix == null) {
            RaceUiState ui = buildUi(
                    "—",
                    ctx.getString(R.string.gps_waiting),
                    "",
                    "",
                    BG_NONE,
                    false,
                    false,
                    Math.max(0, segmentStartIndex),
                    segmentTotal,
                    0);
            return new StepResult(segmentStartIndex, false, false, ui);
        }

        String coords = ctx.getString(R.string.coords_label, lastFix.getLatitude(), lastFix.getLongitude());
        String acc = lastFix.hasAccuracy()
                ? ctx.getString(R.string.accuracy_label, String.format(Locale.US, "%.1f", lastFix.getAccuracy()))
                : ctx.getString(R.string.accuracy_label, "—");

        if (!lastFix.hasAccuracy() || lastFix.getAccuracy() > MAX_FIX_ACCURACY_M) {
            int idx = Math.max(0, segmentStartIndex);
            RaceUiState ui = buildUi(
                    ctx.getString(R.string.checkpoint_progress, idx, segmentTotal),
                    ctx.getString(R.string.accuracy_low_race),
                    coords,
                    acc,
                    BG_NONE,
                    false,
                    false,
                    idx,
                    segmentTotal,
                    0);
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
            RaceUiState ui = buildUi(
                    ctx.getString(R.string.checkpoint_progress, segmentTotal, segmentTotal),
                    ctx.getString(R.string.race_finished),
                    coords,
                    acc,
                    BG_SUCCESS,
                    true,
                    true,
                    segmentTotal,
                    segmentTotal,
                    0);
            return new StepResult(idx, true, true, ui);
        }

        // 3. Normal progress
        String checkpoint = ctx.getString(R.string.checkpoint_progress, idx, segmentTotal);
        LatLngPoint a = track.get(idx);
        LatLngPoint b = track.get(idx + 1);
        double crossDist = GeoUtils.distancePointToSegmentMeters(a, b, p);
        double crossSign = GeoUtils.crossTrackSign(a, b, p);

        if (crossDist <= DEVIATION_THRESHOLD_M) {
            RaceUiState ui = buildUi(
                    checkpoint,
                    ctx.getString(R.string.on_track),
                    coords,
                    acc,
                    BG_NONE,
                    false,
                    checkpointPassed,
                    idx,
                    segmentTotal,
                    0);
            return new StepResult(idx, false, checkpointPassed, ui);
        }

        int bg = BG_WARNING;
        String devText;
        if (crossSign > 0) {
            devText = ctx.getString(R.string.deviation_left);
        } else if (crossSign < 0) {
            devText = ctx.getString(R.string.deviation_right);
        } else {
            devText = ctx.getString(R.string.on_track);
            bg = BG_NONE;
        }
        RaceUiState ui = buildUi(checkpoint, devText, coords, acc, bg, false, checkpointPassed, idx, segmentTotal, crossSign);
        return new StepResult(idx, false, checkpointPassed, ui);
    }

    private static int routeStage(int idx, int segmentTotal, boolean raceFinished) {
        if (raceFinished || idx >= segmentTotal) {
            return RaceUiState.STAGE_FINISH;
        }
        if (idx >= segmentTotal / 2) {
            return RaceUiState.STAGE_MIDDLE;
        }
        return RaceUiState.STAGE_START;
    }

    private static float progressFraction(int idx, int segmentTotal, boolean raceFinished) {
        if (raceFinished || idx >= segmentTotal) {
            return 1f;
        }
        return idx / (float) segmentTotal;
    }

    @NonNull
    private static RaceUiState buildUi(
            @NonNull String checkpointText,
            @NonNull String deviationText,
            @NonNull String coordsText,
            @NonNull String accuracyText,
            int deviationBgKind,
            boolean raceFinished,
            boolean checkpointJustPassed,
            int idx,
            int segmentTotal,
            double crossTrackSign) {
        return new RaceUiState(
                checkpointText,
                deviationText,
                coordsText,
                accuracyText,
                deviationBgKind,
                raceFinished,
                checkpointJustPassed,
                idx,
                segmentTotal,
                progressFraction(idx, segmentTotal, raceFinished),
                routeStage(idx, segmentTotal, raceFinished),
                crossTrackSign);
    }

    private RaceEvaluator() {
    }
}
