package com.javafinal.viewmodel;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.google.gson.Gson;
import com.javafinal.BuildConfig;
import com.javafinal.network.ApiClient;
import com.javafinal.network.PreferencesSessionStore;
import com.javafinal.network.SessionStore;

/** Dùng chung một factory cho toàn app để chia sẻ HTTP client và phiên đăng nhập. */
public final class ApiViewModelFactory implements ViewModelProvider.Factory {
    public static final String EMULATOR_BASE_URL = "http://10.0.2.2:8000/";
    private static ApiViewModelFactory defaultFactory;
    private final ApiClient api;

    public static synchronized ApiViewModelFactory getDefault(Context context) {
        if (defaultFactory == null) defaultFactory = new ApiViewModelFactory(context, BuildConfig.API_BASE_URL);
        return defaultFactory;
    }

    /** Máy thật: truyền URL LAN của máy chạy backend; production: truyền HTTPS. */
    public ApiViewModelFactory(Context context, String baseUrl) {
        Gson gson = new Gson();
        SessionStore session = new PreferencesSessionStore(context, gson);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        api = new ApiClient(baseUrl, session, command -> mainHandler.post(command));
    }

    public ApiViewModelFactory(ApiClient api) { this.api = api; }
    public SessionStore getSession() { return api.getSession(); }

    @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        ViewModel result;
        if (modelClass == AuthViewModel.class) result = new AuthViewModel(api);
        else if (modelClass == UserViewModel.class) result = new UserViewModel(api);
        else if (modelClass == SosViewModel.class) result = new SosViewModel(api);
        else if (modelClass == NotificationViewModel.class) result = new NotificationViewModel(api);
        else if (modelClass == RescuerViewModel.class) result = new RescuerViewModel(api);
        else if (modelClass == LocationViewModel.class) result = new LocationViewModel(api);
        else if (modelClass == EmergencyTypeViewModel.class) result = new EmergencyTypeViewModel(api);
        else if (modelClass == RealtimeViewModel.class) result = new RealtimeViewModel(api);
        else if (modelClass == SystemViewModel.class) result = new SystemViewModel(api);
        else throw new IllegalArgumentException("ViewModel không được hỗ trợ: " + modelClass.getName());
        return modelClass.cast(result);
    }
}
