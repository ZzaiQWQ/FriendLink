package zz.friendlink.friends;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import zz.friendlink.friends.model.FriendActionRequest;
import zz.friendlink.friends.model.FriendData;
import zz.friendlink.friends.model.FriendsListResponse;
import zz.friendlink.friends.model.JoinInfoUpdate;
import zz.friendlink.friends.model.PresenceRequest;
import zz.friendlink.friends.model.PresenceResponse;
import zz.friendlink.i18n.P2PTexts;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class OfficialFriendsClient {
    private static final URI FRIENDS_URI = URI.create("https://api.minecraftservices.com/friends");
    private static final URI PRESENCE_URI = URI.create("https://api.minecraftservices.com/presence");
    private static final URI ATTRIBUTES_URI = URI.create("https://api.minecraftservices.com/player/attributes");
    private static final String PROFILE_BY_NAME_URI = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(UUID.class, (com.google.gson.JsonDeserializer<UUID>) (json, type, context) -> {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            return parseUuid(json.getAsString());
        })
        .registerTypeAdapter(UUID.class, (com.google.gson.JsonSerializer<UUID>) (uuid, type, context) ->
            uuid == null ? null : context.serialize(uuid.toString().replace("-", "")))
        .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, type, context) -> {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            String value = json.getAsString();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                try {
                    long epoch = Long.parseLong(value);
                    return epoch > 100_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                } catch (NumberFormatException exception) {
                    throw new JsonParseException("Invalid instant: " + value, exception);
                }
            }
        })
        .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (instant, type, context) ->
            instant == null ? null : context.serialize(instant.toString()))
        .create();

    private final Gson gson = GSON;
    private final String accessToken;
    private final HttpClient httpClient;
    private String friendsEtag;
    private String presenceEtag;
    private FriendData friendsCache = FriendData.empty();
    private PresenceResponse presenceCache = PresenceResponse.empty();

    public OfficialFriendsClient(String accessToken, ProxySelector proxySelector) {
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .proxy(proxySelector)
            .build();
    }

    public FriendData getFriendData() {
        HttpRequest.Builder builder = authed(FRIENDS_URI).GET();
        if (friendsEtag != null && !friendsEtag.isBlank()) {
            builder.header("If-None-Match", friendsEtag);
        }

        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() == 304) {
            return friendsCache;
        }

        ensureSuccess(response, "friends list");
        friendsEtag = response.headers().firstValue("ETag").orElse(friendsEtag);
        FriendsListResponse body = gson.fromJson(response.body(), FriendsListResponse.class);
        friendsCache = body == null ? FriendData.empty() : body.toFriendData();
        return friendsCache;
    }

    public FriendData putFriendAction(FriendActionRequest actionRequest) {
        String json = gson.toJson(actionRequest);
        HttpResponse<String> response = send(authed(FRIENDS_URI)
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .header("Content-Type", "application/json")
            .build());
        ensureSuccess(response, "friend action");
        friendsEtag = null;
        FriendsListResponse body = gson.fromJson(response.body(), FriendsListResponse.class);
        friendsCache = body == null ? FriendData.empty() : body.toFriendData();
        return friendsCache;
    }

    public FriendData addFriendByName(String name) {
        String cleanedName = name == null ? "" : name.trim();
        if (cleanedName.isBlank()) {
            throw new OfficialFriendsException(P2PTexts.s("status.type_player_name"), null);
        }
        return putFriendAction(FriendActionRequest.addByName(cleanedName));
    }

    public FriendData acceptFriendRequest(UUID profileId) {
        return putFriendAction(FriendActionRequest.addById(profileId));
    }

    public FriendData declineFriendRequest(UUID profileId) {
        return putFriendAction(FriendActionRequest.removeById(profileId));
    }

    public FriendData revokeFriendRequest(UUID profileId) {
        return putFriendAction(FriendActionRequest.removeById(profileId));
    }

    public FriendData removeFriend(UUID profileId) {
        return putFriendAction(FriendActionRequest.removeById(profileId));
    }

    public ProfileLookup lookupProfileByName(String name) {
        String cleanedName = name == null ? "" : name.trim();
        if (cleanedName.isBlank()) {
            throw new OfficialFriendsException(P2PTexts.s("status.type_player_name"), null);
        }

        URI uri = URI.create(PROFILE_BY_NAME_URI + cleanedName);
        HttpResponse<String> response = send(authed(uri).GET().build());
        ensureSuccess(response, "profile lookup");
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        if (json == null || !json.has("id")) {
            throw new OfficialFriendsException(P2PTexts.s("error.unknown_profile"), null);
        }
        String resolvedName = json.has("name") && !json.get("name").isJsonNull()
            ? json.get("name").getAsString()
            : cleanedName;
        return new ProfileLookup(parseUuid(json.get("id").getAsString()), resolvedName);
    }

    public PresenceResponse presence(String status, JoinInfoUpdate joinInfoUpdate) {
        String json = gson.toJson(new PresenceRequest(status, joinInfoUpdate));
        HttpRequest.Builder builder = authed(PRESENCE_URI)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .header("Content-Type", "application/json");
        if (presenceEtag != null && !presenceEtag.isBlank()) {
            builder.header("If-None-Match", presenceEtag);
        }

        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() == 304) {
            return presenceCache;
        }

        ensureSuccess(response, "presence");
        presenceEtag = response.headers().firstValue("ETag").orElse(presenceEtag);
        PresenceResponse body = gson.fromJson(response.body(), PresenceResponse.class);
        presenceCache = body == null ? PresenceResponse.empty() : body.safeCopy();
        return presenceCache;
    }

    public JsonObject updateFriendSettings(boolean friendsEnabled, boolean acceptInvites) {
        JsonObject friendsPreferences = new JsonObject();
        friendsPreferences.addProperty("friends", friendsEnabled ? "ENABLED" : "DISABLED");
        friendsPreferences.addProperty("acceptInvites", acceptInvites ? "ENABLED" : "DISABLED");

        JsonObject request = new JsonObject();
        request.add("friendsPreferences", friendsPreferences);

        HttpResponse<String> response = send(authed(ATTRIBUTES_URI)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(request)))
            .header("Content-Type", "application/json")
            .build());
        ensureSuccess(response, "friend settings");
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private HttpRequest.Builder authed(URI uri) {
        return HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .header("User-Agent", "Minecraft FriendLink/0.1");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new OfficialFriendsException("Network error while calling " + request.uri(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OfficialFriendsException("Interrupted while calling " + request.uri(), exception);
        }
    }

    private static void ensureSuccess(HttpResponse<String> response, String operation) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw OfficialFriendsException.fromHttpStatus(operation, status, response.body());
    }

    private static UUID parseUuid(String value) {
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (cleaned.length() == 32 && cleaned.chars().allMatch(character ->
            (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
            cleaned = cleaned.substring(0, 8)
                + "-" + cleaned.substring(8, 12)
                + "-" + cleaned.substring(12, 16)
                + "-" + cleaned.substring(16, 20)
                + "-" + cleaned.substring(20);
        }
        return UUID.fromString(cleaned);
    }

    public record ProfileLookup(UUID profileId, String name) {
    }
}
