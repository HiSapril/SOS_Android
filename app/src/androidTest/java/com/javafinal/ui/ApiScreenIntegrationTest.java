package com.javafinal.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javafinal.R;
import com.javafinal.model.User;
import com.javafinal.network.ApiClient;
import com.javafinal.network.SessionStore;
import com.javafinal.ui.auth.LoginActivity;
import com.javafinal.ui.rescuer.RealtimeTrackingActivity;
import com.javafinal.ui.rescuer.RescuerHomeActivity;
import com.javafinal.ui.user.HomeActivity;
import com.javafinal.ui.user.SendSosActivity;
import com.javafinal.ui.user.SosResultActivity;
import com.javafinal.ui.user.TrackSosActivity;
import com.javafinal.viewmodel.ApiViewModelFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.Buffer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

/** Phản hồi kiểm thử chỉ nằm trong androidTest; APK chính luôn gọi backend cấu hình. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public class ApiScreenIntegrationTest {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Request> sent = new CopyOnWriteArrayList<>();
    private SessionStore session;
    private ApiViewModelFactory previousFactory;
    private Field defaultFactory;
    private OkHttpClient http;
    private volatile String fullName = "API Citizen", role = "USER", status = "WAITING", visibility = "PUBLIC";
    private volatile boolean userActive, teamActive, rejectAccept, unauthorized;
    private volatile TestSocket socket;

    @Before public void setUp() throws Exception {
        session = new SessionStore();
        session.save("test-session", new Gson().fromJson(user(), User.class));
        http = new OkHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            sent.add(request);
            String path = request.url().encodedPath();
            String method = request.method();
            Buffer buffer = new Buffer();
            if (request.body() != null) request.body().writeTo(buffer);
            String rawBody = buffer.readUtf8();
            JsonObject body = rawBody.isEmpty() ? new JsonObject() : JsonParser.parseString(rawBody).getAsJsonObject();
            int code = 200;
            String response = "{\"success\":true}";
            if (unauthorized && request.header("Authorization") != null) { code = 401; response = "{\"detail\":\"Token expired\"}"; }
            else if (path.equals("/api/auth/request-otp")) response = "{\"success\":true,\"phone\":\"0901234567\",\"expires_in_minutes\":5}";
            else if (path.equals("/api/auth/verify-otp")) {
                if (!"123456".equals(body.get("otp").getAsString())) { code = 400; response = "{\"detail\":\"OTP incorrect from API\"}"; }
                else response = "{\"success\":true,\"access_token\":\"issued-token\",\"user\":" + user() + "}";
            } else if (path.equals("/api/users/profile") || path.equals("/api/auth/me") || path.equals("/api/users/full-name")) {
                if (method.equals("PUT")) fullName = body.get("full_name").getAsString();
                response = "{\"success\":true,\"user\":" + user() + "}";
            } else if (path.equals("/api/emergency-types")) response = "{\"success\":true,\"data\":[{\"id\":\"e1\",\"name\":\"TYPE_FROM_SERVER\"}]}";
            else if (path.equals("/api/sos/public/map")) response = "{\"success\":true,\"count\":1,\"data\":[{\"sos_id\":\"s1\",\"emergency_type\":\"Y_TE\",\"area_lat\":10.78,\"area_lng\":106.70}]}";
            else if (path.equals("/api/sos/my-active") || path.equals("/api/rescuer/my-active")) {
                boolean active = path.contains("rescuer") ? teamActive : userActive;
                response = "{\"active\":" + active + ",\"sos\":" + (active ? sos() : "null") + "}";
            } else if (path.equals("/api/rescuer/queue")) response = "{\"success\":true,\"data\":[" + queueItem("s1", "PUBLIC") + "," + queueItem("s2", "PRIVATE") + "]}";
            else if (path.endsWith("/accept")) {
                if (rejectAccept) { code = 409; response = "{\"detail\":\"Already accepted by another team\"}"; }
                else { teamActive = true; status = "ACCEPTED"; response = action(); }
            } else if (path.endsWith("/status")) { status = body.get("status").getAsString(); response = action(); }
            else if (path.endsWith("/complete")) { teamActive = false; status = "COMPLETED"; response = action(); }
            else if (path.endsWith("/release")) { teamActive = false; status = "WAITING"; response = action(); }
            else if (path.endsWith("/cancel")) { userActive = false; status = "CANCELLED"; response = action(); }
            else if (path.endsWith("/visibility")) { visibility = body.get("visibility").getAsString(); response = "{\"success\":true,\"sos\":" + sos() + "}"; }
            else if (path.equals("/api/sos") && method.equals("POST")) { userActive = true; status = "WAITING"; response = "{\"success\":true,\"sos\":" + sos() + "}"; }
            else if (path.matches("/api/sos/s[12]")) response = "{\"success\":true,\"sos\":" + sos().replace("\"s1\"", "\"" + path.substring(path.lastIndexOf('/') + 1) + "\"") + "}";
            else if (path.endsWith("/logs")) response = "{\"success\":true,\"logs\":[{\"action\":\"CANCEL_SOS\",\"description\":\"Audit from backend\",\"created_at\":\"2026-11-05T15:00:00Z\"}]}";
            else if (path.endsWith("/current")) response = "{\"success\":true,\"sos_id\":\"s1\",\"locations\":" + locations() + "}";
            else if (path.endsWith("/trail")) response = "{\"success\":true,\"trail\":[{\"actor\":\"RESCUER\",\"lat\":10.77,\"lng\":106.69}]}";
            else if (path.startsWith("/api/location/") && method.equals("POST")) response = "{\"success\":true,\"actor\":\"RESCUER\",\"lat\":10.77,\"lng\":106.69}";
            else if (path.equals("/api/sos/notifications/my")) response = "{\"success\":true,\"data\":[{\"notify_id\":\"n1\",\"message\":\"Notification from backend\",\"is_read\":false}]}";
            return new Response.Builder().request(request).code(code).message("test").protocol(Protocol.HTTP_1_1)
                    .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
        })) {
            @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
                TestSocket result = new TestSocket(request, listener); socket = result;
                main.post(() -> listener.onOpen(result, new Response.Builder().request(request).code(101).message("test")
                        .protocol(Protocol.HTTP_1_1).build()));
                return result;
            }
        };
        defaultFactory = ApiViewModelFactory.class.getDeclaredField("defaultFactory"); defaultFactory.setAccessible(true);
        previousFactory = (ApiViewModelFactory) defaultFactory.get(null);
        ApiClient api = new ApiClient("http://localhost:8000/", session, command -> main.post(command), http, new Gson());
        defaultFactory.set(null, new ApiViewModelFactory(api));
        android.os.ParcelFileDescriptor fine = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant com.javafinal android.permission.ACCESS_FINE_LOCATION"); fine.close();
        android.os.ParcelFileDescriptor coarse = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant com.javafinal android.permission.ACCESS_COARSE_LOCATION"); coarse.close();
    }

    @After public void tearDown() throws Exception {
        onMain(() -> {
            for (Stage stage : Stage.values()) for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))) {
                if (!activity.isFinishing()) activity.finish();
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        defaultFactory.set(null, previousFactory);
        http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll();
    }

    @Test public void loginUsesApiOtpAndServerRoleWithoutAutoFillingCode() throws Exception {
        session.clear();
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            until(() -> resumed() instanceof LoginActivity);
            assertEquals("", getText(R.id.edtPhone));
            onMain(() -> ((EditText) resumed().findViewById(R.id.edtPhone)).setText("0901234567"));
            tap(R.id.btnSendOtp);
            until(() -> visible(R.id.layoutStepOtp));
            assertEquals("", getText(R.id.edtOtp1));
            capture("login-otp");
            fillOtp("111111"); tap(R.id.btnConfirmOtp);
            until(() -> hasText("OTP incorrect from API"));
            assertTrue(current() instanceof LoginActivity);
            fillOtp("123456"); tap(R.id.btnConfirmOtp);
            until(() -> resumed() instanceof HomeActivity && getTextOnMain(R.id.tvUserGreeting).contains("API Citizen"));
            assertEquals("issued-token", session.getAccessToken());
            assertEquals(2, count("/api/auth/verify-otp", "POST"));
        }
    }

    @Test public void homeNotificationsAndProfileDisplayResponsesAndWriteBack() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            until(() -> getTextOnMain(R.id.tvUserGreeting).contains("API Citizen") && enabled(R.id.btnSos));
            assertFalse(isVisible(R.id.cardMyActiveSos));
            capture("home-api");
            tap(R.id.btnNotifications);
            until(() -> hasText("Notification from backend"));
            tapText("Đánh dấu đã đọc");
            until(() -> count("/api/sos/notifications/n1/read", "PUT") == 1);
            tapText("Đóng");
            tap(R.id.btnProfile);
            until(() -> hasExactText("API Citizen"));
            tapText("Đổi tên hiển thị");
            onView(androidx.test.espresso.matcher.ViewMatchers.withClassName(org.hamcrest.Matchers.endsWith("TextInputEditText")))
                    .perform(androidx.test.espresso.action.ViewActions.replaceText("Updated via API"));
            tapText("Lưu");
            until(() -> getTextOnMain(R.id.tvUserGreeting).contains("Updated via API"));
            assertEquals(1, count("/api/users/full-name", "PUT"));
        }
    }

    @Test public void sendSosUsesServerCategoryAndUserSelectedMapPoint() throws Exception {
        try (ActivityScenario<SendSosActivity> scenario = ActivityScenario.launch(SendSosActivity.class)) {
            until(() -> resumed() instanceof SendSosActivity && ((MapApiActivity) resumed()).mapReady
                    && ((ChipGroup) resumed().findViewById(R.id.chipGroupType)).getChildCount() == 1);
            assertEquals("", getText(R.id.edtDescription));
            onMain(() -> {
                ChipGroup chips = resumed().findViewById(R.id.chipGroupType);
                ((Chip) chips.getChildAt(0)).setChecked(true);
                ((EditText) resumed().findViewById(R.id.edtDescription)).setText("Description entered by user");
            });
            onView(withId(R.id.mapPreview)).perform(scrollTo(), click());
            until(() -> enabled(R.id.btnSubmitSos));
            tap(R.id.btnSubmitSos);
            until(() -> resumed() instanceof TrackSosActivity && getTextOnMain(R.id.tvSituation).equals("SOS data from backend"));
            Request create = sent.stream().filter(request -> request.url().encodedPath().equals("/api/sos") && request.method().equals("POST")).findFirst().get();
            Buffer body = new Buffer(); create.body().writeTo(body);
            JsonObject payload = JsonParser.parseString(body.readUtf8()).getAsJsonObject();
            assertEquals("e1", payload.get("emergency_type_id").getAsString());
            assertEquals("TYPE_FROM_SERVER", payload.get("emergency_type").getAsString());
            assertEquals("Description entered by user", payload.get("description").getAsString());
            assertEquals(1, count("/api/sos", "POST"));
        }
    }

    @Test public void trackRendersRealtimeAndCancelLoadsResultAndAudit() throws Exception {
        userActive = true;
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), TrackSosActivity.class).putExtra("SOS_ID", "s1");
        try (ActivityScenario<TrackSosActivity> scenario = ActivityScenario.launch(intent)) {
            until(() -> getTextOnMain(R.id.tvSituation).equals("SOS data from backend") && enabled(R.id.btnChangeMode));
            assertFalse(isEnabled(R.id.btnCallTeam));
            tap(R.id.btnChangeMode);
            until(() -> getTextOnMain(R.id.btnChangeMode).equals("Riêng tư"));
            status = "RESCUING";
            socket.emit("{\"type\":\"STATUS_CHANGED\",\"sos_id\":\"s1\",\"status\":\"RESCUING\"}");
            until(() -> getTextOnMain(R.id.tvStep).equals("Đang di chuyển"));
            capture("tracking-api");
            scenario.recreate();
            until(() -> getTextOnMain(R.id.tvStep).equals("Đang di chuyển"));
            tap(R.id.btnCancelSos);
            tapText("Xác nhận hủy");
            until(() -> resumed() instanceof SosResultActivity && getTextOnMain(R.id.tvSosLogs).contains("Audit from backend"));
            assertEquals("ĐÃ HỦY", getText(R.id.tvStatusBadge));
            capture("result-api");
            assertEquals(1, count("/api/sos/s1/cancel", "POST"));
        }
    }

    @Test public void rescueProgressOnlyChangesAfterApiAndCompletesAssignedMission() throws Exception {
        role = "RESCUER"; fullName = "API Rescuer"; teamActive = true; status = "ACCEPTED";
        session.save("rescuer-token", new Gson().fromJson(user(), User.class));
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), RealtimeTrackingActivity.class).putExtra("SOS_ID", "s1");
        try (ActivityScenario<RealtimeTrackingActivity> scenario = ActivityScenario.launch(intent)) {
            until(() -> enabled(R.id.btnArrived) && getTextOnMain(R.id.btnArrived).equals("Bắt đầu di chuyển"));
            tap(R.id.btnArrived);
            until(() -> getTextOnMain(R.id.btnArrived).equals("Đã tới hiện trường") && enabled(R.id.btnArrived));
            tap(R.id.btnArrived);
            until(() -> getTextOnMain(R.id.btnArrived).equals("Hoàn thành ca cứu hộ") && enabled(R.id.btnArrived));
            tap(R.id.btnArrived);
            until(() -> enabled(R.id.cbConfirmComplete) && getTextOnMain(R.id.tvSosCode).contains("s1"));
            onMain(() -> assertFalse(((android.widget.CompoundButton) resumed().findViewById(R.id.cbConfirmComplete)).isChecked()));
            assertFalse(isEnabled(R.id.btnCompleteMission));
            tap(R.id.cbConfirmComplete);
            until(() -> enabled(R.id.btnCompleteMission));
            tap(R.id.btnCompleteMission);
            until(() -> resumed() instanceof RescuerHomeActivity && getTextOnMain(R.id.tvWaitingCount).equals("2"));
            assertEquals(2, count("/api/rescuer/sos/s1/status", "POST"));
            assertEquals(1, count("/api/rescuer/sos/s1/complete", "POST"));
        }
    }

    @Test public void expiredSessionRedirectsToLoginWithoutShowingMockData() throws Exception {
        unauthorized = true;
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            until(() -> resumed() instanceof LoginActivity);
            assertFalse(session.isLoggedIn());
            assertEquals("", getText(R.id.edtPhone));
        }
    }

    @Test public void queueFiltersServerRowsAndConflictKeepsDetailOpen() throws Exception {
        role = "RESCUER"; fullName = "API Rescuer"; rejectAccept = true;
        session.save("rescuer-token", new Gson().fromJson(user(), User.class));
        try (ActivityScenario<RescuerHomeActivity> scenario = ActivityScenario.launch(RescuerHomeActivity.class)) {
            until(() -> getTextOnMain(R.id.tvWaitingCount).equals("2") && enabled(R.id.chipPrivate));
            assertEquals("1", getText(R.id.tvHighPriorityCount));
            assertEquals("1", getText(R.id.tvScanRadius));
            tap(R.id.chipPrivate);
            until(() -> enabled(R.id.btnDetail));
            onMain(() -> assertEquals(1, ((androidx.recyclerview.widget.RecyclerView) resumed().findViewById(R.id.rvSosRequests)).getAdapter().getItemCount()));
            tap(R.id.btnDetail);
            until(() -> getTextOnMain(R.id.tvIncidentTitle).equals("SOS data from backend") && enabled(R.id.btnAcceptMission));
            tap(R.id.btnAcceptMission);
            until(() -> count("/api/rescuer/sos/s2/accept", "POST") == 1 && enabled(R.id.btnAcceptMission));
            assertTrue(current() instanceof com.javafinal.ui.rescuer.SosDetailActivity);
            capture("detail-api");
        }
    }

    private String user() { JsonObject user = new JsonObject(); user.addProperty("id", "u1"); user.addProperty("full_name", fullName);
        user.addProperty("phone", "0901234567"); user.addProperty("role", role); user.addProperty("account_status", "ACTIVE"); user.addProperty("is_valid", true); return user.toString(); }
    private String locations() { return "{\"VICTIM\":{\"lat\":10.78,\"lng\":106.70,\"accuracy_m\":5},\"RESCUER\":{\"lat\":10.77,\"lng\":106.69,\"accuracy_m\":8}}"; }
    private String sos() { return "{\"id\":\"s1\",\"requester_id\":\"u1\",\"team_id\":\"t1\",\"emergency_type\":\"Y_TE\",\"description\":\"SOS data from backend\","
            + "\"status\":\"" + status + "\",\"visibility\":\"" + visibility + "\",\"created_at\":\"2026-11-05T14:00:00Z\",\"updated_at\":\"2026-11-05T15:00:00Z\","
            + "\"requester\":{\"full_name\":\"API Citizen\",\"phone\":\"0901234567\"},\"rescuer\":{\"full_name\":\"API Rescuer\",\"phone\":\"0901112222\",\"team_name\":\"API Team\"},\"locations\":" + locations() + "}"; }
    private String queueItem(String id, String privacy) { return "{\"sos_id\":\"" + id + "\",\"description\":\"Queue from backend\",\"visibility\":\"" + privacy
            + "\",\"emergency_type\":\"Y_TE\",\"initial_lat\":10.78,\"initial_lng\":106.70}"; }
    private String action() { return "{\"success\":true,\"sos_id\":\"s1\",\"status\":\"" + status + "\"}"; }
    private long count(String path, String method) { return sent.stream().filter(request -> path.equals(request.url().encodedPath()) && method.equals(request.method())).count(); }
    private static void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(1000); // Chờ animation camera 750ms và frame cuối trước khi kiểm tra hình ảnh.
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        java.io.File media = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalMediaDirs()[0];
        java.io.File directory = new java.io.File(media, "additional_test_output/ui-checks");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create screenshot directory");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static Activity resumed() { return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).stream().findFirst().orElse(null); }
    private static Activity current() { final Activity[] activity = new Activity[1]; onMain(() -> activity[0] = resumed()); return activity[0]; }
    private static String getTextOnMain(int id) { Activity activity = resumed(); View view = activity == null ? null : activity.findViewById(id); return view instanceof TextView ? ((TextView) view).getText().toString() : ""; }
    private static String getText(int id) { final String[] text = new String[1]; onMain(() -> text[0] = getTextOnMain(id)); return text[0]; }
    private static boolean enabled(int id) { Activity activity = resumed(); View view = activity == null ? null : activity.findViewById(id); return view != null && view.isEnabled(); }
    private static boolean visible(int id) { Activity activity = resumed(); View view = activity == null ? null : activity.findViewById(id); return view != null && view.isShown(); }
    private static boolean isVisible(int id) { AtomicBoolean value = new AtomicBoolean(); onMain(() -> value.set(visible(id))); return value.get(); }
    private static boolean isEnabled(int id) { AtomicBoolean value = new AtomicBoolean(); onMain(() -> value.set(enabled(id))); return value.get(); }
    private static boolean hasText(String text) {
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (findText(root, text, false)) return true;
        return false;
    }
    private static boolean hasExactText(String text) {
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (findText(root, text, true)) return true;
        return false;
    }
    private static boolean findText(View view, String text, boolean exact) {
        if (view instanceof TextView) {
            String value = ((TextView) view).getText().toString();
            if (exact ? value.equals(text) : value.contains(text)) return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (findText(group.getChildAt(i), text, exact)) return true;
        }
        return false;
    }
    private static void tap(int id) { onMain(() -> {
        View view = resumed().findViewById(id);
        assertNotNull("Missing view " + id + " in " + resumed().getClass().getSimpleName(), view);
        assertTrue("Disabled " + resumed().getResources().getResourceEntryName(id) + " in "
                + resumed().getClass().getSimpleName() + ": " + (view instanceof TextView ? ((TextView) view).getText() : ""), view.isEnabled());
        view.performClick();
    }); }
    /** Tránh tọa độ tap bị lệch trong lúc cửa sổ BottomSheet đang animate. */
    private static void tapText(String text) {
        onMain(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                View button = findButton(root, text);
                if (button != null) { button.performClick(); return; }
            }
            fail("Missing enabled button: " + text);
        });
    }
    private static View findButton(View view, String text) {
        if (view instanceof com.google.android.material.button.MaterialButton && view.isShown() && view.isEnabled()
                && text.equals(((TextView) view).getText().toString())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findButton(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static void fillOtp(String code) { onMain(() -> {
        int[] ids = {R.id.edtOtp1, R.id.edtOtp2, R.id.edtOtp3, R.id.edtOtp4, R.id.edtOtp5, R.id.edtOtp6};
        for (int i = 0; i < ids.length; i++) ((EditText) resumed().findViewById(ids[i])).setText(String.valueOf(code.charAt(i)));
    }); }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        AtomicBoolean done = new AtomicBoolean();
        while (!done.get() && System.nanoTime() < deadline) { onMain(() -> done.set(condition.getAsBoolean())); if (!done.get()) Thread.sleep(30); }
        assertTrue("Timed out waiting for API screen state", done.get());
    }

    private final class TestSocket implements WebSocket {
        final Request request; final WebSocketListener listener;
        TestSocket(Request request, WebSocketListener listener) { this.request = request; this.listener = listener; }
        public Request request() { return request; } public long queueSize() { return 0; }
        public boolean send(String text) {
            JsonObject packet = JsonParser.parseString(text).getAsJsonObject();
            if ("PING".equals(packet.get("type").getAsString())) emit("{\"type\":\"PONG\"}");
            return true;
        }
        public boolean send(ByteString bytes) { return true; }
        public boolean close(int code, String reason) { return true; }
        public void cancel() { }
        void emit(String text) { main.post(() -> listener.onMessage(this, text)); }
    }
}
