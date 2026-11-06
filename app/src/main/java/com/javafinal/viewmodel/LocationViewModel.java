package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.SosApiModels.*;
import com.javafinal.network.ApiClient;
import okhttp3.HttpUrl;

/** API #23–25. UI/service cấp GPS thực; ViewModel không giữ Activity/GPS listener. */
public final class LocationViewModel extends BaseApiViewModel {
    public LocationViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<UpdateLocationResponse>> updateLocation(String sosId, double lat,
            double lng, double accuracyM, boolean saveHistory) {
        return request("POST", () -> "api/location/" + ApiClient.segment(sosId),
                new UpdateLocationRequest(lat, lng, accuracyM, saveHistory), true,
                UpdateLocationResponse.class, () -> {
                    InputValidator.coordinates(lat, lng);
                    if (!Double.isFinite(accuracyM) || accuracyM < 0) {
                        throw new IllegalArgumentException("Độ chính xác GPS phải là số không âm.");
                    }
                }, null);
    }

    public LiveData<ApiState<LocationCurrentResponse>> loadCurrent(String sosId) {
        return request("GET", () -> "api/location/" + ApiClient.segment(sosId) + "/current",
                null, true, LocationCurrentResponse.class, null, null);
    }

    public LiveData<ApiState<LocationTrailResponse>> loadTrail(String sosId) {
        return loadTrail(sosId, null, 100);
    }

    public LiveData<ApiState<LocationTrailResponse>> loadTrail(String sosId, String actor, int limit) {
        return request("GET", () -> {
            HttpUrl.Builder url = api.url("api/location/" + ApiClient.segment(sosId) + "/trail").newBuilder();
            if (actor != null) url.addQueryParameter("actor", actor);
            url.addQueryParameter("limit", String.valueOf(limit));
            return url.build().toString();
        }, null, true, LocationTrailResponse.class, () -> {
            if (actor != null) InputValidator.oneOf(actor, "Đối tượng định vị", "VICTIM", "RESCUER");
            if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limit phải từ 1 đến 500.");
        }, null);
    }
}
