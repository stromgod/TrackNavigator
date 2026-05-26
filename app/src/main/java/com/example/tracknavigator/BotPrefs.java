package com.example.tracknavigator;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores bot onboarding state + user's name. */
public final class BotPrefs {

    private static final String PREFS_NAME = "bot_prefs";
    private static final String KEY_NAME = "name";
    private static final String KEY_STEP = "step";

    public enum Step {
        ASK_NAME(0),
        GUIDE_RECORD(1),
        GUIDE_RECORD_IN_RECORD(2),
        GUIDE_RACE(3),
        IN_RACE(4),
        DONE(5);

        public final int value;

        Step(int value) {
            this.value = value;
        }
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getName(Context ctx) {
        return sp(ctx).getString(KEY_NAME, null);
    }

    public static void setName(Context ctx, String name) {
        if (name == null) name = "";
        sp(ctx).edit().putString(KEY_NAME, name).apply();
    }

    public static Step getStep(Context ctx) {
        int v = sp(ctx).getInt(KEY_STEP, Step.ASK_NAME.value);
        for (Step s : Step.values()) if (s.value == v) return s;
        return Step.ASK_NAME;
    }

    public static void setStep(Context ctx, Step step) {
        if (step == null) step = Step.ASK_NAME;
        sp(ctx).edit().putInt(KEY_STEP, step.value).apply();
    }

    public static boolean isOnboardingDone(Context ctx) {
        return getStep(ctx) == Step.DONE;
    }

    private BotPrefs() {}
}

