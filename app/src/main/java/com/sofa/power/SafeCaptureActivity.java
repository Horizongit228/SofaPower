package com.sofa.power;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

/**
 * Privacy-friendly QR bridge used by IntentIntegrator.
 *
 * SofaPower itself never receives camera frames. Google Code Scanner owns the
 * camera UI and returns only the decoded QR string. If the user already copied
 * the Hub QR text with the phone's native camera, this activity can consume it
 * directly from the clipboard instead.
 */
public class SafeCaptureActivity extends Activity {
    private static final String EXTRA_RESULT = "SCAN_RESULT";
    private static final String EXTRA_FORMAT = "SCAN_RESULT_FORMAT";
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String copied = pairingCodeFromClipboard();
        if (copied != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Код SofaPower Hub найден")
                    .setMessage("В буфере обмена уже есть код привязки. Использовать его без камеры?")
                    .setPositiveButton("Использовать", (d, w) -> finishSuccess(copied))
                    .setNegativeButton("Сканировать", (d, w) -> launchScanner())
                    .setOnCancelListener(d -> finishCanceled())
                    .show();
        } else {
            launchScanner();
        }
    }

    private void launchScanner() {
        if (started) return;
        started = true;

        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();

        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String value = barcode.getRawValue();
                    if (isPairingCode(value)) {
                        finishSuccess(value);
                    } else {
                        Toast.makeText(this, "Это не QR-код SofaPower Hub", Toast.LENGTH_LONG).show();
                        started = false;
                        launchScanner();
                    }
                })
                .addOnCanceledListener(this::finishCanceled)
                .addOnFailureListener(error -> {
                    String copied = pairingCodeFromClipboard();
                    if (copied != null) {
                        finishSuccess(copied);
                        return;
                    }
                    Toast.makeText(this,
                            "Сканер недоступен. Отсканируй QR обычной Камерой, нажми «Копировать», затем снова «Связать с Hub».",
                            Toast.LENGTH_LONG).show();
                    finishCanceled();
                });
    }

    private void finishSuccess(String raw) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT, raw);
        data.putExtra(EXTRA_FORMAT, "QR_CODE");
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishCanceled() {
        setResult(RESULT_CANCELED, new Intent());
        finish();
    }

    private String pairingCodeFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            String raw = value == null ? null : value.toString().trim();
            return isPairingCode(raw) ? raw : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isPairingCode(String value) {
        if (value == null || value.length() > 4096) return false;
        return value.startsWith("sofapower://pair?data=");
    }
}
