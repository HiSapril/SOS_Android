package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.SosApiModels.*;
import com.javafinal.model.SosRequest;
import com.javafinal.network.ApiClient;

/** API #16–22. RB05/RB06 và phân quyền được kiểm tra nguyên tử ở backend. */
public final class RescuerViewModel extends BaseApiViewModel {
    public RescuerViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<RescuerQueueResponse>> loadQueue() {
        return request("GET", "api/rescuer/queue", null, true, RescuerQueueResponse.class,
                null, RescuerQueueResponse::getData);
    }

    public LiveData<ApiState<ActiveSosResponse>> loadMyActive() {
        return request("GET", "api/rescuer/my-active", null, true, ActiveSosResponse.class,
                null, ActiveSosResponse::getSos);
    }

    public LiveData<ApiState<AcceptSosResponse>> acceptSos(String sosId) {
        return request("POST", () -> actionPath(sosId, "accept"), null, true,
                AcceptSosResponse.class, null, null);
    }

    public LiveData<ApiState<MissionActionResponse>> updateMissionStatus(String sosId, String status) {
        return request("POST", () -> actionPath(sosId, "status"), new UpdateMissionStatusRequest(status),
                true, MissionActionResponse.class,
                () -> InputValidator.oneOf(status, "Trạng thái", "RESCUING", "ARRIVED"), null);
    }

    /** Kiểm tra trình tự ngay trên UI nếu đã có snapshot của ca hiện tại. */
    public LiveData<ApiState<MissionActionResponse>> updateMissionStatus(SosRequest mission, String status) {
        if (mission == null || !SosRules.canTransition(mission.getStatus(), status)) {
            return invalid("Chỉ được chuyển ACCEPTED → RESCUING → ARRIVED theo thứ tự.");
        }
        return updateMissionStatus(mission.getId(), status);
    }

    public LiveData<ApiState<MissionActionResponse>> releaseSos(String sosId) {
        return request("POST", () -> actionPath(sosId, "release"), null, true,
                MissionActionResponse.class, null, null);
    }

    public LiveData<ApiState<MissionActionResponse>> completeSos(String sosId) {
        return request("POST", () -> actionPath(sosId, "complete"), null, true,
                MissionActionResponse.class, null, null);
    }

    public LiveData<ApiState<RescueTeamsResponse>> loadTeams() {
        return request("GET", "api/rescuer/teams", null, true, RescueTeamsResponse.class, null, null);
    }

    private String actionPath(String sosId, String action) {
        return "api/rescuer/sos/" + ApiClient.segment(sosId) + "/" + action;
    }
}
