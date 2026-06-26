package com.example.datespoof;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Calendar;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_FILE = "com.example.datespoof_preferences";

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

        // ── 加载已保存的值（有则显示，无则用默认） ──
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        swEnabled.setChecked(prefs.getBoolean("enabled", true));
        etYear.setText( prefs.getString("year",  "2025"));
        etMonth.setText(prefs.getString("month", "1"));
        etDay.setText(  prefs.getString("day",   "1"));

        // ── 保存按钮 ──
        btnSave.setOnClickListener(v -> {
            String yearStr  = etYear.getText().toString().trim();
            String monthStr = etMonth.getText().toString().trim();
            String dayStr   = etDay.getText().toString().trim();

            // 基础校验
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

            if (year < 1900 || year > 2100) {
                Toast.makeText(this, "年份范围: 1900-2100", Toast.LENGTH_SHORT).show();
                return;
            }
            if (month < 1 || month > 12) {
                Toast.makeText(this, "月份范围: 1-12", Toast.LENGTH_SHORT).show();
                return;
            }
            if (day < 1 || day > 31) {
                Toast.makeText(this, "日期范围: 1-31", Toast.LENGTH_SHORT).show();
                return;
            }

            // 写入 SharedPreferences —— 注意 EditText 存的值是 String
            boolean enabled = swEnabled.isChecked();
            prefs.edit()
                .putBoolean("enabled", enabled)
                .putString("year",  yearStr)
                .putString("month", monthStr)
                .putString("day",   dayStr)
                .apply();

            // 计算偏移量供用户参考
            Calendar targetCal = Calendar.getInstance();
            targetCal.set(year, month - 1, day, 0, 0, 0);
            targetCal.set(Calendar.MILLISECOND, 0);
            long targetMillis = targetCal.getTimeInMillis();
            long realMillis = System.currentTimeMillis();
            long offsetDays = (targetMillis - realMillis) / 86400000;

            String msg = "已保存: " + year + "年" + month + "月" + day + "日"
                    + "\n偏移: " + offsetDays + " 天"
                    + "\n伪装状态: " + (enabled ? "已启用" : "已禁用")
                    + "\n\n⚠ 请在 LSPosed 中重新勾选目标应用后重启！";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

            // 同时更新验证面板
            verifySettings();
        });

        // ── 验证按钮 ──
        btnVerify.setOnClickListener(v -> verifySettings());

        // 启动时自动验证一次
        verifySettings();
    }

    private void verifySettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);

        boolean enabled = prefs.getBoolean("enabled", true);
        String year  = prefs.getString("year",  "(未设置)");
        String month = prefs.getString("month", "(未设置)");
        String day   = prefs.getString("day",   "(未设置)");

        // 列出 SharedPreferences 中所有 key
        java.util.Map<String, ?> all = prefs.getAll();

        StringBuilder sb = new StringBuilder();
        sb.append("── SharedPreferences 内容 ──\n");
        if (all.isEmpty()) {
            sb.append("(文件为空或不存在)\n");
        } else {
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
        }
        sb.append("\n── 解析后 ──\n");
        sb.append("enabled = ").append(enabled).append("\n");
        sb.append("year    = ").append(year).append("\n");
        sb.append("month   = ").append(month).append("\n");
        sb.append("day     = ").append(day).append("\n");
        sb.append("\n── 文件路径 ──\n");
        sb.append("/data/data/com.example.datespoof/shared_prefs/").append(PREFS_FILE).append(".xml");

        tvResult.setText(sb.toString());
    }
}
