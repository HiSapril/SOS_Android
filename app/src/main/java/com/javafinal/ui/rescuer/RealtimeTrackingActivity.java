package com.javafinal.ui.rescuer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.javafinal.R;
import com.javafinal.model.LocationCurrent;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.ui.LiveSosActivity;
import com.javafinal.util.MapHelper;
import com.javafinal.util.NavigationHelper;
import com.javafinal.util.SosMapData;
import com.javafinal.viewmodel.RescuerViewModel;
import com.javafinal.viewmodel.SosRules;
import java.util.Locale;

public class RealtimeTrackingActivity extends LiveSosActivity {
    private RescuerViewModel rescuer;
    private MaterialButton next, release, call;
    private boolean changing, ownAssignment;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupMapScreen(R.layout.activity_realtime_tracking);
        if (!requireSession()) return;
        rescuer = vm(RescuerViewModel.class);
        next = findViewById(R.id.btnArrived); release = findViewById(R.id.btnYieldMission); call = findViewById(R.id.btnCallVictim);
        next.setEnabled(false); release.setEnabled(false); call.setEnabled(false);
        initLiveSos(state);
        bind("assignment", (SosApiModels.ActiveSosResponse response) -> {
            ownAssignment = response.isActive() && response.getSos() != null && response.getSos().getId().equals(sosId);
            if (!ownAssignment) { stopGps(); home(); return; }
            currentSos = response.getSos(); onSosChanged();
            if (gps != null) gps.request();
        }, error -> showApiError(error, () -> execute("assignment", rescuer::loadMyActive)), null);
        bind("status", (SosApiModels.MissionActionResponse response) -> {
            if (currentSos != null && response.getStatus() != null) currentSos.setStatus(response.getStatus());
            clearStatus(); onSosChanged(); refreshSos();
        }, error -> { showApiError(error, this::refreshSos); refreshSos(); }, this::setChanging);
        bind("release", (SosApiModels.MissionActionResponse response) -> { stopGps(); locationVm.cancelRequests(); home(); },
                error -> showApiError(error, this::refreshSos), this::setChanging);
        initMap(R.id.mapView, state, () -> renderLiveMap(true));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        call.setOnClickListener(v -> { if (currentSos != null) NavigationHelper.dialPhone(this, currentSos.getRequesterPhone()); });
        findViewById(R.id.fabLayers).setOnClickListener(v -> MapHelper.showLayerSwitchDialog(this, map, () -> renderLiveMap(true)));
        findViewById(R.id.fabMyLocation).setOnClickListener(v -> renderLiveMap(true));
        next.setOnClickListener(v -> {
            if (currentSos == null || !ownAssignment) return;
            if ("ARRIVED".equals(currentSos.getStatus())) openSos(ConfirmCompleteActivity.class, sosId);
            else {
                String target = "ACCEPTED".equals(currentSos.getStatus()) ? "RESCUING" : "ARRIVED";
                execute("status", () -> rescuer.updateMissionStatus(currentSos, target));
            }
        });
        release.setOnClickListener(v -> NavigationHelper.showConfirmSheet(this, null, "Nhả ca cứu hộ?",
                "Yêu cầu sẽ trở lại hàng đợi để đội khác tiếp nhận.", "Xác nhận nhả ca", true,
                () -> execute("release", () -> rescuer.releaseSos(sosId)), "Quay lại", null));
    }
    private void setChanging(boolean value) {
        changing = value;
        if (value) showLoading("Đang cập nhật tiến trình cứu hộ…");
        onSosChanged();
    }
    @Override protected boolean canPublishGps() {
        return super.canPublishGps() && ownAssignment && currentSos != null
                && !"WAITING".equals(currentSos.getStatus());
    }
    @Override protected void onSosChanged() {
        if (currentSos == null) return;
        String status = currentSos.getStatus();
        if ("WAITING".equals(status)) { stopGps(); home(); return; }
        ((TextView) findViewById(R.id.tvMissionCode)).setText("SOS " + currentSos.getShortId());
        ((TextView) findViewById(R.id.tvVictimTitle)).setText(text(currentSos.getDescription()));
        ((TextView) findViewById(R.id.tvVictimDetail)).setText(text(currentSos.getRequesterName()) + " • " + text(currentSos.getRequesterPhone()));
        ((TextView) findViewById(R.id.tvTypeBadge)).setText(currentSos.getEmergencyTypeLabel());
        LocationCurrent victim = SosMapData.victim(currentSos), team = SosMapData.rescuer(currentSos);
        ((TextView) findViewById(R.id.tvDistanceRemain)).setText(victim == null || team == null ? "Chưa có GPS"
                : String.format(Locale.US, "%.1f km", SosRequest.computeHaversineKm(team.getLat(), team.getLng(), victim.getLat(), victim.getLng())));
        ((TextView) findViewById(R.id.tvSpeed)).setText(team == null ? "—" : String.format(Locale.US, "±%.1fm", team.getAccuracyM()));
        ((TextView) findViewById(R.id.tvEta)).setText(currentSos.getStatusLabel());
        next.setText("ACCEPTED".equals(status) ? "Bắt đầu di chuyển" : "RESCUING".equals(status) ? "Đã tới hiện trường" : "Hoàn thành ca cứu hộ");
        next.setEnabled(!changing && ownAssignment && ("ACCEPTED".equals(status) || "RESCUING".equals(status) || "ARRIVED".equals(status)));
        release.setVisibility(SosRules.canRelease(status) ? View.VISIBLE : View.GONE);
        release.setEnabled(!changing && ownAssignment && SosRules.canRelease(status));
        call.setEnabled(currentSos.getRequesterPhone() != null);
    }
    private void home() {
        startActivity(new Intent(this, RescuerHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
    }
    @Override protected void onSosEnded() { home(); }
    @Override protected void onStart() { super.onStart(); if (rescuer != null && sosId != null) execute("assignment", rescuer::loadMyActive); }
    @Override protected void onStop() { ownAssignment = false; super.onStop(); }
}
