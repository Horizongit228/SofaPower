package com.sofa.power;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "sofa_power_prefs";
    private static final String KEY_MAC = "pc_mac";
    private static final int WOL_PORT = 9;

    private EditText macInput;
    private TextView networkInfo;
    private TextView status;
    private Button powerButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        refreshNetworkInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNetworkInfo();
    }

    private View buildUi() {
        int pad = dp(24);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(42), pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("SofaPower");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Включай ПК с дивана по Wake-on-LAN ⚡");
        subtitle.setTextSize(16);
        subtitle.setPadding(0, dp(8), 0, dp(28));
        root.addView(subtitle);

        TextView macLabel = new TextView(this);
        macLabel.setText("MAC-адрес сетевой карты ПК");
        macLabel.setTextSize(14);
        root.addView(macLabel);

        macInput = new EditText(this);
        macInput.setHint("AA:BB:CC:DD:EE:FF");
        macInput.setText(prefs.getString(KEY_MAC, ""));
        macInput.setSingleLine(true);
        macInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams macParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        macParams.setMargins(0, dp(6), 0, dp(18));
        root.addView(macInput, macParams);

        networkInfo = new TextView(this);
        networkInfo.setTextSize(14);
        networkInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        networkInfo.setBackgroundColor(0xFFEFEFEF);
        root.addView(networkInfo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        powerButton = new Button(this);
        powerButton.setText("⏻  ВКЛЮЧИТЬ ПК");
        powerButton.setTextSize(20);
        powerButton.setAllCaps(false);
        powerButton.setMinHeight(dp(72));
        powerButton.setOnClickListener(v -> wakePc());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(0, dp(26), 0, dp(12));
        root.addView(powerButton, buttonParams);

        status = new TextView(this);
        status.setText("Готово к запуску");
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("Телефон должен быть подключён к обычному домашнему Wi‑Fi, а ПК — кабелем к тому же роутеру. Гостевая Wi‑Fi сеть может блокировать Wake-on-LAN.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(34), 0, 0);
        root.addView(hint);

        return scroll;
    }

    private void wakePc() {
        String mac = normalizeMac(macInput.getText().toString());
        if (mac == null) {
            macInput.setError("Нужен MAC вида AA:BB:CC:DD:EE:FF");
            return;
        }

        String broadcast = getBroadcastAddress();
        if (broadcast == null) {
            Toast.makeText(this, "Не удалось определить локальную сеть. Подключись к домашнему Wi‑Fi.", Toast.LENGTH_LONG).show();
            refreshNetworkInfo();
            return;
        }

        prefs.edit().putString(KEY_MAC, mac).apply();
        macInput.setText(mac);
        powerButton.setEnabled(false);
        status.setText("Отправляю magic packet…");

        new Thread(() -> {
            try {
                byte[] packet = buildMagicPacket(mac);
                InetAddress destination = InetAddress.getByName(broadcast);

                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    DatagramPacket datagram = new DatagramPacket(packet, packet.length, destination, WOL_PORT);
                    for (int i = 0; i < 3; i++) {
                        socket.send(datagram);
                        Thread.sleep(120);
                    }
                }

                runOnUiThread(() -> {
                    status.setText("Команда отправлена на " + broadcast + ":" + WOL_PORT + " ✓");
                    powerButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Ошибка: " + e.getMessage());
                    powerButton.setEnabled(true);
                });
            }
        }).start();
    }

    private byte[] buildMagicPacket(String mac) {
        String[] hex = mac.split(":");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            macBytes[i] = (byte) Integer.parseInt(hex[i], 16);
        }

        byte[] bytes = new byte[6 + 16 * macBytes.length];
        for (int i = 0; i < 6; i++) bytes[i] = (byte) 0xFF;
        for (int i = 6; i < bytes.length; i += macBytes.length) {
            System.arraycopy(macBytes, 0, bytes, i, macBytes.length);
        }
        return bytes;
    }

    private String normalizeMac(String raw) {
        if (raw == null) return null;
        String compact = raw.trim().replace("-", "").replace(":", "").replace(".", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[0-9A-F]{12}")) return null;

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (out.length() > 0) out.append(':');
            out.append(compact, i, i + 2);
        }
        return out.toString();
    }

    private void refreshNetworkInfo() {
        NetworkDetails details = getNetworkDetails();
        if (details == null) {
            networkInfo.setText("Сеть: не удалось определить IPv4-сеть\nПодключись к домашнему Wi‑Fi");
        } else {
            networkInfo.setText("Телефон: " + details.ip + " /" + details.prefix +
                    "\nBroadcast: " + details.broadcast + "\nUDP-порт WoL: " + WOL_PORT);
        }
    }

    private String getBroadcastAddress() {
        NetworkDetails details = getNetworkDetails();
        return details == null ? null : details.broadcast;
    }

    private NetworkDetails getNetworkDetails() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        LinkProperties props = cm.getLinkProperties(active);
        if (props == null) return null;

        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            int prefix = link.getPrefixLength();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                byte[] raw = address.getAddress();
                int ip = ((raw[0] & 0xFF) << 24)
                        | ((raw[1] & 0xFF) << 16)
                        | ((raw[2] & 0xFF) << 8)
                        | (raw[3] & 0xFF);
                int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
                int broadcast = ip | ~mask;
                String broadcastText = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        (broadcast >>> 24) & 0xFF,
                        (broadcast >>> 16) & 0xFF,
                        (broadcast >>> 8) & 0xFF,
                        broadcast & 0xFF);
                return new NetworkDetails(address.getHostAddress(), prefix, broadcastText);
            }
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class NetworkDetails {
        final String ip;
        final int prefix;
        final String broadcast;

        NetworkDetails(String ip, int prefix, String broadcast) {
            this.ip = ip;
            this.prefix = prefix;
            this.broadcast = broadcast;
        }
    }
}
