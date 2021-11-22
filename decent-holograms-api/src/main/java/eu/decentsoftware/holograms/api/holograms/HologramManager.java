package eu.decentsoftware.holograms.api.holograms;

import eu.decentsoftware.holograms.api.DecentHolograms;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.actions.ClickType;
import eu.decentsoftware.holograms.api.utils.Common;
import eu.decentsoftware.holograms.api.utils.file.FileUtils;
import eu.decentsoftware.holograms.api.utils.scheduler.RunnableTask;
import eu.decentsoftware.holograms.api.utils.scheduler.S;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * This class represents a Manager for handling holograms.
 */
public class HologramManager {

	private static final DecentHolograms DECENT_HOLOGRAMS = DecentHologramsAPI.get();
	private final Map<String, Hologram> hologramMap = new ConcurrentHashMap<>();
	private final Set<HologramLine> temporaryLines = Collections.synchronizedSet(new HashSet<>());
	private final Set<UUID> clickCooldowns = Collections.synchronizedSet(new HashSet<>());
	private final RunnableTask displayUpdateTask;

	public HologramManager() {
		this.displayUpdateTask = new RunnableTask(DecentHologramsAPI.get().getPlugin(), 0L, 5L);
		this.displayUpdateTask.addPart("display_update", () -> Hologram.getCachedHolograms().forEach((hologram) -> {
			for (Player player : Bukkit.getOnlinePlayers()) {
				if (hologram.isEnabled() && !hologram.isVisible(player) && hologram.canShow(player) && hologram.isInDisplayRange(player)) {
					hologram.show(player, hologram.getPlayerPage(player));
				} else if (hologram.isVisible(player) && !(hologram.isEnabled() && hologram.canShow(player) && hologram.isInDisplayRange(player))) {
					hologram.hide(player);
				}
			}
			clickCooldowns.clear();
		}));

		// Reload when worlds are ready
		S.sync(this::reload);
	}

	/**
	 * Spawn a temporary line that is going to disappear after the given duration.
	 * @param location Location of the line.
	 * @param content Content of the line.
	 * @param duration Duration to disappear after. (in ticks)
	 * @return The Hologram Line.
	 */
	public HologramLine spawnTemporaryHologramLine(Location location, String content, long duration) {
		HologramLine line = new HologramLine(null, location, content);
		temporaryLines.add(line);
		line.show();
		S.async(() -> {
			line.destroy();
			temporaryLines.remove(line);
		}, duration);
		return line;
	}

	public boolean onClick(Player player, int entityId, ClickType clickType) {
		UUID uuid = player.getUniqueId();
		if (clickCooldowns.contains(uuid)) return false;
		for (Hologram hologram : getHolograms()) {
			if (!hologram.getLocation().getWorld().getName().equals(player.getLocation().getWorld().getName())) continue;
			if (hologram.onClick(player, entityId, clickType)) {
				clickCooldowns.add(uuid);
				return true;
			}
		}
		return false;
	}

	public void onQuit(Player player) {
		S.async(() -> Hologram.getCachedHolograms().forEach(hologram -> hologram.onQuit(player)));
	}

	/**
	 * Reload this manager and all the holograms.
	 */
	public void reload() {
		this.destroy();
		this.loadHolograms();
		this.displayUpdateTask.restart();
	}

	/**
	 * Destroy this manager and all the holograms.
	 */
	public void destroy() {
		this.displayUpdateTask.stop();
		if (!hologramMap.isEmpty()) {
			hologramMap.values().forEach(Hologram::destroy);
			hologramMap.clear();
		}
		if (!temporaryLines.isEmpty()) {
			temporaryLines.forEach(HologramLine::destroy);
			temporaryLines.clear();
		}
	}

	/**
	 * Show all registered holograms for the given player.
	 * @param player Given player.
	 */
	public void showAll(Player player) {
		if (hologramMap.isEmpty()) return;
		for (Hologram hologram : hologramMap.values()) {
			if (hologram.isEnabled()) {
				hologram.show(player, hologram.getPlayerPage(player));
			}
		}
	}

	/**
	 * Hide all registered holograms for the given player.
	 * @param player Given player.
	 */
	public void hideAll(Player player) {
		if (!hologramMap.isEmpty()) {
			hologramMap.values().forEach(Hologram::hideAll);
		}
		if (!temporaryLines.isEmpty()) {
			temporaryLines.forEach(HologramLine::hide);
		}
	}

	/**
	 * Check whether a hologram with the given name is registered in this manager.
	 * @param name Name of the hologram.
	 * @return Boolean whether a hologram with the given name is registered in this manager.
	 */
	public boolean containsHologram(String name) {
		return hologramMap.containsKey(name);
	}

	/**
	 * Register a new hologram.
	 * @param hologram New hologram.
	 * @return The new hologram or null if it wasn't registered successfully.
	 */
	public Hologram registerHologram(Hologram hologram) {
		return hologramMap.put(hologram.getName(), hologram);
	}

	/**
	 * Get hologram by name.
	 * @param name Name of the hologram.
	 * @return The hologram or null if it wasn't found.
	 */
	public Hologram getHologram(String name) {
		return hologramMap.get(name);
	}

	/**
	 * Remove hologram by name.
	 * @param name Name of the hologram.
	 * @return The hologram or null if it wasn't found.
	 */
	public Hologram removeHologram(String name) {
		Hologram hologram = hologramMap.remove(name);
		S.async(hologram::delete);
		return hologram;
	}

	/**
	 * Get the names of all registered holograms.
	 * @return Set of the names of all registered holograms.
	 */
	public Set<String> getHologramNames() {
		return hologramMap.keySet();
	}

	/**
	 * Get all registered holograms.
	 * @return Collection of all registered holograms.
	 */
	public Collection<Hologram> getHolograms() {
		return hologramMap.values();
	}

	private void loadHolograms() {
		hologramMap.clear();

		String[] fileNames = FileUtils.getFileNames(DECENT_HOLOGRAMS.getDataFolder() + "/holograms", "\\w+\\.yml", true);
		if (fileNames == null || fileNames.length == 0) return;

		int counter = 0;
		Common.log("Loading holograms... ");
		for (String fileName : fileNames) {
			try {
				Hologram hologram = Hologram.fromFile(fileName);
				if (hologram != null && hologram.isEnabled()) {
					hologram.showAll();
					hologram.realignLines();
					this.registerHologram(hologram);
					counter++;
				}
			} catch (Exception e) {
				Common.log(Level.WARNING, "Failed to load hologram from file '%s'!", fileName);
				e.printStackTrace();
			}
		}
		Common.log("Loaded %d holograms!", counter);
	}

}
