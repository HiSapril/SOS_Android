package com.javafinal.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Transport bất đồng bộ dùng OkHttp/Gson đã có trong project. Không tự retry POST. */
public final class ApiClient {
    public interface Result<T> {
        void onSuccess(T value);
        void onError(ApiError error);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HttpUrl baseUrl;
    private final OkHttpClient http;
    private final Gson gson;
    private final SessionStore session;
    private final Executor callbackExecutor;

    public ApiClient(String baseUrl, SessionStore session, Executor callbackExecutor) {
        this(baseUrl, session, callbackExecutor, new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), new Gson());
    }

    public ApiClient(String baseUrl, SessionStore session, Executor callbackExecutor,
                     OkHttpClient http, Gson gson) {
        this.baseUrl = HttpUrl.get(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.session = session;
        this.callbackExecutor = callbackExecutor;
        this.http = http;
        this.gson = gson;
    }

    public SessionStore getSession() { return session; }
    public Executor getCallbackExecutor() { return callbackExecutor; }
    public OkHttpClient getHttp() { return http; }
    public Gson getGson() { return gson; }

    public HttpUrl url(String relativePath) {
        HttpUrl result = baseUrl.resolve(relativePath);
        if (result == null || !result.host().equals(baseUrl.host()) || result.port() != baseUrl.port()
                || !result.scheme().equals(baseUrl.scheme())) {
            throw new IllegalArgumentException("Đường dẫn API không hợp lệ.");
        }
        return result;
    }

    /** Encode từng path segment, không nối thẳng ID do UI cung cấp vào URL. */
    public static String segment(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("ID không được để trống.");
        }
        return new HttpUrl.Builder().scheme("http").host("localhost")
                .addPathSegment(trimmed).build().encodedPath().substring(1);
    }

    public Call newCall(String method, String path, Object body, boolean authenticated) {
        Request.Builder request = new Request.Builder().url(url(path)).header("Accept", "application/json");
        if (authenticated) {
            String token = session.getAccessToken();
            if (token == null || token.isEmpty()) throw new SecurityException("Vui lòng đăng nhập lại.");
            request.header("Authorization", "Bearer " + token);
        }
        RequestBody payload = body == null ? null : RequestBody.create(gson.toJson(body), JSON);
        if (payload == null && ("POST".equals(method) || "PUT".equals(method))) {
            payload = RequestBody.create("{}", JSON);
        }
        return http.newCall(request.method(method, payload).build());
    }

    public <T> void enqueue(Call call, Class<T> responseType, Result<T> callback) {
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failedCall, IOException exception) {
                deliver(call, () -> callback.onError(new ApiError(ApiError.Kind.NETWORK, 0,
                        "Không thể kết nối máy chủ. Vui lòng thử lại.", null, null)));
            }

            @Override public void onResponse(Call completedCall, Response response) {
                try (Response closedResponse = response) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        ApiError error = httpError(response.code(), body, response.header("Retry-After"));
                        String authorization = call.request().header("Authorization");
                        deliver(call, () -> {
                            if (response.code() == 401 && authorization != null) {
                                session.clearIfToken(authorization.substring("Bearer ".length()));
                            }
                            callback.onError(error);
                        });
                        return;
                    }
                    // DELETE có thể trả 204; các response còn lại phải là JSON object.
                    JsonElement json = response.code() == 204 ? new JsonObject() : JsonParser.parseString(body);
                    if (!json.isJsonObject()) throw new JsonParseException("Expected JSON object");
                    JsonObject object = json.getAsJsonObject();
                    if (object.has("success") && object.get("success").isJsonPrimitive()
                            && object.get("success").getAsJsonPrimitive().isBoolean()
                            && !object.get("success").getAsBoolean()) {
                        ApiError error = new ApiError(ApiError.Kind.BUSINESS, response.code(),
                                errorMessage(object, "Yêu cầu không thành công."), object.toString(), null);
                        deliver(call, () -> callback.onError(error));
                        return;
                    }
                    T value = gson.fromJson(json, responseType);
                    deliver(call, () -> callback.onSuccess(value));
                } catch (IOException exception) {
                    deliver(call, () -> callback.onError(new ApiError(ApiError.Kind.NETWORK, 0,
                            "Không đọc được phản hồi máy chủ.", null, null)));
                } catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
                    deliver(call, () -> callback.onError(new ApiError(ApiError.Kind.PARSE, response.code(),
                            "Phản hồi máy chủ không đúng định dạng JSON.", null, null)));
                }
            }
        });
    }

    private void deliver(Call call, Runnable callback) {
        callbackExecutor.execute(() -> { if (!call.isCanceled()) callback.run(); });
    }

    public static ApiError httpError(int code, String body, String retryAfter) {
        String fallback = "Yêu cầu thất bại (HTTP " + code + ").";
        String message = fallback;
        try {
            JsonElement json = JsonParser.parseString(body);
            if (json.isJsonObject()) message = errorMessage(json.getAsJsonObject(), fallback);
        } catch (JsonParseException | IllegalStateException ignored) { }
        return new ApiError(code == 401 ? ApiError.Kind.UNAUTHORIZED : ApiError.Kind.HTTP,
                code, message, body, retryAfter);
    }

    private static String errorMessage(JsonObject object, String fallback) {
        JsonElement detail = object.has("detail") ? object.get("detail") : object.get("message");
        if (detail == null || detail.isJsonNull()) return fallback;
        if (detail.isJsonPrimitive()) return detail.getAsString();
        if (detail.isJsonObject()) {
            JsonObject detailObj = detail.getAsJsonObject();
            JsonElement msg = detailObj.get("message");
            if (msg != null && msg.isJsonPrimitive()) return msg.getAsString();
        }
        // FastAPI 422 có detail là mảng {loc, msg, type}.
        if (detail.isJsonArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonElement item : detail.getAsJsonArray()) {
                if (result.length() > 0) result.append("; ");
                JsonElement message = item.isJsonObject() ? item.getAsJsonObject().get("msg") : null;
                if (message != null && message.isJsonPrimitive()) {
                    result.append(message.getAsString());
                } else result.append(item);
            }
            return result.length() == 0 ? fallback : result.toString();
        }
        return detail.toString();
    }
}
