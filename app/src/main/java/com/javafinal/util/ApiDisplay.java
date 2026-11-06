package com.javafinal.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Định dạng thời gian ISO của server theo múi giờ thiết bị, hỗ trợ Android 24+. */
public final class ApiDisplay {
    private ApiDisplay() { }
    public static String time(String iso) {
        if (iso == null || iso.trim().isEmpty()) return "—";
        String normalized = iso.replaceFirst("\\.\\d+", "").replaceFirst("Z$", "+0000")
                .replaceFirst("([+-]\\d{2}):(\\d{2})$", "$1$2");
        boolean offset = normalized.matches(".*[+-]\\d{4}$");
        SimpleDateFormat parser = new SimpleDateFormat(offset ? "yyyy-MM-dd'T'HH:mm:ssZ" : "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        parser.setLenient(false);
        if (!offset) parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date date = parser.parse(normalized);
            return date == null ? iso : new SimpleDateFormat("HH:mm • dd/MM/yyyy", Locale.getDefault()).format(date);
        } catch (ParseException exception) { return iso; }
    }
    public static String endReason(String reason) {
        if (reason == null || reason.isEmpty()) return "Chưa có thông tin";
        if ("USER_CANCELLED".equals(reason)) return "Người gửi đã hủy yêu cầu";
        if ("RESCUER_COMPLETED".equals(reason)) return "Đội cứu hộ đã hoàn thành hỗ trợ";
        return reason;
    }
    public static String action(String action) {
        if (action == null) return "Cập nhật cứu hộ";
        switch (action) {
            case "CREATE_SOS": return "Gửi yêu cầu SOS";
            case "ACCEPT_SOS": return "Đội cứu hộ tiếp nhận";
            case "UPDATE_STATUS": return "Cập nhật tiến trình";
            case "STATUS_RESCUING": return "Đội cứu hộ đang di chuyển";
            case "STATUS_ARRIVED": return "Đội cứu hộ đã tới hiện trường";
            case "RELEASE_SOS": return "Nhả ca cứu hộ";
            case "COMPLETE_SOS": return "Hoàn thành cứu hộ";
            case "CANCEL_SOS": return "Hủy yêu cầu SOS";
            case "UPDATE_VISIBILITY": return "Đổi chế độ hiển thị";
            default: return action;
        }
    }
}
