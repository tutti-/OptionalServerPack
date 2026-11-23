package jp.tutti.optionalserverpack;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Workaround for Pixelmon's Poke Ball display breaking while a server
 * resource pack is being applied or removed: while a pack transition is
 * in flight, Poke Balls in the player's inventory are visually replaced
 * with a "Loading..." placeholder (packet-only; the server-side
 * inventory is never modified), and the real inventory is re-sent when
 * the client reports the transition as complete.
 *
 * <p>Restore is purely status-driven (see the plugin's
 * PlayerResourcePackStatusEvent listener): pushes report ACCEPTED and
 * then a terminal status after the reload. A plain pack REMOVAL reports
 * nothing at all on a vanilla 1.21.1 client (verified in the decompiled
 * ServerPackManager: RemovalReason.SERVER_REMOVED has a null
 * serverResponse), which is why this plugin turns OFF into a push of an
 * empty pack under the same UUID - a replacement goes through the full
 * push flow, so its completion is reported like any other push.
 *
 * <p>Everything below talks to the server internals (NMS) through
 * reflection only, so the plugin still compiles against the plain
 * Bukkit/Paper API and simply disables this feature when the expected
 * classes are not reachable (written for 1.21.1 Mojang-mapped servers
 * such as Arclight / Paper).
 */
final class BallGhostFix {

    private static final Set<String> COVERED_ITEMS =
            Set.of("pixelmon:poke_ball", "pixelmon:poke_ball_lid");

    private final OptionalServerPack plugin;
    volatile boolean enabled = true;

    private Boolean available; // null = not probed yet
    private final Set<UUID> ghosted = new HashSet<>();

    // Reflective handles, resolved lazily on first use.
    private Method getHandleMethod;
    private Field connectionField;
    private Method connectionSend;
    private Method getInventoryMethod;
    private Method invGetContainerSize;
    private Method invGetItem;
    private Method stackIsEmpty;
    private Method stackGetItem;
    private Method stackCopy;
    private Method stackSet;
    private Object itemRegistry;
    private Method registryGetKey;
    private Object barrierItem;
    private Constructor<?> stackCtor;
    private Object customNameType;
    private Object loreType;
    private Method componentLiteral;
    private Constructor<?> loreCtor;
    private Constructor<?> setSlotCtor;
    private Constructor<?> setContentCtor;
    private Method nonNullListCreate;
    private Field inventoryMenuField;
    private Method menuIncrementStateId;
    private Method menuGetItems;
    private Method menuGetCarried;

    BallGhostFix(OptionalServerPack plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Public operations (called from the plugin's pack push/remove paths
    // and from its resource-pack status listener)
    // ------------------------------------------------------------------

    /** Hide the balls because a pack transition is starting. */
    synchronized void swap(Player player) {
        if (!enabled || ghosted.contains(player.getUniqueId())) return;
        if (!ensureAvailable(player)) return;
        try {
            sendGhostPackets(player);
            ghosted.add(player.getUniqueId());
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Restore the real inventory: a terminal pack status arrived. */
    synchronized void restore(Player player) {
        if (!ghosted.remove(player.getUniqueId())) return;
        sendRestorePackets(player);
    }

    /** Forget a player without sending anything (e.g. they quit mid-transition). */
    synchronized void forget(Player player) {
        ghosted.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Packet work (reflection)
    // ------------------------------------------------------------------

    private void sendGhostPackets(Player player) throws Exception {
        Object handle = getHandleMethod.invoke(player);
        Object menu = inventoryMenuField.get(handle);
        Object inventory = getInventoryMethod.invoke(handle);
        int size = (int) invGetContainerSize.invoke(inventory);
        Object ghost = null;
        for (int i = 0; i < size; i++) {
            Object stack = invGetItem.invoke(inventory, i);
            if (stack == null || (boolean) stackIsEmpty.invoke(stack)) continue;
            Object key = registryGetKey.invoke(itemRegistry, stackGetItem.invoke(stack));
            if (key == null || !COVERED_ITEMS.contains(key.toString())) continue;
            if (ghost == null) ghost = createGhostItem();
            int stateId = (int) menuIncrementStateId.invoke(menu);
            sendPacket(handle, setSlotCtor.newInstance(0, stateId, toContainerSlot(i), ghost));
        }
    }

    private void sendRestorePackets(Player player) {
        if (!player.isOnline() || !ensureAvailable(player)) return;
        try {
            Object handle = getHandleMethod.invoke(player);
            Object menu = inventoryMenuField.get(handle);
            Object items = nonNullListCreate.invoke(null);
            @SuppressWarnings("unchecked")
            List<Object> copy = (List<Object>) items;
            for (Object stack : (List<?>) menuGetItems.invoke(menu)) {
                copy.add(stackCopy.invoke(stack));
            }
            Object carried = stackCopy.invoke(menuGetCarried.invoke(menu));
            int stateId = (int) menuIncrementStateId.invoke(menu);
            sendPacket(handle, setContentCtor.newInstance(0, stateId, items, carried));
        } catch (Throwable t) {
            fail(t);
        }
    }

    private Object createGhostItem() throws Exception {
        Object ghost = stackCtor.newInstance(barrierItem);
        stackSet.invoke(ghost, customNameType, componentLiteral.invoke(null, "Poke Ball Loading..."));
        Object lore = loreCtor.newInstance(List.of(componentLiteral.invoke(null, "Please wait.")));
        stackSet.invoke(ghost, loreType, lore);
        return ghost;
    }

    private void sendPacket(Object serverPlayer, Object packet) throws Exception {
        Object connection = connectionField.get(serverPlayer);
        connectionSend.invoke(connection, packet);
    }

    /** Map an inventory index to a slot of the player inventory container (window 0). */
    private static int toContainerSlot(int inventoryIndex) {
        if (inventoryIndex < 9) {
            return inventoryIndex + 36;          // hotbar
        } else if (inventoryIndex < 36) {
            return inventoryIndex;               // main inventory
        } else if (inventoryIndex < 40) {
            return 8 - (inventoryIndex - 36);    // armor: head..feet -> 5..8
        } else {
            return 45;                           // offhand
        }
    }

    private void fail(Throwable t) {
        if (Boolean.TRUE.equals(available)) {
            plugin.getLogger().warning("ポケボールの表示対策でエラーが発生したため、この機能を無効化します: " + t);
        }
        available = false;
    }

    private boolean ensureAvailable(Player probe) {
        if (available != null) return available;
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> itemStackClass = Class.forName("net.minecraft.world.item.ItemStack");

            getHandleMethod = probe.getClass().getMethod("getHandle");
            connectionField = serverPlayerClass.getField("connection");
            connectionSend = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                    .getMethod("send", packetClass);
            getInventoryMethod = serverPlayerClass.getMethod("getInventory");
            Class<?> inventoryClass = Class.forName("net.minecraft.world.entity.player.Inventory");
            invGetContainerSize = inventoryClass.getMethod("getContainerSize");
            invGetItem = inventoryClass.getMethod("getItem", int.class);
            stackIsEmpty = itemStackClass.getMethod("isEmpty");
            stackGetItem = itemStackClass.getMethod("getItem");
            stackCopy = itemStackClass.getMethod("copy");
            stackSet = itemStackClass.getMethod("set",
                    Class.forName("net.minecraft.core.component.DataComponentType"), Object.class);

            itemRegistry = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("ITEM").get(null);
            registryGetKey = itemRegistry.getClass().getMethod("getKey", Object.class);
            barrierItem = Class.forName("net.minecraft.world.item.Items").getField("BARRIER").get(null);
            stackCtor = itemStackClass.getConstructor(Class.forName("net.minecraft.world.level.ItemLike"));

            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            customNameType = dataComponents.getField("CUSTOM_NAME").get(null);
            loreType = dataComponents.getField("LORE").get(null);
            componentLiteral = Class.forName("net.minecraft.network.chat.Component")
                    .getMethod("literal", String.class);
            loreCtor = Class.forName("net.minecraft.world.item.component.ItemLore")
                    .getConstructor(List.class);

            setSlotCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket")
                    .getConstructor(int.class, int.class, int.class, itemStackClass);
            Class<?> nonNullListClass = Class.forName("net.minecraft.core.NonNullList");
            setContentCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket")
                    .getConstructor(int.class, int.class, nonNullListClass, itemStackClass);
            nonNullListCreate = nonNullListClass.getMethod("create");

            inventoryMenuField = Class.forName("net.minecraft.world.entity.player.Player")
                    .getField("inventoryMenu");
            Class<?> menuClass = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu");
            menuIncrementStateId = menuClass.getMethod("incrementStateId");
            menuGetItems = menuClass.getMethod("getItems");
            menuGetCarried = menuClass.getMethod("getCarried");

            available = true;
            plugin.getLogger().info("ポケボールの表示対策（パック切替中のダミー表示）を有効化しました。");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("ポケボールの表示対策を利用できませんでした（このサーバーでは無効になります）: " + t);
        }
        return available;
    }
}
