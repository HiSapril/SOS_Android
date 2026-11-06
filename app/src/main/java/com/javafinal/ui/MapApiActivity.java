package com.javafinal.ui;

import android.os.Bundle;
import androidx.annotation.NonNull;
import com.javafinal.util.MapHelper;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

public abstract class MapApiActivity extends ApiActivity {
    protected MapView mapView;
    protected MapLibreMap map;
    protected boolean mapReady;

    protected void setupMapScreen(int layout) {
        MapHelper.initMapLibre(this);
        setupScreen(layout);
    }
    protected void initMap(int id, Bundle savedState, Runnable render) {
        mapView = findViewById(id);
        mapView.onCreate(savedState);
        MapHelper.enableScrollViewTouch(mapView);
        mapView.getMapAsync(readyMap -> {
            map = readyMap;
            MapHelper.applyDefaultStyle(map, style -> { mapReady = true; render.run(); });
        });
    }
    @Override protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { if (mapView != null) mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { if (mapView != null) mapView.onStop(); super.onStop(); }
    @Override protected void onDestroy() { if (mapView != null) mapView.onDestroy(); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out); if (mapView != null) mapView.onSaveInstanceState(out);
    }
}
