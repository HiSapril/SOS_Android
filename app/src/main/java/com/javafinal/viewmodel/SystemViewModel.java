package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.google.gson.JsonObject;
import com.javafinal.network.ApiClient;

/** API #32. /health chưa có response schema trong tài liệu, giữ đầy đủ JSON. */
public final class SystemViewModel extends BaseApiViewModel {
    public SystemViewModel(ApiClient api) { super(api); }
    public LiveData<ApiState<JsonObject>> checkHealth() {
        return request("GET", "health", null, false, JsonObject.class, null, null);
    }
}
