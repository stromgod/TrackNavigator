package com.example.tracknavigator;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Activity for Module 2: Race on track.
 * Follows the track from GPX and shows deviation and progress.
 */
public class RaceActivity extends AppCompatActivity implements RaceTrackingService.UiListener {

    private static final int REQ_PERM_LOCATION = 2001;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final double START_THRESHOLD_M = 5.0;
    private static final long BOT_STATE_MIN_DISPLAY_MS = 3500L;
    private static final long BOT_FINISH_READ_MS = 4500L;

    private List<LatLngPoint> track;
    private RaceTrackingService boundService;
    private boolean serviceBound;
    private Location lastPreviewLocation;
    private boolean isStopping = false;

    public static final String EXTRA_TRACK_POINTS = "extra_track_points";

    private MaterialCardView layoutBotRace;
    private ImageView imgBotRace;
    private TextView textBotRace;
    private MaterialCardView layoutBotRaceSetup;
    private ImageView imgBotRaceSetup;
    private TextView textBotRaceSetup;

    private boolean trackPreloaded = false;
    private long raceStartMs = 0;
    private int lastBotMode = -1; // 0 = happy, 1 = bad
    private long lastBotModeChangeAtMs = 0L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private FusedLocationProviderClient fusedClient;
    private final LocationCallback previewLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            if (isStopping || RaceTrackingService.isRaceActive() || serviceBound) return;
            Location loc = locationResult.getLastLocation();
            if (loc != null) {
                lastPreviewLocation = loc;
                updateSetupProximityUi(loc);
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RaceTrackingService.LocalBinder localBinder = (RaceTrackingService.LocalBinder) binder;
            boundService = localBinder.getService();
            serviceBound = true;
            boundService.setUiListener(RaceActivity.this);
            stopPreviewLocationUpdates();
            
            if (!isStopping && RaceTrackingService.isRaceActive()) {
                switchToRaceLayout(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            serviceBound = false;
            if (!isStopping && !RaceTrackingService.isRaceActive()) {
                switchToRaceLayout(false);
                checkPermissionsAndEnableGps();
            }
        }
    };

    private View layoutSetup, layoutActiveRace;
    private TextView textDistToStart;
    private TextView textDeviation;
    private ImageView imgDirectionArrow;
    private TextView textMarkerStart, textMarkerFinish;
    private View raceProgressTrack, raceProgressFill;
    private ProgressBar progressGpsSetup;
    private ImageView imgGpsCheckSetup;
    private MaterialButton btnPickGpx, btnStartRace;

    private int colorMarkerDefault;
    private int colorMarkerActive;
    private int colorStartFar;
    private int colorDirectionGood;
    private int colorDirectionBad;

    private final ActivityResultLauncher<Intent> openGpxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) loadGpxFromUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_race);

        colorMarkerDefault = ContextCompat.getColor(this, R.color.progress_marker);
        colorMarkerActive = ContextCompat.getColor(this, R.color.progress_marker_active);
        colorStartFar = ContextCompat.getColor(this, R.color.race_start_far);
        colorDirectionGood = ContextCompat.getColor(this, R.color.race_deviation_on_track);
        colorDirectionBad = ContextCompat.getColor(this, R.color.race_deviation_off_track);

        // Optional: preloaded track points from RecordTrackActivity.
        Object extra = getIntent().getSerializableExtra(EXTRA_TRACK_POINTS);
        if (extra instanceof ArrayList) {
            ArrayList<?> list = (ArrayList<?>) extra;
            if (!list.isEmpty() && list.get(0) instanceof LatLngPoint) {
                //noinspection unchecked
                track = (ArrayList<LatLngPoint>) extra;
                if (track != null && track.size() >= 2) trackPreloaded = true;
            }
        }

        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        layoutSetup = findViewById(R.id.layoutSetup);
        layoutActiveRace = findViewById(R.id.layoutActiveRace);

        layoutBotRace = findViewById(R.id.layoutBotRace);
        imgBotRace = findViewById(R.id.imgBotRace);
        textBotRace = findViewById(R.id.textBotRace);
        layoutBotRaceSetup = findViewById(R.id.layoutBotRaceSetup);
        imgBotRaceSetup = findViewById(R.id.imgBotRaceSetup);
        textBotRaceSetup = findViewById(R.id.textBotRaceSetup);

        textDistToStart = findViewById(R.id.textDistToStart);
        progressGpsSetup = findViewById(R.id.progressGpsSetup);
        imgGpsCheckSetup = findViewById(R.id.imgGpsCheckSetup);

        imgDirectionArrow = findViewById(R.id.imgDirectionArrow);
        textDeviation = findViewById(R.id.textDeviation);

        raceProgressTrack = findViewById(R.id.raceProgressTrack);
        raceProgressFill = findViewById(R.id.raceProgressFill);
        textMarkerStart = findViewById(R.id.textMarkerStart);
        textMarkerFinish = findViewById(R.id.textMarkerFinish);

        btnPickGpx = findViewById(R.id.btnPickGpx);
        btnStartRace = findViewById(R.id.btnStartRace);
        MaterialButton btnStopRace = findViewById(R.id.btnStopRace);
        MaterialButton btnBackSetup = findViewById(R.id.btnBackFromSetup);

        if (btnStopRace != null) btnStopRace.setOnClickListener(v -> stopRaceUser(0));
        if (btnBackSetup != null) btnBackSetup.setOnClickListener(v -> exitModule());

        btnPickGpx.setOnClickListener(v -> pickGpx());
        btnStartRace.setOnClickListener(v -> {
            raceStartMs = System.currentTimeMillis();
            if (!BotPrefs.isOnboardingDone(this)) {
                BotPrefs.setStep(this, BotPrefs.Step.IN_RACE);
            }
            startRace();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                exitModule();
            }
        });

        if (RaceTrackingService.isRaceActive()) {
            switchToRaceLayout(true);
            bindRaceService();
        } else {
            switchToRaceLayout(false);
            updateSetupButtons();
            checkPermissionsAndEnableGps();
            refreshBotUiForSetup();
        }
    }

    private void checkPermissionsAndEnableGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM_LOCATION);
        } else {
            enableGpsUpdates();
        }
    }

    private void switchToRaceLayout(boolean active) {
        runOnUiThread(() -> {
            if (layoutSetup != null) layoutSetup.setVisibility(active ? View.GONE : View.VISIBLE);
            if (layoutActiveRace != null) layoutActiveRace.setVisibility(active ? View.VISIBLE : View.GONE);
        });
    }

    private void updateSetupButtons() {
        if (btnPickGpx != null) btnPickGpx.setEnabled(!trackPreloaded);
        checkStartEligibility();
    }

    private void checkStartEligibility() {
        boolean hasTrack = (track != null && track.size() >= 2);
        if (btnStartRace != null) btnStartRace.setEnabled(hasTrack);
    }

    private void updateSetupProximityUi(Location loc) {
        if (progressGpsSetup != null) progressGpsSetup.setVisibility(View.GONE);
        if (imgGpsCheckSetup != null) imgGpsCheckSetup.setVisibility(View.VISIBLE);
        
        if (track == null || track.isEmpty()) {
            if (textDistToStart != null) textDistToStart.setVisibility(View.GONE);
            return;
        }
        double dist = GeoUtils.distanceMeters(new LatLngPoint(loc.getLatitude(), loc.getLongitude()), track.get(0));
        if (textDistToStart != null) {
            textDistToStart.setVisibility(View.VISIBLE);
            textDistToStart.setText(getString(R.string.dist_to_start, dist));
            textDistToStart.setTextColor(dist <= START_THRESHOLD_M ? colorMarkerActive : colorStartFar);
        }
        checkStartEligibility();
    }

    private void enableGpsUpdates() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, R.string.enable_gps_toast, Toast.LENGTH_LONG).show();
            if (progressGpsSetup != null) progressGpsSetup.setVisibility(View.GONE);
            return;
        }
        if (progressGpsSetup != null) progressGpsSetup.setVisibility(View.VISIBLE);
        if (imgGpsCheckSetup != null) imgGpsCheckSetup.setVisibility(View.GONE);

        Intent prepare = new Intent(this, RaceTrackingService.class);
        prepare.setAction(RaceTrackingService.ACTION_PREPARE);
        try { startService(prepare); } catch (Exception ignored) {}
        bindRaceService();

        startPreviewLocationUpdates();
    }

    private void startPreviewLocationUpdates() {
        if (serviceBound) return;
        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build();
        try { fusedClient.requestLocationUpdates(request, previewLocationCallback, Looper.getMainLooper()); } catch (SecurityException ignored) {}
    }

    private void stopPreviewLocationUpdates() {
        try { fusedClient.removeLocationUpdates(previewLocationCallback); } catch (Exception ignored) {}
    }

    private void pickGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        openGpxLauncher.launch(intent);
    }

    private void loadGpxFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            track = GpxHelper.readPoints(in);
            if (track == null || track.size() < 2) {
                Toast.makeText(this, R.string.gpx_invalid_file, Toast.LENGTH_LONG).show();
                track = null;
            } else {
                Toast.makeText(this, getString(R.string.gpx_loaded_points, track.size()), Toast.LENGTH_SHORT).show();
                checkPermissionsAndEnableGps();
                if (lastPreviewLocation != null) updateSetupProximityUi(lastPreviewLocation);
            }
            updateSetupButtons();
        } catch (IOException | XmlPullParserException e) {
            Toast.makeText(this, "GPX Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startRace() {
        if (track == null) return;
        isStopping = false;

        switchToRaceLayout(true);

        Intent start = new Intent(this, RaceTrackingService.class);
        start.setAction(RaceTrackingService.ACTION_START);
        start.putExtra(RaceTrackingService.EXTRA_TRACK, new ArrayList<>(track));
        ContextCompat.startForegroundService(this, start);
        bindRaceService();
    }

    private void bindRaceService() {
        if (serviceBound) return;
        bindService(new Intent(this, RaceTrackingService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onRaceUiState(@NonNull RaceUiState state) {
        if (isStopping || isFinishing() || !RaceTrackingService.isRaceActive()) return;

        if (layoutActiveRace != null && layoutActiveRace.getVisibility() != View.VISIBLE) {
            switchToRaceLayout(true);
        }

        if (textDeviation != null) textDeviation.setText(state.deviationText);
        
        updateDirectionArrow(state);
        updateRaceProgress(state);

        if (state.checkpointJustPassed) {
            Toast.makeText(this, R.string.checkpoint_passed, Toast.LENGTH_SHORT).show();
        }

        refreshBotForRaceState(state);

        if (state.raceFinished) {
            Toast.makeText(this, R.string.race_finished, Toast.LENGTH_LONG).show();
            stopRaceUser(BOT_FINISH_READ_MS);
        }
    }

    @Override
    public void onLocationUpdate(@NonNull Location location) {
        if (isStopping || isFinishing() || RaceTrackingService.isRaceActive()) return;
        lastPreviewLocation = location;
        updateSetupProximityUi(location);
    }

    private void updateDirectionArrow(@NonNull RaceUiState state) {
        if (imgDirectionArrow == null) return;
        
        if (state.deviationBgKind == RaceEvaluator.BG_NONE || state.deviationBgKind == RaceEvaluator.BG_SUCCESS) {
            imgDirectionArrow.setRotation(0);
            imgDirectionArrow.setColorFilter(colorDirectionGood);
        } else {
            imgDirectionArrow.setColorFilter(colorDirectionBad);
            if (state.crossTrackSign > 0) {
                imgDirectionArrow.setRotation(-90);
            } else if (state.crossTrackSign < 0) {
                imgDirectionArrow.setRotation(90);
            } else {
                imgDirectionArrow.setRotation(0);
            }
        }
    }

    private void updateRaceProgress(@NonNull RaceUiState state) {
        applyMarkerHighlight(state.routeStage);

        if (raceProgressTrack != null && raceProgressFill != null) {
            raceProgressTrack.post(() -> {
                int trackWidth = raceProgressTrack.getWidth();
                if (trackWidth <= 0) return;
                int fillWidth = Math.round(trackWidth * state.progressFraction);
                ViewGroup.LayoutParams params = raceProgressFill.getLayoutParams();
                params.width = fillWidth;
                raceProgressFill.setLayoutParams(params);
            });
        }
    }

    private void applyMarkerHighlight(int routeStage) {
        if (textMarkerStart != null) {
            textMarkerStart.setTextColor(routeStage == 0 ? colorMarkerActive : colorMarkerDefault);
            textMarkerStart.setTypeface(null, routeStage == 0 ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (textMarkerFinish != null) {
            textMarkerFinish.setTextColor(routeStage == 2 ? colorMarkerActive : colorMarkerDefault);
            textMarkerFinish.setTypeface(null, routeStage == 2 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void refreshBotUiForSetup() {
        if (layoutBotRaceSetup == null || imgBotRaceSetup == null || textBotRaceSetup == null) return;
        if (BotPrefs.isOnboardingDone(this)) {
            layoutBotRaceSetup.setVisibility(View.GONE);
            return;
        }

        BotAssets.setBotIcon(this, imgBotRaceSetup, "Greetings.png");
        if (track == null || track.size() < 2) {
            textBotRaceSetup.setText(getString(R.string.bot_guide_pick_track_file));
        } else {
            textBotRaceSetup.setText(getString(R.string.bot_guide_race_setup));
        }
        layoutBotRaceSetup.setVisibility(View.VISIBLE);
    }

    private void refreshBotForRaceState(@NonNull RaceUiState state) {
        if (layoutBotRace == null || imgBotRace == null || textBotRace == null) return;
        if (layoutBotRaceSetup != null) layoutBotRaceSetup.setVisibility(View.GONE);
        if (BotPrefs.isOnboardingDone(this)) {
            layoutBotRace.setVisibility(View.GONE);
            return;
        }

        if (state.raceFinished) {
            String name = BotPrefs.getName(this);
            if (name == null || name.trim().isEmpty()) name = "User";

            String time = formatDuration(Math.max(0, System.currentTimeMillis() - raceStartMs));
            String distanceKm = computeTrackDistanceKm();

            BotAssets.setBotIcon(this, imgBotRace, "Greetings.png");
            textBotRace.setText(getString(R.string.bot_race_finished, name, time, distanceKm));
            layoutBotRace.setVisibility(View.VISIBLE);

            BotPrefs.setStep(this, BotPrefs.Step.DONE);
            lastBotMode = -1;
            return;
        }

        boolean offTrack = state.deviationBgKind == RaceEvaluator.BG_WARNING;
        int newMode = offTrack ? 1 : 0;
        long now = System.currentTimeMillis();

        if (lastBotMode != -1 && newMode != lastBotMode && (now - lastBotModeChangeAtMs) < BOT_STATE_MIN_DISPLAY_MS) {
            // Keep previous phrase for readability if route state flips too quickly.
            return;
        }

        if (offTrack) {
            if (lastBotMode != 1) {
                BotAssets.setBotIcon(this, imgBotRace, "Bad.png");
                lastBotMode = 1;
                lastBotModeChangeAtMs = now;
            }
            textBotRace.setText(getString(R.string.bot_bad_off_track));
        } else {
            if (lastBotMode != 0) {
                BotAssets.setBotIcon(this, imgBotRace, "Happy.png");
                lastBotMode = 0;
                lastBotModeChangeAtMs = now;
            }
            textBotRace.setText(getString(R.string.bot_happy_on_track));
        }

        layoutBotRace.setVisibility(View.VISIBLE);
    }

    private String formatDuration(long durationMs) {
        long totalSec = Math.max(0, durationMs) / 1000L;
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    private String computeTrackDistanceKm() {
        if (track == null || track.size() < 2) return "0.0";
        double km = 0.0;
        for (int i = 0; i < track.size() - 1; i++) {
            km += GeoUtils.distanceMeters(track.get(i), track.get(i + 1)) / 1000.0;
        }
        return String.format(Locale.US, "%.2f", km);
    }

    private void stopRaceUser(long delayMs) {
        if (isStopping) return;
        isStopping = true;

        RaceTrackingService.requestStopRace(this);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        boundService = null;
        track = null;

        Runnable finishTask = () -> {
            Toast.makeText(this, "Заезд окончен", Toast.LENGTH_SHORT).show();
            finish();
        };

        if (delayMs <= 0) finishTask.run();
        else uiHandler.postDelayed(finishTask, delayMs);
    }

    private void exitModule() {
        // Если гонка активна, системная кнопка назад только закрывает UI
        if (RaceTrackingService.isRaceActive()) {
            finish();
            return;
        }

        // Если гонка не начата, выходим полностью и останавливаем фоновый GPS
        if (isStopping) return;
        isStopping = true;
        RaceTrackingService.requestStopService(this);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableGpsUpdates();
        }
    }

    @Override
    protected void onStop() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        stopPreviewLocationUpdates();
        super.onStop();
    }
}
