package com.biometric.poc.demo.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.biometric.poc.demo.R;

public final class BiometricSettingsNavigator {

    private static final String TAG = "BiometricSettingsNav";

    private BiometricSettingsNavigator() {}

    public static void navigate(Activity activity) {
        // 기기 제조사별 설정 Activity 목록은 res/values/arrays.xml 에서 관리
        String[] biometricSettings =
                activity.getResources().getStringArray(R.array.biometric_settings_activities);

        for (String activityName : biometricSettings) {
            try {
                Intent intent = new Intent();
                intent.setComponent(
                        new ComponentName("com.android.settings", activityName));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                Log.d(TAG, "생체인식 설정 이동 성공: " + activityName);

                Toast.makeText(
                                activity,
                                activity.getString(R.string.face_enroll_hint),
                                Toast.LENGTH_LONG)
                        .show();
                return;

            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, activityName + " 미지원: " + e.getMessage());
            }
        }

        Log.d(TAG, "직접 이동 실패 → 경로 안내 다이얼로그 표시");
        showGuideDialog(activity);
    }

    private static void showGuideDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.face_enroll_guide_title))
                .setMessage(activity.getString(R.string.face_enroll_guide_message))
                .setPositiveButton(
                        activity.getString(R.string.face_enroll_guide_confirm),
                        (dialog, which) -> {
                            activity.startActivity(
                                    new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            Log.d(TAG, "보안 설정 화면으로 폴백 이동");
                        })
                .setNegativeButton(
                        activity.getString(R.string.face_enroll_guide_cancel), null)
                .setCancelable(false)
                .show();
    }
}
