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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * AutoMaceLegit 1.0.3 (MC 1.21.1, Mojang mappings)
 * Menú de configuración: tecla Right Shift (cambiable en Controles).
 * Config guardada en config/automace.json
 */
public class AutoMaceClient implements ClientModInitializer {

    // ---------- Config ----------
    public static class Config {
        public boolean enabled = true;
        public boolean onlyPlayers = false;
        public boolean autoSwap = true;
        public boolean swapBack = true;
        public float minFall = 1.5f;
        public int minDelay = 1;
        public int maxDelay = 3;

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
    private int delay = -1;
    private int swapBackIn = -1;
    private int prevSlot = -1;

    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "AutoMace: abrir menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "AutoMaceLegit"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---------- Lógica ----------
    private void tick(Minecraft mc) {
        while (menuKey.consumeClick()) mc.setScreen(new MenuScreen());

        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (swapBackIn >= 0 && --swapBackIn < 0 && prevSlot >= 0) {
            mc.player.getInventory().selected = prevSlot;
            prevSlot = -1;
        }

        if (mc.screen != null || !cfg.enabled) return;

        LivingEntity target = null;
        if (mc.player.fallDistance >= cfg.minFall && !mc.player.onGround()
                && mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity le && le.isAlive()
                && (!cfg.onlyPlayers || le instanceof Player)) {
            target = le;
        }

        if (target == null) {
            delay = -1;
            scheduleBack();
            return;
        }

        if (delay < 0) {
            delay = cfg.minDelay + rng.nextInt(Math.max(0, cfg.maxDelay - cfg.minDelay) + 1);
        }
        if (delay-- > 0) return;

        int maceSlot = findMace(mc);
        if (maceSlot < 0) return;

        Inventory inv = mc.player.getInventory();
        if (inv.selected != maceSlot) {
            if (!cfg.autoSwap) return;
            if (cfg.swapBack) prevSlot = inv.selected;
            inv.selected = maceSlot;
            delay = 1;
            return;
        }

        if (mc.player.getAttackStrengthScale(0.5f) < 1.0f) return;

        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        delay = -1;
        scheduleBack();
    }

    private void scheduleBack() {
        if (prevSlot >= 0 && swapBackIn < 0) swapBackIn = 3 + rng.nextInt(4);
    }

    private int findMace(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.MACE)) return i;
        }
        return -1;
    }

    // ---------- Menú ----------
    public static class MenuScreen extends Screen {
        public MenuScreen() {
            super(Component.literal("AutoMaceLegit"));
        }

        @Override
        protected void init() {
            Config c = cfg;
            int w = 200, x = width / 2 - w / 2, y = 35, gap = 24;

            addRenderableWidget(toggle(x, y, w, "Activado", () -> c.enabled, v -> c.enabled = v));
            addRenderableWidget(toggle(x, y += gap, w, "Solo jugadores", () -> c.onlyPlayers, v -> c.onlyPlayers = v));
            addRenderableWidget(toggle(x, y += gap, w, "Cambiar a maza auto", () -> c.autoSwap, v -> c.autoSwap = v));
            addRenderableWidget(toggle(x, y += gap, w, "Volver al slot anterior", () -> c.swapBack, v -> c.swapBack = v));
            addRenderableWidget(new Slider(x, y += gap, w, "Caida minima", 1.0, 10.0, false, c.minFall,
                    v -> c.minFall = v.floatValue()));
            addRenderableWidget(new Slider(x, y += gap, w, "Delay min (ticks)", 0, 10, true, c.minDelay,
                    v -> { c.minDelay = v.intValue(); if (c.maxDelay < c.minDelay) c.maxDelay = c.minDelay; }));
            addRenderableWidget(new Slider(x, y += gap, w, "Delay max (ticks)", 0, 10, true, c.maxDelay,
                    v -> { c.maxDelay = v.intValue(); if (c.minDelay > c.maxDelay) c.minDelay = c.maxDelay; }));
            addRenderableWidget(Button.builder(Component.literal("Listo"), b -> onClose())
                    .bounds(x, y + gap, w, 20).build());
        }

        private static Button toggle(int x, int y, int w, String name,
                                     BooleanSupplier get, Consumer<Boolean> set) {
            return Button.builder(label(name, get.getAsBoolean()), b -> {
                set.accept(!get.getAsBoolean());
                b.setMessage(label(name, get.getAsBoolean()));
            }).bounds(x, y, w, 20).build();
        }

        private static Component label(String name, boolean v) {
            return Component.literal(name + ": " + (v ? "ON" : "OFF"));
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float delta) {
            super.render(g, mx, my, delta);
            g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
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
