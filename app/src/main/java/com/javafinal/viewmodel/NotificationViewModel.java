package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import com.javafinal.model.SosApiModels.MarkNotificationReadResponse;
import com.javafinal.model.SosApiModels.NotificationsResponse;
import com.javafinal.network.ApiClient;

/** API #14–15. */
public final class NotificationViewModel extends BaseApiViewModel {
    public NotificationViewModel(ApiClient api) { super(api); }

    public LiveData<ApiState<NotificationsResponse>> loadMyNotifications() {
        return request("GET", "api/sos/notifications/my", null, true,
                NotificationsResponse.class, null, null);
    }

    public LiveData<ApiState<MarkNotificationReadResponse>> markAsRead(String notificationId) {
        return request("PUT", () -> "api/sos/notifications/" + ApiClient.segment(notificationId) + "/read",
                null, true, MarkNotificationReadResponse.class, null, null);
    }
}
