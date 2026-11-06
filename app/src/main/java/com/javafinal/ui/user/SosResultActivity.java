package com.javafinal.ui.user;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.javafinal.R;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosLog;
import com.javafinal.model.SosRequest;
import com.javafinal.ui.ApiActivity;
import com.javafinal.viewmodel.SosRules;
import com.javafinal.viewmodel.SosViewModel;
import com.javafinal.util.ApiDisplay;

public class SosResultActivity extends ApiActivity {
    private SosViewModel sos;
    private SosRequest result;
    private String sosId;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupScreen(R.layout.activity_sos_result);
        if (!requireSession()) return;
        sos = vm(SosViewModel.class);
        sosId = getIntent().getStringExtra(SOS_ID);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> home());
        findViewById(R.id.btnBackToMap).setOnClickListener(v -> home());
        findViewById(R.id.btnNotifyFamily).setEnabled(false);
        bind("result", (SosApiModels.SosSingleResponse response) -> {
            result = response.getSos();
            if (result == null) { showStatus("Không tìm thấy ca cứu hộ.", this::load); return; }
            if (!SosRules.isTerminal(result.getStatus())) { openSos(TrackSosActivity.class, sosId); finish(); return; }
            clearStatus(); render(); execute("logs", () -> sos.loadLogs(sosId));
        }, error -> showApiError(error, this::load), busy -> { if (busy) showLoading("Đang tải kết quả cứu hộ…"); });
        bind("logs", (SosApiModels.SosLogsResponse response) -> {
            StringBuilder content = new StringBuilder();
            if (response.getLogs() != null) for (SosLog entry : response.getLogs()) {
                if (entry == null) continue;
                if (content.length() > 0) content.append("\n\n");
                content.append(ApiDisplay.time(entry.getCreatedAt())).append(" • ").append(ApiDisplay.action(entry.getAction()))
                        .append("\n").append(text(entry.getDescription()));
            }
            ((TextView) findViewById(R.id.tvSosLogs)).setText(content.length() == 0 ? "Chưa có nhật ký." : content.toString());
        }, error -> { ((TextView) findViewById(R.id.tvSosLogs)).setText("Không tải được nhật ký."); showApiError(error, () -> execute("logs", () -> sos.loadLogs(sosId))); }, null);
        findViewById(R.id.btnNotifyFamily).setOnClickListener(v -> {
            if (result == null) return;
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Tôi đã an toàn! SOS " + result.getShortId() + " • " + result.getStatusLabel());
            startActivity(Intent.createChooser(share, "Chia sẻ an toàn"));
        });
        if (sosId == null) showStatus("Chưa có mã SOS để hiển thị kết quả.", null); else load();
    }
    private void load() { if (sosId != null) execute("result", () -> sos.loadDetail(sosId)); }
    private void render() {
        boolean cancelled = "CANCELLED".equals(result.getStatus());
        int color = ContextCompat.getColor(this, cancelled ? R.color.amber : R.color.green);
        int soft = ContextCompat.getColor(this, cancelled ? R.color.amber_soft : R.color.green_soft);
        ImageView icon = findViewById(R.id.imgResult);
        icon.setImageResource(cancelled ? R.drawable.ic_close : R.drawable.ic_check);
        icon.setImageTintList(ColorStateList.valueOf(color)); icon.setBackgroundTintList(ColorStateList.valueOf(soft));
        TextView badge = findViewById(R.id.tvStatusBadge);
        badge.setText(cancelled ? "ĐÃ HỦY" : "HOÀN THÀNH"); badge.setTextColor(color);
        badge.setBackgroundTintList(ColorStateList.valueOf(soft));
        ((TextView) findViewById(R.id.tvResultTitle)).setText(cancelled ? "Đã hủy yêu cầu SOS" : "Đã hoàn thành cứu hộ");
        ((TextView) findViewById(R.id.tvSosCode)).setText(result.getShortId());
        ((TextView) findViewById(R.id.tvClosedAt)).setText(ApiDisplay.time(result.getUpdatedAt()));
        ((TextView) findViewById(R.id.tvEvacuated)).setText("Sự cố: " + result.getEmergencyTypeLabel());
        ((TextView) findViewById(R.id.tvRescueTeam)).setText("Đội hỗ trợ: " + text(result.getTeamName()));
        ((TextView) findViewById(R.id.tvShelter)).setText(text(result.getDescription()));
        ((TextView) findViewById(R.id.tvEndReason)).setText("Lý do kết thúc: " + ApiDisplay.endReason(result.getEndReason()));
        findViewById(R.id.btnNotifyFamily).setEnabled(true);
    }
    private void home() {
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
    }
}
