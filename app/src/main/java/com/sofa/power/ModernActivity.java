package com.sofa.power;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModernActivity extends Activity {
    private static final Pattern MAC_PATTERN = Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}");
    private static final long STATUS_PERIOD_MS = 20_000L;

    private SecureStore store;
    private Button power;
    private View glow;
    private TextView actionStatus;
    private TextView pcPill;
    private TextView pcStatus;
    private TextView networkPill;
    private TextView updateChip;
    private UpdateManager.UpdateInfo availableUpdate;
    private BroadcastReceiver downloadReceiver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean statusCheckRunning = new AtomicBoolean(false);

    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            checkPcStatus();
            handler.postDelayed(this, STATUS_PERIOD_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SecureStore(this);
        configureWindow();
        setContentView(buildScreen());
        registerDownloadReceiver();
        refreshUi();
        handler.postDelayed(() -> checkUpdates(false), 900);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        handler.removeCallbacks(statusLoop);
        handler.post(statusLoop);
        UpdateManager.resumePending(this);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void configureWindow() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(Color.parseColor("#09050F"));
            w.setNavigationBarColor(Color.parseColor("#09050F"));
        }
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM, "#07040D", "#160921", "#09050F"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = column();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titles = column();
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        titles.addView(text("WAKE-ON-LAN", 11, "#B996FF", true));
        TextView title = text("SofaPower", 34, "#FFFFFF", true);
        LinearLayout.LayoutParams titleParams = wrap(); titleParams.setMargins(0, dp(3), 0, 0);
        titles.addView(title, titleParams);
        titles.addView(text("Домашний запуск ПК одним касанием", 13, "#B8AEC8", false));

        updateChip = pill("v" + BuildConfig.VERSION_NAME);
        updateChip.setGravity(Gravity.CENTER);
        updateChip.setClickable(true);
        updateChip.setFocusable(true);
        updateChip.setOnClickListener(v -> updateClicked());
        top.addView(updateChip, new LinearLayout.LayoutParams(-2, -2));

        pcPill = pill("ПК не настроен");
        LinearLayout.LayoutParams pp = wrap(); pp.setMargins(0, dp(18), 0, 0);
        root.addView(pcPill, pp);

        pcStatus = text("○ Статус неизвестен", 13, "#A89CB8", true);
        pcStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ps = wrap(); ps.setMargins(0, dp(9), 0, dp(12));
        root.addView(pcStatus, ps);

        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(18), dp(22), dp(18), dp(20));
        hero.setBackground(glass(30));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) hero.setElevation(dp(8));
        root.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout orb = new FrameLayout(this);
        hero.addView(orb, new LinearLayout.LayoutParams(dp(250), dp(250)));

        glow = new View(this);
        GradientDrawable glowDrawable = gradient(GradientDrawable.Orientation.TL_BR, "#45B64FFF", "#177027FF");
        glowDrawable.setShape(GradientDrawable.OVAL);
        glow.setBackground(glowDrawable);
        orb.addView(glow, new FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER));

        power = new Button(this);
        power.setText("⏻\nВКЛЮЧИТЬ ПК");
        power.setTextColor(Color.WHITE);
        power.setTextSize(23);
        power.setTypeface(Typeface.DEFAULT_BOLD);
        power.setAllCaps(false);
        power.setGravity(Gravity.CENTER);
        power.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable pb = gradient(GradientDrawable.Orientation.TL_BR, "#BC54FF", "#702CFF", "#421292");
        pb.setShape(GradientDrawable.OVAL);
        pb.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        power.setBackground(pb);
        power.setOnClickListener(v -> wake());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) power.setElevation(dp(8));
        orb.addView(power, new FrameLayout.LayoutParams(dp(214), dp(214), Gravity.CENTER));

        actionStatus = glassText("Готово к запуску");
        actionStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams as = new LinearLayout.LayoutParams(-1, -2); as.setMargins(0, dp(16), 0, 0);
        hero.addView(actionStatus, as);

        LinearLayout row1 = actionsRow();
        root.addView(row1, actionsParams());
        addAction(row1, "▣  Связать с Hub", v -> startQrPairing(), true);
        addAction(row1, "⚙  Настройки", v -> showSettings(), false);

        LinearLayout row2 = actionsRow();
        root.addView(row2, actionsParams());
        addAction(row2, "✦  Подсказки", v -> showHelp(), true);
        addAction(row2, "ⓘ  О приложении", v -> showAbout(), false);

        networkPill = glassText("Проверяю домашнюю сеть…");
        networkPill.setTextSize(12);
        networkPill.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2); np.setMargins(0, dp(15), 0, 0);
        root.addView(networkPill, np);

        TextView privacy = text("Локально • без аккаунта • без аналитики", 11, "#786F86", false);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pv = wrap(); pv.setMargins(0, dp(16), 0, 0);
        root.addView(privacy, pv);
        return page;
    }

    private void wake() {
        String mac = WolUtils.normalizeMac(store.getString(SecureStore.KEY_MAC, ""));
        if (mac == null) {
            actionStatus.setText("Сначала свяжи SofaPower с ПК");
            showSettings();
            return;
        }
        if (WolUtils.network(this) == null) {
            actionStatus.setText("Домашняя сеть не найдена");
            Toast.makeText(this, "Подключись к домашнему Wi‑Fi", Toast.LENGTH_LONG).show();
            return;
        }
        animatePowerPress();
        power.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
        power.setEnabled(false);
        actionStatus.setText("Отправляю magic packet…");

        new Thread(() -> {
            try {
                WolUtils.send(this, mac);
                runOnUiThread(() -> {
                    actionStatus.setText("Команда отправлена ⚡");
                    power.setEnabled(true);
                    pulseGlow();
                    SofaPowerWidgetProvider.updateAll(this, "Команда отправлена ⚡");
                    handler.postDelayed(this::checkPcStatus, 2200);
                    handler.postDelayed(this::checkPcStatus, 6000);
                    handler.postDelayed(this::checkPcStatus, 11_000);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    actionStatus.setText("Не удалось отправить пакет");
                    power.setEnabled(true);
                    Toast.makeText(this, "Проверь домашний Wi‑Fi и VPN", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void animatePowerPress() {
        power.animate().cancel();
        power.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                .withEndAction(() -> power.animate().scaleX(1f).scaleY(1f).setDuration(150).start()).start();
    }

    private void pulseGlow() {
        glow.animate().cancel();
        glow.setAlpha(1f); glow.setScaleX(1f); glow.setScaleY(1f);
        glow.animate().alpha(0.55f).scaleX(1.08f).scaleY(1.08f).setDuration(350)
                .withEndAction(() -> glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).start()).start();
    }

    private void startQrPairing() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES);
        integrator.setPrompt("Отсканируй QR-код из SofaPower Hub");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.setBarcodeImageEnabled(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) applyPairing(result.getContents());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void applyPairing(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            if (!"sofapower".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost())) throw new IllegalArgumentException("Это не QR SofaPower Hub");
            String encoded = uri.getQueryParameter("data");
            if (encoded == null || encoded.length() > 2048) throw new IllegalArgumentException("Некорректный QR");
            byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject payload = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!"sofapower".equals(payload.optString("t")) || payload.optInt("v", 0) != 1) throw new IllegalArgumentException("Неподдерживаемая версия QR");
            String mac = WolUtils.normalizeMac(payload.optString("mac", ""));
            String ip = payload.optString("ip", "");
            String name = sanitizeName(payload.optString("name", "Домашний ПК"));
            if (mac == null) throw new IllegalArgumentException("В QR нет корректного MAC");
            if (!WolUtils.isValidIpv4(ip)) ip = "";
            store.putString(SecureStore.KEY_MAC, mac);
            store.putString(SecureStore.KEY_IP, ip);
            store.putString(SecureStore.KEY_NAME, name);
            refreshUi();
            checkPcStatus();
            actionStatus.setText("Hub подключён • настройки сохранены защищённо");
            Toast.makeText(this, "ПК добавлен: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось прочитать QR: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showSettings() {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(text("Данные хранятся только на этом телефоне и шифруются ключом Android Keystore.", 12, "#BEB1CB", false));

        EditText nameInput = darkInput("Имя ПК", store.getString(SecureStore.KEY_NAME, "Домашний ПК"));
        EditText macInput = darkInput("MAC  •  AA:BB:CC:DD:EE:FF", store.getString(SecureStore.KEY_MAC, ""));
        EditText ipInput = darkInput("Локальный IPv4  •  192.168.1.100", store.getString(SecureStore.KEY_IP, ""));
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        addField(box, "Имя", nameInput);
        addField(box, "MAC Ethernet", macInput);
        addField(box, "IPv4 для проверки статуса", ipInput);

        LinearLayout quick = actionsRow();
        Button paste = darkButton("Вставить MAC");
        paste.setOnClickListener(v -> {
            String found = findMac(clipboardText());
            if (found == null) Toast.makeText(this, "MAC в буфере не найден", Toast.LENGTH_SHORT).show();
            else { macInput.setText(found); macInput.setSelection(found.length()); }
        });
        quick.addView(paste, weighted(true));
        Button qr = darkButton("Сканировать QR");
        qr.setOnClickListener(v -> startQrPairing());
        quick.addView(qr, weighted(false));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2); qp.setMargins(0, dp(12), 0, 0);
        box.addView(quick, qp);

        WolUtils.NetworkDetails n = WolUtils.network(this);
        TextView net = glassText(n == null ? "Домашняя сеть сейчас не определена" : "Телефон: " + n.ip + " /" + n.prefix + "\nBroadcast: " + n.broadcast + " • UDP " + WolUtils.PORT);
        LinearLayout.LayoutParams netp = new LinearLayout.LayoutParams(-1, -2); netp.setMargins(0, dp(14), 0, 0);
        box.addView(net, netp);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Настройки SofaPower").setView(box).setPositiveButton("Сохранить", null).setNegativeButton("Закрыть", null).create();
        dialog.setOnShowListener(x -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogBg());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String mac = findMac(macInput.getText().toString());
                String ip = ipInput.getText().toString().trim();
                if (mac == null) { macInput.setError("Нужен корректный Ethernet MAC"); return; }
                if (!ip.isEmpty() && !WolUtils.isValidIpv4(ip)) { ipInput.setError("Некорректный IPv4"); return; }
                store.putString(SecureStore.KEY_MAC, mac);
                store.putString(SecureStore.KEY_IP, ip);
                store.putString(SecureStore.KEY_NAME, sanitizeName(nameInput.getText().toString()));
                refreshUi();
                checkPcStatus();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showHelp() {
        String msg = "1. Самый быстрый способ — открыть SofaPower Hub на ПК и нажать «Показать QR для телефона».\n\n"
                + "2. BIOS/UEFI: Wake on LAN / PCI-E — Enabled, ErP / EuP — Disabled.\n\n"
                + "3. Hub проверит Windows, Wake on Magic Packet и Fast Startup.\n\n"
                + "4. Телефон должен быть в домашнем Wi‑Fi. Если VPN перехватывает LAN — добавь SofaPower в исключения.\n\n"
                + "5. Виджет можно добавить на рабочий стол Android и включать ПК без открытия приложения.";
        showDarkMessage("Подсказки", msg);
    }

    private void showAbout() {
        String msg = "SofaPower " + BuildConfig.VERSION_NAME + "\n\n"
                + "Конфиденциальность:\n"
                + "• MAC, имя и локальный IP шифруются Android Keystore;\n"
                + "• приложение не использует аккаунты, аналитику и рекламные SDK;\n"
                + "• данные ПК не отправляются на GitHub — туда уходит только обычный запрос проверки версии;\n"
                + "• QR Hub передаётся напрямую с экрана ПК на камеру телефона и никуда не загружается;\n"
                + "• обновления принимаются только из релизов Horizongit228/SofaPower и проверяются по SHA‑256, когда GitHub публикует digest.";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("О SofaPower").setMessage(msg)
                .setPositiveButton("Проверить обновления", (d, w) -> checkUpdates(true)).setNegativeButton("Закрыть", null).create();
        dialog.setOnShowListener(x -> { if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogBg()); });
        dialog.show();
    }

    private void updateClicked() {
        if (availableUpdate == null) {
            updateChip.setText("Проверяю…");
            checkUpdates(true);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Доступно обновление " + availableUpdate.version)
                .setMessage("APK будет загружен только из официального GitHub-релиза SofaPower и проверен перед установкой.")
                .setPositiveButton("Скачать", (d, w) -> UpdateManager.downloadAndInstall(this, availableUpdate))
                .setNegativeButton("Позже", null).show();
    }

    private void checkUpdates(boolean announce) {
        UpdateManager.checkAsync((info, error) -> runOnUiThread(() -> {
            if (error != null) {
                updateChip.setText("v" + BuildConfig.VERSION_NAME);
                if (announce) Toast.makeText(this, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show();
                return;
            }
            availableUpdate = info;
            if (info != null) {
                updateChip.setText("↑ " + info.version);
                GradientDrawable d = gradient(GradientDrawable.Orientation.LEFT_RIGHT, "#5A2CAB", "#873EFF");
                d.setCornerRadius(dp(999)); d.setStroke(dp(1), Color.parseColor("#66FFFFFF"));
                updateChip.setBackground(d);
                if (announce) Toast.makeText(this, "Доступна версия " + info.version, Toast.LENGTH_SHORT).show();
            } else {
                updateChip.setText("v" + BuildConfig.VERSION_NAME + " ✓");
                if (announce) Toast.makeText(this, "Установлена актуальная версия", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void refreshUi() {
        String mac = WolUtils.normalizeMac(store.getString(SecureStore.KEY_MAC, ""));
        String name = sanitizeName(store.getString(SecureStore.KEY_NAME, "Домашний ПК"));
        pcPill.setText(mac == null ? "ПК не настроен" : name + "  •  " + WolUtils.maskMac(mac));
        WolUtils.NetworkDetails n = WolUtils.network(this);
        networkPill.setText(n == null ? "Домашняя сеть не определена" : "Домашняя сеть готова  •  " + n.ip + "  →  " + n.broadcast);
        if (mac == null) {
            pcStatus.setText("○ Свяжи приложение с SofaPower Hub");
            pcStatus.setTextColor(Color.parseColor("#A89CB8"));
        } else if (store.getString(SecureStore.KEY_IP, "").isEmpty()) {
            pcStatus.setText("○ Статус появится после QR-связки с Hub");
            pcStatus.setTextColor(Color.parseColor("#A89CB8"));
        }
    }

    private void checkPcStatus() {
        String ip = store.getString(SecureStore.KEY_IP, "");
        if (!WolUtils.isValidIpv4(ip) || !statusCheckRunning.compareAndSet(false, true)) return;
        pcStatus.setText("◌ Проверяю ПК…");
        pcStatus.setTextColor(Color.parseColor("#C6B6DA"));
        new Thread(() -> {
            boolean online = WolUtils.isOnline(ip);
            statusCheckRunning.set(false);
            runOnUiThread(() -> {
                pcStatus.setText(online ? "● ПК онлайн" : "○ ПК выключен или недоступен");
                pcStatus.setTextColor(Color.parseColor(online ? "#79E5A6" : "#9D91AB"));
            });
        }).start();
    }

    private void registerDownloadReceiver() {
        downloadReceiver = UpdateManager.createDownloadReceiver(this);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, UpdateManager.downloadFilter(), Context.RECEIVER_EXPORTED);
            else registerReceiver(downloadReceiver, UpdateManager.downloadFilter());
        } catch (Exception ignored) {}
    }

    private String findMac(String raw) {
        String direct = WolUtils.normalizeMac(raw);
        if (direct != null) return direct;
        if (raw == null) return null;
        Matcher m = MAC_PATTERN.matcher(raw);
        return m.find() ? WolUtils.normalizeMac(m.group()) : null;
    }

    private String clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null || clipboard.getPrimaryClip().getItemCount() == 0) return null;
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        CharSequence text = item.coerceToText(this);
        return text == null ? null : text.toString();
    }

    private String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("[\\r\\n\\t]", " ");
        if (name.isEmpty()) name = "Домашний ПК";
        if (name.length() > 32) name = name.substring(0, 32);
        return name;
    }

    private void showDarkMessage(String title, String msg) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Понятно", null).create();
        dialog.setOnShowListener(x -> { if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogBg()); });
        dialog.show();
    }

    private EditText darkInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#81748E"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        return input;
    }

    private void addField(LinearLayout box, String label, EditText input) {
        TextView l = text(label, 13, "#F4EFFF", true);
        LinearLayout.LayoutParams lp = wrap(); lp.setMargins(0, dp(14), 0, dp(2));
        box.addView(l, lp);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout actionsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams actionsParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(14), 0, 0);
        return p;
    }

    private void addAction(LinearLayout row, String label, View.OnClickListener listener, boolean left) {
        TextView v = glassText(label);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(listener);
        row.addView(v, weighted(left));
    }

    private LinearLayout.LayoutParams weighted(boolean left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        if (left) p.setMargins(0, 0, dp(7), 0); else p.setMargins(dp(7), 0, 0, 0);
        return p;
    }

    private Button darkButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(glass(16));
        return b;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String value) {
        TextView t = text(value, 12, "#F4F0FF", true);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable d = gradient(GradientDrawable.Orientation.LEFT_RIGHT, "#322044", "#25152F");
        d.setCornerRadius(dp(999)); d.setStroke(dp(1), Color.parseColor("#63477C"));
        t.setBackground(d);
        return t;
    }

    private TextView glassText(String value) {
        TextView t = text(value, 13, "#F5F1FF", false);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        t.setBackground(glass(20));
        return t;
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#20FFFFFF"));
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), Color.parseColor("#38D8C9FF"));
        return d;
    }

    private GradientDrawable dialogBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#171020"));
        d.setCornerRadius(dp(25));
        d.setStroke(dp(1), Color.parseColor("#684789"));
        return d;
    }

    private GradientDrawable gradient(GradientDrawable.Orientation orientation, String... colors) {
        int[] values = new int[colors.length];
        for (int i = 0; i < colors.length; i++) values[i] = Color.parseColor(colors[i]);
        return new GradientDrawable(orientation, values);
    }

    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
