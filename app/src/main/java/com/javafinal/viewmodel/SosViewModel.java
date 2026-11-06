package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.SosApiModels.*;
import com.javafinal.network.ApiClient;

/** API #07–13. Server quyết định RB04, quyền sở hữu và thời hạn 2 giờ (RB07). */
public final class SosViewModel extends BaseApiViewModel {
    public SosViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<SosSingleResponse>> createSos(CreateSosRequest body) {
        return request("POST", "api/sos", body, true, SosSingleResponse.class, () -> {
            if (body == null) throw new IllegalArgumentException("Thiếu nội dung SOS.");
            // Chấp nhận danh mục từ server; không giới hạn vào 5 mã mock.
            if ((body.getEmergencyType() == null || body.getEmergencyType().trim().isEmpty())
                    && (body.getEmergencyTypeId() == null || body.getEmergencyTypeId().trim().isEmpty())) {
                throw new IllegalArgumentException("Vui lòng chọn loại sự cố.");
            }
            InputValidator.visibility(body.getVisibility());
            InputValidator.coordinates(body.getInitialLat(), body.getInitialLng());
        }, SosSingleResponse::getSos);
    }

    public LiveData<ApiState<ActiveSosResponse>> loadMyActive() {
        // active=false, sos=null là kết quả thành công hợp lệ.
        return request("GET", "api/sos/my-active", null, true, ActiveSosResponse.class,
                null, ActiveSosResponse::getSos);
    }

    public LiveData<ApiState<PublicMapResponse>> loadPublicMap() {
        return request("GET", "api/sos/public/map", null, false, PublicMapResponse.class, null, null);
    }

    public LiveData<ApiState<SosSingleResponse>> loadDetail(String sosId) {
        return request("GET", () -> "api/sos/" + ApiClient.segment(sosId), null, true,
                SosSingleResponse.class, null, SosSingleResponse::getSos);
    }

    public LiveData<ApiState<SosSingleResponse>> updateVisibility(String sosId, String visibility) {
        return request("PUT", () -> "api/sos/" + ApiClient.segment(sosId) + "/visibility",
                new UpdateVisibilityRequest(visibility), true, SosSingleResponse.class,
                () -> InputValidator.visibility(visibility), SosSingleResponse::getSos);
    }

    public LiveData<ApiState<CancelSosResponse>> cancelSos(String sosId) {
        return request("POST", () -> "api/sos/" + ApiClient.segment(sosId) + "/cancel", null,
                true, CancelSosResponse.class, null, null);
    }

    public LiveData<ApiState<SosLogsResponse>> loadLogs(String sosId) {
        return request("GET", () -> "api/sos/" + ApiClient.segment(sosId) + "/logs", null,
                true, SosLogsResponse.class, null, null);
    }
}
