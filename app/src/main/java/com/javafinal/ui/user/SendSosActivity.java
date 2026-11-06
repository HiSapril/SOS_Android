package com.javafinal.ui.user;

import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.javafinal.R;
import com.javafinal.model.EmergencyType;
import com.javafinal.model.SosApiModels;
import com.javafinal.ui.MapApiActivity;
import com.javafinal.util.DeviceLocation;
import com.javafinal.util.MapHelper;
import com.javafinal.util.NavigationHelper;
import com.javafinal.util.SosMapData;
import com.javafinal.viewmodel.EmergencyTypeViewModel;
import com.javafinal.viewmodel.SosViewModel;
import java.util.Locale;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.geometry.LatLng;

public class SendSosActivity extends MapApiActivity {
    private SosViewModel sos;
    private EmergencyTypeViewModel types;
    private DeviceLocation gps;
    private ChipGroup typeChips;
    private TextView selectedTypeDescView;
    private TextInputEditText description;
    private MaterialButton submit;
    private double lat = Double.NaN, lng = Double.NaN;
    private boolean manualLocation, busy, typesReady;
    private String selectedTypeId;
    private String selectedType;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupMapScreen(R.layout.activity_send_sos);
        if (!requireSession()) return;
        sos = vm(SosViewModel.class);
        types = vm(EmergencyTypeViewModel.class);
        typeChips = findViewById(R.id.chipGroupType);
        typeChips.removeAllViews();
        selectedTypeDescView = findViewById(R.id.tvSelectedTypeDescription);
        description = findViewById(R.id.edtDescription);
        submit = findViewById(R.id.btnSubmitSos);
        submit.setEnabled(false);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        if (state != null) {
            lat = state.getDouble("lat", Double.NaN); lng = state.getDouble("lng", Double.NaN);
            manualLocation = state.getBoolean("manual");
            selectedTypeId = state.getString("type_id");
            selectedType = state.getString("type");
        }
        gps = new DeviceLocation(this, this::onGps, message -> NavigationHelper.showSnack(this, message));
        bind("types", (SosApiModels.EmergencyTypeListResponse response) -> {
            typeChips.removeAllViews();
            if (response.getData() != null) for (EmergencyType type : response.getData()) {
                if (type == null) continue;
                Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip_filter, typeChips, false);
                chip.setId(View.generateViewId()); chip.setCheckable(true);
                chip.setText(type.getDisplayLabel()); chip.setTag(type);
                typeChips.addView(chip);
                boolean matchedById = selectedTypeId != null && type.getId() != null && selectedTypeId.equals(type.getId());
                boolean matchedByName = selectedType != null && type.getName() != null && selectedType.equals(type.getName());
                if (matchedById || matchedByName) chip.setChecked(true);
            }
            typesReady = typeChips.getChildCount() > 0;
            updateSubmit();
            if (typesReady) clearStatus(); else showStatus("Chưa có loại sự cố. Vui lòng thử tải lại.", this::loadTypes);
        }, error -> showApiError(error, this::loadTypes), loading -> { if (loading) showLoading("Đang tải loại sự cố…"); });
        bind("create", (SosApiModels.SosSingleResponse response) -> {
            if (response.getSos() == null || response.getSos().getId() == null) {
                showStatus("Máy chủ chưa trả về mã SOS. Hãy kiểm tra ca đang hoạt động.", this::loadActive); return;
            }
            openSos(TrackSosActivity.class, response.getSos().getId()); finish();
        }, error -> { if (error.isConflict()) loadActive(); else showApiError(error, null); }, value -> {
            busy = value; updateSubmit(); if (value) showLoading("Đang gửi tín hiệu SOS…");
        });
        bind("active", (SosApiModels.ActiveSosResponse response) -> {
            if (response.isActive() && response.getSos() != null) {
                openSos(TrackSosActivity.class, response.getSos().getId()); finish();
            } else showStatus("Chưa tìm thấy SOS đang hoạt động. Vui lòng gửi lại.", null);
        }, error -> showApiError(error, this::loadActive), null);
        typeChips.setOnCheckedStateChangeListener((group, ids) -> {
            View checked = ids.isEmpty() ? null : group.findViewById(ids.get(0));
            EmergencyType chosen = checked != null && checked.getTag() instanceof EmergencyType
                    ? (EmergencyType) checked.getTag() : null;
            selectedTypeId = chosen == null ? null : chosen.getId();
            selectedType = chosen == null ? null : chosen.getName();
            if (selectedTypeDescView != null) {
                String typeDesc = chosen != null && chosen.getDescription() != null ? chosen.getDescription().trim() : "";
                selectedTypeDescView.setText(typeDesc);
                selectedTypeDescView.setVisibility(typeDesc.isEmpty() ? View.GONE : View.VISIBLE);
            }
            updateSubmit();
        });
        initMap(R.id.mapPreview, state, () -> {
            renderPin();
            map.addOnMapClickListener(point -> {
                if (busy) return true;
                lat = point.getLatitude(); lng = point.getLongitude(); manualLocation = true;
                updateCoordinates(); renderPin(); return true;
            });
        });
        findViewById(R.id.btnRefreshGps).setOnClickListener(v -> {
            manualLocation = false;
            gps.request();
            if (gps.getLatest() != null) onGps(gps.getLatest());
        });
        ChipGroup quickTags = findViewById(R.id.chipGroupQuickTags);
        for (int i = 0; i < quickTags.getChildCount(); i++) {
            View child = quickTags.getChildAt(i);
            if (child instanceof Chip) child.setOnClickListener(v -> {
                String tag = ((Chip) v).getText().toString().replace("+ ", "").trim();
                String old = description.getText() == null ? "" : description.getText().toString();
                if (!old.contains(tag)) description.setText(old.isEmpty() ? tag : old + ", " + tag);
            });
        }
        submit.setOnClickListener(v -> sendSos());
        updateCoordinates(); loadTypes(); gps.request();
    }
    private void loadTypes() { execute("types", types::loadAll); }
    private void loadActive() { execute("active", sos::loadMyActive); }
    private void onGps(Location location) {
        if (manualLocation || busy) return;
        lat = location.getLatitude(); lng = location.getLongitude();
        updateCoordinates(); renderPin();
    }
    private boolean hasSelectedType() {
        return (selectedTypeId != null && !selectedTypeId.trim().isEmpty())
                || (selectedType != null && !selectedType.trim().isEmpty());
    }
    private void updateSubmit() { submit.setEnabled(!busy && typesReady && hasSelectedType() && SosMapData.valid(lat, lng)); }
    private void updateCoordinates() {
        ((TextView) findViewById(R.id.tvCoordinates)).setText(SosMapData.valid(lat, lng)
                ? String.format(Locale.US, "%.5f, %.5f • %s", lat, lng, manualLocation ? "Điểm đã chọn" : "GPS thiết bị")
                : "Đang chờ GPS hoặc chạm bản đồ để chọn vị trí");
        updateSubmit();
    }
    private void renderPin() {
        if (!mapReady || !SosMapData.valid(lat, lng)) return;
        map.clear();
        map.addMarker(new MarkerOptions().position(new LatLng(lat, lng)).title("Vị trí cần cứu hộ")
                .icon(MapHelper.createPinIcon(this, android.graphics.Color.RED, "SOS")));
        MapHelper.moveCamera(map, lat, lng, 15);
    }
    private void sendSos() {
        String content = description.getText() == null ? "" : description.getText().toString().trim();
        if (content.isEmpty()) { description.setError("Vui lòng mô tả tình trạng"); return; }
        if (content.length() > 500) { description.setError("Mô tả tối đa 500 ký tự"); return; }
        if (!SosMapData.valid(lat, lng) || !hasSelectedType()) return;
        String visibility = ((RadioGroup) findViewById(R.id.rgPrivacy)).getCheckedRadioButtonId() == R.id.rbPrivate ? "PRIVATE" : "PUBLIC";
        SosApiModels.CreateSosRequest request = new SosApiModels.CreateSosRequest(selectedTypeId, selectedType, content, visibility, lat, lng);
        execute("create", () -> sos.createSos(request));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putDouble("lat", lat); state.putDouble("lng", lng);
        state.putBoolean("manual", manualLocation);
        state.putString("type_id", selectedTypeId);
        state.putString("type", selectedType);
    }
}