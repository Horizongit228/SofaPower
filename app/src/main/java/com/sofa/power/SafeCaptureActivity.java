package com.sofa.power;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * Defensive wrapper around JourneyApps CaptureActivity.
 * Some OEM camera stacks can throw during Activity startup/resume.  Keep those
 * failures inside the scanner instead of taking SofaPower down with them.
 */
public class SafeCaptureActivity extends CaptureActivity {
    private static final String TAG = "SofaPowerScanner";
    private boolean failed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
        } catch (RuntimeException | LinkageError error) {
            failGracefully(error);
        }
    }

    @Override
    protected void onResume() {
        if (failed) return;
        try {
            super.onResume();
        } catch (RuntimeException | LinkageError error) {
            failGracefully(error);
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Scanner pause failed", error);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            super.onDestroy();
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Scanner destroy failed", error);
        }
    }

    private void failGracefully(Throwable error) {
        if (failed) return;
        failed = true;
        Log.e(TAG, "QR scanner failed", error);
        try {
            Intent result = new Intent();
            result.putExtra("sofapower_scanner_error", error.getClass().getSimpleName());
            setResult(RESULT_CANCELED, result);
        } catch (Exception ignored) {
        }
        try {
            Toast.makeText(this,
                    "Камера-сканер недоступна. Открой обычную Камеру телефона и отсканируй QR из Hub.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
        try {
            finish();
        } catch (Exception ignored) {
        }
    }
}
