package com.javafinal.util;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.util.function.Consumer;

/** GPS thật, chỉ cập nhật khi màn hình hiển thị; không tạo tọa độ dự phòng. */
public final class DeviceLocation implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final FusedLocationProviderClient client;
    private final Consumer<Location> onLocation;
    private final Consumer<String> onError;
    private final ActivityResultLauncher<String[]> permission;
    private boolean requested;
    private boolean active;
    private Location latest;
    private final LocationCallback callback = new LocationCallback() {
        @Override public void onLocationResult(LocationResult result) {
            Location location = result.getLastLocation();
            if (active && location != null) {
                latest = location;
                onLocation.accept(location);
            }
        }
    };

    public DeviceLocation(AppCompatActivity activity, Consumer<Location> onLocation, Consumer<String> onError) {
        this.activity = activity;
        this.onLocation = onLocation;
        this.onError = onError;
        client = LocationServices.getFusedLocationProviderClient(activity);
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (hasPermission()) start();
            else onError.accept("Chưa được cấp quyền vị trí. Bạn có thể chọn điểm trên bản đồ.");
        });
        activity.getLifecycle().addObserver(this);
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    public Location getLatest() { return latest; }
    public void request() {
        requested = true;
        if (hasPermission()) start();
        else permission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }
    @SuppressWarnings("MissingPermission") private void start() {
        if (active || !hasPermission() || !activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
        active = true;
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 7000)
                .setMinUpdateIntervalMillis(5000).build();
        client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener(error -> {
            active = false;
            onError.accept("Không lấy được GPS. Hãy bật vị trí hoặc chọn điểm trên bản đồ.");
        });
        client.getLocationAvailability().addOnSuccessListener(availability -> {
            if (active && !availability.isLocationAvailable()) onError.accept("Đang chờ GPS. Hãy bật vị trí trên thiết bị.");
        });
    }
    public void stop() {
        requested = false;
        active = false;
        client.removeLocationUpdates(callback);
    }
    @Override public void onStart(LifecycleOwner owner) { if (requested) start(); }
    @Override public void onStop(LifecycleOwner owner) {
        active = false;
        client.removeLocationUpdates(callback);
    }
    @Override public void onDestroy(LifecycleOwner owner) { stop(); }
}
