package com.example.tracknavigator;

import androidx.annotation.NonNull;

/** Snapshot of race UI row strings and deviation highlight for activity + notification. */
public final class RaceUiState {
    public static final int STAGE_START = 0;
    public static final int STAGE_MIDDLE = 1;
    public static final int STAGE_FINISH = 2;

    @NonNull
    public final String checkpointText;
    @NonNull
    public final String deviationText;
    @NonNull
    public final String coordsText;
    @NonNull
    public final String accuracyText;
    /** {@link RaceEvaluator#BG_NONE}, {@link RaceEvaluator#BG_WARNING}, {@link RaceEvaluator#BG_SUCCESS} */
    public final int deviationBgKind;
    public final boolean raceFinished;
    public final boolean checkpointJustPassed;
    /** Current segment index along the track (0 = at start). */
    public final int segmentIndex;
    /** Total segments between control points (track points - 1). */
    public final int segmentTotal;
    /** Discrete progress 0..1 for the route bar (no smooth animation). */
    public final float progressFraction;
    /** {@link #STAGE_START}, {@link #STAGE_MIDDLE}, or {@link #STAGE_FINISH}. */
    public final int routeStage;

    public RaceUiState(
            @NonNull String checkpointText,
            @NonNull String deviationText,
            @NonNull String coordsText,
            @NonNull String accuracyText,
            int deviationBgKind,
            boolean raceFinished,
            boolean checkpointJustPassed,
            int segmentIndex,
            int segmentTotal,
            float progressFraction,
            int routeStage) {
        this.checkpointText = checkpointText;
        this.deviationText = deviationText;
        this.coordsText = coordsText;
        this.accuracyText = accuracyText;
        this.deviationBgKind = deviationBgKind;
        this.raceFinished = raceFinished;
        this.checkpointJustPassed = checkpointJustPassed;
        this.segmentIndex = segmentIndex;
        this.segmentTotal = segmentTotal;
        this.progressFraction = progressFraction;
        this.routeStage = routeStage;
    }
}
