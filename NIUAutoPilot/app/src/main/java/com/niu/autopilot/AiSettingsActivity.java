package com.niu.autopilot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.niu.autopilot.calibration.CalibrationActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.32 AI 智能调参配置页面
 */
public class AiSettingsActivity extends AppCompatActivity {
    private Spinner spinnerApi, spinnerModel;
    private EditText etCustomApi, etApiKey, etGithubToken;
    private Button btnFetchModels, btnSave;
    private TextView tvStatus;
    private View llCustomApi;
    private List<String> modelList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        setTitle("AI 智能调参设置");

        spinnerApi = findViewById(R.id.spinner_api);
        spinnerModel = findViewById(R.id.spinner_model);
        etCustomApi = findViewById(R.id.et_custom_api);
        etApiKey = findViewById(R.id.et_api_key);
        etGithubToken = findViewById(R.id.et_github_token);
        btnFetchModels = findViewById(R.id.btn_fetch_models);
        btnSave = findViewById(R.id.btn_save);
        findViewById(R.id.btn_ai_calibrate).setOnClickListener(v -> {
            Settings ss = new Settings(this);
            String mac = ss.getDeviceMac();
            if (mac == null || mac.isEmpty()) {
                Toast.makeText(this, "请先扫描并绑定车辆再校准", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent it = new Intent(this, CalibrationActivity.class);
            it.putExtra("mac_address", mac);
            startActivity(it);
        });
        tvStatus = findViewById(R.id.tv_ai_status);
        llCustomApi = findViewById(R.id.ll_custom_api);

        Settings s = new Settings(this);
        etApiKey.setText(s.getAiApiKey());
        etGithubToken.setText(s.getGithubToken());

        // 初始化接口下拉列表
        List<String> apiNames = new ArrayList<>();
        for (String[] preset : AiApiHelper.PRESET_APIS) {
            apiNames.add(preset[0]);
        }
        ArrayAdapter<String> apiAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, apiNames);
        spinnerApi.setAdapter(apiAdapter);

        // 回显已保存的接口
        String savedBase = s.getAiApiBase();
        int selectedPos = 0;
        for (int i = 0; i < AiApiHelper.PRESET_APIS.length; i++) {
            if (AiApiHelper.PRESET_APIS[i][1].equals(savedBase)) {
                selectedPos = i;
                break;
            }
            if (AiApiHelper.PRESET_APIS[i][0].equals("自定义")) {
                selectedPos = i;
            }
        }
        spinnerApi.setSelection(selectedPos);
        etCustomApi.setText(savedBase);

        // 接口选择变化
        spinnerApi.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String name = AiApiHelper.PRESET_APIS[position][0];
                if ("自定义".equals(name)) {
                    llCustomApi.setVisibility(View.VISIBLE);
                } else {
                    llCustomApi.setVisibility(View.GONE);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 获取模型列表
        btnFetchModels.setOnClickListener(v -> {
            String apiBase = getCurrentApiBase();
            String apiKey = etApiKey.getText().toString().trim();
            if (apiBase.isEmpty()) {
                tvStatus.setText("请填写接口地址");
                tvStatus.setTextColor(0xFFC62828);
                return;
            }
            if (apiKey.isEmpty()) {
                tvStatus.setText("请填写 API Key");
                tvStatus.setTextColor(0xFFC62828);
                return;
            }
            tvStatus.setText("正在获取模型列表...");
            tvStatus.setTextColor(0xFF1976D2);
            btnFetchModels.setEnabled(false);

            AiApiHelper.fetchModels(apiBase, apiKey, new AiApiHelper.ModelsCallback() {
                @Override
                public void onSuccess(List<String> models) {
                    btnFetchModels.setEnabled(true);
                    modelList = models;
                    ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(AiSettingsActivity.this,
                            android.R.layout.simple_spinner_dropdown_item, models);
                    spinnerModel.setAdapter(modelAdapter);
                    String savedModel = new Settings(AiSettingsActivity.this).getAiModel();
                    for (int i = 0; i < models.size(); i++) {
                        if (models.get(i).equals(savedModel)) {
                            spinnerModel.setSelection(i);
                            break;
                        }
                    }
                    tvStatus.setText("获取成功，共 " + models.size() + " 个模型");
                    tvStatus.setTextColor(0xFF2E7D32);
                }

                @Override
                public void onFailure(String error) {
                    btnFetchModels.setEnabled(true);
                    tvStatus.setText("获取失败: " + error);
                    tvStatus.setTextColor(0xFFC62828);
                }
            });
        });

        // 保存配置
        btnSave.setOnClickListener(v -> {
            String apiBase = getCurrentApiBase();
            String apiKey = etApiKey.getText().toString().trim();
            String githubToken = etGithubToken.getText().toString().trim();
            String model = spinnerModel.getSelectedItem() != null
                    ? spinnerModel.getSelectedItem().toString() : "";

            if (apiBase.isEmpty()) {
                Toast.makeText(this, "请填写接口地址", Toast.LENGTH_SHORT).show();
                return;
            }
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "请填写 API Key", Toast.LENGTH_SHORT).show();
                return;
            }
            if (model.isEmpty()) {
                Toast.makeText(this, "请先获取并选择模型", Toast.LENGTH_SHORT).show();
                return;
            }

            Settings settings = new Settings(this);
            settings.setAiApiBase(apiBase);
            settings.setAiApiKey(apiKey);
            settings.setAiModel(model);
            settings.setGithubToken(githubToken);
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private String getCurrentApiBase() {
        int pos = spinnerApi.getSelectedItemPosition();
        String name = AiApiHelper.PRESET_APIS[pos][0];
        if ("自定义".equals(name)) {
            return etCustomApi.getText().toString().trim();
        }
        return AiApiHelper.PRESET_APIS[pos][1];
    }
}
