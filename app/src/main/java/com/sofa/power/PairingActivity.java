package com.sofa.power;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Receives sofapower://pair links from the normal Android camera / QR reader.
 * This gives pairing a second path that does not depend on the embedded camera.
 */
public class PairingActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        String message;
        boolean ok = false;
        try {
            Uri uri = intent == null ? null : intent.getData();
            if (uri == null || !"sofapower".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Некорректная ссылка SofaPower");
            }

            String encoded = uri.getQueryParameter("data");
            if (encoded == null || encoded.isEmpty() || encoded.length() > 2048) {
                throw new IllegalArgumentException("Некорректные данные QR");
            }

            byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!"sofapower".equals(payload.optString("t")) || payload.optInt("v", 0) != 1) {
                throw new IllegalArgumentException("Неподдерживаемая версия QR");
            }

            String mac = WolUtils.normalizeMac(payload.optString("mac", ""));
            String ip = payload.optString("ip", "").trim();
            String name = sanitizeName(payload.optString("name", "Домашний ПК"));
            if (mac == null) throw new IllegalArgumentException("В QR нет корректного MAC");
            if (!WolUtils.isValidIpv4(ip)) ip = "";

            SecureStore store = new SecureStore(this);
            store.putString(SecureStore.KEY_MAC, mac);
            store.putString(SecureStore.KEY_IP, ip);
            store.putString(SecureStore.KEY_NAME, name);
            ok = true;
            message = "ПК добавлен: " + name;
        } catch (Exception error) {
            message = "Не удалось связать с Hub: " + safeMessage(error);
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent main = new Intent(this, ModernActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (ok) main.putExtra("sofapower_pairing_success", true);
        startActivity(main);
        finish();
    }

    private String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("[\\r\\n\\t]", " ");
        if (name.isEmpty()) name = "Домашний ПК";
        if (name.length() > 32) name = name.substring(0, 32);
        return name;
    }

    private String safeMessage(Exception error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? "неизвестная ошибка" : text;
    }
}
