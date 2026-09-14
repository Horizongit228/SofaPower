package com.sofa.power;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModernActivity extends Activity {
    private static final String PREFS = "sofa_power_prefs";
    private static final String KEY_MAC = "pc_mac";
    private static final int PORT = 9;
    private static final Pattern MAC = Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}");

    private SharedPreferences prefs;
    private Button power;
    private TextView status;
    private TextView pcPill;
    private TextView networkPill;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.parseColor("#09050F"));
            w.setNavigationBarColor(Color.parseColor("#09050F"));
        }
        setContentView(buildScreen());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                "#08050F", "#170A22", "#0A0610"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.addView(scroll, match());

        LinearLayout root = column();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(30), dp(24), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = text("WAKE-ON-LAN", 12, "#BE9CFF", true);
        root.addView(eyebrow);

        TextView title = text("SofaPower", 36, "#FFFFFF", true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = wrap(); tp.setMargins(0, dp(5), 0, 0);
        root.addView(title, tp);

        TextView subtitle = text("Включай ПК одним касанием", 15, "#D8CFF0", false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = wrap(); sp.setMargins(0, dp(4), 0, dp(18));
        root.addView(subtitle, sp);

        pcPill = pill("ПК ещё не настроен");
        root.addView(pcPill, wrap());

        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(18), dp(24), dp(18), dp(20));
        hero.setBackground(glass(30));
        if (Build.VERSION.SDK_INT >= 21) hero.setElevation(dp(10));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(14), 0, 0);
        root.addView(hero, hp);

        FrameLayout orb = new FrameLayout(this);
        hero.addView(orb, new LinearLayout.LayoutParams(dp(250), dp(250)));

        View glow = new View(this);
        GradientDrawable gd = gradient(GradientDrawable.Orientation.TL_BR, "#38B553FF", "#126C24FF");
        gd.setShape(GradientDrawable.OVAL);
        glow.setBackground(gd);
        orb.addView(glow, new FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER));

        power = new Button(this);
        power.setText("⏻\nВКЛЮЧИТЬ ПК");
        power.setTextColor(Color.WHITE);
        power.setTextSize(23);
        power.setTypeface(Typeface.DEFAULT_BOLD);
        power.setAllCaps(false);
        power.setGravity(Gravity.CENTER);
        power.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable pb = gradient(GradientDrawable.Orientation.TL_BR,
                "#B34AFF", "#7028FF", "#48149E");
        pb.setShape(GradientDrawable.OVAL);
        pb.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        power.setBackground(pb);
        power.setOnClickListener(v -> wake());
        if (Build.VERSION.SDK_INT >= 21) power.setElevation(dp(8));
        orb.addView(power, new FrameLayout.LayoutParams(dp(214), dp(214), Gravity.CENTER));

        status = glassText("Готово к запуску");
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, -2);
        st.setMargins(0, dp(16), 0, 0);
        hero.addView(status, st);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.setMargins(0, dp(16), 0, 0);
        root.addView(actions, ap);

        TextView settings = action("⚙  Настройки");
        settings.setOnClickListener(v -> settings());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMargins(0, 0, dp(7), 0);
        actions.addView(settings, left);

        TextView help = action("✦  Подсказки");
        help.setOnClickListener(v -> help());
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMargins(dp(7), 0, 0, 0);
        actions.addView(help, right);

        networkPill = glassText("Проверяю сеть…");
        networkPill.setTextSize(13);
        networkPill.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(0, dp(16), 0, 0);
        root.addView(networkPill, np);

        TextView foot = text("SofaPower 1.2  •  режим диванного комфорта", 11, "#847A92", false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = wrap(); fp.setMargins(0, dp(20), 0, 0);
        root.addView(foot, fp);
        return page;
    }

    private void wake() {
        String mac = normalize(prefs.getString(KEY_MAC, ""));
        if (mac == null) {
            status.setText("Добавь MAC-адрес в настройках");
            settings();
            return;
        }
        Net n = network();
        if (n == null) {
            status.setText("Нет домашней IPv4-сети");
            Toast.makeText(this, "Подключись к домашнему Wi‑Fi", Toast.LENGTH_LONG).show();
            return;
        }
        power.setEnabled(false);
        status.setText("Отправляю magic packet…");
        new Thread(() -> {
            try {
                byte[] bytes = packet(mac);
                InetAddress to = InetAddress.getByName(n.broadcast);
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    DatagramPacket d = new DatagramPacket(bytes, bytes.length, to, PORT);
                    for (int i = 0; i < 3; i++) { socket.send(d); Thread.sleep(120); }
                }
                runOnUiThread(() -> {
                    status.setText("Команда отправлена ⚡");
                    power.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Ошибка: " + e.getMessage());
                    power.setEnabled(true);
                });
            }
        }).start();
    }

    private void settings() {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView intro = text("Здесь только необходимое для Wake-on-LAN.", 13, "#DAD2E6", false);
        box.addView(intro);

        TextView label = text("MAC-адрес Ethernet-карты ПК", 14, "#FFFFFF", true);
        LinearLayout.LayoutParams lp = wrap(); lp.setMargins(0, dp(16), 0, dp(6));
        box.addView(label, lp);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#867994"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(prefs.getString(KEY_MAC, ""));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        Button paste = new Button(this);
        paste.setText("Вставить MAC из буфера");
        paste.setAllCaps(false);
        paste.setOnClickListener(v -> {
            String clip = clipboard();
            String found = findMac(clip);
            if (found == null) Toast.makeText(this, "MAC в буфере не найден", Toast.LENGTH_SHORT).show();
            else { input.setText(found); input.setSelection(found.length()); }
        });
        box.addView(paste, new LinearLayout.LayoutParams(-1, -2));

        Net n = network();
        TextView net = glassText(n == null
                ? "Сеть не определена\nПодключись к домашнему Wi‑Fi"
                : "Телефон: " + n.ip + " /" + n.prefix + "\nBroadcast: " + n.broadcast + "\nUDP: " + PORT);
        LinearLayout.LayoutParams netp = new LinearLayout.LayoutParams(-1, -2);
        netp.setMargins(0, dp(12), 0, 0);
        box.addView(net, netp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(box)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Закрыть", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getWindow().setBackgroundDrawable(dialogBg());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String mac = findMac(input.getText().toString());
                if (mac == null) { input.setError("Нужен MAC вида AA:BB:CC:DD:EE:FF"); return; }
                prefs.edit().putString(KEY_MAC, mac).apply();
                refresh();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void help() {
        String mac = normalize(prefs.getString(KEY_MAC, ""));
        String msg = "• BIOS/UEFI: Wake on LAN — включено/доступно, ErP — выключено.\n\n"
                + "• Windows → Realtek: Wake on Magic Packet — включено.\n\n"
                + "• Разреши сетевой карте выводить ПК из сна только магическим пакетом.\n\n"
                + "• Телефон — в домашнем Wi‑Fi, ПК — Ethernet-кабелем к тому же роутеру.\n\n"
                + "• Если включён VPN на телефоне и SofaPower перестал работать, разреши LAN/local network или временно отключи VPN.\n\n"
                + "Сохранённый MAC: " + (mac == null ? "не задан" : mac);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Подсказки")
                .setMessage(msg)
                .setPositiveButton("Понятно", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getWindow().setBackgroundDrawable(dialogBg()));
        dialog.show();
    }

    private void refresh() {
        String mac = normalize(prefs.getString(KEY_MAC, ""));
        pcPill.setText(mac == null ? "ПК не настроен" : "ПК  •  " + mac);
        Net n = network();
        networkPill.setText(n == null ? "Домашняя сеть не определена" : "Домашняя сеть готова  •  " + n.ip);
    }

    private byte[] packet(String mac) {
        String[] parts = mac.split(":");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] out = new byte[102];
        for (int i = 0; i < 6; i++) out[i] = (byte) 0xFF;
        for (int i = 6; i < out.length; i += 6) System.arraycopy(m, 0, out, i, 6);
        return out;
    }

    private String clipboard() {
        ClipboardManager c = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (c == null || !c.hasPrimaryClip() || c.getPrimaryClip() == null || c.getPrimaryClip().getItemCount() == 0) return null;
        ClipData.Item item = c.getPrimaryClip().getItemAt(0);
        CharSequence t = item.coerceToText(this);
        return t == null ? null : t.toString();
    }

    private String findMac(String raw) {
        String direct = normalize(raw);
        if (direct != null) return direct;
        if (raw == null) return null;
        Matcher m = MAC.matcher(raw);
        return m.find() ? normalize(m.group()) : null;
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String x = raw.trim().replace("-", "").replace(":", "").replace(".", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!x.matches("[0-9A-F]{12}")) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) { if (b.length() > 0) b.append(':'); b.append(x, i, i + 2); }
        return b.toString();
    }

    private Net network() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        LinkProperties p = cm.getLinkProperties(active);
        if (p == null) return null;
        for (LinkAddress link : p.getLinkAddresses()) {
            InetAddress a = link.getAddress();
            int prefix = link.getPrefixLength();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                byte[] r = a.getAddress();
                int ip = ((r[0] & 255) << 24) | ((r[1] & 255) << 16) | ((r[2] & 255) << 8) | (r[3] & 255);
                int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
                int bc = ip | ~mask;
                String broadcast = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        (bc >>> 24) & 255, (bc >>> 16) & 255, (bc >>> 8) & 255, bc & 255);
                return new Net(a.getHostAddress(), prefix, broadcast);
            }
        }
        return null;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int size, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String s) {
        TextView t = text(s, 13, "#F4F0FF", false);
        t.setPadding(dp(15), dp(9), dp(15), dp(9));
        GradientDrawable d = gradient(GradientDrawable.Orientation.LEFT_RIGHT, "#2DBB62FF", "#198848FF");
        d.setCornerRadius(dp(999)); d.setStroke(dp(1), Color.parseColor("#55FFFFFF"));
        t.setBackground(d); return t;
    }

    private TextView glassText(String s) {
        TextView t = text(s, 14, "#F5F1FF", false);
        t.setPadding(dp(15), dp(13), dp(15), dp(13)); t.setBackground(glass(22));
        return t;
    }

    private TextView action(String s) {
        TextView t = glassText(s);
        t.setGravity(Gravity.CENTER); t.setClickable(true); t.setFocusable(true);
        return t;
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#24FFFFFF")); d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), Color.parseColor("#3CE4D6FF")); return d;
    }

    private GradientDrawable dialogBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#171020")); d.setCornerRadius(dp(26));
        d.setStroke(dp(1), Color.parseColor("#69478B")); return d;
    }

    private GradientDrawable gradient(GradientDrawable.Orientation o, String... colors) {
        int[] c = new int[colors.length];
        for (int i = 0; i < colors.length; i++) c[i] = Color.parseColor(colors[i]);
        return new GradientDrawable(o, c);
    }

    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(-1, -1); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private static class Net {
        final String ip; final int prefix; final String broadcast;
        Net(String ip, int prefix, String broadcast) { this.ip = ip; this.prefix = prefix; this.broadcast = broadcast; }
    }
}
