package com.javafinal.ui.rescuer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.javafinal.R;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.ui.ApiActivity;
import com.javafinal.viewmodel.RescuerViewModel;
import com.javafinal.viewmodel.SosRules;

public class ConfirmCompleteActivity extends ApiActivity {
    private RescuerViewModel rescuer;
    private SosRequest mission;
    private String sosId;
    private boolean busy;
    private MaterialCheckBox confirm;
    private MaterialButton complete;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupScreen(R.layout.activity_confirm_complete);
        if (!requireSession()) return;
        rescuer = vm(RescuerViewModel.class);
        sosId = getIntent().getStringExtra(SOS_ID);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        confirm = findViewById(R.id.cbConfirmComplete);
        complete = findViewById(R.id.btnCompleteMission);
        complete.setEnabled(false);
        confirm.setOnCheckedChangeListener((button, checked) -> updateButton());
        bind("mission", (SosApiModels.ActiveSosResponse response) -> {
            mission = response.isActive() ? response.getSos() : null;
            if (mission == null || !mission.getId().equals(sosId)) {
                mission = null; updateButton(); showStatus("Đây không còn là ca cứu hộ đang xử lý của đội.", this::load); return;
            }
            clearStatus();
            ((TextView) findViewById(R.id.tvSosCode)).setText("SOS " + mission.getShortId());
            ((TextView) findViewById(R.id.tvEmergencyNature)).setText(mission.getEmergencyTypeLabel());
            ((TextView) findViewById(R.id.tvVictimStatus)).setText(text(mission.getRequesterName()) + " • " + text(mission.getRequesterPhone()));
            ((TextView) findViewById(R.id.tvAddress)).setText(text(mission.getDescription()));
            if (!SosRules.canComplete(mission.getStatus())) showStatus("Chỉ có thể hoàn thành sau khi đã tới hiện trường.", this::load);
            updateButton();
        }, error -> showApiError(error, this::load), loading -> { if (loading) showLoading("Đang kiểm tra ca cứu hộ…"); });
        bind("complete", (SosApiModels.MissionActionResponse response) -> {
            startActivity(new Intent(this, RescuerHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
        }, error -> showApiError(error, this::load), loading -> { busy = loading; updateButton(); if (loading) showLoading("Đang hoàn thành ca cứu hộ…"); });
        complete.setOnClickListener(v -> { if (confirm.isChecked()) execute("complete", () -> rescuer.completeSos(sosId)); });
    }
    private void updateButton() { complete.setEnabled(!busy && confirm.isChecked() && mission != null && SosRules.canComplete(mission.getStatus())); }
    private void load() { execute("mission", rescuer::loadMyActive); }
    @Override protected void onStart() { super.onStart(); if (rescuer != null) load(); }
}