package com.javafinal.ui.rescuer;

import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.javafinal.R;
import com.javafinal.model.EmergencyType;
import com.javafinal.model.SosApiModels;
import com.javafinal.model.SosRequest;
import com.javafinal.network.ApiError;
import com.javafinal.ui.ApiActivity;
import com.javafinal.util.DeviceLocation;
import com.javafinal.util.NavigationHelper;
import com.javafinal.viewmodel.EmergencyTypeViewModel;
import com.javafinal.viewmodel.RealtimeViewModel;
import com.javafinal.viewmodel.RescuerViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RescuerHomeActivity extends ApiActivity {
    private RescuerViewModel rescuer;
    private EmergencyTypeViewModel types;
    private RealtimeViewModel realtime;
    private SosRequestAdapter adapter;
    private SosRequest active;
    private List<SosRequest> queue = Collections.emptyList();
    private String filter = "ALL";
    private String filterTypeId;
    private boolean activeReady, accepting, queueLoading;
    private DeviceLocation gps;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setupScreen(R.layout.activity_rescuer_home);
        if (!requireSession()) return;
        rescuer = vm(RescuerViewModel.class);
        types = vm(EmergencyTypeViewModel.class);
        realtime = vm(RealtimeViewModel.class);
        adapter = new SosRequestAdapter(Collections.emptyList(), new SosRequestAdapter.OnSosActionListener() {
            @Override public void onViewDetail(SosRequest request) { openSos(SosDetailActivity.class, request.getId()); }
            @Override public void onAcceptMission(SosRequest request) { execute("accept", () -> rescuer.acceptSos(request.getId())); }
        });
        RecyclerView list = findViewById(R.id.rvSosRequests);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter);
        adapter.setActionsEnabled(false);
        gps = new DeviceLocation(this, location -> adapter.setPosition(location.getLatitude(), location.getLongitude()),
                message -> NavigationHelper.showSnack(this, message));
        bind("types", (SosApiModels.EmergencyTypeListResponse response) -> {
            renderTypeChips(response.getData());
            renderActive();
            renderQueue();
        }, error -> { }, null);
        bind("queue", (SosApiModels.RescuerQueueResponse response) -> {
            queue = response.getData() == null ? Collections.emptyList() : response.getData();
            renderQueue();
        }, error -> { queueLoading = false; showApiError(error, this::refresh); }, loading -> {
            queueLoading = loading; if (loading) showLoading("Đang tải hàng đợi cứu hộ…");
        });
        bind("active", (SosApiModels.ActiveSosResponse response) -> {
            active = response.isActive() ? response.getSos() : null; activeReady = true;
            renderActive();
        }, error -> showApiError(error, this::refresh), null);
        bind("accept", (SosApiModels.AcceptSosResponse response) -> {
            clearStatus();
            if (response.getSosId() != null) openSos(RealtimeTrackingActivity.class, response.getSosId());
            refresh();
        }, error -> { showApiError(error, this::refresh); if (error.isConflict()) { NavigationHelper.showSnack(this, error.getMessage()); refresh(); } }, loading -> {
            accepting = loading; updateActions(); if (loading) showLoading("Đang tiếp nhận ca cứu hộ…");
        });
        ((ChipGroup) findViewById(R.id.chipGroupRescuerFilter)).setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? 0 : ids.get(0);
            if (id == R.id.chipPrivate) {
                filter = "PRIVATE";
                filterTypeId = null;
            } else if (id == R.id.chipPublic) {
                filter = "PUBLIC";
                filterTypeId = null;
            } else if (id == 0 || id == R.id.chipAll) {
                filter = "ALL";
                filterTypeId = null;
            } else {
                View checked = group.findViewById(id);
                EmergencyType chosen = checked != null && checked.getTag() instanceof EmergencyType
                        ? (EmergencyType) checked.getTag() : null;
                filter = chosen != null && chosen.getName() != null ? chosen.getName() : "ALL";
                filterTypeId = chosen != null ? chosen.getId() : null;
            }
            renderQueue();
        });
        findViewById(R.id.btnOpenActiveMission).setOnClickListener(v -> openActive());
        findViewById(R.id.btnEmergencyAlert).setOnClickListener(v -> NavigationHelper.showNotificationsDialog(this));
        findViewById(R.id.btnProfile).setOnClickListener(v -> NavigationHelper.showProfileDialog(this, this::refresh));
        BottomNavigationView bottom = findViewById(R.id.bottomNav);
        bottom.setSelectedItemId(R.id.nav_rescuer_requests);
        bottom.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_rescuer_requests) { refresh(); return true; }
            if (id == R.id.nav_rescuer_team) NavigationHelper.showRescueTeamSheet(this, this::refresh);
            else if (active != null) openActive();
            else showStatus("Đội chưa có ca cứu hộ đang xử lý.", this::refresh);
            return false;
        });
        realtime.getMessages().observe(this, message -> {
            if (message != null && !"PONG".equals(message.getEvent()) && !"SUBSCRIBED".equals(message.getEvent())) refresh();
        });
        realtime.getConnectionState().observe(this, value -> { if (value == RealtimeViewModel.ConnectionState.CONNECTED) refresh(); });
        realtime.getErrors().observe(this, error -> { if (error != null) showApiError(error, this::refresh); });
    }
    private void refresh() {
        execute("types", types::loadAll);
        execute("queue", rescuer::loadQueue);
        execute("active", rescuer::loadMyActive);
    }
    private void renderTypeChips(List<EmergencyType> items) {
        ChipGroup group = findViewById(R.id.chipGroupRescuerFilter);
        if (group == null) return;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            int id = child.getId();
            if (id != R.id.chipAll && id != R.id.chipPrivate && id != R.id.chipPublic) {
                group.removeViewAt(i);
            }
        }
        if (items == null) return;
        int insertIndex = 1;
        for (EmergencyType type : items) {
            if (type == null) continue;
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip_filter, group, false);
            chip.setId(View.generateViewId());
            chip.setText(type.getDisplayLabel());
            chip.setTag(type);
            int targetIndex = Math.min(insertIndex++, group.getChildCount());
            group.addView(chip, targetIndex);
            boolean matchedById = filterTypeId != null && type.getId() != null && filterTypeId.equalsIgnoreCase(type.getId());
            boolean matchedByName = !"ALL".equals(filter) && !"PRIVATE".equals(filter) && !"PUBLIC".equals(filter)
                    && type.getName() != null && filter.equalsIgnoreCase(type.getName());
            if (matchedById || matchedByName) chip.setChecked(true);
        }
    }
    private boolean matchesFilter(SosRequest item) {
        if ("ALL".equals(filter)) return true;
        if (filter.equalsIgnoreCase(item.getVisibility())) return true;
        if (filterTypeId != null && item.getEmergencyTypeId() != null && filterTypeId.equalsIgnoreCase(item.getEmergencyTypeId())) {
            return true;
        }
        return item.getEmergencyType() != null && filter.equalsIgnoreCase(item.getEmergencyType());
    }
    private void updateActions() { adapter.setActionsEnabled(activeReady && active == null && !accepting); }
    private void renderActive() {
        findViewById(R.id.layoutActiveMission).setVisibility(active == null ? View.GONE : View.VISIBLE);
        if (active != null) ((TextView) findViewById(R.id.tvActiveMissionTitle)).setText(active.getEmergencyTypeLabel() + " • " + active.getStatusLabel());
        updateActions();
    }
    private void renderQueue() {
        List<SosRequest> filtered = new ArrayList<>();
        int publicCount = 0, privateCount = 0;
        for (SosRequest item : queue) {
            if (item == null) continue;
            if ("PUBLIC".equals(item.getVisibility())) publicCount++;
            if ("PRIVATE".equals(item.getVisibility())) privateCount++;
            if (matchesFilter(item)) filtered.add(item);
        }
        adapter.submitList(filtered);
        ((TextView) findViewById(R.id.tvWaitingCount)).setText(String.valueOf(queue.size()));
        ((TextView) findViewById(R.id.tvHighPriorityCount)).setText(String.valueOf(publicCount));
        ((TextView) findViewById(R.id.tvScanRadius)).setText(String.valueOf(privateCount));
        if (!queueLoading) {
            if (filtered.isEmpty()) showStatus(queue.isEmpty() ? "Chưa có yêu cầu cứu hộ đang chờ." : "Không có yêu cầu phù hợp bộ lọc.", this::refresh);
            else clearStatus();
        }
    }
    private void openActive() { if (active != null) openSos(RealtimeTrackingActivity.class, active.getId()); }
    @Override protected void onStart() { super.onStart(); if (rescuer != null) { refresh(); realtime.connect(); gps.request(); } }
    @Override protected void onStop() { if (realtime != null) realtime.disconnect(); super.onStop(); }
}
