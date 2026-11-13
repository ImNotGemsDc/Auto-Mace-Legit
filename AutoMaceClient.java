package com.automace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * AutoMaceLegit 1.0.6 (MC 1.21.1, Mojang mappings)
 * Menú: Right Shift (cambiable en Controles). Config: config/automace.json
 */
public class AutoMaceClient implements ClientModInitializer {

    // ---------- Config ----------
    public static class Config {
        // Auto mace
        public boolean enabled = true;
        public boolean playersOnly = true;
        public boolean autoSwap = true;
        public boolean preSwap = true;
        public boolean swapBack = true;
        public int preSwapRange = 10;
        public float minFall = 1.5f;
        public int minDelay = 1;
        public int maxDelay = 3;
        // Macros (códigos GLFW, -1 = ninguna)
        public int pearlKey = -1;
        public int elytraKey = -1;
        public int windKey = -1;
        public int pearlWindDelay = 5;
        public int windJumpDelay = 3;

        static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve("automace.json");
        }

        static Config load() {
            try {
                Path p = file();
                if (Files.exists(p)) {
                    Config c = GSON.fromJson(Files.readString(p), Config.class);
                    if (c != null) return c;
                }
            } catch (Exception ignored) {}
            return new Config();
        }

        void save() {
            try {
                Files.writeString(file(), GSON.toJson(this));
            } catch (IOException ignored) {}
        }
    }

    static Config cfg = Config.load();
    static KeyMapping menuKey;

    private final Random rng = new Random();

    // estado auto mace
    private int delay = -1;
    private int swapBackIn = -1;
    private int prevSlot = -1;

    // estado macros
    private record Step(Consumer<Minecraft> run, int waitAfter) {}
    private final ArrayDeque<Step> queue = new ArrayDeque<>();
    private int macroWait = 0;
    private boolean pearlWas, elytraWas, windWas;

    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "AutoMace: abrir menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "AutoMaceLegit"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---------- Tick ----------
    private void tick(Minecraft mc) {
        while (menuKey.consumeClick()) mc.setScreen(new MenuScreen());

        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (swapBackIn >= 0 && --swapBackIn < 0 && prevSlot >= 0) {
            mc.player.getInventory().selected = prevSlot;
            prevSlot = -1;
        }

        if (mc.screen != null) return;

        // ----- macros -----
        boolean p = down(mc, cfg.pearlKey);
        boolean e = down(mc, cfg.elytraKey);
        boolean w = down(mc, cfg.windKey);
        if (queue.isEmpty() && macroWait <= 0) {
            if (p && !pearlWas) pearlCatch(mc);
            else if (e && !elytraWas) elytraSwap(mc);
            else if (w && !windWas) windJump(mc);
        }
        pearlWas = p;
        elytraWas = e;
        windWas = w;

        if (macroWait > 0 && --macroWait > 0) return;
        Step s = queue.poll();
        if (s != null) {
            s.run().accept(mc);
            macroWait = s.waitAfter();
            return;
        }

        // ----- auto mace -----
        if (!cfg.enabled) return;
        autoMace(mc);
    }

    private void autoMace(Minecraft mc) {
        boolean falling = mc.player.fallDistance >= cfg.minFall && !mc.player.onGround();

        LivingEntity target = null;
        if (falling && mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity le && le.isAlive()
                && (!cfg.playersOnly || le instanceof Player)) {
            target = le;
        }

        boolean wantMace = target != null
                || (falling && cfg.preSwap && playerNear(mc, cfg.preSwapRange));

        if (!wantMace) {
            delay = -1;
            scheduleBack();
            return;
        }

        swapBackIn = -1;

        int maceSlot = findHotbar(mc, Items.MACE);
        if (maceSlot < 0) return;

        Inventory inv = mc.player.getInventory();
        if (inv.selected != maceSlot) {
            if (!cfg.autoSwap) return;
            if (cfg.swapBack && prevSlot < 0) prevSlot = inv.selected;
            inv.selected = maceSlot;
        }

        if (target == null) {
            delay = -1; // ya cambiado a la maza, esperando a que la mira llegue al jugador
            return;
        }

        if (delay < 0) {
            delay = cfg.minDelay + rng.nextInt(Math.max(0, cfg.maxDelay - cfg.minDelay) + 1);
        }
        if (delay-- > 0) return;


        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        delay = -1;
        scheduleBack();
    }

    private boolean playerNear(Minecraft mc, double range) {
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (mc.player.distanceToSqr(p) <= range * range) return true;
        }
        return false;
    }

    private void scheduleBack() {
        if (prevSlot >= 0 && swapBackIn < 0) swapBackIn = 3 + rng.nextInt(4);
    }

    // ---------- Macros ----------
    private void enqueue(Consumer<Minecraft> run, int waitAfter) {
        queue.add(new Step(run, waitAfter));
    }

    private boolean down(Minecraft mc, int key) {
        return key > 0 && InputConstants.isKeyDown(mc.getWindow().getWindow(), key);
    }

    private int findHotbar(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private void select(Minecraft mc, int slot) {
        mc.player.getInventory().selected = slot;
    }

    private void use(Minecraft mc) {
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** Pearl catch: lanza la perla y luego una wind charge. */
    private void pearlCatch(Minecraft mc) {
        int pearl = findHotbar(mc, Items.ENDER_PEARL);
        int wind = findHotbar(mc, Items.WIND_CHARGE);
        if (pearl < 0) return;
        int orig = mc.player.getInventory().selected;

        enqueue(m -> select(m, pearl), 1);
        enqueue(this::use, Math.max(1, cfg.pearlWindDelay));
        if (wind >= 0) {
            enqueue(m -> select(m, wind), 1);
            enqueue(this::use, 1);
        }
        enqueue(m -> select(m, orig), 0);
    }

    /** Elytra swap: alterna entre elytra y peto. */
    private void elytraSwap(Minecraft mc) {
        Player pl = mc.player;
        boolean wearingElytra = pl.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        int src = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = pl.getInventory().getItem(i);
            boolean match = wearingElytra
                    ? (st.getItem() instanceof ArmorItem a && a.getEquipmentSlot() == EquipmentSlot.CHEST)
                    : st.is(Items.ELYTRA);
            if (match) {
                src = i;
                break;
            }
        }
        if (src < 0) return;
        final int slot = src < 9 ? 36 + src : src;

        enqueue(m -> click(m, slot), 1);
        enqueue(m -> click(m, 6), 1);
        enqueue(m -> click(m, slot), 0);
    }

    private void click(Minecraft mc, int menuSlot) {
        mc.gameMode.handleInventoryMouseClick(
                mc.player.inventoryMenu.containerId, menuSlot, 0, ClickType.PICKUP, mc.player);
    }

    /** Auto wind charge: salta y lanza la wind charge (mira hacia abajo tú mismo). */
    private void windJump(Minecraft mc) {
        int wind = findHotbar(mc, Items.WIND_CHARGE);
        if (wind < 0) return;
        int orig = mc.player.getInventory().selected;

        enqueue(m -> {
            select(m, wind);
            m.options.keyJump.setDown(true);
        }, 1);
        enqueue(m -> m.options.keyJump.setDown(false), Math.max(0, cfg.windJumpDelay));
        enqueue(this::use, 1);
        enqueue(m -> select(m, orig), 0);
    }

    // ---------- Menú ----------
    private static class KeyRow {
        final String name;
        final IntSupplier get;
        final IntConsumer set;
        Button button;

        KeyRow(String name, IntSupplier get, IntConsumer set) {
            this.name = name;
            this.get = get;
            this.set = set;
        }

        Component label(boolean listening) {
            if (listening) return Component.literal(name + ": > pulsa <");
            int k = get.getAsInt();
            String s = k > 0 ? InputConstants.Type.KEYSYM.getOrCreate(k).getDisplayName().getString() : "Ninguna";
            return Component.literal(name + ": " + s);
        }
    }

    public static class MenuScreen extends Screen {
        private static final int CW = 150;
        private int tab = 0;
        private int colL, colR;
        private KeyRow listening = null;

        public MenuScreen() {
            super(Component.literal("AutoMaceLegit"));
        }

        private int px(int i) { return i % 2 == 0 ? colL : colR; }
        private int py(int i) { return 56 + (i / 2) * 22; }

        @Override
        protected void init() {
            listening = null;
            colL = width / 2 - CW - 5;
            colR = width / 2 + 5;
            Config c = cfg;

            addRenderableWidget(Button.builder(Component.literal(tab == 0 ? "> Auto mace <" : "Auto mace"),
                    b -> { tab = 0; clearWidgets(); init(); }).bounds(colL, 28, CW, 20).build());
            addRenderableWidget(Button.builder(Component.literal(tab == 1 ? "> Macros <" : "Macros"),
                    b -> { tab = 1; clearWidgets(); init(); }).bounds(colR, 28, CW, 20).build());

            int i = 0;
            if (tab == 0) {
                toggle(i++, "Auto mace", () -> c.enabled, v -> c.enabled = v);
                toggle(i++, "Solo jugadores", () -> c.playersOnly, v -> c.playersOnly = v);
                toggle(i++, "Cambio auto a maza", () -> c.autoSwap, v -> c.autoSwap = v);
                toggle(i++, "Pre-cambio", () -> c.preSwap, v -> c.preSwap = v);
                toggle(i++, "Volver al slot", () -> c.swapBack, v -> c.swapBack = v);
                slider(i++, "Rango pre-cambio", 3, 20, true, c.preSwapRange, v -> c.preSwapRange = v.intValue());
                slider(i++, "Caida minima", 1.0, 10.0, false, c.minFall, v -> c.minFall = v.floatValue());
                slider(i++, "Delay min", 0, 10, true, c.minDelay,
                        v -> { c.minDelay = v.intValue(); if (c.maxDelay < c.minDelay) c.maxDelay = c.minDelay; });
                slider(i++, "Delay max", 0, 10, true, c.maxDelay,
                        v -> { c.maxDelay = v.intValue(); if (c.minDelay > c.maxDelay) c.minDelay = c.maxDelay; });
            } else {
                keyRow(i++, "Pearl catch", () -> c.pearlKey, v -> c.pearlKey = v);
                keyRow(i++, "Elytra swap", () -> c.elytraKey, v -> c.elytraKey = v);
                keyRow(i++, "Wind charge", () -> c.windKey, v -> c.windKey = v);
                slider(i++, "Delay pearl-wind", 1, 20, true, c.pearlWindDelay, v -> c.pearlWindDelay = v.intValue());
                slider(i++, "Delay salto-wind", 0, 10, true, c.windJumpDelay, v -> c.windJumpDelay = v.intValue());
            }

            addRenderableWidget(Button.builder(Component.literal("Listo"), b -> onClose())
                    .bounds(width / 2 - 75, height - 26, 150, 20).build());
        }

        private void toggle(int idx, String name, BooleanSupplier get, Consumer<Boolean> set) {
            addRenderableWidget(Button.builder(label(name, get.getAsBoolean()), b -> {
                set.accept(!get.getAsBoolean());
                b.setMessage(label(name, get.getAsBoolean()));
            }).bounds(px(idx), py(idx), CW, 20).build());
        }

        private static Component label(String name, boolean v) {
            return Component.literal(name + ": " + (v ? "ON" : "OFF"));
        }

        private void slider(int idx, String name, double min, double max, boolean integer,
                            double cur, Consumer<Double> setter) {
            addRenderableWidget(new Slider(px(idx), py(idx), CW, name, min, max, integer, cur, setter));
        }

        private void keyRow(int idx, String name, IntSupplier get, IntConsumer set) {
            KeyRow row = new KeyRow(name, get, set);
            row.button = Button.builder(row.label(false), b -> {
                if (listening != null) listening.button.setMessage(listening.label(false));
                listening = row;
                row.button.setMessage(row.label(true));
            }).bounds(px(idx), py(idx), CW, 20).build();
            addRenderableWidget(row.button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (listening != null) {
                listening.set.accept(keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode);
                listening.button.setMessage(listening.label(false));
                listening = null;
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            super.render(g, mx, my, delta);
            g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
            if (tab == 1) {
                g.drawCenteredString(font,
                        Component.literal("Click en una tecla y pulsa la nueva (Esc = quitar)"),
                        width / 2, height - 42, 0xAAAAAA);
            }
        }

        @Override
        public void onClose() {
            cfg.save();
            super.onClose();
        }
    }

    private static class Slider extends AbstractSliderButton {
        private final String name;
        private final double min, max;
        private final boolean integer;
        private final Consumer<Double> setter;

        Slider(int x, int y, int w, String name, double min, double max,
               boolean integer, double cur, Consumer<Double> setter) {
            super(x, y, w, 20, Component.empty(), (cur - min) / (max - min));
            this.name = name;
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.setter = setter;
            updateMessage();
        }

        private double real() {
            double v = min + value * (max - min);
            return integer ? Math.round(v) : Math.round(v * 10) / 10.0;
        }

        @Override
        protected void updateMessage() {
            double r = real();
            setMessage(Component.literal(name + ": " + (integer ? String.valueOf((int) r) : String.valueOf(r))));
        }

        @Override
        protected void applyValue() {
            setter.accept(real());
        }
    }
}
