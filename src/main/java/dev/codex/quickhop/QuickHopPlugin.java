package dev.codex.quickhop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class QuickHopPlugin extends JavaPlugin implements Listener {
    private static final int MIN_TICKS = 1;
    private static final int MAX_TICKS = 1200;
    private static final int MAX_AMOUNT = 64;
    private int transferCooldown;
    private int transferAmount;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        transferCooldown = Math.max(MIN_TICKS, Math.min(MAX_TICKS, getConfig().getInt("transfer-cooldown", 8)));
        transferAmount = Math.max(1, Math.min(MAX_AMOUNT, getConfig().getInt("transfer-amount", 1)));
        getServer().getPluginManager().registerEvents(this, this);
        for (World world : Bukkit.getWorlds()) applyAmountToWorld(world);
        getLogger().info("Hopper transfer cooldown: " + transferCooldown + " tick(s).");
        getLogger().info("Hopper transfer amount: " + transferAmount + " item(s) per transfer.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hoppertick")) {
            return handleAmountCommand(sender, args);
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Hopper cooldown: " + transferCooldown + " tick(s). Use /hopperspeed <1-1200>.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /hopperspeed <1-1200>");
            return true;
        }
        final int requested;
        try {
            requested = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Enter a whole number from 1 to 1200 ticks.");
            return true;
        }
        if (requested < MIN_TICKS || requested > MAX_TICKS) {
            sender.sendMessage(ChatColor.RED + "Cooldown must be between 1 and 1200 ticks.");
            return true;
        }
        transferCooldown = requested;
        getConfig().set("transfer-cooldown", transferCooldown);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Hopper cooldown set to " + transferCooldown + " tick(s) (" + formatSeconds(transferCooldown) + " seconds).");
        return true;
    }

    private boolean handleAmountCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Hopper transfer amount: " + transferAmount + " item(s) per transfer. Use /hoppertick <1-64>.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /hoppertick <1-64>");
            return true;
        }
        final int requested;
        try {
            requested = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Enter a whole number from 1 to 64.");
            return true;
        }
        if (requested < 1 || requested > MAX_AMOUNT) {
            sender.sendMessage(ChatColor.RED + "Transfer amount must be between 1 and 64.");
            return true;
        }

        transferAmount = requested;
        for (World world : Bukkit.getWorlds()) {
            if (!applyAmountToWorld(world)) {
                sender.sendMessage(ChatColor.RED + "Could not apply the transfer amount on this server. Check the server console.");
                return true;
            }
        }
        getConfig().set("transfer-amount", transferAmount);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Hoppers will move up to " + transferAmount + " item(s) per transfer. Actual throughput also depends on /hopperspeed, inventory space, and server tick rate.");
        return true;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        applyAmountToWorld(event.getWorld());
    }

    private boolean applyAmountToWorld(World world) {
        try {
            Method getHandle = world.getClass().getMethod("getHandle");
            Object serverLevel = getHandle.invoke(world);
            Field spigotConfigField = findField(serverLevel.getClass(), "spigotConfig");
            spigotConfigField.setAccessible(true);
            Object worldConfig = spigotConfigField.get(serverLevel);
            Field amountField = findField(worldConfig.getClass(), "hopperAmount");
            amountField.setAccessible(true);
            amountField.setInt(worldConfig, transferAmount);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().severe("Could not set hopper-amount for world " + world.getName() + ": " + exception.getMessage());
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep looking through the server's class hierarchy.
            }
        }
        throw new NoSuchFieldException(name);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        applyCooldown(event.getInitiator());
    }

    private void applyCooldown(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (!(holder instanceof Hopper hopper)) return;

        // Hopper's own tick sets its vanilla cooldown after the move event. Apply
        // ours on the next tick so the server does not immediately overwrite it.
        var block = hopper.getBlock();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!block.getChunk().isLoaded() || block.getType() != org.bukkit.Material.HOPPER) return;
            BlockState state = block.getState();
            if (state instanceof Hopper current) {
                current.setTransferCooldown(transferCooldown);
                current.update(true, false);
            }
        }, 1L);
    }

    private static String formatSeconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.2f", ticks / 20.0);
    }
}
