package zz.friendlink.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import zz.friendlink.FriendLinkClient;
import zz.friendlink.friends.OfficialFriendsClient;
import zz.friendlink.friends.OfficialFriendsException;
import zz.friendlink.friends.model.FriendData;
import zz.friendlink.friends.model.FriendDto;
import zz.friendlink.i18n.P2PTexts;
import zz.friendlink.util.Uuids;

import java.net.ProxySelector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class P2PConnectScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 190;
    private static final int MAX_PANEL_HEIGHT = 190;
    private static final int ROW_COUNT = 3;
    private static final int RENDER_ROW_COUNT = ROW_COUNT + 1;
    private static final int ROW_HEIGHT = 20;
    private static final int SCROLL_STEP = 6;
    private static final long SUCCESS_REFRESH_COOLDOWN_MS = 20_000L;
    private static final long FAILURE_REFRESH_COOLDOWN_MS = 120_000L;
    private static FriendData cachedFriendData = FriendData.empty();
    private static Component cachedStatus = P2PTexts.c("status.ready");
    private static OfficialFriendsClient cachedFriendsClient;
    private static String cachedUserKey = "";
    private static long nextFriendsFetchAt;

    private final Screen parent;
    private final List<Button> rowButtons = new ArrayList<>();
    private EditBox profileBox;
    private Button friendsTab;
    private Button requestsTab;
    private Button addButton;
    private Button refreshButton;
    private Button listenButton;
    private Button connectButton;
    private Button backButton;
    private FriendData friendData = FriendData.empty();
    private Tab activeTab = Tab.FRIENDS;
    private UUID selectedPeer;
    private String selectedName = "";
    private Component status = P2PTexts.c("status.ready");
    private boolean loadingFriends;
    private boolean closed;
    private int scrollPixels;

    public P2PConnectScreen(Screen parent) {
        super(P2PTexts.c("title.friends"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.closed = false;
        this.rowButtons.clear();
        ensureUserCache(this.minecraft.getUser());

        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        int tabWidth = (panelWidth - 28) / 2;

        this.friendsTab = this.addRenderableWidget(Button.builder(P2PTexts.c("title.friends"), button -> setTab(Tab.FRIENDS))
            .bounds(left + 14, top - 27, tabWidth, 25)
            .build());
        this.requestsTab = this.addRenderableWidget(Button.builder(requestsTitle(), button -> setTab(Tab.REQUESTS))
            .bounds(left + 14 + tabWidth, top - 27, tabWidth, 25)
            .build());

        this.profileBox = new EditBox(this.font, left + 14, top + 14, panelWidth - 42, 18, P2PTexts.c("input.player_or_host"));
        this.profileBox.setMaxLength(64);
        this.profileBox.setHint(P2PTexts.c("input.player_or_host"));
        this.addRenderableWidget(this.profileBox);

        this.addButton = this.addRenderableWidget(Button.builder(Component.literal("+"), button -> addFriend())
            .bounds(left + panelWidth - 24, top + 14, 16, 18)
            .build());

        for (int index = 0; index < RENDER_ROW_COUNT; index++) {
            int row = index;
            Button button = this.addRenderableWidget(Button.builder(Component.empty(), ignored -> selectVisibleRow(row))
                .bounds(left + 12, rowsTop() + row * ROW_HEIGHT, panelWidth - 24, 17)
                .build());
            this.rowButtons.add(button);
        }

        int bottom = top + panelHeight - 23;
        this.listenButton = this.addRenderableWidget(Button.builder(P2PTexts.c("button.listen"), button -> primaryAction())
            .bounds(left + 12, bottom, 40, 18)
            .build());
        this.connectButton = this.addRenderableWidget(Button.builder(P2PTexts.c("button.connect"), button -> secondaryAction())
            .bounds(left + 56, bottom, 40, 18)
            .build());
        this.refreshButton = this.addRenderableWidget(Button.builder(P2PTexts.c("button.refresh"), button -> tertiaryAction())
            .bounds(left + 100, bottom, 40, 18)
            .build());
        this.backButton = this.addRenderableWidget(Button.builder(P2PTexts.c("button.back"), button -> this.minecraft.setScreen(this.parent))
            .bounds(left + 144, bottom, 34, 18)
            .build());

        this.friendData = cachedFriendData;
        this.status = cachedStatus;
        updateWidgets();
        refreshFriends(false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = panelTop();
        this.extractMenuBackground(graphics);

        drawTabBackdrops(graphics, left, top);
        drawPanel(graphics, left, top);
        drawHeader(graphics, left, top);
        drawContent(graphics, left, top);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.closed = true;
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void removed() {
        this.closed = true;
    }

    private void setTab(Tab tab) {
        this.activeTab = tab;
        this.selectedPeer = null;
        this.selectedName = "";
        this.scrollPixels = 0;
        updateWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<FriendDto> rows = allRows();
        int maxScroll = maxScrollPixels(rows.size());
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int delta = scrollY < 0 ? SCROLL_STEP : -SCROLL_STEP;
        int nextScroll = Math.max(0, Math.min(maxScroll, this.scrollPixels + delta));
        if (nextScroll == this.scrollPixels) {
            return true;
        }
        this.scrollPixels = nextScroll;
        updateWidgets();
        return true;
    }

    private void refreshFriends(boolean manual) {
        Minecraft client = Minecraft.getInstance();
        User user = client.getUser();
        long now = System.currentTimeMillis();
        if (now < nextFriendsFetchAt) {
            long seconds = Math.max(1L, (nextFriendsFetchAt - now + 999L) / 1000L);
            this.status = P2PTexts.c("status.refresh_cooldown", manual ? P2PTexts.s("status.refresh") : P2PTexts.s("status.auto_refresh"), seconds);
            cachedStatus = this.status;
            updateWidgets();
            return;
        }

        this.loadingFriends = true;
        this.status = P2PTexts.c("status.loading_friends");
        cachedStatus = this.status;
        updateWidgets();

        CompletableFuture
            .supplyAsync(() -> friendsClient(user).getFriendData())
            .whenComplete((data, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                this.loadingFriends = false;
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    this.status = P2PTexts.c("status.friends_failed", userMessage(cause));
                    cachedStatus = this.status;
                    nextFriendsFetchAt = System.currentTimeMillis() + FAILURE_REFRESH_COOLDOWN_MS;
                    FriendLinkClient.LOGGER.warn("FriendLink friends UI refresh failed", cause);
                    updateWidgets();
                    return;
                }
                this.friendData = data == null ? FriendData.empty() : data;
                cachedFriendData = this.friendData;
                this.status = P2PTexts.c("status.friends_loaded");
                cachedStatus = this.status;
                nextFriendsFetchAt = System.currentTimeMillis() + SUCCESS_REFRESH_COOLDOWN_MS;
                updateWidgets();
            }));
    }

    private void addFriend() {
        Minecraft client = Minecraft.getInstance();
        String raw = this.profileBox.getValue().trim();
        if (raw.isBlank()) {
            this.status = P2PTexts.c("status.type_player_name");
            return;
        }
        try {
            this.selectedPeer = Uuids.parseFlexible(raw);
            this.selectedName = "";
            this.status = P2PTexts.c("status.selected_host");
            updateWidgets();
            return;
        } catch (IllegalArgumentException ignored) {
        }

        this.addButton.active = false;
        this.status = P2PTexts.c("status.sending_friend_request");
        CompletableFuture
            .supplyAsync(() -> friendsClient(client.getUser()).addFriendByName(raw))
            .whenComplete((data, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                this.addButton.active = true;
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    this.status = P2PTexts.c("status.friend_action_failed", userMessage(cause));
                    FriendLinkClient.LOGGER.warn("FriendLink friend action failed", cause);
                    return;
                }
                this.friendData = data == null ? FriendData.empty() : data;
                cachedFriendData = this.friendData;
                this.status = P2PTexts.c("status.friend_request_updated");
                cachedStatus = this.status;
                updateWidgets();
            }));
    }

    private void primaryAction() {
        if (this.activeTab == Tab.FRIENDS) {
            listen();
            return;
        }
        if (this.selectedPeer == null) {
            this.status = P2PTexts.c("status.no_request_selected");
            return;
        }
        if (isSelectedIncomingRequest()) {
            updateRequest(P2PTexts.c("status.request_accepting"),
                () -> friendsClient(this.minecraft.getUser()).acceptFriendRequest(this.selectedPeer));
            return;
        }
        if (isSelectedOutgoingRequest()) {
            updateRequest(P2PTexts.c("status.request_revoking"),
                () -> friendsClient(this.minecraft.getUser()).revokeFriendRequest(this.selectedPeer));
        }
    }

    private void secondaryAction() {
        if (this.activeTab == Tab.FRIENDS) {
            connect();
            return;
        }
        if (this.selectedPeer == null || !isSelectedIncomingRequest()) {
            this.status = P2PTexts.c("status.no_request_selected");
            return;
        }
        updateRequest(P2PTexts.c("status.request_declining"),
            () -> friendsClient(this.minecraft.getUser()).declineFriendRequest(this.selectedPeer));
    }

    private void tertiaryAction() {
        if (this.activeTab == Tab.FRIENDS && isSelectedFriend()) {
            removeSelectedFriend();
            return;
        }
        refreshFriends(true);
    }

    private void removeSelectedFriend() {
        if (this.selectedPeer == null) {
            this.status = P2PTexts.c("status.no_friend_selected");
            return;
        }
        updateFriendData(P2PTexts.c("status.friend_removing"), P2PTexts.c("status.friend_removed"),
            () -> friendsClient(this.minecraft.getUser()).removeFriend(this.selectedPeer));
    }

    private void updateRequest(Component pendingStatus, Supplier<FriendData> action) {
        updateFriendData(pendingStatus, P2PTexts.c("status.request_updated"), action);
    }

    private void updateFriendData(Component pendingStatus, Component successStatus, Supplier<FriendData> action) {
        Minecraft client = Minecraft.getInstance();
        this.listenButton.active = false;
        this.connectButton.active = false;
        this.refreshButton.active = false;
        this.addButton.active = false;
        this.status = pendingStatus;
        CompletableFuture
            .supplyAsync(action)
            .whenComplete((data, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    this.status = P2PTexts.c("status.friend_action_failed", userMessage(cause));
                    FriendLinkClient.LOGGER.warn("FriendLink friend request update failed", cause);
                    updateWidgets();
                    return;
                }
                this.friendData = data == null ? FriendData.empty() : data;
                cachedFriendData = this.friendData;
                this.selectedPeer = null;
                this.selectedName = "";
                this.status = successStatus;
                cachedStatus = this.status;
                updateWidgets();
            }));
    }

    private void listen() {
        Minecraft client = Minecraft.getInstance();
        this.listenButton.active = false;
        this.listenButton.setMessage(P2PTexts.c("button.listening"));
        P2PUiActions.listen(client, this::setStatus)
            .whenComplete((ignored, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                this.listenButton.active = true;
                this.listenButton.setMessage(P2PTexts.c("button.listen"));
            }));
    }

    private void connect() {
        Minecraft client = Minecraft.getInstance();
        UUID peerPmid = this.selectedPeer;
        String rawPeer = this.profileBox.getValue().trim();
        if (peerPmid == null && !rawPeer.isBlank()) {
            try {
                peerPmid = Uuids.parseFlexible(rawPeer);
            } catch (IllegalArgumentException exception) {
                resolveNameAndConnect(client, rawPeer);
                return;
            }
        }
        if (peerPmid == null) {
            P2PUiActions.status(client, this::setStatus, P2PTexts.s("status.no_friend_selected"));
            return;
        }

        this.connectButton.active = false;
        this.connectButton.setMessage(P2PTexts.c("button.connecting"));
        connectTo(client, peerPmid);
    }

    private void resolveNameAndConnect(Minecraft client, String playerName) {
        this.connectButton.active = false;
        this.connectButton.setMessage(P2PTexts.c("button.parsing"));
        this.status = P2PTexts.c("status.name_resolving");
        CompletableFuture
            .supplyAsync(() -> friendsClient(client.getUser()).lookupProfileByName(playerName))
            .whenComplete((profile, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                if (throwable != null) {
                    Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                    this.status = P2PTexts.c("status.name_resolve_failed", userMessage(cause));
                    this.connectButton.active = true;
                    this.connectButton.setMessage(P2PTexts.c("button.connect"));
                    return;
                }
                this.selectedPeer = profile.profileId();
                this.selectedName = profile.name();
                this.profileBox.setValue(profile.profileId().toString());
                this.status = P2PTexts.c("status.name_resolved", profile.name());
                connectTo(client, profile.profileId());
            }));
    }

    private void connectTo(Minecraft client, UUID peerPmid) {
        P2PUiActions.connect(client, peerPmid, this::setStatus)
            .whenComplete((ignored, throwable) -> client.execute(() -> {
                if (!isCurrent(client)) {
                    return;
                }
                this.connectButton.active = true;
                this.connectButton.setMessage(P2PTexts.c("button.connect"));
            }));
    }

    private void selectVisibleRow(int row) {
        List<FriendDto> rows = visibleRows();
        if (row < 0 || row >= rows.size()) {
            return;
        }
        FriendDto friend = rows.get(row);
        this.selectedPeer = friend.profileId();
        this.selectedName = friend.name() == null ? "" : friend.name();
        this.profileBox.setValue(this.selectedName);
        this.status = P2PTexts.c("status.selected", displayName(friend));
        updateWidgets();
    }

    private void updateWidgets() {
        if (this.friendsTab == null) {
            return;
        }
        clampScrollOffset();
        this.friendsTab.active = this.activeTab != Tab.FRIENDS;
        this.requestsTab.active = this.activeTab != Tab.REQUESTS;
        this.requestsTab.setMessage(requestsTitle());
        this.addButton.active = !this.loadingFriends;
        if (this.activeTab == Tab.FRIENDS) {
            this.listenButton.setMessage(P2PTexts.c("button.listen"));
            this.listenButton.active = true;
            this.connectButton.setMessage(P2PTexts.c("button.connect"));
            this.connectButton.active = true;
            this.refreshButton.setMessage(isSelectedFriend() ? P2PTexts.c("button.remove") : P2PTexts.c("button.refresh"));
            this.refreshButton.active = !this.loadingFriends && (isSelectedFriend() || System.currentTimeMillis() >= nextFriendsFetchAt);
        } else {
            boolean incoming = isSelectedIncomingRequest();
            boolean outgoing = isSelectedOutgoingRequest();
            this.listenButton.setMessage(outgoing ? P2PTexts.c("button.revoke") : P2PTexts.c("button.accept"));
            this.listenButton.active = !this.loadingFriends && (incoming || outgoing);
            this.connectButton.setMessage(P2PTexts.c("button.decline"));
            this.connectButton.active = !this.loadingFriends && incoming;
            this.refreshButton.setMessage(P2PTexts.c("button.refresh"));
            this.refreshButton.active = !this.loadingFriends && System.currentTimeMillis() >= nextFriendsFetchAt;
        }

        List<FriendDto> rows = visibleRows();
        int pixelRemainder = this.scrollPixels % ROW_HEIGHT;
        int listTop = rowsTop();
        int listBottom = listTop + ROW_COUNT * ROW_HEIGHT;
        for (int index = 0; index < this.rowButtons.size(); index++) {
            Button button = this.rowButtons.get(index);
            boolean visible = index < rows.size();
            int rowY = listTop + index * ROW_HEIGHT - pixelRemainder;
            boolean insideList = rowY > listTop - 15 && rowY < listBottom - 4;
            button.setY(rowY);
            button.visible = visible && insideList;
            button.active = visible && insideList;
            if (visible) {
                FriendDto friend = rows.get(index);
                String prefix = friend.profileId().equals(this.selectedPeer) ? "> " : "";
                button.setMessage(Component.literal(prefix + displayName(friend)));
            }
        }
    }

    private void setStatus(String message) {
        if (this.closed) {
            return;
        }
        this.status = Component.literal(message);
    }

    private void drawTabBackdrops(GuiGraphicsExtractor graphics, int left, int top) {
        int panelWidth = panelWidth();
        int tabWidth = (panelWidth - 28) / 2;
        int activeLeft = this.activeTab == Tab.FRIENDS ? left + 14 : left + 14 + tabWidth;
        graphics.fill(left + 14, top - 32, left + 14 + tabWidth * 2, top - 2, 0xFF1B1B1B);
        graphics.fill(activeLeft, top - 32, activeLeft + tabWidth, top - 2, 0xFF8E8E8E);
        graphics.outline(left + 14, top - 32, tabWidth, 30, 0xFF000000);
        graphics.outline(left + 14 + tabWidth, top - 32, tabWidth, 30, 0xFF000000);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int left, int top) {
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        graphics.fill(left - 4, top - 4, left + panelWidth + 4, top + panelHeight + 4, 0xFFDDDDDD);
        graphics.fill(left - 2, top - 2, left + panelWidth + 2, top + panelHeight + 2, 0xFF000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF343434);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFFFFFFFF);
        graphics.outline(left + 4, top + 4, panelWidth - 8, panelHeight - 8, 0xFF1C1C1C);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int left, int top) {
        int panelWidth = panelWidth();
        graphics.fill(left + 10, top + 8, left + panelWidth - 10, top + 46, 0xFF2A2A2A);
        graphics.text(this.font, P2PTexts.s("ui.profile", this.minecraft.getUser().getName()), left + 14, top + 35, 0xFFCFCFCF);
        int statusColor = this.status.getString().contains(P2PTexts.s("word.failed")) ? 0xFFFFFF55 : 0xFFCFCFCF;
        graphics.text(this.font, fit(this.status.getString(), panelWidth - 28), left + 14, top + 52, statusColor);
        graphics.fill(left + 8, top + 67, left + panelWidth - 8, top + 68, 0xFF1C1C1C);
    }

    private void drawContent(GuiGraphicsExtractor graphics, int left, int top) {
        List<FriendDto> rows = allRows();
        if (this.loadingFriends) {
            graphics.centeredText(this.font, P2PTexts.c("ui.loading_friends"), left + panelWidth() / 2, top + 92, 0xFFFFFFFF);
            return;
        }
        if (!rows.isEmpty()) {
            drawRows(graphics, left);
            drawScrollHint(graphics, left, top, rows.size());
            return;
        }

        int panelWidth = panelWidth();
        drawEmptyScene(graphics, left + panelWidth / 2 - 46, top + 70);
    }

    private void drawRows(GuiGraphicsExtractor graphics, int left) {
        int y = rowsTop();
        for (int index = 0; index < ROW_COUNT; index++) {
            int rowY = y + index * ROW_HEIGHT;
            int color = index % 2 == 0 ? 0xFF3F3F3F : 0xFF383838;
            graphics.fill(left + 10, rowY - 2, left + panelWidth() - 10, rowY + 19, color);
        }
    }

    private void drawEmptyScene(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x + 6, y + 34, x + 86, y + 44, 0xFF5FA236);
        graphics.fill(x + 6, y + 44, x + 34, y + 54, 0xFF6B4A2E);
        graphics.fill(x + 46, y + 44, x + 84, y + 50, 0xFF7C5432);
        graphics.fill(x + 30, y + 14, x + 54, y + 34, 0xFF2E8F2E);
        graphics.fill(x + 22, y + 24, x + 64, y + 38, 0xFF36A832);
        graphics.fill(x + 38, y + 32, x + 45, y + 49, 0xFF7A5433);
        graphics.fill(x + 40, y + 35, x + 48, y + 43, 0xFF94693B);
        graphics.outline(x + 40, y + 35, 8, 8, 0xFF5D3B20);
        graphics.fill(x + 64, y + 39, x + 78, y + 47, 0xFFF47A21);
        graphics.fill(x + 67, y + 36, x + 75, y + 41, 0xFFFF8B2B);
        graphics.fill(x + 75, y + 39, x + 81, y + 44, 0xFFFFFFFF);
        graphics.fill(x + 66, y + 44, x + 77, y + 49, 0xFFFFFFFF);
    }

    private List<FriendDto> visibleRows() {
        List<FriendDto> rows = allRows();
        int from = Math.min(this.scrollPixels / ROW_HEIGHT, rows.size());
        int to = Math.min(from + RENDER_ROW_COUNT, rows.size());
        return rows.subList(from, to);
    }

    private List<FriendDto> allRows() {
        if (this.activeTab == Tab.FRIENDS) {
            return this.friendData.friends();
        }
        List<FriendDto> requests = new ArrayList<>();
        requests.addAll(this.friendData.incomingRequests());
        requests.addAll(this.friendData.outgoingRequests());
        return requests;
    }

    private void clampScrollOffset() {
        this.scrollPixels = Math.max(0, Math.min(this.scrollPixels, maxScrollPixels(allRows().size())));
    }

    private int maxScrollPixels(int rowCount) {
        return Math.max(0, rowCount * ROW_HEIGHT - ROW_COUNT * ROW_HEIGHT);
    }

    private void drawScrollHint(GuiGraphicsExtractor graphics, int left, int top, int rowCount) {
        if (rowCount <= ROW_COUNT) {
            return;
        }
        int trackTop = rowsTop();
        int trackHeight = ROW_COUNT * ROW_HEIGHT - 3;
        int trackLeft = left + panelWidth() - 8;
        int maxOffset = Math.max(1, maxScrollPixels(rowCount));
        int thumbHeight = Math.max(8, trackHeight * ROW_COUNT / rowCount);
        int thumbY = trackTop + (trackHeight - thumbHeight) * this.scrollPixels / maxOffset;
        graphics.fill(trackLeft, trackTop, trackLeft + 2, trackTop + trackHeight, 0xFF1F1F1F);
        graphics.fill(trackLeft, thumbY, trackLeft + 2, thumbY + thumbHeight, 0xFFBFBFBF);
    }

    private Component requestsTitle() {
        int count = this.friendData.incomingRequests().size() + this.friendData.outgoingRequests().size();
        return P2PTexts.c("ui.requests", count);
    }

    private boolean isSelectedIncomingRequest() {
        return this.selectedPeer != null && this.friendData.incomingRequests().stream()
            .anyMatch(friend -> friend.profileId().equals(this.selectedPeer));
    }

    private boolean isSelectedOutgoingRequest() {
        return this.selectedPeer != null && this.friendData.outgoingRequests().stream()
            .anyMatch(friend -> friend.profileId().equals(this.selectedPeer));
    }

    private boolean isSelectedFriend() {
        return this.selectedPeer != null && this.friendData.friends().stream()
            .anyMatch(friend -> friend.profileId().equals(this.selectedPeer));
    }

    private String displayName(FriendDto friend) {
        String name = friend.name();
        return name == null || name.isBlank() ? P2PTexts.s("ui.unknown_player") : fit(name, 130);
    }

    private String fit(String value, int maxWidth) {
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        return this.font.plainSubstrByWidth(value, Math.max(10, maxWidth - this.font.width("..."))) + "...";
    }

    private String userMessage(Throwable cause) {
        if (cause instanceof OfficialFriendsException friendsException) {
            return friendsException.userMessage();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private int panelLeft() {
        return (this.width - panelWidth()) / 2;
    }

    private int panelTop() {
        return Math.max(44, (this.height - panelHeight()) / 2);
    }

    private int rowsTop() {
        return panelTop() + 78;
    }

    private int panelWidth() {
        return Math.max(176, Math.min(MAX_PANEL_WIDTH, this.width - 220));
    }

    private int panelHeight() {
        return Math.max(170, Math.min(MAX_PANEL_HEIGHT, this.height - 210));
    }

    private boolean isCurrent(Minecraft client) {
        return !this.closed && client.screen == this;
    }

    private static synchronized OfficialFriendsClient friendsClient(User user) {
        ensureUserCache(user);
        return cachedFriendsClient;
    }

    private static synchronized void ensureUserCache(User user) {
        String key = user.getProfileId() + "|" + user.getAccessToken();
        if (cachedFriendsClient != null && key.equals(cachedUserKey)) {
            return;
        }
        cachedUserKey = key;
        cachedFriendsClient = new OfficialFriendsClient(user.getAccessToken(), ProxySelector.getDefault());
        cachedFriendData = FriendData.empty();
        cachedStatus = P2PTexts.c("status.ready");
        nextFriendsFetchAt = 0L;
    }

    private enum Tab {
        FRIENDS,
        REQUESTS
    }
}
