package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.AuthModels;
import com.javafinal.network.ApiClient;

/** API #05–06 và PUT /api/users/profile tương thích ngược. */
public final class UserViewModel extends BaseApiViewModel {
    public UserViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<AuthModels.UserResponse>> loadProfile() {
        return request("GET", "api/users/profile", null, true, AuthModels.UserResponse.class,
                null, this::saveProfile);
    }

    public LiveData<ApiState<AuthModels.UserResponse>> updateFullName(String fullName) {
        return updateName("api/users/full-name", fullName);
    }

    public LiveData<ApiState<AuthModels.UserResponse>> updateProfile(String fullName) {
        return updateName("api/users/profile", fullName);
    }

    private LiveData<ApiState<AuthModels.UserResponse>> updateName(String path, String fullName) {
        AuthModels.UpdateFullNameRequest body = new AuthModels.UpdateFullNameRequest();
        return request("PUT", path, body, true, AuthModels.UserResponse.class,
                () -> body.setFullName(InputValidator.required(fullName, "Họ tên")), this::saveProfile);
    }

    private void saveProfile(AuthModels.UserResponse response) {
        if (response.getUser() == null) throw new IllegalArgumentException("Phản hồi thiếu hồ sơ tài khoản.");
        api.getSession().updateUser(response.getUser());
    }
}
