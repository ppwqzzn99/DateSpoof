package com.example.datespoof;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public class SettingsActivity extends AppCompatActivity {

    // ä¸»è·¯å¾ï¼/data/local/tmp/ â Android ææè¿ç¨å¯è¯»åï¼Scoped Storage ç®¡ä¸å°
    private static final String CONFIG_DIR_PRIMARY = "/data/local/tmp/DateSpoof";
    private static final String CONFIG_PATH_PRIMARY = "/data/local/tmp/DateSpoof/config.json";

    // è¾å©è·¯å¾ï¼/sdcard/ â æ¹ä¾¿ç¨æ·ç¨æä»¶ç®¡çå¨æ¥ç
    private static final String CONFIG_DIR_SDCARD  = "/sdcard/DateSpoof";
    private static final String CONFIG_PATH_SDCARD  = "/sdcard/DateSpoof/config.json";

    private static final int REQ_STORAGE = 1001;

    private Switch swEnabled;
    private EditText etYear, etMonth, etDay;
    private TextView tvResult;
    private Button btnSave, btnVerify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swEnabled = findViewById(R.id.sw_enabled);
        etYear   = findViewById(R.id.et_year);
        etMonth  = findViewById(R.id.et_month);
        etDay    = findViewById(R.id.et_day);
        tvResult = findViewById(R.id.tv_result);
        btnSave  = findViewById(R.id.btn_save);
        btnVerify = findViewById(R.id.btn_verify);

        requestStoragePerms();
        ensureConfigDirs();
        loadConfig();

        btnSave.setOnClickListener(v -> saveConfig());
        btnVerify.setOnClickListener(v -> verifyConfig());

        verifyConfig();
    }

    private void requestStoragePerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "è¯·æäºãæææä»¶ç®¡çæéãåä½¿ç¨", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())
                    ));
                } catch (Exception ignored) {}
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
            }
        }
    }

    private void ensureConfigDir(File dir) {
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) {
                dir.setExecutable(true, false);
            }
        }
    }

    private void ensureConfigDirs() {
        ensureConfigDir(new File(CONFIG_DIR_PRIMARY));
        ensureConfigDir(new File(CONFIG_DIR_SDCARD));
    }

    private void loadConfig() {
        // ä¼åä»ä¸»è·¯å¾è¯»
        File file = new File(CONFIG_PATH_PRIMARY);
        if (!file.exists()) {
            file = new File(CONFIG_PATH_SDCARD);
        }
        if (!file.exists()) {
            swEnabled.setChecked(true);
            etYear.setText("2025");
            etMonth.setText("1");
            etDay.setText("1");
            return;
        }
        try {
            JSONObject json = new JSONObject(readFileContent(file));
            swEnabled.setChecked(json.optBoolean("enabled", true));
            etYear.setText( String.valueOf(json.optInt("year", 2025)));
            etMonth.setText(String.valueOf(json.optInt("month", 1)));
            etDay.setText(  String.valueOf(json.optInt("day", 1)));
        } catch (Exception e) {
            Toast.makeText(this, "è¯»åéç½®å¤±è´¥: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveConfig() {
        String yearStr  = etYear.getText().toString().trim();
        String monthStr = etMonth.getText().toString().trim();
        String dayStr   = etDay.getText().toString().trim();

        if (yearStr.isEmpty() || monthStr.isEmpty() || dayStr.isEmpty()) {
            Toast.makeText(this, "å¹´/æ/æ¥ä¸è½ä¸ºç©º", Toast.LENGTH_SHORT).show();
            return;
        }

        int year, month, day;
        try {
            year  = Integer.parseInt(yearStr);
            month = Integer.parseInt(monthStr);
            day   = Integer.parseInt(dayStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "è¯·è¾å¥æææ°å­", Toast.LENGTH_SHORT).show();
            return;
        }

        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            Toast.makeText(this, "æ¥æèå´ä¸åæ³", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean enabled = swEnabled.isChecked();

        try {
            ensureConfigDirs();

            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("year", year);
            json.put("month", month);
            json.put("day", day);
            String jsonStr = json.toString(2);

            // ====== åå¥ä¸»è·¯å¾ ======
            OutputStreamWriter writer1 = new OutputStreamWriter(
                    new FileOutputStream(CONFIG_PATH_PRIMARY), StandardCharsets.UTF_8);
            writer1.write(jsonStr);
            writer1.close();
            fixPermissions(CONFIG_PATH_PRIMARY);

            // ====== åå¥ /sdcard/ å¯æ¬ ======
            OutputStreamWriter writer2 = new OutputStreamWriter(
                    new FileOutputStream(CONFIG_PATH_SDCARD), StandardCharsets.UTF_8);
            writer2.write(jsonStr);
            writer2.close();
            fixPermissions(CONFIG_PATH_SDCARD);

            // ç¡®è®¤åå¥
            Calendar targetCal = Calendar.getInstance();
            targetCal.set(year, month - 1, day, 0, 0, 0);
            targetCal.set(Calendar.MILLISECOND, 0);
            long offsetDays = (targetCal.getTimeInMillis() - System.currentTimeMillis()) / 86400000;

            String msg = "â éç½®å·²ä¿å­!\n\n"
                    + "ç®æ : " + year + "å¹´" + month + "æ" + day + "æ¥\n"
                    + "åç§»: " + offsetDays + " å¤©\n"
                    + "ç¶æ: " + (enabled ? "å·²å¯ç¨" : "å·²ç¦ç¨") + "\n\n"
                    + "ä¸»è·¯å¾: " + CONFIG_PATH_PRIMARY + "\n"
                    + "å¯è·¯å¾: " + CONFIG_PATH_SDCARD + "\n\n"
                    + "â  è¯·å¨ LSPosed ä¸­éæ°å¾éç®æ åºç¨å¹¶éå¯ï¼";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "åå¥å¤±è´¥: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        verifyConfig();
    }

    /**
     * ä¿®å¤æä»¶æéä½¿å¶å¨å±å¯è¯»ï¼
     * 1. Java setReadable(true, false)
     * 2. Runtime chmod 666 (ååº)
     */
    private void fixPermissions(String path) {
        try {
            File f = new File(path);
            f.setReadable(true, false);
            f.setWritable(true, true);  // ä» owner å¯å
        } catch (Exception ignored) {}
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "666", path});
        } catch (Exception ignored) {}
    }

    private void verifyConfig() {
        StringBuilder sb = new StringBuilder();

        // ä¸»è·¯å¾éªè¯
        File filePrimary = new File(CONFIG_PATH_PRIMARY);
        sb.append("ââ ä¸»è·¯å¾ ââ\n");
        sb.append(CONFIG_PATH_PRIMARY).append("\n");
        sb.append("å­å¨: ").append(filePrimary.exists() ? "â" : "â").append("\n");
        sb.append("å¤§å°: ").append(filePrimary.exists() ? filePrimary.length() + " å­è" : "â").append("\n");
        sb.append("å¯è¯»: ").append(filePrimary.canRead() ? "â" : "â").append("\n\n");

        // å¯è·¯å¾éªè¯
        File fileSdcard = new File(CONFIG_PATH_SDCARD);
        sb.append("ââ å¯è·¯å¾ ââ\n");
        sb.append(CONFIG_PATH_SDCARD).append("\n");
        sb.append("å­å¨: ").append(fileSdcard.exists() ? "â" : "â").append("\n");
        sb.append("å¤§å°: ").append(fileSdcard.exists() ? fileSdcard.length() + " å­è" : "â").append("\n");
        sb.append("å¯è¯»: ").append(fileSdcard.canRead() ? "â" : "â").append("\n\n");

        // æä»¶åå®¹
        File readFrom = filePrimary.exists() ? filePrimary : (fileSdcard.exists() ? fileSdcard : null);
        if (readFrom == null) {
            sb.append("ââ æä»¶åå®¹ ââ\n");
            sb.append("(éç½®æä»¶ä¸å­å¨ â è¯·åç¹å»ãä¿å­è®¾ç½®ã)\n");
            sb.append("(é¦æ¬¡ä½¿ç¨éææå­å¨æé)");
        } else {
            try {
                String content = readFileContent(readFrom);
                JSONObject json = new JSONObject(content);

                sb.append("ââ æä»¶åå®¹ ââ\n");
                sb.append(content).append("\n\n");

                sb.append("ââ è§£æç»æ ââ\n");
                sb.append("enabled = ").append(json.optBoolean("enabled")).append("\n");
                sb.append("year    = ").append(json.optInt("year")).append("\n");
                sb.append("month   = ").append(json.optInt("month")).append("\n");
                sb.append("day     = ").append(json.optInt("day")).append("\n");

                Calendar c = Calendar.getInstance();
                c.set(json.optInt("year"), json.optInt("month") - 1, json.optInt("day"), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                long d = (c.getTimeInMillis() - System.currentTimeMillis()) / 86400000;
                sb.append("åç§»    = ").append(d).append(" å¤©");

            } catch (Exception e) {
                sb.append("ââ è¯»åå¼å¸¸ ââ\n");
                sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            }
        }

        tvResult.setText(sb.toString());
    }

    private String readFileContent(File file) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        return sb.toString().trim();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            Toast.makeText(this, granted ? "å­å¨æéå·²ææ" : "å­å¨æéè¢«æç»ï¼å¯è½æ æ³ä¿å­éç½®",
                Toast.LENGTH_SHORT).show();
        }
    }
}
