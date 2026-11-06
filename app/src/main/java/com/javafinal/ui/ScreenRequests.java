package com.javafinal.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import com.javafinal.viewmodel.ApiState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Giữ request qua xoay màn hình; mỗi kết quả thao tác chỉ xử lý một lần. */
public final class ScreenRequests extends ViewModel {
    private final Map<String, LiveData<?>> requests = new HashMap<>();
    private final Set<String> consumed = new HashSet<>();

    @SuppressWarnings("unchecked")
    public <T> LiveData<ApiState<T>> get(String key) {
        return (LiveData<ApiState<T>>) requests.get(key);
    }
    public <T> void put(String key, LiveData<ApiState<T>> request) {
        requests.put(key, request);
        consumed.remove(key);
    }
    public boolean isConsumed(String key) { return consumed.contains(key); }
    public boolean consume(String key, LiveData<?> source) {
        return requests.get(key) == source && consumed.add(key);
    }
}
