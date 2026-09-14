package com.sofa.power;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class UpdateManager {
    private static final String REPO = "Horizongit228/SofaPower";
    private static final String PREFIX = "android-v";
    private static final String STATE_PREFS = "sofapower_update_state";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_EXPECTED_DIGEST = "expected_digest";
    private static final String KEY_VERSION = "download_version";

    public interface Callback {
        void onResult(UpdateInfo info, Exception error);
    }

    public static final class UpdateInfo {
        public final String version;
        public final String downloadUrl;
        public final String digest;
        public final String releaseUrl;
        UpdateInfo(String version, String downloadUrl, String digest, String releaseUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
            this.releaseUrl = releaseUrl;
        }
    }

    private UpdateManager() {}

    public static void checkAsync(Callback callback) {
        new Thread(() -> {
            try {
                callback.onResult(findLatest(), null);
            } catch (Exception e) {
                callback.onResult(null, e);
            }
        }).start();
    }

    private static UpdateInfo findLatest() throws Exception {
        URL url = new URL("https://api.github.com/repos/" + REPO + "/releases?per_page=30");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "SofaPower-Android/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            if (connection.getResponseCode() / 100 != 2) throw new IllegalStateException("GitHub returned " + connection.getResponseCode());
            JSONArray releases = new JSONArray(readAll(connection.getInputStream()));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue;
                String tag = release.optString("tag_name", "");
                if (!tag.startsWith(PREFIX)) continue;
                String version = tag.substring(PREFIX.length());
                if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    if (!"SofaPower.apk".equalsIgnoreCase(asset.optString("name", ""))) continue;
                    String download = asset.optString("browser_download_url", "");
                    if (!download.startsWith("https://github.com/")) continue;
                    return new UpdateInfo(version, download, asset.optString("digest", ""), release.optString("html_url", ""));
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    public static void downloadAndInstall(Activity activity, UpdateInfo info) {
        if (info == null || info.downloadUrl == null || info.downloadUrl.isEmpty()) return;
        try {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            String fileName = "SofaPower-v" + info.version + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.downloadUrl));
            request.setTitle("SofaPower " + info.version);
            request.setDescription("Обновление из официального GitHub-релиза");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
            long id = manager.enqueue(request);
            activity.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_DOWNLOAD_ID, id)
                    .putString(KEY_EXPECTED_DIGEST, info.digest == null ? "" : info.digest)
                    .putString(KEY_VERSION, info.version)
                    .apply();
            Toast.makeText(activity, "Скачиваю SofaPower " + info.version + "…", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Не удалось начать загрузку: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void resumePending(Activity activity) {
        SharedPreferences state = activity.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        long id = state.getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                verifyAndInstall(activity, manager, id, state.getString(KEY_EXPECTED_DIGEST, ""));
            } else if (status == DownloadManager.STATUS_FAILED) {
                clearPending(activity);
                Toast.makeText(activity, "Загрузка обновления не удалась", Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {}
    }

    private static void verifyAndInstall(Activity activity, DownloadManager manager, long id, String expectedDigest) {
        new Thread(() -> {
            try {
                Uri uri = manager.getUriForDownloadedFile(id);
                if (uri == null) throw new IllegalStateException("Downloaded APK not found");
                if (expectedDigest != null && expectedDigest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
                    String expected = expectedDigest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
                    String actual;
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("Cannot read downloaded APK");
                        actual = sha256(in);
                    }
                    if (!expected.equals(actual)) {
                        clearPending(activity);
                        activity.runOnUiThread(() -> Toast.makeText(activity, "Проверка SHA-256 не пройдена. APK не будет установлен.", Toast.LENGTH_LONG).show());
                        return;
                    }
                }
                activity.runOnUiThread(() -> install(activity, uri));
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, "Не удалось проверить обновление: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void install(Activity activity, Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                Toast.makeText(activity, "Разреши установку обновлений для SofaPower и вернись в приложение.", Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            clearPending(activity);
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(activity, "Не удалось открыть установщик APK", Toast.LENGTH_LONG).show();
        }
    }

    public static BroadcastReceiver createDownloadReceiver(Activity activity) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                long expected = activity.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -2L);
                if (completed == expected) resumePending(activity);
            }
        };
    }

    public static IntentFilter downloadFilter() {
        return new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    }

    private static void clearPending(Context context) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        int max = Math.max(aa.length, bb.length);
        for (int i = 0; i < max; i++) {
            int av = i < aa.length ? numericPrefix(aa[i]) : 0;
            int bv = i < bb.length ? numericPrefix(bb[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int numericPrefix(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return 0; }
    }

    private static String sha256(InputStream in) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
