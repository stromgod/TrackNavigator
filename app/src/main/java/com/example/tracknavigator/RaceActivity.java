package com.example.tracknavigator;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Locale;

public class RaceActivity extends AppCompatActivity implements RaceTrackingService.UiListener {

    private static final int REQ_PERM_LOCATION = 2001;
    private static final int REQ_PERM_NOTIF = 2002;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final double START_THRESHOLD_M = 5.0;

    private List<LatLngPoint> track;
    private RaceTrackingService boundService;
    private boolean serviceBound;
    private boolean gpsActive = false;
    private Location lastPreviewLocation;

    private FusedLocationProviderClient fusedClient;
    private final LocationCallback previewLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            if (RaceTrackingService.isRunning()) return;
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
            switchToRaceLayout(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            serviceBound = false;
            switchToRaceLayout(false);
        }
    };

    private View layoutSetup, layoutActiveRace;
    private TextView textSetupStatus, textDistToStart;
    private TextView textCheckpoint, textCoords, textAccuracy, textDeviation;
    private MaterialButton btnPickGpx, btnStartGps, btnStartRace;

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

        textSetupStatus = findViewById(R.id.textSetupStatus);
        textDistToStart = findViewById(R.id.textDistToStart);

        textCheckpoint = findViewById(R.id.textCheckpoint);
        textCoords = findViewById(R.id.textCoords);
        textAccuracy = findViewById(R.id.textRaceAccuracy);
        textDeviation = findViewById(R.id.textDeviation);

        btnPickGpx = findViewById(R.id.btnPickGpx);
        btnStartGps = findViewById(R.id.btnStartGps);
        btnStartRace = findViewById(R.id.btnStartRace);
        MaterialButton btnStopRace = findViewById(R.id.btnStopRace);
        MaterialButton btnBackSetup = findViewById(R.id.btnBackFromSetup);

        btnPickGpx.setOnClickListener(v -> pickGpx());
        btnStartGps.setOnClickListener(v -> startGpsClicked());
        btnStartRace.setOnClickListener(v -> startRace());
        btnStopRace.setOnClickListener(v -> stopRaceUser());
        btnBackSetup.setOnClickListener(v -> finish());

        if (RaceTrackingService.isRunning()) {
            bindRaceService();
        } else {
            switchToRaceLayout(false);
            updateSetupButtons();
        }
    }

    private void switchToRaceLayout(boolean active) {
        layoutSetup.setVisibility(active ? View.GONE : View.VISIBLE);
        layoutActiveRace.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void updateSetupButtons() {
        btnPickGpx.setEnabled(true);
        btnStartGps.setEnabled(!gpsActive);
        checkStartEligibility();
    }

    private void checkStartEligibility() {
        boolean hasTrack = (track != null && track.size() >= 2);
        boolean atStart = false;
        if (hasTrack && lastPreviewLocation != null) {
            double dist = GeoUtils.distanceMeters(new LatLngPoint(lastPreviewLocation.getLatitude(), lastPreviewLocation.getLongitude()), track.get(0));
            atStart = dist <= START_THRESHOLD_M;
        }
        btnStartRace.setEnabled(hasTrack && gpsActive && atStart);
    }

    private void updateSetupProximityUi(Location loc) {
        if (track == null || track.isEmpty()) {
            textSetupStatus.setText(R.string.gps_ready_record);
            return;
        }
        double dist = GeoUtils.distanceMeters(new LatLngPoint(loc.getLatitude(), loc.getLongitude()), track.get(0));
        textDistToStart.setVisibility(View.VISIBLE);
        textDistToStart.setText(getString(R.string.dist_to_start, dist));

        if (dist <= START_THRESHOLD_M) {
            textSetupStatus.setText(getString(R.string.race_status_idle));
            textDistToStart.setTextColor(ContextCompat.getColor(this, R.color.success_bg));
        } else {
            textSetupStatus.setText(getString(R.string.wait_at_start, START_THRESHOLD_M));
            textDistToStart.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }
        checkStartEligibility();
    }

    private void startGpsClicked() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM_LOCATION);
            return;
        }
        enableGpsUpdates();
    }

    private void enableGpsUpdates() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, R.string.enable_gps_toast, Toast.LENGTH_LONG).show();
            return;
        }
        gpsActive = true;
        updateSetupButtons();
        startPreviewLocationUpdates();
    }

    private void startPreviewLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build();
        try { fusedClient.requestLocationUpdates(request, previewLocationCallback, Looper.getMainLooper()); } catch (SecurityException ignored) {}
    }

    private void stopPreviewLocationUpdates() {
        fusedClient.removeLocationUpdates(previewLocationCallback);
    }

    private void pickGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        openGpxLauncher.launch(intent);
    }

    private void loadGpxFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            track = GpxHelper.readPoints(in);
            if (track == null || track.size() < 2) {
                Toast.makeText(this, R.string.gpx_invalid_file, Toast.LENGTH_LONG).show();
                track = null;
            } else {
                Toast.makeText(this, getString(R.string.gpx_loaded_points, track.size()), Toast.LENGTH_SHORT).show();
                if (lastPreviewLocation != null) updateSetupProximityUi(lastPreviewLocation);
            }
            updateSetupButtons();
        } catch (IOException | XmlPullParserException e) {
            Toast.makeText(this, "GPX Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startRace() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM_NOTIF);
                return;
            }
        }
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
        textCheckpoint.setText(state.checkpointText);
        textDeviation.setText(state.deviationText);
        textCoords.setText(state.coordsText);
        textAccuracy.setText(state.accuracyText);
        applyDeviationBackground(state.deviationBgKind);

        if (state.checkpointJustPassed) {
            Toast.makeText(this, R.string.checkpoint_passed, Toast.LENGTH_SHORT).show();
        }
        if (state.raceFinished) {
            Toast.makeText(this, R.string.race_finished, Toast.LENGTH_LONG).show();
            stopRaceUser();
        }
    }

    private void applyDeviationBackground(int kind) {
        int colorRes = android.R.color.transparent;
        if (kind == RaceEvaluator.BG_WARNING) colorRes = R.color.warning_bg;
        else if (kind == RaceEvaluator.BG_SUCCESS) colorRes = R.color.success_bg;
        textDeviation.setBackgroundColor(ContextCompat.getColor(this, colorRes));
    }

    private void stopRaceUser() {
        RaceTrackingService.requestStop(this);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        boundService = null;
        gpsActive = false;
        switchToRaceLayout(false);
        updateSetupButtons();
        textDistToStart.setVisibility(View.GONE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        stopPreviewLocationUpdates();
        super.onStop();
    }
}
