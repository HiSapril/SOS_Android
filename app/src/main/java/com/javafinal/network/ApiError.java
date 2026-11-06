package com.javafinal.network;

/** Lỗi dùng chung cho HTTP, validation, JSON và WebSocket. */
public final class ApiError {
    public enum Kind { VALIDATION, NETWORK, HTTP, PARSE, BUSINESS, UNAUTHORIZED, CANCELLED }

    private final Kind kind;
    private final int statusCode;
    private final String message;
    private final String detail;
    private final String retryAfter;

    public ApiError(Kind kind, int statusCode, String message, String detail, String retryAfter) {
        this.kind = kind;
        this.statusCode = statusCode;
        this.message = message;
        this.detail = detail;
        this.retryAfter = retryAfter;
    }

    public static ApiError validation(String message) {
        return new ApiError(Kind.VALIDATION, 0, message, null, null);
    }

    public static ApiError unauthorized() {
        return new ApiError(Kind.UNAUTHORIZED, 401, "Vui lòng đăng nhập lại.", null, null);
    }

    public Kind getKind() { return kind; }
    public int getStatusCode() { return statusCode; }
    public String getMessage() { return message; }
    public String getDetail() { return detail; }
    /** Giá trị nguyên gốc của header Retry-After (số giây hoặc HTTP date). */
    public String getRetryAfter() { return retryAfter; }
    public boolean isUnauthorized() { return statusCode == 401; }
    public boolean isConflict() { return statusCode == 409; }
}
