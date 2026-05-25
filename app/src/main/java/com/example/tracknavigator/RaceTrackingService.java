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
 * Starts GPS search early and keeps it running seamlessly.
 */
public class RaceTrackingService extends Service {

    public static final String ACTION_PREPARE = "com.example.tracknavigator.action.RACE_PREPARE";
    public static final String ACTION_START = "com.example.tracknavigator.action.RACE_START";
    public static final String ACTION_STOP_RACE = "com.example.tracknavigator.action.RACE_STOP_RACE";
    public static final String ACTION_STOP_SERVICE = "com.example.tracknavigator.action.RACE_STOP_SERVICE";
    public static final String EXTRA_TRACK = "extra_track";

    private static final String TAG = "RaceTrackingService";
    private static final int NOTIF_ID = 7421;
    private static final String CHANNEL_ID = "race_location";
    private static final long TICK_MS = 2000L;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final long DEVIATION_SOUND_REPEAT_MS = 5000L;

    private static volatile boolean sRunning;
    private static volatile boolean sRaceActive;

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean isRaceActive() {
        return sRaceActive;
    }

    public static void requestStopRace(@NonNull Context ctx) {
        Intent i = new Intent(ctx, RaceTrackingService.class);
        i.setAction(ACTION_STOP_RACE);
        ctx.startService(i);
    }

    public static void requestStopService(@NonNull Context ctx) {
        Intent i = new Intent(ctx, RaceTrackingService.class);
        i.setAction(ACTION_STOP_SERVICE);
        ctx.startService(i);
    }

    public interface UiListener {
        void onRaceUiState(@NonNull RaceUiState state);
        void onLocationUpdate(@NonNull Location location);
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
                if (uiListener != null) uiListener.onLocationUpdate(location);
            }
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sRaceActive) return;
            runEvaluationTick();
            if (sRaceActive) mainHandler.postDelayed(this, TICK_MS);
        }
    };

    @Nullable private UiListener uiListener;
    @Nullable private List<LatLngPoint> track;
    private int segmentStartIndex;
    @Nullable private Location lastFix;
    @Nullable private RaceUiState lastUiState;

    private int lastDeviationBgForSound = RaceEvaluator.BG_NONE;
    private long lastDeviationSoundElapsedRealtimeMs;

    @Nullable private SoundPool soundPool;
    private int warningSoundId;
    private int middleSoundId;
    private int finishSoundId;
    private boolean soundLoaded;
    private boolean hasPlayedMiddleSound;

    public void setUiListener(@Nullable UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            if (lastUiState != null) listener.onRaceUiState(lastUiState);
            if (lastFix != null) listener.onLocationUpdate(lastFix);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        ensureChannel();
        sRunning = true;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        
        if (ACTION_STOP_SERVICE.equals(action)) {
            shutdownFromStopAction();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_RACE.equals(action)) {
            onRaceCompleted();
            return START_STICKY;
        }
        
        if (ACTION_PREPARE.equals(action)) {
            startForegroundServiceMode();
            ensureLocationUpdatesRunning();
            return START_STICKY;
        }
        
        if (ACTION_START.equals(action)) {
            sRaceActive = true;
            List<LatLngPoint> loaded = readTrackExtra(intent);
            if (loaded != null) beginSession(loaded);
            else { sRaceActive = false; startForegroundServiceMode(); }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        tearDownLocationAndTicker();
        sRunning = false;
        sRaceActive = false;
        super.onDestroy();
    }

    private void shutdownFromStopAction() {
        tearDownLocationAndTicker();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        sRunning = false;
        sRaceActive = false;
        stopSelf();
    }

    private void tearDownLocationAndTicker() {
        sRaceActive = false;
        locationUpdatesRunning = false;
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

    private boolean locationUpdatesRunning;
    private void ensureLocationUpdatesRunning() {
        if (locationUpdatesRunning) return;
        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY).build();
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            locationUpdatesRunning = true;
        } catch (SecurityException ignored) {}
    }

    private void startForegroundServiceMode() {
        startForeground(NOTIF_ID, buildNotification(lastUiState), 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
    }

    private void beginSession(@NonNull List<LatLngPoint> points) {
        mainHandler.removeCallbacks(tickRunnable);
        lastUiState = null;
        hasPlayedMiddleSound = false;
        initSoundPool();
        track = points;
        segmentStartIndex = 0;
        sRaceActive = true;

        startForegroundServiceMode();
        ensureLocationUpdatesRunning();
        
        runEvaluationTick();
        
        mainHandler.postDelayed(tickRunnable, TICK_MS);
    }

    private void runEvaluationTick() {
        if (track == null) return;
        RaceEvaluator.StepResult step = RaceEvaluator.evaluateStep(this, track, segmentStartIndex, lastFix);
        segmentStartIndex = step.newSegmentStartIndex;
        lastUiState = step.ui;
        
        maybePlayDeviationWarning(step.ui);
        maybePlayMilestoneSounds(step.ui, step.raceFinished);
        
        updateNotification(step.ui, step.checkpointJustPassed);

        if (uiListener != null) uiListener.onRaceUiState(step.ui);
        if (step.raceFinished) onRaceCompleted();
    }

    private void maybePlayMilestoneSounds(@NonNull RaceUiState ui, boolean raceFinished) {
        if (!soundLoaded || soundPool == null) return;

        // Финиш
        if (raceFinished && finishSoundId != 0) {
            soundPool.play(finishSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            return;
        }

        // Половина дистанции
        if (!hasPlayedMiddleSound && ui.routeStage == RaceUiState.STAGE_MIDDLE && middleSoundId != 0) {
            soundPool.play(middleSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            hasPlayedMiddleSound = true;
        }
    }

    private void onRaceCompleted() {
        sRaceActive = false;
        track = null;
        mainHandler.removeCallbacks(tickRunnable);
        startForegroundServiceMode(); // Switch to "waiting" notification
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.race_notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @NonNull
    private Notification buildNotification(@Nullable RaceUiState state) {
        String content = (state != null && sRaceActive) ? state.checkpointText + " - " + state.deviationText : getString(R.string.gps_waiting);
        Intent intent = new Intent(this, RaceActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

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
        if (!sRaceActive) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent intent = new Intent(this, RaceActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.race_notif_title))
                .setContentText(state.checkpointText + " | " + state.deviationText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOnlyAlertOnce(!alert)
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        
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
            if (soundPool != null && soundLoaded && warningSoundId != 0) soundPool.play(warningSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
            lastDeviationSoundElapsedRealtimeMs = now;
        }
        lastDeviationBgForSound = ui.deviationBgKind;
    }

    private void initSoundPool() {
        if (soundPool != null) return;
        soundPool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
        soundPool.setOnLoadCompleteListener((p, s, st) -> soundLoaded = (st == 0));
        
        String pkg = getPackageName();
        int warnRes = getResources().getIdentifier("deviation_warning", "raw", pkg);
        if (warnRes != 0) warningSoundId = soundPool.load(this, warnRes, 1);
        
        int middleRes = getResources().getIdentifier("race_middle", "raw", pkg);
        if (middleRes != 0) middleSoundId = soundPool.load(this, middleRes, 1);
        
        int finishRes = getResources().getIdentifier("race_finish", "raw", pkg);
        if (finishRes != 0) finishSoundId = soundPool.load(this, finishRes, 1);
    }

    private void releaseSoundPool() { if (soundPool != null) { soundPool.release(); soundPool = null; soundLoaded = false; } }
}
