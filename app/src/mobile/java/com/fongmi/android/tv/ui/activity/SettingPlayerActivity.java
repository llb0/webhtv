package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;

public class SettingPlayerActivity extends AppCompatActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPlayerActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting_player);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, SettingPlayerFragment.newInstance())
                    .commit();
        }
    }
}
