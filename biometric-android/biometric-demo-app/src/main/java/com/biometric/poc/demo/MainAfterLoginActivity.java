package com.biometric.poc.demo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.biometric.poc.lib.BiometricLibConstants;
import com.biometric.poc.lib.storage.TokenStorage;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class MainAfterLoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_after_login);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Intent intent = getIntent();
        String userId = intent.getStringExtra("user_id");
        String accessToken = intent.getStringExtra("access_token");
        int expiresIn = intent.getIntExtra("expires_in", BiometricLibConstants.TOKEN_EXPIRES_IN_DEFAULT_SEC);

        if (userId == null || accessToken == null) {
            try {
                TokenStorage ts = new TokenStorage(this);
                if (userId == null) {
                    userId = ts.getUserId();
                }
                if (accessToken == null) {
                    accessToken = ts.getAccessToken();
                }
            } catch (GeneralSecurityException | IOException ignored) {
            }
        }

        TextView textWelcome = findViewById(R.id.text_welcome);
        TextView textTokenPreview = findViewById(R.id.text_token_preview);
        TextView textExpires = findViewById(R.id.text_expires);

        String displayUserId = userId != null ? userId : "-";
        textWelcome.setText("안녕하세요, " + displayUserId + "님!");

        String tokenPreview;
        if (accessToken == null || accessToken.isEmpty()) {
            tokenPreview = "-";
        } else {
            tokenPreview =
                    accessToken.substring(0, Math.min(BiometricLibConstants.TOKEN_PREVIEW_MAX_CHARS, accessToken.length())) + "...";
        }
        textTokenPreview.setText(tokenPreview);

        int minutes = expiresIn / 60;
        textExpires.setText("만료까지: " + minutes + "분");

        Button buttonLogout = findViewById(R.id.button_logout);
        buttonLogout.setOnClickListener(
                v -> {
                    try {
                        TokenStorage tokenStorage = new TokenStorage(this);
                        tokenStorage.clearAll();
                    } catch (GeneralSecurityException | IOException e) {
                        throw new RuntimeException(e);
                    }
                    Intent login =
                            new Intent(MainAfterLoginActivity.this, LoginActivity.class);
                    startActivity(login);
                    finishAffinity();
                });
    }
}
