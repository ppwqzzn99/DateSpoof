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

    // 主路径：/data/local/tmp/ — Android 所有进程可读写，Scoped Storage 管不到
    private static final String CONFIG_DIR_PRIMARY = "/data/local/tmp/DateSpoof";
    private static final String CONFIG_PATH_PRIMARY = "/data/local/tmp/DateSpoof/config.json";

    // 辅助路径：/sdcard/ — 方便用户用文件管理器查看
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
                Toast.makeText(this, "请授予「所有文件管理权限」后使用", Toast.LENGTH_LONG).show();
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
        // 优先从主路径读
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
            Toast.makeText(this, "读取配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveConfig() {
        String yearStr  = etYear.getText().toString().trim();
        String monthStr = etMonth.getText().toString().trim();
        String dayStr   = etDay.getText().toString().trim();

        if (yearStr.isEmpty() || monthStr.isEmpty() || dayStr.isEmpty()) {
            Toast.makeText(this, "年/月/日不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        int year, month, day;
        try {
            year  = Integer.parseInt(yearStr);
            month = Integer.parseInt(monthStr);
            day   = Integer.parseInt(dayStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
            return;
        }

        if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            Toast.makeText(this, "日期范围不合法", Toast.LENGTH_SHORT).show();
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

            // ====== 写入主路径 ======
            OutputStreamWriter writer1 = new OutputStreamWriter(
                    new FileOutputStream(CONFIG_PATH_PRIMARY), StandardCharsets.UTF_8);
            writer1.write(jsonStr);
            writer1.close();
            fixPermissions(CONFIG_PATH_PRIMARY);

            // ====== 写入 /sdcard/ 副本 ======
            OutputStreamWriter writer2 = new OutputStreamWriter(
                    new FileOutputStream(CONFIG_PATH_SDCARD), StandardCharsets.UTF_8);
            writer2.write(jsonStr);
            writer2.close();
            fixPermissions(CONFIG_PATH_SDCARD);

            // 确认写入
            Calendar targetCal = Calendar.getInstance();
            targetCal.set(year, month - 1, day, 0, 0, 0);
            targetCal.set(Calendar.MILLISECOND, 0);
            long offsetDays = (targetCal.getTimeInMillis() - System.currentTimeMillis()) / 86400000;

            String msg = "✓ 配置已保存!\n\n"
                    + "目标: " + year + "年" + month + "月" + day + "日\n"
                    + "偏移: " + offsetDays + " 天\n"
                    + "状态: " + (enabled ? "已启用" : "已禁用") + "\n\n"
                    + "主路径: " + CONFIG_PATH_PRIMARY + "\n"
                    + "副路径: " + CONFIG_PATH_SDCARD + "\n\n"
                    + "⚠ 请在 LSPosed 中重新勾选目标应用并重启！";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        verifyConfig();
    }

    /**
     * 修复文件权限使其全局可读：
     * 1. Java setReadable(true, false)
     * 2. Runtime chmod 666 (兜底)
     */
    private void fixPermissions(String path) {
        try {
            File f = new File(path);
            f.setReadable(true, false);
            f.setWritable(true, true);  // 仅 owner 可写
        } catch (Exception ignored) {}
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "666", path});
        } catch (Exception ignored) {}
    }

    private void verifyConfig() {
        StringBuilder sb = new StringBuilder();

        // 主路径验证
        File filePrimary = new File(CONFIG_PATH_PRIMARY);
        sb.append("── 主路径 ──\n");
        sb.append(CONFIG_PATH_PRIMARY).append("\n");
        sb.append("存在: ").append(filePrimary.exists() ? "✓" : "✗").append("\n");
        sb.append("大小: ").append(filePrimary.exists() ? filePrimary.length() + " 字节" : "—").append("\n");
        sb.append("可读: ").append(filePrimary.canRead() ? "✓" : "✗").append("\n\n");

        // 副路径验证
        File fileSdcard = new File(CONFIG_PATH_SDCARD);
        sb.append("── 副路径 ──\n");
        sb.append(CONFIG_PATH_SDCARD).append("\n");
        sb.append("存在: ").append(fileSdcard.exists() ? "✓" : "✗").append("\n");
        sb.append("大小: ").append(fileSdcard.exists() ? fileSdcard.length() + " 字节" : "—").append("\n");
        sb.append("可读: ").append(fileSdcard.canRead() ? "✓" : "✗").append("\n\n");

        // 文件内容
        File readFrom = filePrimary.exists() ? filePrimary : (fileSdcard.exists() ? fileSdcard : null);
        if (readFrom == null) {
            sb.append("── 文件内容 ──\n");
            sb.append("(配置文件不存在 — 请先点击「保存设置」)\n");
            sb.append("(首次使用需授权存储权限)");
        } else {
            try {
                String content = readFileContent(readFrom);
                JSONObject json = new JSONObject(content);

                sb.append("── 文件内容 ──\n");
                sb.append(content).append("\n\n");

                sb.append("── 解析结果 ──\n");
                sb.append("enabled = ").append(json.optBoolean("enabled")).append("\n");
                sb.append("year    = ").append(json.optInt("year")).append("\n");
                sb.append("month   = ").append(json.optInt("month")).append("\n");
                sb.append("day     = ").append(json.optInt("day")).append("\n");

                Calendar c = Calendar.getInstance();
                c.set(json.optInt("year"), json.optInt("month") - 1, json.optInt("day"), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                long d = (c.getTimeInMillis() - System.currentTimeMillis()) / 86400000;
                sb.append("偏移    = ").append(d).append(" 天");

            } catch (Exception e) {
                sb.append("── 读取异常 ──\n");
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
            Toast.makeText(this, granted ? "存储权限已授权" : "存储权限被拒绝，可能无法保存配置",
                Toast.LENGTH_SHORT).show();
        }
    }
}
