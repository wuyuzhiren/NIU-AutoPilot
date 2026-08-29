package com.niu.autopilot;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 我的页：小牛账号登录 + 车辆SN绑定
 */
public class MyFragment extends Fragment {
    private MainActivity act;
    private EditText etAccount, etPassword, etSn;
    private ImageView ivEye, ivEditSn, ivSnCheck;
    private CheckBox cbRemember;
    private TextView btnLogin, tvLoggedAccount, btnLogout, btnSaveSn;
    private View llLoginForm, llLoggedIn;
    private boolean passwordVisible = false;
    private boolean snEditable = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        act = (MainActivity) getActivity();
        etAccount = view.findViewById(R.id.et_account);
        etPassword = view.findViewById(R.id.et_password);
        etSn = view.findViewById(R.id.et_sn);
        ivEye = view.findViewById(R.id.iv_eye);
        ivEditSn = view.findViewById(R.id.iv_edit_sn);
        ivSnCheck = view.findViewById(R.id.iv_sn_check);
        cbRemember = view.findViewById(R.id.cb_remember);
        btnLogin = view.findViewById(R.id.btn_login);
        tvLoggedAccount = view.findViewById(R.id.tv_logged_account);
        btnLogout = view.findViewById(R.id.btn_logout);
        btnSaveSn = view.findViewById(R.id.btn_save_sn);
        llLoginForm = view.findViewById(R.id.ll_login_form);
        llLoggedIn = view.findViewById(R.id.ll_logged_in);

        ivEye.setOnClickListener(v -> togglePassword());
        ivEditSn.setOnClickListener(v -> toggleSnEdit());
        btnLogin.setOnClickListener(v -> doLogin());
        btnLogout.setOnClickListener(v -> doLogout());
        btnSaveSn.setOnClickListener(v -> saveSn());

        refreshUi();
    }

    private void togglePassword() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            ivEye.setColorFilter(0xFF1565C0);
        } else {
            etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ivEye.setColorFilter(0xFF9E9E9E);
        }
        etPassword.setSelection(etPassword.getText().length());
    }

    private void toggleSnEdit() {
        snEditable = !snEditable;
        etSn.setFocusable(snEditable);
        etSn.setFocusableInTouchMode(snEditable);
        etSn.setCursorVisible(snEditable);
        if (snEditable) {
            etSn.requestFocus();
            ivEditSn.setColorFilter(0xFF1565C0);
        } else {
            ivEditSn.setColorFilter(0xFF9E9E9E);
        }
    }

    private void doLogin() {
        String account = etAccount.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        if (account.isEmpty() || password.isEmpty()) {
            act.toast("请输入账号和密码");
            return;
        }
        act.settings.setAccount(account);
        act.settings.setPassword(password);
        act.settings.setRememberPassword(cbRemember.isChecked());
        btnLogin.setText("登录中...");
        btnLogin.setEnabled(false);

        String sn = act.settings.getSn();
        if (sn.isEmpty()) {
            act.settings.setAccount(account);
            act.settings.setPassword(password);
            btnLogin.setText("登录");
            btnLogin.setEnabled(true);
            act.toast("账号已保存，请先绑定车辆SN");
            refreshUi();
            return;
        }
        act.http.queryAccState(account, password, sn, (ok, msg) -> act.runOnUiThread(() -> {
            btnLogin.setText("登录");
            btnLogin.setEnabled(true);
            if (ok) {
                act.vehicleState = "0".equals(msg) ? "已关机" : ("1".equals(msg) ? "已开机" : msg);
                act.settings.setVehicleState(act.vehicleState);
                act.appendLog("登录验证成功，车辆状态: " + act.vehicleState);
                act.toast("登录成功");
                refreshUi();
            } else {
                act.toast("登录失败: " + msg);
            }
        }));
    }

    private void doLogout() {
        act.settings.setAccount("");
        act.settings.setPassword("");
        act.settings.setRememberPassword(false);
        etAccount.setText("");
        etPassword.setText("");
        cbRemember.setChecked(false);
        act.toast("已退出登录");
        refreshUi();
    }

    private void saveSn() {
        String sn = etSn.getText().toString().trim();
        if (sn.isEmpty()) {
            act.toast("请输入车辆SN");
            return;
        }
        act.settings.setSn(sn);
        ivSnCheck.setVisibility(View.VISIBLE);
        act.toast("SN已保存");
        act.appendLog("车辆SN已绑定: " + sn);
        // 保存后设为只读
        snEditable = false;
        etSn.setFocusable(false);
        etSn.setFocusableInTouchMode(false);
        etSn.setCursorVisible(false);
        ivEditSn.setColorFilter(0xFF9E9E9E);
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public void refreshUi() {
        if (act == null || getView() == null) return;
        String account = act.settings.getAccount();
        boolean loggedIn = !account.isEmpty() && !act.settings.getPassword().isEmpty();

        if (loggedIn) {
            llLoginForm.setVisibility(View.GONE);
            llLoggedIn.setVisibility(View.VISIBLE);
            tvLoggedAccount.setText("已登录: " + maskPhone(account));
        } else {
            llLoginForm.setVisibility(View.VISIBLE);
            llLoggedIn.setVisibility(View.GONE);
            etAccount.setText(account);
            if (act.settings.isRememberPassword()) {
                etPassword.setText(act.settings.getPassword());
                cbRemember.setChecked(true);
            }
        }

        String sn = act.settings.getSn();
        etSn.setText(sn);
        if (!sn.isEmpty()) {
            ivSnCheck.setVisibility(View.VISIBLE);
            // 默认只读
            etSn.setFocusable(false);
            etSn.setFocusableInTouchMode(false);
            etSn.setCursorVisible(false);
        } else {
            ivSnCheck.setVisibility(View.GONE);
            etSn.setFocusable(true);
            etSn.setFocusableInTouchMode(true);
            etSn.setCursorVisible(true);
        }
    }
}
