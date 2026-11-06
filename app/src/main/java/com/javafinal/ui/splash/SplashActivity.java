package com.javafinal.ui.splash;

import android.os.Bundle;
import com.google.gson.JsonObject;
import com.javafinal.R;
import com.javafinal.model.AuthModels;
import com.javafinal.ui.ApiActivity;
import com.javafinal.viewmodel.AuthViewModel;
import com.javafinal.viewmodel.SystemViewModel;

public class SplashActivity extends ApiActivity {
    private SystemViewModel system;
    private AuthViewModel auth;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupScreen(R.layout.activity_splash);
        system = vm(SystemViewModel.class);
        auth = vm(AuthViewModel.class);
        findViewById(R.id.progressLinear).setVisibility(android.view.View.GONE);
        bind("health", (JsonObject response) -> {
            if (auth.isLoggedIn()) execute("me", auth::loadMe);
            else goToLogin();
        }, error -> showApiError(error, this::checkServer), busy -> { if (busy) showLoading("Đang kết nối máy chủ…"); });
        bind("me", (AuthModels.UserResponse response) -> goToRoleHome(response.getUser()),
                error -> showApiError(error, () -> execute("me", auth::loadMe)),
                busy -> { if (busy) showLoading("Đang kiểm tra phiên đăng nhập…"); });
        checkServer();
    }
    private void checkServer() { execute("health", system::checkHealth); }
}
