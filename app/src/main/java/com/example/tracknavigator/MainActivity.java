package com.example.tracknavigator;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialButton btnRecord = findViewById(R.id.btnRecord);
        MaterialButton btnRace = findViewById(R.id.btnRace);
        MaterialButton btnExit = findViewById(R.id.btnExit);

        btnRecord.setOnClickListener(v ->
                startActivity(new Intent(this, RecordTrackActivity.class)));

        btnRace.setOnClickListener(v ->
                startActivity(new Intent(this, RaceActivity.class)));

        btnExit.setOnClickListener(v -> finishAffinity());
    }
}
