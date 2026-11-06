package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.EmergencyType;
import com.javafinal.model.SosApiModels.*;
import com.javafinal.network.ApiClient;

/** API #27–31. Lỗi danh mục đang được sử dụng được giữ nguyên từ backend. */
public final class EmergencyTypeViewModel extends BaseApiViewModel {
    public EmergencyTypeViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<EmergencyTypeListResponse>> loadAll() {
        return request("GET", "api/emergency-types", null, false,
                EmergencyTypeListResponse.class, null,
                response -> { if (response != null) EmergencyType.cacheAll(response.getData()); });
    }

    public LiveData<ApiState<EmergencyTypeSingleResponse>> loadDetail(String id) {
        return request("GET", () -> "api/emergency-types/" + ApiClient.segment(id), null,
                false, EmergencyTypeSingleResponse.class, null,
                response -> { if (response != null) EmergencyType.cache(response.getData()); });
    }

    public LiveData<ApiState<EmergencyTypeSingleResponse>> create(String name, String description) {
        return upsert("POST", null, name, description);
    }

    public LiveData<ApiState<EmergencyTypeSingleResponse>> update(String id, String name, String description) {
        return upsert("PUT", id, name, description);
    }

    private LiveData<ApiState<EmergencyTypeSingleResponse>> upsert(String method, String id,
            String name, String description) {
        EmergencyTypeUpsertRequest body = new EmergencyTypeUpsertRequest();
        return request(method, () -> "api/emergency-types" + ("POST".equals(method)
                        ? "" : "/" + ApiClient.segment(id)), body, true, EmergencyTypeSingleResponse.class,
                () -> {
                    body.setName(InputValidator.required(name, "Tên loại sự cố"));
                    body.setDescription(description == null ? null : description.trim());
                }, response -> { if (response != null) EmergencyType.cache(response.getData()); });
    }

    public LiveData<ApiState<EmergencyTypeSingleResponse>> delete(String id) {
        return request("DELETE", () -> "api/emergency-types/" + ApiClient.segment(id), null,
                true, EmergencyTypeSingleResponse.class, null, null);
    }
}
