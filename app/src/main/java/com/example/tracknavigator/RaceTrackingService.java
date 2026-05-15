package com.example.tracknavigator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service for tracking race progress.
 * Sends notifications on checkpoint arrival.
 */
public class RaceTrackingService extends Service {

    public static final String ACTION_START = "com.example.tracknavigator.action.RACE_START";
    public static final String ACTION_STOP = "com.example.tracknavigator.action.RACE_STOP";
    public static final String EXTRA_TRACK = "extra_track";

    private static final String TAG = "RaceTrackingService";
    private static final int NOTIF_ID = 7421;
    private static final String CHANNEL_ID = "race_location";
    private static final long TICK_MS = 2000L;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final long DEVIATION_SOUND_REPEAT_MS = 5000L;

    private static volatile boolean sRunning;

    public static boolean isRunning() {
        return sRunning;
    }

    public static void requestStop(@NonNull Context ctx) {
        Intent i = new Intent(ctx, RaceTrackingService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public interface UiListener {
        void onRaceUiState(@NonNull RaceUiState state);
    }

    public class LocalBinder extends android.os.Binder {
        @NonNull
        public RaceTrackingService getService() {
            return RaceTrackingService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FusedLocationProviderClient fusedClient;
    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            for (Location location : locationResult.getLocations()) {
                lastFix = location;
            }
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!raceActive) return;
            runEvaluationTick();
            if (raceActive) mainHandler.postDelayed(this, TICK_MS);
        }
    };

    @Nullable private UiListener uiListener;
    @Nullable private List<LatLngPoint> track;
    private int segmentStartIndex;
    private boolean raceActive;
    @Nullable private Location lastFix;
    @Nullable private RaceUiState lastUiState;

    private int lastDeviationBgForSound = RaceEvaluator.BG_NONE;
    private long lastDeviationSoundElapsedRealtimeMs;

    @Nullable private SoundPool soundPool;
    private int warningSoundId;
    private boolean soundLoaded;

    public void setUiListener(@Nullable UiListener listener) {
        uiListener = listener;
        if (listener != null && lastUiState != null) {
            listener.onRaceUiState(lastUiState);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) {
            shutdownFromStopAction();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction())) {
            List<LatLngPoint> loaded = readTrackExtra(intent);
            if (loaded != null) beginSession(loaded);
            else stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        tearDownLocationAndTicker();
        sRunning = false;
        super.onDestroy();
    }

    private void shutdownFromStopAction() {
        tearDownLocationAndTicker();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        sRunning = false;
        stopSelf();
    }

    private void tearDownLocationAndTicker() {
        raceActive = false;
        mainHandler.removeCallbacks(tickRunnable);
        try { fusedClient.removeLocationUpdates(locationCallback); } catch (Exception ignored) {}
        releaseSoundPool();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private List<LatLngPoint> readTrackExtra(@NonNull Intent intent) {
        try {
            return (ArrayList<LatLngPoint>) intent.getSerializableExtra(EXTRA_TRACK);
        } catch (Exception e) { return null; }
    }

    private void beginSession(@NonNull List<LatLngPoint> points) {
        tearDownLocationAndTicker();
        initSoundPool();
        track = points;
        segmentStartIndex = 0;
        raceActive = true;
        sRunning = true;

        startForeground(NOTIF_ID, buildNotification(null), 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);

        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY).build();
        try { fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper()); } catch (SecurityException ignored) {}
        mainHandler.post(tickRunnable);
    }

    private void runEvaluationTick() {
        if (track == null) return;
        RaceEvaluator.StepResult step = RaceEvaluator.evaluateStep(this, track, segmentStartIndex, lastFix);
        segmentStartIndex = step.newSegmentStartIndex;
        lastUiState = step.ui;
        
        maybePlayDeviationWarning(step.ui);
        
        // Update notification. If checkpoint passed, it will show the new number.
        updateNotification(step.ui, step.checkpointJustPassed);

        if (uiListener != null) uiListener.onRaceUiState(step.ui);
        if (step.raceFinished) onRaceCompleted();
    }

    private void onRaceCompleted() {
        raceActive = false;
        mainHandler.removeCallbacks(tickRunnable);
        sRunning = false;
        stopSelf();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.race_notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @NonNull
    private Notification buildNotification(@Nullable RaceUiState state) {
        String content = (state != null) ? state.checkpointText + " - " + state.deviationText : getString(R.string.gps_waiting);
        PendingIntent tap = PendingIntent.getActivity(this, 0, new Intent(this, RaceActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.race_notif_title))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(tap)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(@NonNull RaceUiState state, boolean alert) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.race_notif_title))
                .setContentText(state.checkpointText + " | " + state.deviationText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOnlyAlertOnce(!alert) // Alert if checkpoint passed
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, RaceActivity.class), PendingIntent.FLAG_IMMUTABLE));
        
        if (alert) {
            builder.setSubText(getString(R.string.checkpoint_passed));
            builder.setPriority(NotificationCompat.PRIORITY_HIGH);
            builder.setDefaults(Notification.DEFAULT_ALL);
        }

        nm.notify(NOTIF_ID, builder.build());
    }

    private void maybePlayDeviationWarning(@NonNull RaceUiState ui) {
        if (ui.deviationBgKind != RaceEvaluator.BG_WARNING) { lastDeviationBgForSound = ui.deviationBgKind; return; }
        long now = SystemClock.elapsedRealtime();
        if (lastDeviationBgForSound != RaceEvaluator.BG_WARNING || (now - lastDeviationSoundElapsedRealtimeMs) >= DEVIATION_SOUND_REPEAT_MS) {
            if (soundPool != null && soundLoaded) soundPool.play(warningSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            lastDeviationSoundElapsedRealtimeMs = now;
        }
        lastDeviationBgForSound = ui.deviationBgKind;
    }

    private void initSoundPool() {
        soundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
        soundPool.setOnLoadCompleteListener((p, s, st) -> soundLoaded = (st == 0));
        int resId = getResources().getIdentifier("deviation_warning", "raw", getPackageName());
        if (resId != 0) warningSoundId = soundPool.load(this, resId, 1);
    }

    private void releaseSoundPool() { if (soundPool != null) { soundPool.release(); soundPool = null; } }
}
