package com.javafinal.model;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Model ánh xạ bảng {@code emergency_types} trong CSDL (so_do_csdl.jpg)
 * và nhóm API Danh mục Loại Khẩn Cấp (#27 -> #31: {@code /api/emergency-types}).
 */
public class EmergencyType implements Serializable {

    private static final Map<String, EmergencyType> CACHE_BY_ID =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, EmergencyType> CACHE_BY_NAME =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** PK uuid */
    @SerializedName("id")
    private String id;

    /** UK varchar - Mã loại khẩn cấp từ bảng emergency_types trong CSDL */
    @SerializedName("name")
    private String name;

    /** text - Mô tả chi tiết tiếng Việt từ bảng emergency_types trong CSDL */
    @SerializedName("description")
    private String description;

    /** timestamptz - Thời điểm khởi tạo */
    @SerializedName("created_at")
    private String createdAt;

    public EmergencyType() {
    }

    public EmergencyType(String id, String name, String description, String createdAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
    }

    /**
     * Lưu danh sách {@link EmergencyType} tải từ API {@code GET /api/emergency-types}
     * vào bộ nhớ đệm để đồng bộ hiển thị trên toàn ứng dụng theo dữ liệu CSDL.
     */
    public static void cacheAll(Collection<EmergencyType> items) {
        if (items == null) return;
        synchronized (CACHE_BY_ID) {
            CACHE_BY_ID.clear();
            CACHE_BY_NAME.clear();
            for (EmergencyType item : items) {
                cache(item);
            }
        }
    }

    /**
     * Lưu hoặc cập nhật một bản ghi {@link EmergencyType} từ CSDL vào bộ nhớ đệm.
     */
    public static void cache(EmergencyType item) {
        if (item == null) return;
        if (item.id != null && !item.id.trim().isEmpty()) {
            CACHE_BY_ID.put(item.id.trim().toLowerCase(Locale.ROOT), item);
        }
        if (item.name != null && !item.name.trim().isEmpty()) {
            CACHE_BY_NAME.put(item.name.trim().toUpperCase(Locale.ROOT), item);
        }
    }

    public static List<EmergencyType> getCachedAll() {
        synchronized (CACHE_BY_ID) {
            if (!CACHE_BY_ID.isEmpty()) {
                return new ArrayList<>(CACHE_BY_ID.values());
            }
            return new ArrayList<>(CACHE_BY_NAME.values());
        }
    }

    /**
     * Tìm {@link EmergencyType} đã tải từ CSDL theo {@code id} (UUID) hoặc {@code name} (mã UK).
     */
    public static EmergencyType find(String idOrName) {
        if (idOrName == null || idOrName.trim().isEmpty()) return null;
        String key = idOrName.trim();
        EmergencyType byId = CACHE_BY_ID.get(key.toLowerCase(Locale.ROOT));
        if (byId != null) return byId;
        return CACHE_BY_NAME.get(key.toUpperCase(Locale.ROOT));
    }

    /**
     * Phân giải nhãn hiển thị cho một phiếu SOS dựa trên {@code emergency_type_id} và {@code emergency_type}
     * lấy từ CSDL.
     */
    public static String resolveLabel(String emergencyTypeId, String emergencyTypeCode) {
        EmergencyType matched = find(emergencyTypeId);
        if (matched == null) {
            matched = find(emergencyTypeCode);
        }
        if (matched != null) {
            return matched.getDisplayLabel();
        }
        if (emergencyTypeCode != null && !emergencyTypeCode.trim().isEmpty()) {
            return formatFallbackFromDbSeed(emergencyTypeCode.trim(), null);
        }
        if (emergencyTypeId != null && !emergencyTypeId.trim().isEmpty()) {
            return emergencyTypeId.trim();
        }
        return "Khẩn cấp";
    }

    /**
     * Nhãn hiển thị ngắn gọn trên Chip / Badge được trích xuất từ dữ liệu bảng {@code emergency_types}.
     */
    public String getDisplayLabel() {
        return formatFallbackFromDbSeed(name, description);
    }

    private static String formatFallbackFromDbSeed(String rawName, String rawDescription) {
        String code = rawName == null ? "" : rawName.trim().toUpperCase(Locale.ROOT);
        if (rawDescription != null && !rawDescription.trim().isEmpty()) {
            String desc = rawDescription.trim();
            int commaIdx = desc.indexOf(',');
            if (commaIdx > 0 && commaIdx <= 32) {
                String firstClause = desc.substring(0, commaIdx).trim();
                if (!firstClause.isEmpty()) {
                    return firstClause;
                }
            }
            if (desc.length() <= 32) {
                return desc;
            }
        }
        switch (code) {
            case "NGAP_LUT":
                return "Ngập lụt";
            case "LO_DAT":
                return "Sạt lở đất đá";
            case "TAI_NAN":
                return "Tai nạn giao thông";
            case "CHAY_NO":
                return "Hỏa hoạn / Cháy nổ";
            case "MAC_KET":
                return "Mắc kẹt";
            case "Y_TE_KHAN_CAP":
                return "Cấp cứu y tế khẩn cấp";
            case "KHAC":
                return "Khẩn cấp khác";
            default:
                break;
        }
        if (rawDescription != null && !rawDescription.trim().isEmpty()) {
            return rawDescription.trim();
        }
        return rawName != null && !rawName.trim().isEmpty() ? rawName.trim() : "Khẩn cấp";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
