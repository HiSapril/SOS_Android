package com.javafinal.ui.rescuer;

import android.os.Bundle;
import android.widget.TextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.javafinal.R;
import com.javafinal.model.LocationCurrent;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.ui.MapApiActivity;
import com.javafinal.util.DeviceLocation;
import com.javafinal.util.ApiDisplay;
import com.javafinal.util.NavigationHelper;
import com.javafinal.util.SosMapData;
import com.javafinal.viewmodel.RescuerViewModel;
import com.javafinal.viewmodel.SosRules;
import com.javafinal.viewmodel.SosViewModel;
import java.util.Collections;
import java.util.Locale;

public class SosDetailActivity extends MapApiActivity {
    private SosViewModel sos;
    private RescuerViewModel rescuer;
    private SosRequest current;
    private String sosId;
    private MaterialButton accept, call, sms;
    private DeviceLocation gps;
    private boolean busy;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupMapScreen(R.layout.activity_sos_detail);
        if (!requireSession()) return;
        sos = vm(SosViewModel.class); rescuer = vm(RescuerViewModel.class);
        sosId = getIntent().getStringExtra(SOS_ID);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        accept = findViewById(R.id.btnAcceptMission); call = findViewById(R.id.btnCallVictim); sms = findViewById(R.id.btnSmsVictim);
        accept.setEnabled(false); call.setEnabled(false); sms.setEnabled(false);
        gps = new DeviceLocation(this, location -> render(), message -> NavigationHelper.showSnack(this, message));
        bind("detail", (SosApiModels.SosSingleResponse response) -> {
            current = response.getSos(); clearStatus(); render();
            if (current == null) showStatus("Không tìm thấy yêu cầu cứu hộ.", this::load);
        }, error -> showApiError(error, this::load), value -> { if (value) showLoading("Đang tải chi tiết SOS…"); });
        bind("accept", (SosApiModels.AcceptSosResponse response) -> {
            openSos(RealtimeTrackingActivity.class, sosId); finish();
        }, error -> { showApiError(error, this::load); if (error.isConflict()) { NavigationHelper.showSnack(this, error.getMessage()); execute("active", rescuer::loadMyActive); } }, value -> {
            busy = value; if (value) showLoading("Đang tiếp nhận ca…"); render();
        });
        bind("active", (SosApiModels.ActiveSosResponse response) -> {
            if (response.isActive() && response.getSos() != null) {
                NavigationHelper.showConfirmSheet(this, null, "Đội đang có ca cứu hộ",
                        response.getSos().getStatusLabel(), "Mở ca hiện tại", false,
                        () -> openSos(RealtimeTrackingActivity.class, response.getSos().getId()), "Đóng", this::load);
            } else load();
        }, error -> showApiError(error, this::load), null);
        initMap(R.id.mapView, state, this::render);
        call.setOnClickListener(v -> { if (current != null) NavigationHelper.dialPhone(this, current.getRequesterPhone()); });
        sms.setOnClickListener(v -> { if (current != null) NavigationHelper.sendSms(this, current.getRequesterPhone(), "Đội cứu hộ đã nhận được vị trí SOS của bạn."); });
        accept.setOnClickListener(v -> execute("accept", () -> rescuer.acceptSos(sosId)));
        if (sosId == null) showStatus("Không có mã SOS.", null);
        gps.request();
    }
    private void load() { if (sosId != null) execute("detail", () -> sos.loadDetail(sosId)); }
    private void render() {
        if (current == null) return;
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("SOS " + current.getShortId());
        ((TextView) findViewById(R.id.tvPrivacyLevel)).setText(current.getEmergencyTypeLabel() + " • " + current.getVisibilityLabel());
        ((TextView) findViewById(R.id.tvIncidentTitle)).setText(text(current.getDescription()));
        ((TextView) findViewById(R.id.tvCreatedAt)).setText(ApiDisplay.time(current.getCreatedAt()));
        ((TextView) findViewById(R.id.tvVictimContact)).setText(text(current.getRequesterName()) + " • " + text(current.getRequesterPhone()));
        LocationCurrent victim = SosMapData.victim(current);
        ((TextView) findViewById(R.id.tvAddress)).setText(SosMapData.coordinates(victim));
        String distance = "Cần GPS thiết bị để tính khoảng cách";
        if (victim != null && gps.getLatest() != null) distance = String.format(Locale.US, "Cách %.1f km (đường thẳng)",
                SosRequest.computeHaversineKm(gps.getLatest().getLatitude(), gps.getLatest().getLongitude(), victim.getLat(), victim.getLng()));
        ((TextView) findViewById(R.id.tvCoordinates)).setText(distance);
        accept.setEnabled(!busy && SosRules.canAccept(current.getStatus()));
        if (!SosRules.canAccept(current.getStatus())) accept.setText(current.getStatusLabel());
        call.setEnabled(current.getRequesterPhone() != null); sms.setEnabled(current.getRequesterPhone() != null);
        if (mapReady) SosMapData.draw(this, map, current, Collections.emptyList(), true);
    }
    @Override protected void onStart() { super.onStart(); if (sos != null) load(); }
}
