package com.javafinal.network;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.javafinal.model.User;

/** Chỉ giữ application context; không giữ Activity trong ViewModel. */
public final class PreferencesSessionStore extends SessionStore {
    public static final String FILE_NAME = "api_session";
    private final SharedPreferences preferences;
    private final Gson gson;

    public PreferencesSessionStore(Context context, Gson gson) {
        this.gson = gson;
        preferences = context.getApplicationContext().getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        try {
            restore(preferences.getString("token", null),
                    gson.fromJson(preferences.getString("user", null), User.class));
        } catch (JsonParseException exception) {
            clear();
        }
    }

    @Override protected void persist() {
        preferences.edit().putString("token", getAccessToken())
                .putString("user", gson.toJson(getUser())).apply();
    }
}
