package com.example.tracknavigator;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
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

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity for Module 2: Race on track.
 * Follows the track from GPX and shows deviation and progress.
 */
public class RaceActivity extends AppCompatActivity implements RaceTrackingService.UiListener {

    private static final int REQ_PERM_LOCATION = 2001;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final double START_THRESHOLD_M = 5.0;

    private List<LatLngPoint> track;
    private RaceTrackingService boundService;
    private boolean serviceBound;
    private Location lastPreviewLocation;
    private boolean isStopping = false;

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
    private TextView textCheckpoint, textDeviation;
    private ImageView imgDirectionArrow;
    private TextView textMarkerStart, textMarkerMiddle, textMarkerFinish, textRaceStage;
    private View raceProgressTrack, raceProgressFill;
    private ProgressBar progressGpsSetup;
    private ImageView imgGpsCheckSetup;
    private MaterialButton btnPickGpx, btnStartRace;

    private final int colorMarkerDefault = Color.parseColor("#B0BEC5"); 
    private final int colorMarkerActive = Color.WHITE;

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

        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        layoutSetup = findViewById(R.id.layoutSetup);
        layoutActiveRace = findViewById(R.id.layoutActiveRace);

        textDistToStart = findViewById(R.id.textDistToStart);
        progressGpsSetup = findViewById(R.id.progressGpsSetup);
        imgGpsCheckSetup = findViewById(R.id.imgGpsCheckSetup);

        textCheckpoint = findViewById(R.id.textCheckpoint);
        imgDirectionArrow = findViewById(R.id.imgDirectionArrow);
        textDeviation = findViewById(R.id.textDeviation);

        raceProgressTrack = findViewById(R.id.raceProgressTrack);
        raceProgressFill = findViewById(R.id.raceProgressFill);
        textMarkerStart = findViewById(R.id.textMarkerStart);
        textMarkerMiddle = findViewById(R.id.textMarkerMiddle);
        textMarkerFinish = findViewById(R.id.textMarkerFinish);
        textRaceStage = findViewById(R.id.textRaceStage);

        btnPickGpx = findViewById(R.id.btnPickGpx);
        btnStartRace = findViewById(R.id.btnStartRace);
        MaterialButton btnStopRace = findViewById(R.id.btnStopRace);
        MaterialButton btnBackSetup = findViewById(R.id.btnBackFromSetup);

        btnPickGpx.setOnClickListener(v -> pickGpx());
        btnStartRace.setOnClickListener(v -> startRace());
        btnStopRace.setOnClickListener(v -> stopRaceUser());
        btnBackSetup.setOnClickListener(v -> exitModule());

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
        if (btnPickGpx != null) btnPickGpx.setEnabled(true);
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
            textDistToStart.setTextColor(dist <= START_THRESHOLD_M ? Color.WHITE : Color.parseColor("#FFCCBC"));
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

        if (textCheckpoint != null) textCheckpoint.setText(state.checkpointText);
        if (textDeviation != null) textDeviation.setText(state.deviationText);
        
        updateDirectionArrow(state);
        updateRaceProgress(state);

        if (state.checkpointJustPassed) {
            Toast.makeText(this, R.string.checkpoint_passed, Toast.LENGTH_SHORT).show();
        }
        if (state.raceFinished) {
            Toast.makeText(this, R.string.race_finished, Toast.LENGTH_LONG).show();
            stopRaceUser();
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
            imgDirectionArrow.setColorFilter(Color.GREEN);
        } else {
            imgDirectionArrow.setColorFilter(Color.RED);
            if (state.crossTrackSign > 0) {
                // Deviation to the left -> Arrow points left
                imgDirectionArrow.setRotation(-90);
            } else if (state.crossTrackSign < 0) {
                // Deviation to the right -> Arrow points right
                imgDirectionArrow.setRotation(90);
            } else {
                // Just in case, if bg is warning but sign is 0
                imgDirectionArrow.setRotation(0);
            }
        }
    }

    private void updateRaceProgress(@NonNull RaceUiState state) {
        if (textRaceStage != null) textRaceStage.setText(stageLabel(state.routeStage));
        applyMarkerHighlight(state.routeStage);

        if (raceProgressTrack != null && raceProgressFill != null) {
            raceProgressTrack.post(() -> {
                int trackHeight = raceProgressTrack.getHeight();
                if (trackHeight <= 0) return;
                int fillHeight = Math.round(trackHeight * state.progressFraction);
                ViewGroup.LayoutParams params = raceProgressFill.getLayoutParams();
                params.height = fillHeight;
                raceProgressFill.setLayoutParams(params);
            });
        }
    }

    @NonNull
    private String stageLabel(int routeStage) {
        if (routeStage == 2) return getString(R.string.race_stage_finish);
        if (routeStage == 1) return getString(R.string.race_stage_middle);
        return getString(R.string.race_stage_start);
    }

    private void applyMarkerHighlight(int routeStage) {
        if (textMarkerStart != null) {
            textMarkerStart.setTextColor(routeStage == 0 ? colorMarkerActive : colorMarkerDefault);
            textMarkerStart.setTypeface(null, routeStage == 0 ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (textMarkerMiddle != null) {
            textMarkerMiddle.setTextColor(routeStage == 1 ? colorMarkerActive : colorMarkerDefault);
            textMarkerMiddle.setTypeface(null, routeStage == 1 ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (textMarkerFinish != null) {
            textMarkerFinish.setTextColor(routeStage == 2 ? colorMarkerActive : colorMarkerDefault);
            textMarkerFinish.setTypeface(null, routeStage == 2 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void stopRaceUser() {
        if (isStopping) return;
        isStopping = true;

        RaceTrackingService.requestStopRace(this);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        boundService = null;
        track = null;
        
        Toast.makeText(this, "Заезд остановлен", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void exitModule() {
        if (isStopping) return;
        isStopping = true;
        RaceTrackingService.requestStopRace(this);
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
