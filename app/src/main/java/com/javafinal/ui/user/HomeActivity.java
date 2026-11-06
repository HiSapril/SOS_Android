package com.javafinal.ui.user;

import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.javafinal.R;
import com.javafinal.model.EmergencyType;
import com.javafinal.model.PublicSosPoint;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.model.User;
import com.javafinal.network.ApiError;
import com.javafinal.ui.MapApiActivity;
import com.javafinal.util.DeviceLocation;
import com.javafinal.util.MapHelper;
import com.javafinal.util.NavigationHelper;
import com.javafinal.util.SosMapData;
import com.javafinal.viewmodel.EmergencyTypeViewModel;
import com.javafinal.viewmodel.RealtimeViewModel;
import com.javafinal.viewmodel.SosViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.geometry.LatLng;

public class HomeActivity extends MapApiActivity {
    private SosViewModel sos;
    private EmergencyTypeViewModel types;
    private RealtimeViewModel realtime;
    private DeviceLocation gps;
    private List<PublicSosPoint> points = Collections.emptyList();
    private SosRequest active;
    private boolean activeReady, mapLoading, activeLoading, centered;
    private ApiError mapError, activeError;
    private String filter = "ALL";
    private String filterId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupMapScreen(R.layout.activity_home);
        if (!requireSession()) return;
        sos = vm(SosViewModel.class);
        types = vm(EmergencyTypeViewModel.class);
        realtime = vm(RealtimeViewModel.class);
        findViewById(R.id.cardMyActiveSos).setVisibility(View.GONE);
        findViewById(R.id.btnSos).setEnabled(false);
        gps = new DeviceLocation(this, location -> renderMap(), message -> NavigationHelper.showSnack(this, message));
        bind("types", (SosApiModels.EmergencyTypeListResponse response) -> {
            renderTypeChips(response.getData());
            renderBanner();
            renderMap();
        }, error -> { }, null);
        bind("public", (SosApiModels.PublicMapResponse response) -> {
            points = response.getData() == null ? Collections.emptyList() : response.getData();
            mapError = null; renderMap(); updateStatus();
        }, error -> { mapError = error; updateStatus(); }, busy -> { mapLoading = busy; if (busy) updateStatus(); });
        bind("active", (SosApiModels.ActiveSosResponse response) -> {
            active = response.isActive() ? response.getSos() : null;
            activeReady = true; activeError = null; renderBanner(); updateStatus();
        }, error -> { activeError = error; updateStatus(); }, busy -> { activeLoading = busy; if (busy) updateStatus(); });
        initMap(R.id.mapView, state, this::renderMap);
        findViewById(R.id.btnTrackActiveSos).setOnClickListener(v -> openActive());
        findViewById(R.id.btnSos).setOnClickListener(v -> {
            if (active != null) openActive();
            else startActivity(new android.content.Intent(this, SendSosActivity.class));
        });
        ((ChipGroup) findViewById(R.id.chipGroupFilter)).setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? 0 : ids.get(0);
            View checked = id == 0 ? null : group.findViewById(id);
            EmergencyType chosen = checked != null && checked.getTag() instanceof EmergencyType
                    ? (EmergencyType) checked.getTag() : null;
            if (chosen == null || id == R.id.chipAll) {
                filter = "ALL";
                filterId = null;
            } else {
                filter = chosen.getName() != null ? chosen.getName() : "ALL";
                filterId = chosen.getId();
            }
            renderMap();
        });
        findViewById(R.id.fabLayers).setOnClickListener(v -> MapHelper.showLayerSwitchDialog(this, map, this::renderMap));
        findViewById(R.id.fabMyLocation).setOnClickListener(v -> {
            gps.request();
            Location location = gps.getLatest();
            if (mapReady && location != null) MapHelper.moveCamera(map, location.getLatitude(), location.getLongitude(), 15);
        });
        findViewById(R.id.btnNotifications).setOnClickListener(v -> NavigationHelper.showNotificationsDialog(this));
        findViewById(R.id.btnProfile).setOnClickListener(v -> NavigationHelper.showProfileDialog(this, this::renderBanner));
        BottomNavigationView bottom = findViewById(R.id.bottomNav);
        bottom.setSelectedItemId(R.id.nav_home);
        bottom.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_home) { refresh(); return true; }
            if (item.getItemId() == R.id.nav_requests) {
                if (active != null) openActive();
                else showStatus("Bạn chưa có SOS đang hoạt động.", this::refresh);
            } else if (item.getItemId() == R.id.nav_alerts) NavigationHelper.showNotificationsDialog(this);
            else if (item.getItemId() == R.id.nav_settings) NavigationHelper.showProfileDialog(this, this::renderBanner);
            return false;
        });
        realtime.getMessages().observe(this, message -> {
            if (message != null && !"PONG".equals(message.getEvent()) && !"SUBSCRIBED".equals(message.getEvent())) refresh();
        });
        realtime.getErrors().observe(this, error -> { if (error != null && error.isUnauthorized()) showApiError(error, null); });
    }
    private void refresh() {
        execute("types", types::loadAll);
        execute("public", sos::loadPublicMap);
        execute("active", sos::loadMyActive);
    }
    private void renderTypeChips(List<EmergencyType> items) {
        ChipGroup group = findViewById(R.id.chipGroupFilter);
        if (group == null) return;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getId() != R.id.chipAll) group.removeViewAt(i);
        }
        if (items == null) return;
        for (EmergencyType type : items) {
            if (type == null) continue;
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip_filter, group, false);
            chip.setId(View.generateViewId());
            chip.setText(type.getDisplayLabel());
            chip.setTag(type);
            group.addView(chip);
            boolean matchedById = filterId != null && type.getId() != null && filterId.equalsIgnoreCase(type.getId());
            boolean matchedByName = !"ALL".equals(filter) && type.getName() != null && filter.equalsIgnoreCase(type.getName());
            if (matchedById || matchedByName) chip.setChecked(true);
        }
        if (group.getCheckedChipId() == View.NO_ID) {
            Chip chipAll = group.findViewById(R.id.chipAll);
            if (chipAll != null) chipAll.setChecked(true);
        }
    }
    private boolean matchesFilter(PublicSosPoint point) {
        if ("ALL".equals(filter)) return true;
        if (filterId != null && point.getEmergencyTypeId() != null && filterId.equalsIgnoreCase(point.getEmergencyTypeId())) {
            return true;
        }
        return point.getEmergencyType() != null && filter.equalsIgnoreCase(point.getEmergencyType());
    }
    private void renderBanner() {
        User user = factory.getSession().getUser();
        ((TextView) findViewById(R.id.tvUserGreeting)).setText(user == null ? "Bản đồ cứu hộ" : "Xin chào, " + text(user.getFullName()));
        findViewById(R.id.cardMyActiveSos).setVisibility(active == null ? View.GONE : View.VISIBLE);
        if (active != null) ((TextView) findViewById(R.id.tvActiveSosSummary)).setText(active.getEmergencyTypeLabel() + " • " + active.getStatusLabel());
        findViewById(R.id.btnSos).setEnabled(activeReady);
    }
    private void updateStatus() {
        if (mapError != null || activeError != null) showApiError(mapError != null ? mapError : activeError, this::refresh);
        else if (mapLoading || activeLoading) showLoading("Đang tải bản đồ cứu hộ…");
        else if (points.isEmpty()) showStatus("Chưa có cảnh báo SOS công khai.", this::refresh);
        else clearStatus();
    }
    private void openActive() { if (active != null) openSos(TrackSosActivity.class, active.getId()); }
    private void renderMap() {
        if (!mapReady) return;
        map.clear();
        List<LatLng> bounds = new ArrayList<>();
        Location own = gps.getLatest();
        if (own != null) {
            LatLng position = new LatLng(own.getLatitude(), own.getLongitude());
            bounds.add(position);
            map.addMarker(new MarkerOptions().position(position).title("Vị trí của bạn")
                    .icon(MapHelper.createPinIcon(this, android.graphics.Color.BLACK, "BẠN")));
        }
        for (PublicSosPoint point : points) {
            if (point == null || !matchesFilter(point)
                    || !SosMapData.valid(point.getAreaLat(), point.getAreaLng())) continue;
            bounds.add(new LatLng(point.getAreaLat(), point.getAreaLng()));
            MapHelper.addFloodZonePolygon(map, point.getAreaLat(), point.getAreaLng(), 0.005);
            map.addMarker(new MarkerOptions().position(new LatLng(point.getAreaLat(), point.getAreaLng()))
                    .title(point.getEmergencyTypeLabel()).snippet("Vị trí đã làm mờ • " + text(point.getCreatedAt()))
                    .icon(MapHelper.createPinIcon(this, android.graphics.Color.parseColor("#E5383B"), "SOS")));
        }
        if (!centered && !bounds.isEmpty()) {
            if (bounds.size() == 1) MapHelper.moveCamera(map, bounds.get(0).getLatitude(), bounds.get(0).getLongitude(), 13);
            else MapHelper.fitRouteBounds(map, bounds, 100);
            centered = true;
        }
    }
    @Override protected void onStart() {
        super.onStart();
        if (sos != null) { renderBanner(); refresh(); realtime.connect(); gps.request(); }
    }
    @Override protected void onStop() { if (realtime != null) realtime.disconnect(); super.onStop(); }
}