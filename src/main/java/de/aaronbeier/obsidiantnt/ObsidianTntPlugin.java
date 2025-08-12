package de.aaronbeier.obsidiantnt;

import com.jeff_media.morepersistentdatatypes.DataType;
import com.jeff_media.morepersistentdatatypes.datatypes.collections.CollectionDataType;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public final class ObsidianTntPlugin extends JavaPlugin implements Listener {

	private static final CollectionDataType<HashSet<BlockVector>, BlockVector> bedrockBreakerLocationSetDataType = DataType.asHashSet(DataType.BLOCK_VECTOR);
	private static final Random random = new Random();
	private static final int[][] directionsToCheck = {
		{ 0,  0, -1}, // North
		{ 1,  0,  0}, // East
		{ 0,  0,  1}, // South
		{-1,  0,  0}, // West
		{ 0,  1,  0}, // Up
		{ 0, -1,  0}  // Down
	};

	final NamespacedKey bedrockBreakerKey; // Generic name, used for multiple things at once because im lazy
	final NamespacedKey hasSeenGunpowderKey; // We want to unlock the recipe after the player has collected both obsidian and gunpowder
	final NamespacedKey hasSeenObsidianKey; //  so we have to keep track of if a player has ever had gp and obsidian in their inventory

	public ObsidianTntPlugin() {
		this.bedrockBreakerKey = new NamespacedKey(this, "bedrock_breaker");
		this.hasSeenGunpowderKey = new NamespacedKey(this, "gunpowder");
		this.hasSeenObsidianKey = new NamespacedKey(this, "obsidian");
	}

	@Override
	public void onEnable() {
		final ItemStack bedrockBreakerItem = new ItemStack(Material.TNT);
		bedrockBreakerItem.editMeta((meta) -> {
			meta.itemName(Component.text("Obsidian TNT").decoration(TextDecoration.ITALIC, false));
			meta.setRarity(ItemRarity.UNCOMMON);
			meta.lore(List.of(
				Component.text("A block of TNT reinforced with obsidian.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
				Component.text("This might be able to blow up bedrock...", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
			));
			meta.setCustomModelData(7177); // Random value so we don't have to add one in case we'd ever have a custom model/texture
			meta.getPersistentDataContainer().set(this.bedrockBreakerKey, PersistentDataType.BOOLEAN, true);
		});

		final ShapedRecipe bedrockBreakerRecipe = new ShapedRecipe(this.bedrockBreakerKey, bedrockBreakerItem);
		bedrockBreakerRecipe.shape("OTO", "TOT", "OTO");
		bedrockBreakerRecipe.setIngredient('O', Material.OBSIDIAN);
		bedrockBreakerRecipe.setIngredient('T', Material.TNT);
		bedrockBreakerRecipe.setCategory(CraftingBookCategory.REDSTONE);

		Bukkit.addRecipe(bedrockBreakerRecipe);
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	void onPlayerPickupItem(final EntityPickupItemEvent event) {
		if (!(event.getEntity() instanceof final Player player)) {
			return;
		}

		final Material material = event.getItem().getItemStack().getType();
		if (material != Material.GUNPOWDER && material != Material.OBSIDIAN) {
			return;
		}

		final PersistentDataContainer playerData = player.getPersistentDataContainer();
		boolean hasSeenGunpowder = playerData.has(this.hasSeenGunpowderKey);
		boolean hasSeenObsidian = playerData.has(this.hasSeenObsidianKey);

		if (hasSeenGunpowder && hasSeenObsidian) {
			return; // Player has already had both items in their inv and thus already unlocked the recipe
		}

		// I really don't like the double check, but I also want to have a guard statement that returns if the item is not gp or obsidian
		// because there are potentially a lot of pickup events and getting the PDC before checking every time might cause some lag?
		switch (material) {
			case GUNPOWDER -> {
				if (!playerData.has(this.hasSeenGunpowderKey)) {
					playerData.set(this.hasSeenGunpowderKey, PersistentDataType.BOOLEAN, true);
				}

				hasSeenGunpowder = true;
			}

			case OBSIDIAN -> {
				if (!playerData.has(this.hasSeenObsidianKey)) {
					playerData.set(this.hasSeenObsidianKey, PersistentDataType.BOOLEAN, true);
				}

				hasSeenObsidian = true;
			}

			default -> throw new RuntimeException("this should be unreachable");
		}

		if (hasSeenGunpowder && hasSeenObsidian) {
			player.discoverRecipe(this.bedrockBreakerKey);
		}
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	void onBlockPlace(final BlockPlaceEvent event) {
		final ItemStack stack = event.getItemInHand();
		if (!stack.hasItemMeta()) { // Quick check to rule out a lot of items
			return;
		}

		final PersistentDataContainerView stackPersistentData = stack.getPersistentDataContainer();
		if (!stackPersistentData.getOrDefault(this.bedrockBreakerKey, PersistentDataType.BOOLEAN, false)) {
			return;
		}

		final Block block = event.getBlockPlaced();
		final PersistentDataContainer worldPersistentData = block.getWorld().getPersistentDataContainer();
		final HashSet<BlockVector> bedrockBreakerLocations = worldPersistentData.getOrDefault(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, new HashSet<>());

		bedrockBreakerLocations.add(new BlockVector(block.getX(), block.getY(), block.getZ()));
		worldPersistentData.set(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, bedrockBreakerLocations);
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	void onBlockBreak(final BlockBreakEvent event) {
		final Block block = event.getBlock();

		if (block.getType() != Material.TNT) {
			return;
		}

		final PersistentDataContainer worldPersistentData = block.getWorld().getPersistentDataContainer();
		final HashSet<BlockVector> bedrockBreakerLocations = worldPersistentData.getOrDefault(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, new HashSet<>());

		bedrockBreakerLocations.remove(new BlockVector(block.getX(), block.getY(), block.getZ()));
		worldPersistentData.set(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, bedrockBreakerLocations);
	}

	// Could add prime event and add the key to the entities persistent data container here as a paranoid double check

	@EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
	void onEntityExplode(final EntityExplodeEvent event) {
		if (!(event.getEntity() instanceof final TNTPrimed primedTnt)) {
			return;
		}

		// Could check entity persistent data container here

		final @Nullable Location sourceLocation = primedTnt.getOrigin();
		if (sourceLocation == null) {
			return;
		}

		final World world = primedTnt.getWorld();
		final PersistentDataContainer worldPersistentData = world.getPersistentDataContainer();
		final HashSet<BlockVector> bedrockBreakerLocations = worldPersistentData.getOrDefault(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, new HashSet<>());

		final BlockVector positionToCheck = new BlockVector(sourceLocation.getBlockX(), sourceLocation.getBlockY(), sourceLocation.getBlockZ());
		if (!bedrockBreakerLocations.contains(positionToCheck)) {
			return;
		}

		bedrockBreakerLocations.remove(positionToCheck);
		worldPersistentData.set(this.bedrockBreakerKey, bedrockBreakerLocationSetDataType, bedrockBreakerLocations);

		final Location finalLocation = event.getLocation().toBlockLocation();
		// Need this so that if the TNT gets moved by water or gravity we blow up Bedrock at the new position

		for (final int[] direction : directionsToCheck) {
			final Block block = world.getBlockAt(finalLocation.clone().add(direction[0], direction[1], direction[2]));

			if (block.getType() == Material.BEDROCK && random.nextFloat() > 0.2f) {
				block.setType(Material.AIR);
			}
		}

		// Could trigger special effects here
	}
}
