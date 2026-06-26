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
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Calendar;

public class SettingsActivity extends AppCompatActivity {

    private static final String CONFIG_DIR  = "/sdcard/DateSpoof";
    private static final String CONFIG_PATH = "/sdcard/DateSpoof/config.json";
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

        // 请求存储权限（Android 11+ 需要 MANAGE_EXTERNAL_STORAGE）
        requestStoragePerms();

        // 创建配置目录
        ensureConfigDir();

        // 加载已有配置
        loadConfig();

        // 保存按钮
        btnSave.setOnClickListener(v -> saveConfig());

        // 验证按钮
        btnVerify.setOnClickListener(v -> verifyConfig());

        // 自动验证
        verifyConfig();
    }

    private void requestStoragePerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "请授予「所有文件管理权限」后使用", Toast.LENGTH_LONG).show();
                // 引导到设置页
                try {
                    startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())
                    ));
                } catch (Exception ignored) {}
            }
        } else {
            // Android 10 及以下
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

    private void ensureConfigDir() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            Toast.makeText(this, ok ? "配置目录已创建" : "创建目录失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadConfig() {
        File file = new File(CONFIG_PATH);
        if (!file.exists()) {
            // 文件不存在，使用默认值
            swEnabled.setChecked(true);
            etYear.setText("2025");
            etMonth.setText("1");
            etDay.setText("1");
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
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
            ensureConfigDir();

            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("year", year);
            json.put("month", month);
            json.put("day", day);

            FileWriter writer = new FileWriter(CONFIG_PATH);
            writer.write(json.toString(2));
            writer.close();

            // 确认写入
            Calendar targetCal = Calendar.getInstance();
            targetCal.set(year, month - 1, day, 0, 0, 0);
            targetCal.set(Calendar.MILLISECOND, 0);
            long offsetDays = (targetCal.getTimeInMillis() - System.currentTimeMillis()) / 86400000;

            String msg = "✓ 配置已保存到:\n" + CONFIG_PATH
                    + "\n\n目标: " + year + "年" + month + "月" + day + "日"
                    + "\n偏移: " + offsetDays + " 天"
                    + "\n状态: " + (enabled ? "已启用" : "已禁用")
                    + "\n\n⚠ 请在 LSPosed 中重新勾选目标应用后重启！";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        verifyConfig();
    }

    private void verifyConfig() {
        File file = new File(CONFIG_PATH);
        StringBuilder sb = new StringBuilder();

        sb.append("── 配置文件 ──\n");
        sb.append("路径: ").append(CONFIG_PATH).append("\n");
        sb.append("存在: ").append(file.exists() ? "是" : "否").append("\n");
        if (file.exists()) {
            sb.append("大小: ").append(file.length()).append(" 字节\n");
        }
        sb.append("\n── 文件内容 ──\n");

        if (!file.exists()) {
            sb.append("(文件不存在 — 请先点击「保存设置」)");
        } else {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();

                // 解析并注解
                sb.append("\n── 解析结果 ──\n");
                JSONObject json = new JSONObject(readFileContent(file));
                sb.append("enabled = ").append(json.optBoolean("enabled")).append("\n");
                sb.append("year    = ").append(json.optInt("year")).append("\n");
                sb.append("month   = ").append(json.optInt("month")).append("\n");
                sb.append("day     = ").append(json.optInt("day")).append("\n");

                // 计算偏移
                Calendar c = Calendar.getInstance();
                c.set(json.optInt("year"), json.optInt("month") - 1, json.optInt("day"), 0, 0, 0);
                c.set(Calendar.MILLISECOND, 0);
                long d = (c.getTimeInMillis() - System.currentTimeMillis()) / 86400000;
                sb.append("偏移    = ").append(d).append(" 天");

            } catch (Exception e) {
                sb.append("(读取异常: ").append(e.getMessage()).append(")");
            }
        }

        tvResult.setText(sb.toString());
    }

    private String readFileContent(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
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
