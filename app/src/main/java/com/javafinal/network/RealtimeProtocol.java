package com.javafinal.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.javafinal.model.WebSocketMessage;

/** Đặc tả WS dùng type, model cũ dùng event: chuẩn hóa tại ranh giới mạng. */
public final class RealtimeProtocol {
    private RealtimeProtocol() { }

    public static String ping() {
        JsonObject message = new JsonObject();
        message.addProperty("type", WebSocketMessage.EVENT_PING);
        return message.toString();
    }

    public static String subscribe(String sosId) {
        ApiClient.segment(sosId);
        JsonObject message = new JsonObject();
        message.addProperty("type", WebSocketMessage.EVENT_SUBSCRIBE_SOS);
        message.addProperty("sos_id", sosId.trim());
        return message.toString();
    }

    public static WebSocketMessage decode(Gson gson, String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new JsonParseException("WS message must be an object");
        JsonObject object = parsed.getAsJsonObject();
        if (object.has("type")) object.add("event", object.get("type"));
        WebSocketMessage message = gson.fromJson(object, WebSocketMessage.class);
        if (message.getEvent() == null || message.getEvent().trim().isEmpty()) {
            throw new JsonParseException("WS message missing type/event");
        }
        return message;
    }
}
