package com.example.tracknavigator;

import androidx.annotation.NonNull;

/** Snapshot of race UI row strings and deviation highlight for activity + notification. */
public final class RaceUiState {
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

    public RaceUiState(
            @NonNull String checkpointText,
            @NonNull String deviationText,
            @NonNull String coordsText,
            @NonNull String accuracyText,
            int deviationBgKind,
            boolean raceFinished,
            boolean checkpointJustPassed) {
        this.checkpointText = checkpointText;
        this.deviationText = deviationText;
        this.coordsText = coordsText;
        this.accuracyText = accuracyText;
        this.deviationBgKind = deviationBgKind;
        this.raceFinished = raceFinished;
        this.checkpointJustPassed = checkpointJustPassed;
    }
}
