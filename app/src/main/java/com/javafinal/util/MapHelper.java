package com.javafinal.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.javafinal.R;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.PolygonOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class MapHelper {

    public static final int LAYER_STREET_OSM = 0;
    public static final int LAYER_VECTOR_OPENFREEMAP = 1;
    public static final int LAYER_SATELLITE_ESRI = 2;
    public static final int LAYER_DARK_TACTICAL = 3;

    private static int currentLayerIndex = LAYER_STREET_OSM;
    private static Context appContext;

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static void initMapLibre(Context context) {
        appContext = context.getApplicationContext();
        MapLibre.getInstance(appContext);
    }

    public static String getRasterStyleJson(String tileUrl, String bgHex) {
        return "{\n" +
                "  \"version\": 8,\n" +
                "  \"name\": \"RescueSOS Map\",\n" +
                "  \"sources\": {\n" +
                "    \"raster-tiles\": {\n" +
                "      \"type\": \"raster\",\n" +
                "      \"tiles\": [\n" +
                "        \"" + tileUrl + "\"\n" +
                "      ],\n" +
                "      \"tileSize\": 256,\n" +
                "      \"attribution\": \"© OpenStreetMap\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"layers\": [\n" +
                "    {\n" +
                "      \"id\": \"background\",\n" +
                "      \"type\": \"background\",\n" +
                "      \"paint\": {\n" +
                "        \"background-color\": \"" + bgHex + "\"\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"id\": \"simple-tiles\",\n" +
                "      \"type\": \"raster\",\n" +
                "      \"source\": \"raster-tiles\",\n" +
                "      \"minzoom\": 0,\n" +
                "      \"maxzoom\": 20\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    public static void applyMapStyle(MapLibreMap map, int layerIndex, Style.OnStyleLoaded callback) {
        if (map == null) return;
        UiSettings ui = map.getUiSettings();
        ui.setLogoEnabled(false);
        ui.setAttributionEnabled(false);
        ui.setCompassEnabled(false);
        setupMinimalInfoWindow(map);
        currentLayerIndex = layerIndex;
        Style.Builder builder = new Style.Builder();
        switch (layerIndex) {
            case LAYER_VECTOR_OPENFREEMAP:
                builder.fromUri("https://tiles.openfreemap.org/styles/liberty");
                break;
            case LAYER_SATELLITE_ESRI:
                builder.fromJson(getRasterStyleJson(
                        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                        "#132232"
                ));
                break;
            case LAYER_DARK_TACTICAL:
                builder.fromJson(getRasterStyleJson(
                        "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png?key=cb1_52ik_1_1ef32e1ab2c6e505e4c47879",
                        "#14161A"
                ));
                break;
            case LAYER_STREET_OSM:
            default:
                builder.fromJson(getRasterStyleJson(
                        "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png?key=cb1_52ik_1_1ef32e1ab2c6e505e4c47879",
                        "#F4F4F6"
                ));
                break;
        }
        if (callback != null) {
            map.setStyle(builder, callback);
        } else {
            map.setStyle(builder);
        }
    }

    public static void applyDefaultStyle(MapLibreMap map, Style.OnStyleLoaded callback) {
        applyMapStyle(map, currentLayerIndex, callback);
    }

    private static void setupMinimalInfoWindow(MapLibreMap map) {
        if (appContext == null) return;
        map.setInfoWindowAdapter(marker -> {
            LinearLayout box = new LinearLayout(appContext);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_info_window);
            box.setPadding(dp(appContext, 12), dp(appContext, 8), dp(appContext, 12), dp(appContext, 8));

            if (marker.getTitle() != null && !marker.getTitle().isEmpty()) {
                TextView title = new TextView(appContext);
                title.setText(marker.getTitle());
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                title.setTextColor(Color.parseColor("#111111"));
                box.addView(title);
            }
            if (marker.getSnippet() != null && !marker.getSnippet().isEmpty()) {
                TextView sub = new TextView(appContext);
                sub.setText(marker.getSnippet());
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                sub.setTextColor(Color.parseColor("#6B6B70"));
                box.addView(sub);
            }
            return box;
        });
    }

    public static void showLayerSwitchDialog(Context context, MapLibreMap map, Runnable onReloadAnnotations) {
        if (map == null) {
            if (context instanceof Activity) {
                NavigationHelper.showSnack((Activity) context, "Bản đồ đang khởi tạo...");
            }
            return;
        }
        String[][] options = new String[][]{
                {"Đường phố", "Mặc định"},
                {"Bản đồ 3D", "Vector"},
                {"Vệ tinh", "Thực địa"},
                {"Giao diện tối", "Ban đêm"}
        };

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout root = NavigationHelper.createSheetRoot(
                context,
                null,
                "Kiểu bản đồ",
                null
        );

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.bg_box);
        list.setPadding(dp(context, 14), dp(context, 4), dp(context, 14), dp(context, 4));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(context, 16);
        listLp.bottomMargin = dp(context, 8);
        list.setLayoutParams(listLp);

        for (int i = 0; i < options.length; i++) {
            final int which = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView t1 = new TextView(context);
            t1.setText(options[i][0]);
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t1.setTextColor(ContextCompat.getColor(context, R.color.ink));
            col.addView(t1);

            TextView t2 = new TextView(context);
            t2.setText(options[i][1]);
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t2.setTextColor(ContextCompat.getColor(context, R.color.ink_tertiary));
            col.addView(t2);

            row.addView(col);

            if (i == currentLayerIndex) {
                TextView activeTag = new TextView(context);
                activeTag.setText("Đang dùng");
                activeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                activeTag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                activeTag.setTextColor(ContextCompat.getColor(context, R.color.white));
                activeTag.setBackgroundResource(R.drawable.bg_pill);
                activeTag.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ink)));
                activeTag.setPadding(dp(context, 10), dp(context, 3), dp(context, 10), dp(context, 3));
                row.addView(activeTag);
            }

            row.setOnClickListener(v -> {
                dialog.dismiss();
                applyMapStyle(map, which, style -> {
                    if (onReloadAnnotations != null) {
                        onReloadAnnotations.run();
                    }
                });
            });
            list.addView(row);

            if (i < options.length - 1) {
                View div = new View(context);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
                div.setBackgroundColor(ContextCompat.getColor(context, R.color.line));
                list.addView(div);
            }
        }

        root.addView(list);

        MaterialButton btnClose = NavigationHelper.createSheetButton(context, "Đóng", false, false);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        root.addView(btnClose);

        dialog.setContentView(root);
        dialog.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void enableScrollViewTouch(MapView mapView) {
        if (mapView == null) return;
        mapView.setOnTouchListener((v, event) -> {
            ViewParent parent = v.getParent();
            if (parent != null) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        parent.requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        parent.requestDisallowInterceptTouchEvent(false);
                        break;
                }
            }
            return false;
        });
    }

    public static Icon createPinIcon(Context context, int fillColor, String badgeText) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(42 * density);
        int height = Math.round(32 * density);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.argb(40, 0, 0, 0));

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(fillColor);
        bgPaint.setStyle(Paint.Style.FILL);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.8f * density);

        float pillLeft = 2.5f * density;
        float pillTop = 2f * density;
        float pillRight = width - 2.5f * density;
        float pillBottom = height - 7.5f * density;
        float corner = (pillBottom - pillTop) / 2f;

        RectF shadowRect = new RectF(pillLeft, pillTop + 1.2f * density, pillRight, pillBottom + 1.2f * density);
        canvas.drawRoundRect(shadowRect, corner, corner, shadowPaint);

        Path pointer = new Path();
        float centerX = width / 2f;
        pointer.moveTo(centerX - 4 * density, pillBottom - 1f * density);
        pointer.lineTo(centerX + 4 * density, pillBottom - 1f * density);
        pointer.lineTo(centerX, height - 1.5f * density);
        pointer.close();
        canvas.drawPath(pointer, bgPaint);

        RectF rect = new RectF(pillLeft, pillTop, pillRight, pillBottom);
        canvas.drawRoundRect(rect, corner, corner, bgPaint);
        canvas.drawRoundRect(rect, corner, corner, strokePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(9.5f * density);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = rect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(badgeText, rect.centerX(), textY, textPaint);

        return IconFactory.getInstance(context).fromBitmap(bitmap);
    }

    public static void moveCamera(MapLibreMap map, double lat, double lng, double zoom) {
        if (map == null) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), zoom), 750);
    }

    public static void addFloodZonePolygon(MapLibreMap map, double centerLat, double centerLng, double radiusDeg) {
        if (map == null) return;
        List<LatLng> circlePoints = new ArrayList<>();
        int steps = 28;
        for (int i = 0; i < steps; i++) {
            double angle = 2 * Math.PI * i / steps;
            double dLat = radiusDeg * Math.sin(angle);
            double dLng = radiusDeg * Math.cos(angle);
            circlePoints.add(new LatLng(centerLat + dLat, centerLng + dLng));
        }
        map.addPolygon(new PolygonOptions()
                .addAll(circlePoints)
                .fillColor(Color.argb(32, 229, 56, 59))
                .strokeColor(Color.parseColor("#E5383B")));
    }

    public static void fitRouteBounds(MapLibreMap map, List<LatLng> points, int paddingPx) {
        if (map == null || points == null || points.isEmpty()) return;
        if (points.size() == 1) {
            moveCamera(map, points.get(0).getLatitude(), points.get(0).getLongitude(), 15.2);
            return;
        }
        try {
            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            for (LatLng p : points) {
                builder.include(p);
            }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx), 750);
        } catch (Exception e) {
            LatLng p1 = points.get(0);
            LatLng p2 = points.get(points.size() - 1);
            moveCamera(map, (p1.getLatitude() + p2.getLatitude()) / 2.0,
                    (p1.getLongitude() + p2.getLongitude()) / 2.0, 14.5);
        }
    }

    public static void fitTwoPoints(MapLibreMap map, LatLng p1, LatLng p2, int paddingPx) {
        if (map == null) return;
        fitRouteBounds(map, Arrays.asList(p1, p2), paddingPx);
    }
}
