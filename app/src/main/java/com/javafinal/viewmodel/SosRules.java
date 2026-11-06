package com.javafinal.viewmodel;

import com.javafinal.model.SosRequest;

/** Dùng để bật/tắt action trên UI; backend vẫn kiểm tra lại trên dữ liệu mới nhất. */
public final class SosRules {
    private SosRules() { }
    public static boolean isTerminal(String status) {
        return SosRequest.STATUS_COMPLETED.equals(status) || SosRequest.STATUS_CANCELLED.equals(status);
    }
    public static boolean canAccept(String status) { return SosRequest.STATUS_WAITING.equals(status); }
    public static boolean canRelease(String status) {
        return SosRequest.STATUS_ACCEPTED.equals(status) || SosRequest.STATUS_RESCUING.equals(status);
    }
    public static boolean canComplete(String status) { return SosRequest.STATUS_ARRIVED.equals(status); }
    public static boolean canTransition(String current, String next) {
        return (SosRequest.STATUS_ACCEPTED.equals(current) && SosRequest.STATUS_RESCUING.equals(next))
                || (SosRequest.STATUS_RESCUING.equals(current) && SosRequest.STATUS_ARRIVED.equals(next));
    }
}
