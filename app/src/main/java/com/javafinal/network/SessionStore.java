package com.javafinal.network;

import com.javafinal.model.User;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Phiên dùng chung giữa các ViewModel; có thể dùng trực tiếp trong unit test. */
public class SessionStore {
    private String accessToken;
    private User user;
    private long version;
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    public synchronized String getAccessToken() { return accessToken; }
    public synchronized User getUser() { return user; }
    public synchronized long getVersion() { return version; }
    public synchronized boolean isLoggedIn() { return accessToken != null && !accessToken.isEmpty(); }

    public synchronized void save(String token, User user) {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Token không hợp lệ.");
        this.accessToken = token;
        this.user = user;
        version++;
        persist();
        notifyChanged();
    }

    public synchronized void updateUser(User user) {
        this.user = user;
        persist();
    }

    public synchronized void clear() {
        accessToken = null;
        user = null;
        version++;
        persist();
        notifyChanged();
    }

    /** Response 401 của phiên cũ không được xóa phiên mới. */
    public synchronized void clearIfToken(String token) {
        if (token != null && Objects.equals(accessToken, token)) clear();
    }

    protected void persist() { }
    protected synchronized void restore(String token, User user) {
        this.accessToken = token;
        this.user = user;
    }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void notifyChanged() { for (Runnable listener : listeners) listener.run(); }
}
