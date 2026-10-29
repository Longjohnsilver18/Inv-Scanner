package de.invscanner;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Geht alle Online-Spieler der Reihe nach durch: "/invsee Name" senden, Fenster abwarten,
 * Inhalt lesen, Fenster schließen. Läuft im Client-Tick, damit andere Bildschirme egal sind.
 */
public final class ScanEngine {
    enum Phase { IDLE, SEND, WAIT_OPEN, WAIT_READ }

    public static final Map<String, Map<Item, Integer>> DATA = new LinkedHashMap<>();
    public static int total, done, failed;
    public static long finishedAt;

    private static Phase phase = Phase.IDLE;
    private static final List<String> queue = new ArrayList<>();
    private static int index, sinceSend, waitTicks, readTicks;
    private static String current = "";
    private static long lastHash;

    public static boolean running() {
        return phase != Phase.IDLE;
    }

    public static boolean start(MinecraftClient mc) {
        if (mc.player == null || mc.getNetworkHandler() == null || running()) return false;
        String me = mc.player.getName().getString();
        Set<String> names = new LinkedHashSet<>();
        for (String n : mc.getNetworkHandler().getCommandSource().getPlayerNames()) {
            if (!n.equalsIgnoreCase(me)) names.add(n);
        }
        if (names.isEmpty()) return false;
        queue.clear();
        queue.addAll(names);
        DATA.clear();
        total = queue.size();
        done = 0;
        failed = 0;
        index = 0;
        sinceSend = 1000;
        phase = Phase.SEND;
        return true;
    }

    private static boolean isInvScreen(Screen s) {
        return s instanceof HandledScreen<?> h && !(h.getScreenHandler() instanceof PlayerScreenHandler);
    }

    public static void tick(MinecraftClient mc) {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.getNetworkHandler() == null) {
            phase = Phase.IDLE;
            return;
        }
        sinceSend++;
        Screen s = mc.currentScreen;
        switch (phase) {
            case SEND -> {
                if (s != null) {
                    cancel(mc);
                    return;
                }
                if (sinceSend >= Config.tempoTicks()) {
                    current = queue.get(index);
                    mc.getNetworkHandler().sendChatCommand(Config.data.command + " " + current);
                    sinceSend = 0;
                    waitTicks = 0;
                    phase = Phase.WAIT_OPEN;
                    mc.player.sendMessage(Text.literal("Inv Scanner: " + (index + 1) + "/" + total + "  " + current
                            + "   (ESC = Abbruch)"), true);
                }
            }
            case WAIT_OPEN -> {
                if (isInvScreen(s)) {
                    phase = Phase.WAIT_READ;
                    readTicks = 0;
                    lastHash = -1;
                } else if (s != null) {
                    cancel(mc);
                } else if (++waitTicks > 40) {
                    failed++;
                    advance(mc);
                }
            }
            case WAIT_READ -> {
                if (!isInvScreen(s)) {
                    cancel(mc);
                    return;
                }
                ScreenHandler sh = ((HandledScreen<?>) s).getScreenHandler();
                long hs = hash(mc, sh);
                readTicks++;
                if ((readTicks >= 4 && hs == lastHash) || readTicks >= 14) {
                    DATA.put(current, read(mc, sh));
                    done++;
                    mc.player.closeHandledScreen();
                    advance(mc);
                } else {
                    lastHash = hs;
                }
            }
            default -> {
            }
        }
    }

    private static void advance(MinecraftClient mc) {
        index++;
        if (index >= queue.size()) {
            phase = Phase.IDLE;
            finishedAt = System.currentTimeMillis();
            mc.player.sendMessage(Text.literal("Inv Scanner: fertig – " + done + " Spieler gescannt"
                    + (failed > 0 ? ", " + failed + " nicht erreichbar" : "")), true);
            mc.setScreen(new ResultsScreen());
        } else {
            phase = Phase.SEND;
        }
    }

    private static void cancel(MinecraftClient mc) {
        phase = Phase.IDLE;
        finishedAt = System.currentTimeMillis();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("Inv Scanner: abgebrochen – " + done + " Spieler gescannt. "
                    + "Teilergebnis über die Ä-Taste → Letzte Daten."), false);
        }
    }

    private static long hash(MinecraftClient mc, ScreenHandler sh) {
        long h = 0;
        for (Slot slot : sh.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack st = slot.getStack();
            if (!st.isEmpty()) h = h * 31 + System.identityHashCode(st.getItem()) * 7L + st.getCount();
        }
        return h;
    }

    private static Map<Item, Integer> read(MinecraftClient mc, ScreenHandler sh) {
        Map<Item, Integer> m = new HashMap<>();
        for (Slot slot : sh.slots) {
            if (slot.inventory == mc.player.getInventory()) continue; // eigenes Inventar überspringen
            ItemStack st = slot.getStack();
            if (st.isEmpty()) continue;
            m.merge(st.getItem(), st.getCount(), Integer::sum);
            if (Config.data.containers) {
                ContainerComponent cc = st.get(DataComponentTypes.CONTAINER);
                if (cc != null) {
                    for (ItemStack in : cc.iterateNonEmpty()) {
                        m.merge(in.getItem(), in.getCount() * st.getCount(), Integer::sum);
                    }
                }
            }
        }
        return m;
    }

    private ScanEngine() {}
}
