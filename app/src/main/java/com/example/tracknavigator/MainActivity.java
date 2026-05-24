package com.example.tracknavigator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM_LOCATION = 1001;
    private MaterialButton btnRecord;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btnRecord);
        MaterialButton btnRace = findViewById(R.id.btnRace);
        MaterialButton btnExit = findViewById(R.id.btnExit);

        btnRecord.setOnClickListener(v -> {
            if (RaceTrackingService.isRaceActive()) {
                Toast.makeText(this, R.string.cannot_record_during_race, Toast.LENGTH_SHORT).show();
            } else {
                startActivity(new Intent(this, RecordTrackActivity.class));
            }
        });

        btnRace.setOnClickListener(v ->
                startActivity(new Intent(this, RaceActivity.class)));

        btnExit.setOnClickListener(v -> {
            RaceTrackingService.requestStopService(this);
            finishAffinity();
        });

        checkPermissionsAndStartGps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (btnRecord != null) {
            boolean isRaceActive = RaceTrackingService.isRaceActive();
            // We keep it enabled so the Toast can show WHY it's not working, 
            // but we could also visually dim it.
            btnRecord.setAlpha(isRaceActive ? 0.5f : 1.0f);
        }
    }

    private void checkPermissionsAndStartGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM_LOCATION);
        } else {
            startGpsService();
        }
    }

    private void startGpsService() {
        Intent prepare = new Intent(this, RaceTrackingService.class);
        prepare.setAction(RaceTrackingService.ACTION_PREPARE);
        try {
            startService(prepare);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start GPS service", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsService();
        }
    }
}
