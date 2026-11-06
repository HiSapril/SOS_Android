package com.javafinal.viewmodel;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javafinal.model.User;
import com.javafinal.model.WebSocketMessage;
import com.javafinal.network.ApiClient;
import com.javafinal.network.SessionStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

/** WebSocket thật với server loopback: kiểm tra handshake, frames, reconnect và cleanup. */
public class RealtimeViewModelTest {
    @Test public void connectsSubscribesReceivesEventsReconnectsAndStopsOnLogout() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable runnable) { runnable.run(); }
            @Override public void postToMainThread(Runnable runnable) { callbacks.add(runnable); }
            @Override public boolean isMainThread() { return true; }
        });
        SessionStore session = new SessionStore();
        session.save("token?&+", new User());
        try (LocalWebSocketServer server = new LocalWebSocketServer()) {
            ApiClient api = new ApiClient("http://127.0.0.1:" + server.port() + "/", session, callbacks::add);
            RealtimeViewModel vm = new RealtimeViewModel(api);
            try {
                List<WebSocketMessage> received = new ArrayList<>();
                vm.getMessages().observeForever(message -> { if (message != null) received.add(message); });
                vm.subscribeSos("s1");
                vm.connect();
                until(callbacks, () -> vm.getConnectionState().getValue() == RealtimeViewModel.ConnectionState.CONNECTED
                        && !server.frames.isEmpty());
                String requestLine = server.handshakes.poll();
                assertNotNull(requestLine);
                String path = requestLine.split(" ")[1];
                assertEquals("token?&+", HttpUrl.get("http://localhost" + path).queryParameter("token"));
                JsonObject subscription = JsonParser.parseString(server.frames.poll()).getAsJsonObject();
                assertEquals("SUBSCRIBE_SOS", subscription.get("type").getAsString());
                assertEquals("s1", subscription.get("sos_id").getAsString());
                assertFalse(subscription.has("event"));
                vm.ping();
                until(callbacks, () -> received.stream().anyMatch(message -> "PONG".equals(message.getEvent())));
                assertEquals("PING", JsonParser.parseString(server.frames.poll()).getAsJsonObject()
                        .get("type").getAsString());

                String[] events = {"NEW_SOS", "SOS_ACCEPTED", "STATUS_CHANGED", "LOCATION_BROADCAST",
                        "SOS_RELEASED", "QUEUE_UPDATED"};
                for (String event : events) {
                    server.send("{\"type\":\"" + event + "\",\"sos_id\":\"s1\",\"status\":\"RESCUING\"}");
                    until(callbacks, () -> received.stream().anyMatch(message -> event.equals(message.getEvent())));
                }

                server.dropConnection();
                until(callbacks, () -> server.connections.get() == 2 && !server.frames.isEmpty()
                        && vm.getConnectionState().getValue() == RealtimeViewModel.ConnectionState.CONNECTED);
                assertEquals("SUBSCRIBE_SOS", JsonParser.parseString(server.frames.poll()).getAsJsonObject()
                        .get("type").getAsString());
                server.send("{\"type\":\"SOS_COMPLETED\",\"sos_id\":\"s1\",\"status\":\"COMPLETED\"}");
                until(callbacks, () -> received.stream().anyMatch(message -> "SOS_COMPLETED".equals(message.getEvent())));

                // Room đã kết thúc không được đăng ký lại khi reconnect.
                server.dropConnection();
                until(callbacks, () -> server.connections.get() == 3
                        && vm.getConnectionState().getValue() == RealtimeViewModel.ConnectionState.CONNECTED);
                assertTrue(server.frames.isEmpty());
                server.send("{\"type\":\"STALE_EVENT\",\"sos_id\":\"s1\"}");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (callbacks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);
                assertFalse(callbacks.isEmpty());
                session.clear();
                until(callbacks, () -> vm.getConnectionState().getValue() == RealtimeViewModel.ConnectionState.DISCONNECTED);
                assertNull(vm.getMessages().getValue());
                assertFalse(received.stream().anyMatch(message -> "STALE_EVENT".equals(message.getEvent())));
            } finally {
                vm.onCleared();
                api.getHttp().dispatcher().executorService().shutdownNow();
                api.getHttp().connectionPool().evictAll();
            }
        } finally {
            ArchTaskExecutor.getInstance().setDelegate(null);
        }
    }

    private static void until(Queue<Runnable> callbacks, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Runnable action;
            while ((action = callbacks.poll()) != null) action.run();
            Thread.sleep(2);
        }
        assertTrue("Timed out waiting for WebSocket", condition.getAsBoolean());
    }

    private static final class LocalWebSocketServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private volatile Socket current;
        private volatile boolean closed;
        final AtomicInteger connections = new AtomicInteger();
        final Queue<String> handshakes = new ConcurrentLinkedQueue<>();
        final Queue<String> frames = new ConcurrentLinkedQueue<>();

        LocalWebSocketServer() throws IOException {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::run, "test-websocket-server");
            worker.setDaemon(true);
            worker.start();
        }
        int port() { return server.getLocalPort(); }

        private void run() {
            while (!closed) {
                try (Socket accepted = server.accept()) {
                    current = accepted;
                    accepted.setSoTimeout(10000);
                    InputStream input = accepted.getInputStream();
                    String headers = readHeaders(input);
                    handshakes.add(headers.split("\r\n")[0]);
                    String key = null;
                    for (String line : headers.split("\r\n")) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                            key = line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    accepted.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    accepted.getOutputStream().flush();
                    connections.incrementAndGet();
                    while (!closed) {
                        int opcode = input.read();
                        if (opcode == -1 || (opcode & 15) == 8) break;
                        int flags = input.read();
                        int length = flags & 127;
                        if (length == 126) length = (input.read() << 8) | input.read();
                        if (length > 8192) throw new IOException("Frame too large");
                        byte[] mask = (flags & 128) == 0 ? new byte[4] : readBytes(input, 4);
                        byte[] body = readBytes(input, length);
                        for (int i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
                        String frame = new String(body, StandardCharsets.UTF_8);
                        frames.add(frame);
                        if (frame.contains("\"PING\"")) send("{\"type\":\"PONG\"}");
                    }
                } catch (Exception ignored) {
                    // dropConnection() cố ý làm read() thất bại để test reconnect.
                }
            }
        }

        synchronized void send(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream output = current.getOutputStream();
            output.write(0x81);
            if (bytes.length < 126) output.write(bytes.length);
            else { output.write(126); output.write(bytes.length >>> 8); output.write(bytes.length & 255); }
            output.write(bytes);
            output.flush();
        }

        void dropConnection() throws IOException { if (current != null) current.close(); }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (bytes.size() < 16384) {
                int value = input.read();
                if (value == -1) throw new IOException("Unexpected EOF");
                bytes.write(value);
                byte[] data = bytes.toByteArray();
                int size = data.length;
                if (size >= 4 && data[size - 4] == 13 && data[size - 3] == 10
                        && data[size - 2] == 13 && data[size - 1] == 10) {
                    return new String(data, StandardCharsets.US_ASCII);
                }
            }
            throw new IOException("Headers too large");
        }

        private static byte[] readBytes(InputStream input, int count) throws IOException {
            byte[] result = new byte[count];
            int position = 0;
            while (position < count) {
                int read = input.read(result, position, count - position);
                if (read == -1) throw new IOException("Unexpected EOF");
                position += read;
            }
            return result;
        }

        @Override public void close() throws Exception {
            closed = true;
            dropConnection();
            server.close();
            worker.join(1000);
        }
    }
}
