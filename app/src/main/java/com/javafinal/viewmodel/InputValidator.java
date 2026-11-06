package com.javafinal.viewmodel;

import com.javafinal.model.User;

final class InputValidator {
    private InputValidator() { }
    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " không được để trống.");
        return value.trim();
    }
    static String phone(String phone) {
        String value = required(phone, "Số điện thoại");
        if (!value.matches("(?:0[0-9]{9}|\\+84[0-9]{9})")) {
            throw new IllegalArgumentException("Số điện thoại Việt Nam không hợp lệ.");
        }
        return value;
    }
    static void role(String role) { oneOf(role, "Vai trò", User.ROLE_USER, User.ROLE_RESCUER); }
    static void visibility(String value) { oneOf(value, "Chế độ hiển thị", "PUBLIC", "PRIVATE"); }
    static void oneOf(String value, String name, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return;
        throw new IllegalArgumentException(name + " không hợp lệ.");
    }
    static void coordinates(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Tọa độ GPS không hợp lệ.");
        }
    }
}
