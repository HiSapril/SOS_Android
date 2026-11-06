package com.javafinal.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.javafinal.model.WebSocketMessage;
import com.javafinal.network.ApiClient;
import com.javafinal.network.ApiError;
import com.javafinal.network.RealtimeProtocol;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** API #26: auth, heartbeat JSON, reconnect và đăng ký lại các phòng SOS. */
public final class RealtimeViewModel extends BaseApiViewModel {
    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    private final MutableLiveData<ConnectionState> connection = new MutableLiveData<>(ConnectionState.DISCONNECTED);
    private final MutableLiveData<WebSocketMessage> messages = new MutableLiveData<>();
    private final MutableLiveData<ApiError> errors = new MutableLiveData<>();
    private final Set<String> subscriptions = new LinkedHashSet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sos-websocket-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable sessionListener;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> reconnect;
    private WebSocket socket;
    private boolean wanted;
    private boolean disposed;
    private int generation;
    private int retryCount;
    private String connectedToken;

    public RealtimeViewModel(ApiClient api) {
        super(api);
        sessionListener = () -> api.getCallbackExecutor().execute(() -> {
            if (!disposed && !Objects.equals(connectedToken, api.getSession().getAccessToken())) {
                disconnect();
                subscriptions.clear();
                messages.setValue(null);
            }
        });
        api.getSession().addListener(sessionListener);
    }

    public LiveData<ConnectionState> getConnectionState() { return connection; }
    public LiveData<WebSocketMessage> getMessages() { return messages; }
    public LiveData<ApiError> getErrors() { return errors; }

    public void connect() {
        if (disposed || wanted) return;
        if (!api.getSession().isLoggedIn()) {
            errors.setValue(ApiError.unauthorized());
            connection.setValue(ConnectionState.ERROR);
            return;
        }
        wanted = true;
        retryCount = 0;
        connectedToken = api.getSession().getAccessToken();
        open(++generation);
    }

    private void open(int attempt) {
        if (!wanted || disposed || attempt != generation) return;
        connection.setValue(retryCount == 0 ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING);
        Request request = new Request.Builder().url(api.url("ws").newBuilder()
                .addQueryParameter("token", connectedToken).build()).build();
        socket = api.getHttp().newWebSocket(request, new WebSocketListener() {
            private void dispatch(WebSocket webSocket, Runnable action) {
                api.getCallbackExecutor().execute(() -> {
                    if (!disposed && wanted && generation == attempt && socket == webSocket
                            && Objects.equals(connectedToken, api.getSession().getAccessToken())) action.run();
                });
            }

            @Override public void onOpen(WebSocket webSocket, Response response) {
                dispatch(webSocket, () -> {
                    retryCount = 0;
                    errors.setValue(null);
                    connection.setValue(ConnectionState.CONNECTED);
                    for (String id : subscriptions) webSocket.send(RealtimeProtocol.subscribe(id));
                    heartbeat = scheduler.scheduleWithFixedDelay(() -> api.getCallbackExecutor().execute(() -> {
                        if (attempt == generation && wanted && socket == webSocket) ping();
                    }), 25, 25, TimeUnit.SECONDS);
                });
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                dispatch(webSocket, () -> {
                    try {
                        WebSocketMessage message = RealtimeProtocol.decode(api.getGson(), text);
                        messages.setValue(message);
                        if (WebSocketMessage.EVENT_SOS_COMPLETED.equals(message.getEvent())
                                || WebSocketMessage.EVENT_SOS_CANCELLED.equals(message.getEvent())) {
                            subscriptions.remove(message.getSosId());
                        }
                    } catch (RuntimeException exception) {
                        errors.setValue(new ApiError(ApiError.Kind.PARSE, 0,
                                "Gói tin WebSocket không hợp lệ.", null, null));
                    }
                });
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                dispatch(webSocket, () -> {
                    if (code == 1008 || code == 4401 || code == 4403) {
                        stopForAuth(code == 4403 ? 403 : 401);
                    } else retry(attempt);
                });
            }

            @Override public void onFailure(WebSocket webSocket, Throwable exception, Response response) {
                dispatch(webSocket, () -> {
                    if (response != null && (response.code() == 401 || response.code() == 403)) {
                        stopForAuth(response.code());
                    } else {
                        errors.setValue(new ApiError(ApiError.Kind.NETWORK, response == null ? 0 : response.code(),
                                "Mất kết nối realtime. Đang kết nối lại.", null, null));
                        retry(attempt);
                    }
                });
            }
        });
    }

    private void stopForAuth(int code) {
        String token = connectedToken;
        disconnect();
        if (code == 401) api.getSession().clearIfToken(token);
        errors.setValue(new ApiError(code == 401 ? ApiError.Kind.UNAUTHORIZED : ApiError.Kind.HTTP,
                code, "Không có quyền kết nối realtime. Vui lòng kiểm tra phiên đăng nhập.", null, null));
        connection.setValue(ConnectionState.ERROR);
    }

    private void retry(int attempt) {
        if (heartbeat != null) heartbeat.cancel(false);
        if (!wanted || disposed || attempt != generation) return;
        socket = null;
        connection.setValue(ConnectionState.RECONNECTING);
        long delay = Math.min(30, 1L << Math.min(retryCount++, 5));
        int nextAttempt = ++generation;
        reconnect = scheduler.schedule(() -> api.getCallbackExecutor().execute(() -> open(nextAttempt)),
                delay, TimeUnit.SECONDS);
    }

    /** Có thể gọi trước connect(); phòng sẽ được đăng ký sau khi handshake thành công. */
    public void subscribeSos(String sosId) {
        if (disposed) return;
        try {
            String payload = RealtimeProtocol.subscribe(sosId);
            if (subscriptions.add(sosId.trim()) && connection.getValue() == ConnectionState.CONNECTED) {
                send(payload);
            }
        } catch (IllegalArgumentException exception) {
            errors.setValue(ApiError.validation(exception.getMessage()));
        }
    }

    public void ping() { send(RealtimeProtocol.ping()); }

    private void send(String payload) {
        if (socket == null || connection.getValue() != ConnectionState.CONNECTED || !socket.send(payload)) {
            errors.setValue(new ApiError(ApiError.Kind.NETWORK, 0,
                    "WebSocket chưa sẵn sàng gửi dữ liệu.", null, null));
        }
    }

    /** Gọi khi rời màn hình theo dõi nếu không cần giữ kết nối. */
    public void disconnect() {
        wanted = false;
        generation++;
        if (heartbeat != null) heartbeat.cancel(false);
        if (reconnect != null) reconnect.cancel(false);
        if (socket != null) {
            socket.close(1000, "Client disconnected");
            socket = null;
        }
        connection.setValue(ConnectionState.DISCONNECTED);
    }

    @Override protected void onCleared() {
        disposed = true;
        api.getSession().removeListener(sessionListener);
        disconnect();
        scheduler.shutdownNow();
        subscriptions.clear();
        super.onCleared();
    }
}
