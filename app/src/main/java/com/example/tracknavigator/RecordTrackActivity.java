package com.example.tracknavigator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordTrackActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;
    private static final float MAX_ACCURACY_METERS = 15f; // Increased to allow "Poor" but showing
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    private static final long FIX_BUFFER_MAX_AGE_MS = 10_000L;
    private static final int FIX_BUFFER_MAX_SIZE = 32;

    private FusedLocationProviderClient fusedLocationClient;
    private final ArrayDeque<Location> recentFixes = new ArrayDeque<>();
    private final List<LatLngPoint> controlPoints = new ArrayList<>();
    private Location lastFix;
    private double totalDistanceKm = 0.0;

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            for (Location location : locationResult.getLocations()) {
                pushLocationSample(location);
            }
            updateGpsUi();
        }
    };

    private String pendingSaveFileName;
    private TextView textAccuracy, textPointsCount, textDistance;
    private ProgressBar progressGps;
    private ImageView imgGpsCheck;
    private MaterialButton btnAddPoint, btnFinish;

    private final ActivityResultLauncher<String> createDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/gpx+xml"),
            this::onCustomSaveLocationPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        textAccuracy = findViewById(R.id.textAccuracy);
        textPointsCount = findViewById(R.id.textPointsCount);
        textDistance = findViewById(R.id.textDistance);
        progressGps = findViewById(R.id.progressGps);
        imgGpsCheck = findViewById(R.id.imgGpsCheck);
        btnAddPoint = findViewById(R.id.btnAddPoint);
        btnFinish = findViewById(R.id.btnFinish);
        MaterialButton btnBack = findViewById(R.id.btnBack);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        btnAddPoint.setOnClickListener(v -> addControlPoint());
        btnFinish.setOnClickListener(v -> saveGpxAndFinish());
        btnBack.setOnClickListener(v -> finish());

        refreshUiLabels();
        checkPermissionsAndStartGps();
    }

    private void checkPermissionsAndStartGps() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_PERM);
        } else {
            startGpsUpdates();
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsUpdates();
        }
    }

    private void startGpsUpdates() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean enabled = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? lm.isLocationEnabled() : lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        
        if (!enabled) {
            if (progressGps != null) progressGps.setVisibility(View.GONE);
            if (imgGpsCheck != null) imgGpsCheck.setVisibility(View.GONE);
            Toast.makeText(this, R.string.enable_gps_toast, Toast.LENGTH_LONG).show();
            return;
        }

        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                .build();
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            btnAddPoint.setEnabled(true);
            btnFinish.setEnabled(true);
        } catch (SecurityException ignored) {}
    }

    private void pushLocationSample(@NonNull Location location) {
        lastFix = location;
        recentFixes.addLast(new Location(location));
        pruneFixBuffer();
    }

    private void pruneFixBuffer() {
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        recentFixes.removeIf(loc -> {
            long ertNanos = loc.getElapsedRealtimeNanos();
            long ageMs = (ertNanos == 0L) ? Math.max(0L, System.currentTimeMillis() - loc.getTime()) : (nowNanos - ertNanos) / 1_000_000L;
            return ageMs > FIX_BUFFER_MAX_AGE_MS;
        });
        while (recentFixes.size() > FIX_BUFFER_MAX_SIZE) recentFixes.pollFirst();
    }

    @Nullable
    private Location getBestRecentFix() {
        pruneFixBuffer();
        Location best = null;
        float bestAcc = Float.MAX_VALUE;
        for (Location loc : recentFixes) {
            if (loc.hasAccuracy() && loc.getAccuracy() < bestAcc) {
                bestAcc = loc.getAccuracy();
                best = loc;
            }
        }
        return best;
    }

    private void updateGpsUi() {
        if (lastFix == null) {
            if (progressGps != null) progressGps.setVisibility(View.VISIBLE);
            if (imgGpsCheck != null) imgGpsCheck.setVisibility(View.GONE);
            if (textAccuracy != null) textAccuracy.setText("—");
            return;
        }
        if (progressGps != null) progressGps.setVisibility(View.GONE);
        if (imgGpsCheck != null) imgGpsCheck.setVisibility(View.VISIBLE);

        if (textAccuracy != null) {
            float acc = lastFix.getAccuracy();
            String accText;
            if (acc < 5) {
                accText = getString(R.string.accuracy_high);
            } else if (acc <= 8) {
                accText = getString(R.string.accuracy_good);
            } else if (acc <= 10) {
                accText = getString(R.string.accuracy_medium);
            } else {
                accText = getString(R.string.accuracy_poor);
            }
            textAccuracy.setText(accText);
        }
    }

    private void addControlPoint() {
        Location fix = getBestRecentFix();
        if (fix == null) fix = lastFix;
        if (fix == null) { Toast.makeText(this, R.string.gps_waiting, Toast.LENGTH_SHORT).show(); return; }
        
        // We still allow adding if it's reasonably accurate, or you might want to block "Poor"
        if (!fix.hasAccuracy() || fix.getAccuracy() > 15f) {
            Toast.makeText(this, R.string.accuracy_too_poor, Toast.LENGTH_LONG).show();
            return;
        }

        LatLngPoint newPoint = new LatLngPoint(fix.getLatitude(), fix.getLongitude());
        if (!controlPoints.isEmpty()) {
            LatLngPoint lastPoint = controlPoints.get(controlPoints.size() - 1);
            totalDistanceKm += GeoUtils.distanceMeters(lastPoint, newPoint) / 1000.0;
        }
        
        controlPoints.add(newPoint);
        refreshUiLabels();
    }

    private void refreshUiLabels() {
        if (textPointsCount != null) textPointsCount.setText(getString(R.string.points_count, controlPoints.size()));
        if (textDistance != null) textDistance.setText(getString(R.string.distance_label, totalDistanceKm));
    }

    private void saveGpxAndFinish() {
        if (controlPoints.size() < 2) {
            Toast.makeText(this, R.string.need_two_points, Toast.LENGTH_LONG).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        EditText input = new EditText(this);
        input.setHint("track_" + stamp);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_name_title)
                .setMessage(R.string.save_name_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_action, (dialog, which) -> {
                    String typed = input.getText().toString().trim();
                    String baseName = typed.isEmpty() ? "track_" + stamp : typed;
                    pendingSaveFileName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".gpx";
                    createDocumentLauncher.launch(pendingSaveFileName);
                })
                .show();
    }

    private void onCustomSaveLocationPicked(Uri uri) {
        if (uri == null) { pendingSaveFileName = null; return; }
        String trackName = pendingSaveFileName.replace(".gpx", "");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os != null) {
                GpxHelper.writeTrack(os, trackName, controlPoints);
                Toast.makeText(this, R.string.gpx_saved, Toast.LENGTH_LONG).show();
                finish();
            }
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.save_failed_with_reason, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            pendingSaveFileName = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        super.onDestroy();
    }
}
