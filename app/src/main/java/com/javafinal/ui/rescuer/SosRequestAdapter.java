package com.javafinal.ui.rescuer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.javafinal.R;
import com.javafinal.model.LocationCurrent;
import com.javafinal.util.SosMapData;
import com.javafinal.model.SosRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SosRequestAdapter extends RecyclerView.Adapter<SosRequestAdapter.SosViewHolder> {

    public interface OnSosActionListener {
        void onViewDetail(SosRequest request);
        void onAcceptMission(SosRequest request);
    }

    private final List<SosRequest> items = new ArrayList<>();
    private final OnSosActionListener listener;
    private double ownLat = Double.NaN, ownLng = Double.NaN;
    private boolean actionsEnabled;

    public void setPosition(double lat, double lng) { ownLat = lat; ownLng = lng; notifyDataSetChanged(); }
    public void setActionsEnabled(boolean enabled) { actionsEnabled = enabled; notifyDataSetChanged(); }

    public SosRequestAdapter(List<SosRequest> initialItems, OnSosActionListener listener) {
        if (initialItems != null) {
            items.addAll(initialItems);
        }
        this.listener = listener;
    }

    public void submitList(List<SosRequest> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SosViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sos_request, parent, false);
        return new SosViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SosViewHolder holder, int position) {
        SosRequest req = items.get(position);
        Context context = holder.itemView.getContext();

        holder.tvPrivacyBadge.setText(req.getEmergencyTypeLabel() + " • " + req.getVisibilityLabel());
        holder.tvIncidentTitle.setText(req.getDescription());
        holder.tvPeopleSummary.setText(safe(req.getRequesterName()) + " • " + safe(req.getRequesterPhone()));

        LocationCurrent victim = SosMapData.victim(req);
        holder.tvDistance.setText(victim != null && SosMapData.valid(ownLat, ownLng)
                ? String.format(Locale.US, "~%.1f km (đường thẳng)", SosRequest.computeHaversineKm(ownLat, ownLng, victim.getLat(), victim.getLng()))
                : "Chưa có GPS");
        holder.btnAccept.setEnabled(actionsEnabled);

        boolean isPrivate = SosRequest.VISIBILITY_PRIVATE.equals(req.getVisibility());
        if (isPrivate) {
            holder.tvPrivacyBadge.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.red_soft)));
            holder.tvPrivacyBadge.setTextColor(ContextCompat.getColor(context, R.color.red));
        } else {
            holder.tvPrivacyBadge.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.fill)));
            holder.tvPrivacyBadge.setTextColor(ContextCompat.getColor(context, R.color.ink_secondary));
        }

        holder.btnDetail.setOnClickListener(v -> {
            if (listener != null) listener.onViewDetail(req);
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onViewDetail(req);
        });

        holder.btnAccept.setOnClickListener(v -> {
            if (listener != null) listener.onAcceptMission(req);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
    private static String safe(String value) { return value == null || value.isEmpty() ? "—" : value; }

    static class SosViewHolder extends RecyclerView.ViewHolder {
        final TextView tvPrivacyBadge;
        final TextView tvIncidentTitle;
        final TextView tvPeopleSummary;
        final TextView tvDistance;
        final MaterialButton btnDetail;
        final MaterialButton btnAccept;

        SosViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPrivacyBadge = itemView.findViewById(R.id.tvPrivacyBadge);
            tvIncidentTitle = itemView.findViewById(R.id.tvIncidentTitle);
            tvPeopleSummary = itemView.findViewById(R.id.tvPeopleSummary);
            tvDistance = itemView.findViewById(R.id.tvDistance);
            btnDetail = itemView.findViewById(R.id.btnDetail);
            btnAccept = itemView.findViewById(R.id.btnAccept);
        }
    }
}
