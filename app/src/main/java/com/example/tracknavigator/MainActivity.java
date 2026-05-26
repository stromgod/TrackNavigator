package com.example.tracknavigator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM_LOCATION = 1001;
    private MaterialButton btnRecord;

    private MaterialCardView layoutBotMain;
    private ImageView imgBotMain;
    private TextView textBotMain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        layoutBotMain = findViewById(R.id.layoutBotMain);
        imgBotMain = findViewById(R.id.imgBotMain);
        textBotMain = findViewById(R.id.textBotMain);
        initBotOnboardingIfNeeded();

        btnRecord = findViewById(R.id.btnRecord);
        MaterialButton btnRace = findViewById(R.id.btnRace);
        MaterialButton btnExit = findViewById(R.id.btnExit);

        btnRecord.setOnClickListener(v -> {
            if (RaceTrackingService.isRaceActive()) {
                Toast.makeText(this, R.string.cannot_record_during_race, Toast.LENGTH_SHORT).show();
            } else {
                if (!BotPrefs.isOnboardingDone(this)) {
                    BotPrefs.setStep(this, BotPrefs.Step.GUIDE_RECORD_IN_RECORD);
                }
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
        refreshBotUi();
    }

    private void updateButtonStates() {
        if (btnRecord != null) {
            boolean isRaceActive = RaceTrackingService.isRaceActive();
            // We keep it enabled so the Toast can show WHY it's not working, 
            // but we could also visually dim it.
            btnRecord.setAlpha(isRaceActive ? 0.5f : 1.0f);
        }
    }

    private void initBotOnboardingIfNeeded() {
        if (layoutBotMain == null) return;

        String name = BotPrefs.getName(this);
        BotPrefs.Step step = BotPrefs.getStep(this);

        if (name == null || name.trim().isEmpty() || step == BotPrefs.Step.ASK_NAME) {
            showNameDialog();
            return;
        }

        BotAssets.setBotIcon(this, imgBotMain, "Greetings.png");
        refreshBotUi();
    }

    private void showNameDialog() {
        if (layoutBotMain == null || imgBotMain == null || textBotMain == null) return;

        BotAssets.setBotIcon(this, imgBotMain, "Greetings.png");
        textBotMain.setText(getString(R.string.bot_intro_message));
        layoutBotMain.setVisibility(View.VISIBLE);

        EditText input = new EditText(this);
        input.setHint(getString(R.string.bot_name_dialog_hint));

        new AlertDialog.Builder(this)
                .setTitle(R.string.bot_name_dialog_title)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String typed = input.getText().toString().trim();
                    if (typed.isEmpty()) typed = "User";
                    BotPrefs.setName(this, typed);
                    BotPrefs.setStep(this, BotPrefs.Step.GUIDE_RECORD);
                    refreshBotUi();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    BotPrefs.setStep(this, BotPrefs.Step.ASK_NAME);
                    refreshBotUi();
                })
                .show();
    }

    private void refreshBotUi() {
        if (layoutBotMain == null || imgBotMain == null || textBotMain == null) return;

        if (BotPrefs.isOnboardingDone(this)) {
            layoutBotMain.setVisibility(View.GONE);
            return;
        }

        String name = BotPrefs.getName(this);
        BotPrefs.Step step = BotPrefs.getStep(this);

        if (name == null || name.trim().isEmpty() || step == BotPrefs.Step.ASK_NAME) {
            BotAssets.setBotIcon(this, imgBotMain, "Greetings.png");
            textBotMain.setText(getString(R.string.bot_intro_message));
            layoutBotMain.setVisibility(View.VISIBLE);
            return;
        }

        BotAssets.setBotIcon(this, imgBotMain, "Greetings.png");

        if (step == BotPrefs.Step.GUIDE_RACE) {
            textBotMain.setText(getString(R.string.bot_guide_race_setup));
        } else if (step == BotPrefs.Step.DONE) {
            layoutBotMain.setVisibility(View.GONE);
            return;
        } else {
            // Основное руководство с главного экрана: переход к записи трека.
            textBotMain.setText(getString(R.string.bot_guide_press_record, name));
        }

        layoutBotMain.setVisibility(View.VISIBLE);
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
