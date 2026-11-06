package com.javafinal.viewmodel;

import androidx.annotation.MainThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.javafinal.network.ApiClient;
import com.javafinal.network.ApiError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.Call;

/** Các public API method phải được gọi từ main thread như LiveData.observe(). */
public abstract class BaseApiViewModel extends ViewModel {
    protected final ApiClient api;
    private final Map<Call, Runnable> calls = new HashMap<>();
    private boolean cleared;

    protected BaseApiViewModel(ApiClient api) { this.api = api; }

    @MainThread
    protected <T> LiveData<ApiState<T>> request(String method, String path, Object body,
            boolean auth, Class<T> type, Runnable validation, Consumer<T> onSuccess) {
        return request(method, () -> path, body, auth, type, validation, onSuccess);
    }

    @MainThread
    protected <T> LiveData<ApiState<T>> request(String method, Supplier<String> path, Object body,
            boolean auth, Class<T> type, Runnable validation, Consumer<T> onSuccess) {
        MutableLiveData<ApiState<T>> state = new MutableLiveData<>(ApiState.loading());
        if (cleared) {
            state.setValue(ApiState.error(ApiError.validation("ViewModel đã được giải phóng.")));
            return state;
        }
        try {
            if (validation != null) validation.run();
            Call call = api.newCall(method, path.get(), body, auth);
            long sessionVersion = api.getSession().getVersion();
            calls.put(call, () -> state.setValue(ApiState.error(new ApiError(ApiError.Kind.CANCELLED,
                    0, "Yêu cầu đã được hủy.", null, null))));
            api.enqueue(call, type, new ApiClient.Result<T>() {
                private boolean finish() {
                    calls.remove(call);
                    if (cleared) return false;
                    if (auth && sessionVersion != api.getSession().getVersion()) {
                        state.setValue(ApiState.error(ApiError.unauthorized()));
                        return false;
                    }
                    return true;
                }
                @Override public void onSuccess(T value) {
                    if (!finish()) return;
                    try {
                        if (onSuccess != null) onSuccess.accept(value);
                        state.setValue(ApiState.success(value));
                    } catch (IllegalArgumentException exception) {
                        state.setValue(ApiState.error(new ApiError(ApiError.Kind.PARSE, 0,
                                exception.getMessage(), null, null)));
                    }
                }
                @Override public void onError(ApiError error) {
                    calls.remove(call);
                    if (!cleared) state.setValue(ApiState.error(error));
                }
            });
        } catch (SecurityException exception) {
            state.setValue(ApiState.error(ApiError.unauthorized()));
        } catch (IllegalArgumentException exception) {
            state.setValue(ApiState.error(ApiError.validation(exception.getMessage())));
        }
        return state;
    }

    protected <T> LiveData<ApiState<T>> invalid(String message) {
        return new MutableLiveData<>(ApiState.error(ApiError.validation(message)));
    }

    @MainThread public void cancelRequests() {
        ArrayList<Runnable> cancellations = new ArrayList<>(calls.values());
        for (Call call : calls.keySet()) call.cancel();
        calls.clear();
        for (Runnable cancellation : cancellations) cancellation.run();
    }

    @Override protected void onCleared() {
        cleared = true;
        cancelRequests();
    }
}
