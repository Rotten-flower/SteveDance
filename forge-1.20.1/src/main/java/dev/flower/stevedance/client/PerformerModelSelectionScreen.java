package dev.flower.stevedance.client;

import com.mojang.authlib.GameProfile;

import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每个创作的角色一份草稿，带有可搜索的原生库模型预览网格。
 * <p>One draft per authored actor, with a searchable grid of native library model previews.</p>
 */
public final class PerformerModelSelectionScreen extends RhythmScreen {
    private record Entry(PerformerModelChoice choice, Component name) {}

    private final net.minecraft.client.gui.screens.Screen parent;
    private java.util.function.Consumer<PerformerModelChoice> photoChoice;
    private final String song;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, PerformerModelChoice> draft = new HashMap<>();
    private final Set<Integer> explicitActors = new HashSet<>();
    private final Map<PerformerModelChoice, RemotePlayer> previews =
            new LinkedHashMap<>(16, .75f, true);
    private final YsmModelPreview ysmPreviews = new YsmModelPreview();
    private final dev.flower.stevedance.client.YsmRigPreparation rigPreparation =
            new dev.flower.stevedance.client.YsmRigPreparation();
    private List<Integer> actors = List.of();
    private int actorIndex, selectedIndex;
    private boolean initialized;
    private net.minecraft.client.gui.components.EditBox search;
    private String query = "";
    private int category, scrollRow;
    private final Map<String, net.minecraft.world.entity.LivingEntity> maidPreviews =
            new LinkedHashMap<>();

    @Override
    protected void init() {
        search =
                new net.minecraft.client.gui.components.EditBox(
                        font, 0, 0, 260, 18, Component.translatable("ui.stevedance.model.search"));
        search.setBordered(false);
        search.setMaxLength(80);
        search.setValue(query);
        search.setTextColor(0xffefffff);
        search.setResponder(
                value -> {
                    query = value;
                    scrollRow = 0;
                });
        search.setHint(Component.translatable("ui.stevedance.model.search"));
    }

    @Override
    public void tick() {
        if (search != null) {
            search.tick();
        }
        for (var entity : maidPreviews.values()) {
            entity.tickCount++;
            entity.yRotO = entity.getYRot();
            entity.xRotO = entity.getXRot();
        }
        if (!entries.isEmpty()
                && !actors.isEmpty()
                && entries.get(selectedIndex).choice().kind() == PerformerModelChoice.Kind.YSM) {
            rigPreparation.tick(entries.get(selectedIndex).choice().modelId());
        } else {
            rigPreparation.close();
        }
    }

    private int group(PerformerModelChoice choice) {
        return switch (choice.kind()) {
            case YSM -> 1;
            case MAID -> 2;
            default -> 0;
        };
    }

    private List<Integer> filtered() {
        List<Integer> result = new ArrayList<>();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (group(e.choice()) == category
                    && (needle.isEmpty()
                            || e.name().getString().toLowerCase(Locale.ROOT).contains(needle)
                            || e.choice().modelId().toLowerCase(Locale.ROOT).contains(needle))) {
                result.add(i);
            }
        }
        return result;
    }

    private void revealSelection() {
        if (entries.isEmpty()) {
            return;
        }
        category = group(entries.get(selectedIndex).choice());
        var rows = filtered();
        int position = rows.indexOf(selectedIndex);
        if (position >= 0) {
            scrollRow = position / 5;
        }
    }

    private long nextLibraryPoll;
    private List<String> libraryIds = List.of();
    private List<String> maidIds = List.of();
    private List<dev.flower.stevedance.client.VanillaSkins.Entry> skinEntries = List.of();
    private List<String> playerIds = List.of();
    private String error = "";

    public PerformerModelSelectionScreen(SongSetupScreen parent) {
        super("ui.stevedance.model.title");
        this.parent = parent;
        song = RhythmClientController.song;
        MenuCharacterPresentation.stop();
        OriginalUiAssets.prepare();
        PerformerModelSelection.slots(song);
    }

    public PerformerModelSelectionScreen(
            net.minecraft.client.gui.screens.Screen parent,
            PerformerModelChoice selected,
            java.util.function.Consumer<PerformerModelChoice> callback) {
        super("ui.stevedance.model.title");
        this.parent = parent;
        this.song = "";
        this.photoChoice = callback;
        actors = List.of(0);
        draft.put(0, selected);
        MenuCharacterPresentation.stop();
        OriginalUiAssets.prepare();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    private void loadEntries() {
        long now = System.nanoTime();
        if (initialized && now < nextLibraryPoll) {
            return;
        }
        nextLibraryPoll = now + 1_000_000_000L;
        var ids = YsmModelLibraryBridge.modelIds();
        var maids = dev.flower.stevedance.content.MaidModels.ids();
        var skins = dev.flower.stevedance.client.VanillaSkins.entries();
        var players = dev.flower.stevedance.client.VanillaSkins.players();
        var playerKeys =
                players.stream()
                        .map(p -> p.getProfile().getId() + ":" + p.getProfile().getName())
                        .toList();
        if (initialized
                && ids.equals(libraryIds)
                && maids.equals(maidIds)
                && skins.equals(skinEntries)
                && playerKeys.equals(playerIds)) {
            return;
        }
        skinEntries = skins;
        playerIds = playerKeys;
        maidIds = maids;
        initialized = true;
        libraryIds = ids;
        PerformerModelSelection.libraryChanged();
        entries.clear();
        for (var kind :
                List.of(
                        PerformerModelChoice.Kind.STEVE,
                        PerformerModelChoice.Kind.PLAYER,
                        PerformerModelChoice.Kind.ALEX)) {
            entries.add(
                    new Entry(
                            new PerformerModelChoice(kind, ""),
                            Component.translatable(
                                    "ui.stevedance.model."
                                            + kind.name().toLowerCase(Locale.ROOT))));
        }
        for (var skin : skins) {
            entries.add(
                    new Entry(
                            new PerformerModelChoice(PerformerModelChoice.Kind.SKIN, skin.id()),
                            Component.literal(skin.name())));
        }
        for (var player : players) {
            entries.add(
                    new Entry(
                            new PerformerModelChoice(
                                    PerformerModelChoice.Kind.ONLINE_PLAYER,
                                    player.getProfile().getId().toString()),
                            Component.translatable(
                                    "ui.stevedance.model.online_player",
                                    player.getProfile().getName())));
        }
        for (String id : ids) {
            entries.add(
                    new Entry(
                            new PerformerModelChoice(PerformerModelChoice.Kind.YSM, id),
                            Component.literal(YsmModelLibraryBridge.displayName(id))));
        }
        for (String id : maids) {
            entries.add(
                    new Entry(
                            new PerformerModelChoice(PerformerModelChoice.Kind.MAID, id),
                            dev.flower.stevedance.content.MaidModels.displayName(id)));
        }
        if (!actors.isEmpty()) {
            selectActor(actorIndex);
        }
    }

    private void synchronizeActors() {
        if (photoChoice != null) {
            return;
        }
        var slots = PerformerModelSelection.slots(song);
        if (!slots.ready()) {
            return;
        }
        if (!slots.error().isEmpty()) {
            error = slots.error();
            return;
        }
        if (actors.equals(slots.actors())) {
            return;
        }
        actors = slots.actors();
        for (int actor : actors) {
            draft.putIfAbsent(actor, PerformerModelSelection.choice(song, actor));
        }
        selectActor(0);
    }

    private void selectActor(int index) {
        if (actors.isEmpty()) {
            return;
        }
        actorIndex = Math.floorMod(index, actors.size());
        var c = draft.get(actors.get(actorIndex));
        selectedIndex = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).choice().equals(c)) {
                selectedIndex = i;
                revealSelection();
                if (error.equals("ui.stevedance.model.unavailable")) {
                    error = "";
                }
                return;
            }
        }
        // 中文：不可用的已保存模型会保留在草稿中，直到玩家明确更改它。
        // EN: An unavailable saved model stays in the draft until the player explicitly changes it.
        error = "ui.stevedance.model.unavailable";
    }

    private void choose(int index) {
        if (entries.isEmpty() || actors.isEmpty()) {
            return;
        }
        selectedIndex = Math.floorMod(index, entries.size());
        draft.put(actors.get(actorIndex), entries.get(selectedIndex).choice());
        explicitActors.add(actors.get(actorIndex));
        error = "";
        RhythmSoundPlayer.play("select");
    }

    private Component actorName() {
        return Component.translatable(
                "ui.stevedance.model.actor", actors.isEmpty() ? 1 : actors.get(actorIndex) + 1);
    }

    private RemotePlayer preview(PerformerModelChoice choice) {
        var actor = previews.get(choice);
        if (actor != null) {
            return actor;
        }
        if (minecraft.level == null) {
            return null;
        }
        actor =
                new RemotePlayer(
                        minecraft.level, new GameProfile(UUID.randomUUID(), "SteveDancePreview"));
        actor.setPos(0, 0, 0);
        actor.setNoGravity(true);
        PerformerModelSelection.preview(actor, choice);
        previews.put(choice, actor);
        while (previews.size() > 8) {
            var first = previews.entrySet().iterator();
            var value = first.next();
            PerformerModelSelection.releasePreview(value.getValue());
            first.remove();
        }
        return actor;
    }

    private void drawModel(
            GuiGraphics g,
            PerformerModelChoice choice,
            int x,
            int bottom,
            int size,
            float mx,
            float my) {
        if (choice.kind() == PerformerModelChoice.Kind.MAID) {
            var entity =
                    maidPreviews.computeIfAbsent(
                            choice.modelId(),
                            dev.flower.stevedance.content.MaidModels::createPreview);
            while (maidPreviews.size() > 24) {
                maidPreviews.remove(maidPreviews.keySet().iterator().next());
            }
            if (entity != null) {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                        g,
                        x,
                        bottom,
                        Math.max(
                                1,
                                Math.round(
                                        size
                                                * dev.flower.stevedance.content.MaidModels
                                                        .previewScale(choice.modelId()))),
                        mx,
                        my,
                        entity);
            }
            return;
        }
        if (choice.kind() == PerformerModelChoice.Kind.YSM) {
            ysmPreviews.render(g, choice.modelId(), x, bottom, size);
            return;
        }
        var actor = preview(choice);
        if (actor == null) {
            return;
        }
        actor.tickCount = minecraft.player == null ? 0 : minecraft.player.tickCount;
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, x, bottom, size, mx, my, actor);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        loadEntries();
        synchronizeActors();
        start(g);
        OriginalUiAssets.loop(g, "song_setup", "bg", seconds());
        ModelSelectionStyle.label(this, g, title, 236, 42, 360, 2.4f, 0xff173e4b);
        ModelSelectionStyle.button(
                this,
                g,
                mx,
                my,
                440,
                35,
                54,
                42,
                "ui.stevedance.model.previous_actor",
                () -> selectActor(actorIndex - 1),
                actors.size() > 1);
        ModelSelectionStyle.label(this, g, actorName(), 630, 48, 250, 1.8f, 0xff173e4b);
        ModelSelectionStyle.button(
                this,
                g,
                mx,
                my,
                770,
                35,
                54,
                42,
                "ui.stevedance.model.next_actor",
                () -> selectActor(actorIndex + 1),
                actors.size() > 1);
        ModelSelectionStyle.label(
                this,
                g,
                Component.translatable(
                        "ui.stevedance.model.actor_count", actorIndex + 1, actors.size()),
                1075,
                48,
                270,
                1.5f,
                0xff173e4b);
        // 中文：共享窗口调色板；固定区域将预览和标签分开。
        // EN: Shared window palette; fixed zones keep previews and labels separate.
        ModelSelectionStyle.panel(g, 36, 105, 380, 540, 0xd9233946);
        ModelSelectionStyle.panel(g, 440, 105, 804, 540, 0xdc243a47);
        for (int i = 0; i < 3; i++) {
            final int c = i;
            int x = 458 + i * 150;
            boolean active = category == i;
            ModelSelectionStyle.panel(g, x, 121, 138, 36, active ? 0xff198f9d : 0xff354e5b);
            if (active) {
                g.fill(x, 154, x + 138, 157, 0xff7cffff);
            }
            ModelSelectionStyle.label(
                    this,
                    g,
                    Component.translatable("ui.stevedance.model.category." + i),
                    x + 69,
                    130,
                    126,
                    1.8f,
                    0xfff2ffff);
            controls.add(
                    new Control(
                            x,
                            121,
                            138,
                            36,
                            "category",
                            () -> {
                                category = c;
                                scrollRow = 0;
                            }));
        }
        ModelSelectionStyle.panel(g, 458, 174, 762, 40, 0xff162c38);
        g.fill(458, 212, 1220, 214, 0xff48c8d1);
        if (search != null) {
            g.pose().pushPose();
            g.pose().translate(475, 186, 0);
            g.pose().scale(2, 2, 1);
            search.setWidth(360);
            search.render(
                    g, (int) ((virtualX(mx) - 475) / 2), (int) ((virtualY(my) - 186) / 2), partial);
            g.pose().popPose();
        }
        var visible = filtered();
        int totalRows = (visible.size() + 4) / 5;
        scrollRow = Math.max(0, Math.min(Math.max(0, totalRows - 3), scrollRow));
        Component hoveredName = null;
        for (int cell = 0; cell < 15; cell++) {
            int pos = scrollRow * 5 + cell;
            if (pos >= visible.size()) {
                break;
            }
            int index = visible.get(pos);
            Entry e = entries.get(index);
            int x = 458 + (cell % 5) * 148, y = 238 + (cell / 5) * 123;
            boolean picked =
                    !actors.isEmpty() && e.choice().equals(draft.get(actors.get(actorIndex)));
            boolean hover =
                    virtualX(mx) >= x
                            && virtualX(mx) < x + 138
                            && virtualY(my) >= y
                            && virtualY(my) < y + 113;
            ModelSelectionStyle.panel(
                    g, x, y, 138, 113, picked ? 0xff255963 : hover ? 0xff365764 : 0xaa182f3b);
            ModelSelectionStyle.modelCorners(g, x, y, 138, 113, picked);
            g.enableScissor(
                    (int) ((width - 1280 * scale) / 2 + x * scale),
                    (int) ((height - 728 * scale) / 2 + y * scale),
                    (int) ((width - 1280 * scale) / 2 + (x + 138) * scale),
                    (int) ((height - 728 * scale) / 2 + (y + 91) * scale));
            drawModel(g, e.choice(), x + 69, y + 85, 37, 0, 0);
            g.disableScissor();
            ModelSelectionStyle.label(
                    this, g, cardName(e.name()), x + 69, y + 94, 126, 1.8f, 0xffeaffff);
            if (hover) {
                hoveredName = e.name();
            }
            controls.add(
                    new Control(x, y, 138, 113, "ui.stevedance.model.choose", () -> choose(index)));
        }
        if (visible.isEmpty()) {
            ModelSelectionStyle.label(
                    this,
                    g,
                    Component.translatable("ui.stevedance.model.no_results"),
                    830,
                    390,
                    680,
                    1.8f,
                    0xffd9eff2);
        }
        ModelSelectionStyle.label(
                this,
                g,
                Component.translatable("ui.stevedance.model.matches", visible.size()),
                610,
                618,
                290,
                1.6f,
                0xffc1e2e8);
        if (totalRows > 3) {
            g.fill(1210, 238, 1214, 597, 0xff405865);
            int h = Math.max(24, 359 * 3 / totalRows),
                    y = 238 + (359 - h) * scrollRow / (totalRows - 3);
            g.fill(1210, y, 1214, y + h, 0xff7aebed);
        }
        if (!actors.isEmpty() && !entries.isEmpty()) {
            Entry e = entries.get(selectedIndex);
            g.enableScissor(
                    (int) ((width - 1280 * scale) / 2 + 44 * scale),
                    (int) ((height - 728 * scale) / 2 + 165 * scale),
                    (int) ((width - 1280 * scale) / 2 + 408 * scale),
                    (int) ((height - 728 * scale) / 2 + 535 * scale));
            drawModel(
                    g,
                    e.choice(),
                    226,
                    517,
                    132,
                    (226 - virtualX(mx)) * .03f,
                    (300 - virtualY(my)) * .025f);
            g.disableScissor();
            ModelSelectionStyle.label(this, g, e.name(), 226, 550, 336, 1.9f, 0xfff0ffff);
            ModelSelectionStyle.label(
                    this,
                    g,
                    e.choice().kind() == PerformerModelChoice.Kind.MAID
                            ? dev.flower.stevedance.content.MaidModels.packName(
                                    e.choice().modelId())
                            : Component.translatable(
                                    "ui.stevedance.model.category." + group(e.choice())),
                    226,
                    590,
                    330,
                    1.6f,
                    0xff9cdbdf);
        }
        if (!slotsReady()) {
            ModelSelectionStyle.label(
                    this,
                    g,
                    Component.translatable("ui.stevedance.model.loading"),
                    226,
                    295,
                    330,
                    1.6f,
                    0xfff0ffff);
        }
        if (!error.isEmpty()) {
            ModelSelectionStyle.label(
                    this, g, Component.translatable(error), 640, 652, 1120, 1.3f, 0xff9e2350);
        }
        boolean ysmSelected =
                !entries.isEmpty()
                        && !actors.isEmpty()
                        && entries.get(selectedIndex).choice().kind()
                                == PerformerModelChoice.Kind.YSM;
        if (ysmSelected) {
            ModelSelectionStyle.label(
                    this, g, rigPreparation.status(), 226, 620, 336, 1.25f, 0xffb8edef);
        }
        ModelSelectionStyle.button(
                this,
                g,
                mx,
                my,
                44,
                676,
                168,
                38,
                ysmSelected ? "ui.stevedance.rig.folder" : "ui.stevedance.model.skin_folder",
                () -> {
                    var dir =
                            ysmSelected
                                    ? dev.flower.stevedance.client.YsmRigPreparation.directory()
                                    : dev.flower.stevedance.client.VanillaSkins.directory();
                    try {
                        java.nio.file.Files.createDirectories(dir);
                        net.minecraft.Util.getPlatform().openFile(dir.toFile());
                    } catch (java.io.IOException ex) {
                        dev.flower.stevedance.SteveDanceMod.LOGGER.warn(
                                "Cannot open model data folder", ex);
                    }
                },
                true);
        ModelSelectionStyle.button(
                this,
                g,
                mx,
                my,
                226,
                676,
                130,
                38,
                ysmSelected ? "ui.stevedance.rig.refresh" : "ui.stevedance.model.skin_refresh",
                () -> {
                    if (ysmSelected) {
                        rigPreparation.refresh();
                    } else {
                        dev.flower.stevedance.client.VanillaSkins.refresh();
                    }
                    nextLibraryPoll = 0;
                },
                true);
        ModelSelectionStyle.label(
                this,
                g,
                ysmSelected
                        ? Component.translatable("ui.stevedance.rig.hint")
                        : dev.flower.stevedance.client.VanillaSkins.status(),
                631,
                687,
                520,
                1.3f,
                0xff173e4b);
        ModelSelectionStyle.button(
                this,
                g,
                mx,
                my,
                916,
                676,
                150,
                38,
                "gui.done",
                this::confirm,
                !actors.isEmpty() && error.isEmpty());
        ModelSelectionStyle.button(
                this, g, mx, my, 1086, 676, 150, 38, "gui.cancel", this::onClose, true);
        finish(g);
        if (hoveredName != null) {
            g.renderTooltip(font, hoveredName, mx, my);
        }
    }

    @Override
    public void onFilesDrop(List<java.nio.file.Path> paths) {
        dev.flower.stevedance.client.VanillaSkins.importFiles(paths, hasShiftDown());
    }

    private Component cardName(Component name) {
        String text = name.getString();
        return font.width(text) <= 70
                ? name
                : Component.literal(font.plainSubstrByWidth(text, 60) + "…");
    }

    private boolean slotsReady() {
        return photoChoice != null || PerformerModelSelection.slots(song).ready();
    }

    private void moveSelection(int delta) {
        var list = filtered();
        if (list.isEmpty()) {
            return;
        }
        int index = list.indexOf(selectedIndex);
        int next = Math.floorMod((index < 0 ? 0 : index) + delta, list.size());
        choose(list.get(next));
        int row = next / 5;
        if (row < scrollRow) {
            scrollRow = row;
        } else if (row >= scrollRow + 3) {
            scrollRow = row - 2;
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (search != null) {
            boolean inside =
                    virtualX(x) >= 458
                            && virtualX(x) < 1220
                            && virtualY(y) >= 174
                            && virtualY(y) < 214;
            search.setFocused(inside);
            if (inside) {
                search.mouseClicked((virtualX(x) - 475) / 2, (virtualY(y) - 186) / 2, button);
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return search != null && search.isFocused()
                ? search.charTyped(c, mods)
                : super.charTyped(c, mods);
    }

    private void confirm() {
        if (actors.isEmpty() || !error.isEmpty()) {
            return;
        }
        if (draft.values().stream()
                .anyMatch(c -> entries.stream().noneMatch(e -> e.choice().equals(c)))) {
            error = "ui.stevedance.model.unavailable";
            return;
        }
        if (photoChoice != null) {
            photoChoice.accept(draft.get(0));
            returnToSong();
            return;
        }
        if (!PerformerModelSelection.save(song, draft, explicitActors)) {
            error = "ui.stevedance.model.save_failed";
            return;
        }
        RhythmSoundPlayer.play("select");
        returnToSong();
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 16 || bit == 32) {
            selectActor(actorIndex + (bit == 32 ? 1 : -1));
            return;
        }
        super.padPressed(bit);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (virtualX(x) < 440 || virtualX(x) > 1244 || virtualY(y) < 218 || virtualY(y) > 645) {
            return false;
        }
        scrollRow =
                Math.max(
                        0,
                        Math.min(
                                Math.max(0, (filtered().size() + 4) / 5 - 3),
                                scrollRow + (delta < 0 ? 1 : -1)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (search != null && search.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                search.setFocused(false);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER) {
                search.setFocused(false);
                return true;
            }
            return search.keyPressed(key, scan, modifiers);
        }
        if (key == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            search.setFocused(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT
                || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP
                || key == GLFW.GLFW_KEY_DOWN) {
            moveSelection(
                    key == GLFW.GLFW_KEY_LEFT
                            ? -1
                            : key == GLFW.GLFW_KEY_RIGHT ? 1 : key == GLFW.GLFW_KEY_UP ? -5 : 5);
            return true;
        }
        if (key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_E) {
            selectActor(actorIndex + (key == GLFW.GLFW_KEY_E ? 1 : -1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void returnToSong() {
        if (photoChoice == null) {
            MenuCharacterPresentation.begin("select");
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        RhythmSoundPlayer.play("cancel");
        returnToSong();
    }

    @Override
    public void removed() {
        rigPreparation.close();
        ysmPreviews.close();
        maidPreviews.clear();
        for (var actor : previews.values()) {
            PerformerModelSelection.releasePreview(actor);
        }
        previews.clear();
    }
}
