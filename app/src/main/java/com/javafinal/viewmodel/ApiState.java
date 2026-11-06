package com.javafinal.viewmodel;

import com.javafinal.network.ApiError;

/** Một lần gọi API có state riêng, tránh ghi đè kết quả giữa các endpoint. */
public final class ApiState<T> {
    public enum Status { LOADING, SUCCESS, ERROR }

    private final Status status;
    private final T data;
    private final ApiError error;

    private ApiState(Status status, T data, ApiError error) {
        this.status = status;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiState<T> loading() { return new ApiState<>(Status.LOADING, null, null); }
    public static <T> ApiState<T> success(T data) { return new ApiState<>(Status.SUCCESS, data, null); }
    public static <T> ApiState<T> error(ApiError error) { return new ApiState<>(Status.ERROR, null, error); }
    public Status getStatus() { return status; }
    public T getData() { return data; }
    public ApiError getError() { return error; }
}
