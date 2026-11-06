package com.javafinal.ui.user;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.javafinal.R;
import com.javafinal.model.LocationCurrent;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.ui.LiveSosActivity;
import com.javafinal.util.NavigationHelper;
import com.javafinal.util.ApiDisplay;
import com.javafinal.util.SosMapData;
import java.util.Locale;

public class TrackSosActivity extends LiveSosActivity {
    private MaterialButton visibility, cancel, call;
    private boolean changing;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupMapScreen(R.layout.activity_track_sos);
        if (!requireSession()) return;
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        visibility = findViewById(R.id.btnChangeMode);
        cancel = findViewById(R.id.btnCancelSos);
        call = findViewById(R.id.btnCallTeam);
        visibility.setEnabled(false); cancel.setEnabled(false); call.setEnabled(false);
        initLiveSos(state);
        initMap(R.id.mapView, state, () -> renderLiveMap(true));
        bind("visibility", (SosApiModels.SosSingleResponse response) -> { clearStatus(); refreshSos(); },
                error -> showApiError(error, this::refreshSos), this::setChanging);
        bind("cancel", (SosApiModels.CancelSosResponse response) -> {
            stopGps(); locationVm.cancelRequests();
            openSos(SosResultActivity.class, sosId); finish();
        }, error -> showApiError(error, this::refreshSos), this::setChanging);
        findViewById(R.id.btnCenterMap).setOnClickListener(v -> renderLiveMap(true));
        call.setOnClickListener(v -> { if (currentSos != null) NavigationHelper.dialPhone(this, currentSos.getRescuerPhone()); });
        visibility.setOnClickListener(v -> {
            if (currentSos == null) return;
            String next = "PUBLIC".equals(currentSos.getVisibility()) ? "PRIVATE" : "PUBLIC";
            execute("visibility", () -> sosVm.updateVisibility(sosId, next));
        });
        cancel.setOnClickListener(v -> NavigationHelper.showConfirmSheet(this, null, "Hủy yêu cầu SOS?",
                "Bạn đã an toàn và muốn dừng yêu cầu cứu hộ này?", "Xác nhận hủy", true,
                () -> execute("cancel", () -> sosVm.cancelSos(sosId)), "Quay lại", null));
    }
    private void setChanging(boolean value) {
        changing = value;
        if (value) showLoading("Đang cập nhật SOS…");
        if (currentSos != null) onSosChanged();
    }
    @Override protected void onSosChanged() {
        if (currentSos == null) return;
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("SOS " + currentSos.getShortId());
        ((TextView) findViewById(R.id.tvStep)).setText(currentSos.getStatusLabel());
        String status = currentSos.getStatus();
        int step = "WAITING".equals(status) ? 1 : "ACCEPTED".equals(status) ? 2 : "RESCUING".equals(status) ? 3 : 4;
        ((LinearProgressIndicator) findViewById(R.id.progressRescue)).setProgressCompat(step, true);
        boolean waiting = "WAITING".equals(status);
        ((TextView) findViewById(R.id.tvTeamName)).setText(waiting ? "Đang tìm đội cứu hộ…" : text(currentSos.getTeamName()));
        ((TextView) findViewById(R.id.tvTeamStatus)).setText(waiting ? "Yêu cầu đã được gửi tới hệ thống"
                : text(currentSos.getRescuerName()) + " • " + text(currentSos.getRescuerPhone()));
        call.setEnabled(!waiting && currentSos.getRescuerPhone() != null);
        ((TextView) findViewById(R.id.tvCoordinates)).setText(SosMapData.coordinates(SosMapData.victim(currentSos)));
        ((TextView) findViewById(R.id.tvEmergencyTypeBadge)).setText(currentSos.getEmergencyTypeLabel());
        ((TextView) findViewById(R.id.tvSituation)).setText(text(currentSos.getDescription()));
        ((TextView) findViewById(R.id.tvAddress)).setText(ApiDisplay.time(currentSos.getCreatedAt()));
        visibility.setText(currentSos.getVisibilityLabel());
        visibility.setEnabled(!changing); cancel.setEnabled(!changing);
        LocationCurrent rescuer = SosMapData.rescuer(currentSos), victim = SosMapData.victim(currentSos);
        if ("RESCUING".equals(status) && rescuer != null && victim != null) {
            double distance = SosRequest.computeHaversineKm(rescuer.getLat(), rescuer.getLng(), victim.getLat(), victim.getLng());
            ((TextView) findViewById(R.id.tvTeamStatus)).setText(String.format(Locale.US, "Cách %.1f km (đường thẳng) • %s", distance, text(currentSos.getRescuerPhone())));
        }
    }
    @Override protected void onSosEnded() { openSos(SosResultActivity.class, sosId); finish(); }
}
