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
import android.widget.EditText;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Module 1: GPS fixes with accuracy check; append control points to a GPX track until finish.
 * Uses Fused Location (GPS + sensors + network fusion). When adding a point, picks the best
 * accuracy among samples from the last few seconds instead of only the latest callback.
 */
public class RecordTrackActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;
    /** Only add points when horizontal accuracy is at least this good (dense ~4–5 m tracks). */
    private static final float MAX_ACCURACY_METERS = 8f;
    private static final long GPS_MIN_INTERVAL_MS = 500L;
    /** Consider samples at most this old (by elapsed realtime) when choosing best fix. */
    private static final long FIX_BUFFER_MAX_AGE_MS = 10_000L;
    private static final int FIX_BUFFER_MAX_SIZE = 32;

    private FusedLocationProviderClient fusedLocationClient;
    private final ArrayDeque<Location> recentFixes = new ArrayDeque<>();
    private final List<LatLngPoint> controlPoints = new ArrayList<>();
    private Location lastFix;

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

    private TextView textStatus;
    private TextView textCoords;
    private TextView textAccuracy;
    private TextView textPointsCount;
    private MaterialButton btnStartGps;
    private MaterialButton btnAddPoint;
    private MaterialButton btnFinish;
    private final ActivityResultLauncher<String> createDocumentLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/gpx+xml"),
            this::onCustomSaveLocationPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);

        textStatus = findViewById(R.id.textStatus);
        textCoords = findViewById(R.id.textCoords);
        textAccuracy = findViewById(R.id.textAccuracy);
        textPointsCount = findViewById(R.id.textPointsCount);
        btnStartGps = findViewById(R.id.btnStartGps);
        btnAddPoint = findViewById(R.id.btnAddPoint);
        btnFinish = findViewById(R.id.btnFinish);
        MaterialButton btnBack = findViewById(R.id.btnBack);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        btnStartGps.setOnClickListener(v -> startGpsIfPermitted());
        btnAddPoint.setOnClickListener(v -> addControlPoint());
        btnFinish.setOnClickListener(v -> saveGpxAndFinish());
        btnBack.setOnClickListener(v -> finish());

        refreshPointsLabel();
        textCoords.setText("—");
        textAccuracy.setText("—");
    }

    private void startGpsIfPermitted() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_PERM);
            return;
        }
        startGpsUpdates();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsUpdates();
        } else {
            Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDeviceLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void startGpsUpdates() {
        if (!isDeviceLocationEnabled()) {
            textStatus.setText(R.string.record_status_idle);
            Toast.makeText(this, R.string.enable_gps_toast, Toast.LENGTH_LONG).show();
            return;
        }
        LocationRequest request = new LocationRequest.Builder(GPS_MIN_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                .build();
        try {
            recentFixes.clear();
            lastFix = null;
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            btnStartGps.setEnabled(false);
            btnAddPoint.setEnabled(true);
            btnFinish.setEnabled(true);
            textStatus.setText(R.string.gps_waiting);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show();
        }
    }

    private void pushLocationSample(@NonNull Location location) {
        lastFix = location;
        recentFixes.addLast(new Location(location));
        pruneFixBuffer();
    }

    /** Age of sample; uses elapsed realtime when present, else wall clock vs {@link Location#getTime()}. */
    private static long sampleAgeMs(@NonNull Location loc, long nowElapsedRealtimeNanos) {
        long ertNanos = loc.getElapsedRealtimeNanos();
        if (ertNanos == 0L) {
            return Math.max(0L, System.currentTimeMillis() - loc.getTime());
        }
        return (nowElapsedRealtimeNanos - ertNanos) / 1_000_000L;
    }

    private void pruneFixBuffer() {
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        Iterator<Location> it = recentFixes.iterator();
        while (it.hasNext()) {
            Location loc = it.next();
            if (sampleAgeMs(loc, nowNanos) > FIX_BUFFER_MAX_AGE_MS) {
                it.remove();
            }
        }
        while (recentFixes.size() > FIX_BUFFER_MAX_SIZE) {
            recentFixes.pollFirst();
        }
    }

    /**
     * Among recent fused samples, the location with smallest horizontal accuracy
     * (typically better for adding a control point than the very last callback).
     */
    @Nullable
    private Location getBestRecentFix() {
        pruneFixBuffer();
        Location best = null;
        float bestAcc = Float.MAX_VALUE;
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        for (Location loc : recentFixes) {
            if (!loc.hasAccuracy()) {
                continue;
            }
            if (sampleAgeMs(loc, nowNanos) > FIX_BUFFER_MAX_AGE_MS) {
                continue;
            }
            float acc = loc.getAccuracy();
            if (acc < bestAcc) {
                bestAcc = acc;
                best = loc;
            }
        }
        return best;
    }

    private void updateGpsUi() {
        if (!btnStartGps.isEnabled()) {
            if (lastFix == null) {
                textStatus.setText(R.string.gps_waiting);
            } else {
                textStatus.setText(R.string.gps_ready_record);
            }
        }
        if (lastFix == null) {
            textCoords.setText("—");
            textAccuracy.setText("—");
            return;
        }
        textCoords.setText(getString(R.string.coords_label, lastFix.getLatitude(), lastFix.getLongitude()));
        if (lastFix.hasAccuracy()) {
            textAccuracy.setText(getString(R.string.accuracy_label, String.format(Locale.US, "%.1f", lastFix.getAccuracy())));
        } else {
            textAccuracy.setText(getString(R.string.accuracy_label, "—"));
        }
    }

    private void addControlPoint() {
        Location fix = getBestRecentFix();
        if (fix == null) {
            fix = lastFix;
        }
        if (fix == null) {
            Toast.makeText(this, R.string.gps_waiting, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!fix.hasAccuracy() || fix.getAccuracy() > MAX_ACCURACY_METERS) {
            Toast.makeText(this, R.string.accuracy_too_poor, Toast.LENGTH_LONG).show();
            return;
        }
        controlPoints.add(new LatLngPoint(fix.getLatitude(), fix.getLongitude()));
        refreshPointsLabel();
        Toast.makeText(this, getString(R.string.points_count, controlPoints.size()), Toast.LENGTH_SHORT).show();
    }

    private void refreshPointsLabel() {
        textPointsCount.setText(getString(R.string.points_count, controlPoints.size()));
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
                    String safeName = sanitizeFileName(baseName);
                    pendingSaveFileName = safeName;
                    createDocumentLauncher.launch(safeName);
                })
                .show();
    }

    private String sanitizeFileName(String baseName) {
        String safeName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safeName.toLowerCase(Locale.US).endsWith(".gpx")) {
            safeName = safeName + ".gpx";
        }
        return safeName;
    }

    private void onCustomSaveLocationPicked(Uri uri) {
        if (uri == null) {
            pendingSaveFileName = null;
            return;
        }
        String safeName = pendingSaveFileName != null ? pendingSaveFileName : "track.gpx";
        String trackName = safeName.substring(0, safeName.length() - 4);
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            GpxHelper.writeTrack(os, trackName, controlPoints);
            Toast.makeText(this, getString(R.string.gpx_saved, uri.toString()), Toast.LENGTH_LONG).show();
            stopGps();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.save_failed_with_reason, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            pendingSaveFileName = null;
        }
    }

    private void stopGps() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    protected void onDestroy() {
        stopGps();
        super.onDestroy();
    }
}
