package com.github.tejaslamba2006.stopesp.manager;

import com.github.tejaslamba2006.stopesp.StopESP;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

public class DeepShieldConfig {
   public static final int CURRENT_CONFIG_VERSION = 6;
   // Extra base-building blocks; names that don't exist on this server version are skipped.
   private static final String[] BASE_BLOCK_PRESET = new String[]{"LECTERN", "TRAPPED_CHEST", "CHEST", "BARREL", "ENDER_CHEST", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "WHITE_BED", "ORANGE_BED", "MAGENTA_BED", "LIGHT_BLUE_BED", "YELLOW_BED", "LIME_BED", "PINK_BED", "GRAY_BED", "LIGHT_GRAY_BED", "CYAN_BED", "PURPLE_BED", "BLUE_BED", "BROWN_BED", "GREEN_BED", "RED_BED", "BLACK_BED", "FURNACE", "BLAST_FURNACE", "SMOKER", "BREWING_STAND", "ENCHANTING_TABLE", "BEACON", "RESPAWN_ANCHOR", "CAULDRON", "WATER_CAULDRON", "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON", "COMPOSTER", "CRAFTER", "VAULT", "TRIAL_SPAWNER", "CRAFTING_TABLE", "SMITHING_TABLE", "LOOM", "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "STONECUTTER", "GRINDSTONE", "LODESTONE", "CONDUIT", "DECORATED_POT", "CHISELED_BOOKSHELF", "END_PORTAL_FRAME", "COPPER_CHEST", "EXPOSED_COPPER_CHEST", "WEATHERED_COPPER_CHEST", "OXIDIZED_COPPER_CHEST", "WAXED_COPPER_CHEST", "WAXED_EXPOSED_COPPER_CHEST", "WAXED_WEATHERED_COPPER_CHEST", "WAXED_OXIDIZED_COPPER_CHEST", "DISPENSER", "DROPPER", "HOPPER", "BEEHIVE", "BEE_NEST", "NOTE_BLOCK", "JUKEBOX"};
   private static final String[] ADDED_IN_V3 = new String[]{"WHITE_BED", "ORANGE_BED", "MAGENTA_BED", "LIGHT_BLUE_BED", "YELLOW_BED", "LIME_BED", "PINK_BED", "GRAY_BED", "LIGHT_GRAY_BED", "CYAN_BED", "PURPLE_BED", "BLUE_BED", "BROWN_BED", "GREEN_BED", "RED_BED", "BLACK_BED", "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL"};
   private boolean hideOres;
   private boolean chunkRewriteEnabled;
   private boolean antiEspHidePaintings;
   private boolean antiEspHideLeashKnots;
   private boolean baseBlocksPreset;
   private boolean antiEspHideBoats;
   private boolean antiEspHideMinecarts;
   private boolean teamVisible;
   private boolean friendsEnabled;
   private boolean bedrockDisableJitter;
   private int bedrockScanMultiplier;
   private boolean afkEnabled;
   private int afkIdleSeconds;
   private int afkMultiplier;
   private boolean afkSkipSpectators;
   private int minWorkerThreads;
   private boolean dirtyChunksEnabled;
   private double dirtyMoveDistance;
   private double dirtyNearRadius;
   private boolean yRulesEnabled;
   private int yRulesSkipAboveY;
   private double yRulesOreRevealRadius;
   private boolean violationsEnabled;
   private int violationThreshold;
   private int violationWindowSeconds;
   private int violationCooldownSeconds;
   private double violationChainDistance;
   private boolean violationLogConsole;
   private String discordWebhook;
   private boolean blockEntityHideUpdates;
   private boolean tpsBackoffEnabled;
   private double msptThreshold;
   private int maxMultiplier;
   private int maxEntitiesPerPlayer;
   private int maxBlockChecksPerScan;
   private boolean antiEspHideItems;
   private boolean antiEspHideProjectiles;
   private boolean antiEspHideItemFrames;
   private boolean antiEspHideArmorStands;
   private boolean antiEspHideVillagers;
   private boolean jitterEnabled;
   private double jitterMinDistance;
   private int jitterInterval;
   private double jitterBlocks;
   private final StopESP plugin;
   private File configFile;
   private FileConfiguration config;
   private double revealRadius;
   private boolean showThanksMessage;
   private boolean frustumCulling;
   private int visibilityBuffer;
   private long scanInterval;
   private int workerThreads;
   private String fakeBlockMode;
   private final Map<String, Double> worldRadiusOverrides = new HashMap();
   private final EnumSet<Material> protectedBlocks = EnumSet.noneOf(Material.class);
   private final Set<String> enabledWorlds = new HashSet();
   private boolean antiEspEnabled;
   private double antiEspRevealRadius;
   private double antiEspPlayerRadius;
   private long antiEspHideDelayMs;
   private double antiEspPeekPadding;
   private int antiEspMinVisibleSamples;
   private double antiEspNearRevealRadius;
   private double antiEspThirdPersonReach;
   private boolean antiEspFailClosed;
   private boolean leavesBlockSight;
   private boolean antiEspObfuscatePlayers;
   private boolean antiEspObfuscateMobs;
   private boolean antiEspHideNameplates;
   private boolean antiEspHideInvisibleEntities;
   private final EnumSet<EntityType> antiEspExtraEntityHide = EnumSet.noneOf(EntityType.class);

   public DeepShieldConfig(StopESP plugin) {
      this.plugin = plugin;
      this.configFile = new File(plugin.getDataFolder(), "config.yml");
      this.saveDefaultConfig();
      this.migrate();
      this.reload();
   }

   public void saveDefaultConfig() {
      if (!this.configFile.exists()) {
         this.plugin.saveResource("config.yml", false);
      }

   }

   public void reload() {
      this.config = YamlConfiguration.loadConfiguration(this.configFile);
      this.revealRadius = this.config.getDouble("reveal-radius", (double)20.0F);
      this.showThanksMessage = this.config.getBoolean("show-thanks-message", true);
      this.frustumCulling = this.config.getBoolean("frustum-culling", true);
      this.visibilityBuffer = this.config.getInt("visibility-buffer", 3);
      this.scanInterval = (long)Math.max(1, this.config.getInt("scan-interval", 1));
      this.workerThreads = this.config.getInt("worker-threads", -1);
      this.fakeBlockMode = this.config.getString("fake-block-mode", "AUTO");
      this.enabledWorlds.clear();
      this.enabledWorlds.addAll(this.config.getStringList("enabled-worlds"));
      this.worldRadiusOverrides.clear();
      if (this.config.isConfigurationSection("world-radius-overrides")) {
         for(String worldName : this.config.getConfigurationSection("world-radius-overrides").getKeys(false)) {
            this.worldRadiusOverrides.put(worldName, this.config.getDouble("world-radius-overrides." + worldName, this.revealRadius));
         }
      }

      this.loadMaterialSet(this.config.getStringList("protected-blocks"), this.protectedBlocks);
      if (this.baseBlocksPreset) {
         for(String name : BASE_BLOCK_PRESET) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
               this.protectedBlocks.add(m);
            }
         }
      }
      this.antiEspEnabled = this.config.getBoolean("anti-esp.enabled", true);
      this.antiEspRevealRadius = this.config.getDouble("anti-esp.reveal-radius", (double)32.0F);
      this.antiEspPlayerRadius = Math.max(this.antiEspRevealRadius, this.config.getDouble("anti-esp.player-radius", 160.0));
      this.antiEspHideDelayMs = Math.max(0L, this.config.getLong("anti-esp.hide-delay-ms", 400L));
      this.antiEspPeekPadding = Math.max(0.0, this.config.getDouble("anti-esp.peek-padding", 0.4));
      this.antiEspMinVisibleSamples = Math.max(1, this.config.getInt("anti-esp.min-visible-samples", 2));
      this.antiEspNearRevealRadius = Math.max(0.0, this.config.getDouble("anti-esp.near-reveal-radius", 0.0));
      this.antiEspThirdPersonReach = Math.min(8.0, Math.max(0.0, this.config.getDouble("anti-esp.third-person-reach", 4.0)));
      this.antiEspFailClosed = this.config.getBoolean("anti-esp.fail-closed", true);
      this.leavesBlockSight = this.config.getBoolean("anti-esp.leaves-block-sight", true);
      this.antiEspObfuscatePlayers = this.config.getBoolean("anti-esp.obfuscate-players", true);
      this.antiEspObfuscateMobs = this.config.getBoolean("anti-esp.obfuscate-mobs", true);
      this.antiEspHideNameplates = this.config.getBoolean("anti-esp.hide-nameplates", true);
      this.antiEspHideInvisibleEntities = this.config.getBoolean("anti-esp.hide-invisible-entities", false);
      this.loadEntityTypeSet(this.config.getStringList("anti-esp.extra-entity-hide"), this.antiEspExtraEntityHide);
      this.chunkRewriteEnabled = this.config.getBoolean("chunk-rewrite.enabled", true);
      this.antiEspHidePaintings = this.config.getBoolean("anti-esp.hide-paintings", true);
      this.antiEspHideLeashKnots = this.config.getBoolean("anti-esp.hide-leash-knots", true);
      this.baseBlocksPreset = this.config.getBoolean("base-blocks-preset", true);
      this.antiEspHideBoats = this.config.getBoolean("anti-esp.hide-boats", true);
      this.antiEspHideMinecarts = this.config.getBoolean("anti-esp.hide-minecarts", true);
      this.teamVisible = this.config.getBoolean("visibility.team-visible", true);
      this.friendsEnabled = this.config.getBoolean("visibility.friends-enabled", true);
      this.bedrockDisableJitter = this.config.getBoolean("bedrock.disable-jitter", true);
      this.bedrockScanMultiplier = Math.max(1, this.config.getInt("bedrock.scan-multiplier", 1));
      this.afkEnabled = this.config.getBoolean("performance.afk-backoff.enabled", true);
      this.afkIdleSeconds = Math.max(3, this.config.getInt("performance.afk-backoff.idle-seconds", 20));
      this.afkMultiplier = Math.max(1, this.config.getInt("performance.afk-backoff.multiplier", 5));
      this.afkSkipSpectators = this.config.getBoolean("performance.afk-backoff.skip-spectators", false);
      this.minWorkerThreads = Math.max(1, this.config.getInt("performance.min-worker-threads", 1));
      this.dirtyChunksEnabled = this.config.getBoolean("performance.dirty-chunks.enabled", true);
      this.dirtyMoveDistance = Math.max(0.5, this.config.getDouble("performance.dirty-chunks.move-distance", 2.0));
      this.dirtyNearRadius = Math.max(16.0, this.config.getDouble("performance.dirty-chunks.near-radius", 32.0));
      this.hideOres = this.config.getBoolean("hide-ores", false);
      this.yRulesEnabled = this.config.getBoolean("y-rules.enabled", false);
      this.yRulesSkipAboveY = this.config.getInt("y-rules.skip-ores-above-y", 64);
      this.yRulesOreRevealRadius = this.config.getDouble("y-rules.ore-reveal-radius", 12.0);
      this.violationsEnabled = this.config.getBoolean("violations.enabled", true);
      this.violationThreshold = Math.max(1, this.config.getInt("violations.alert-threshold", 3));
      this.violationWindowSeconds = Math.max(10, this.config.getInt("violations.window-seconds", 300));
      this.violationCooldownSeconds = Math.max(0, this.config.getInt("violations.alert-cooldown-seconds", 30));
      this.violationChainDistance = Math.max(1.0, this.config.getDouble("violations.tunnel-chain-distance", 3.0));
      this.violationLogConsole = this.config.getBoolean("violations.log-to-console", true);
      this.discordWebhook = this.config.getString("violations.discord-webhook", "");
      this.blockEntityHideUpdates = this.config.getBoolean("block-entities.hide-updates", true);
      this.tpsBackoffEnabled = this.config.getBoolean("performance.tps-backoff.enabled", true);
      this.msptThreshold = this.config.getDouble("performance.tps-backoff.mspt-threshold", 35.0);
      this.maxMultiplier = Math.max(1, this.config.getInt("performance.tps-backoff.max-multiplier", 4));
      this.maxEntitiesPerPlayer = this.config.getInt("performance.max-entities-per-player", 64);
      this.maxBlockChecksPerScan = this.config.getInt("performance.max-block-checks-per-scan", 4000);
      this.antiEspHideItems = this.config.getBoolean("anti-esp.hide-items", true);
      this.antiEspHideProjectiles = this.config.getBoolean("anti-esp.hide-projectiles", true);
      this.antiEspHideItemFrames = this.config.getBoolean("anti-esp.hide-item-frames", true);
      this.antiEspHideArmorStands = this.config.getBoolean("anti-esp.hide-armor-stands", true);
      this.antiEspHideVillagers = this.config.getBoolean("anti-esp.hide-villagers", true);
      this.jitterEnabled = this.config.getBoolean("anti-esp.movement-jitter.enabled", true);
      this.jitterMinDistance = Math.max(8.0, this.config.getDouble("anti-esp.movement-jitter.min-distance", 48.0));
      this.jitterInterval = Math.max(2, this.config.getInt("anti-esp.movement-jitter.update-interval", 4));
      this.jitterBlocks = Math.min(2.0, Math.max(0.0, this.config.getDouble("anti-esp.movement-jitter.jitter-blocks", 0.5)));
      this.plugin.getLogger().info("Config reloaded! Active in: " + String.join(", ", this.enabledWorlds));
   }

   /** Adds any keys that newer versions introduced, keeping the admin's existing values. */
   private void migrate() {
      try {
         YamlConfiguration cur = YamlConfiguration.loadConfiguration(this.configFile);
         int oldVersion = cur.getInt("config-version", 1);
         InputStream in = this.plugin.getResource("config.yml");
         if (in == null) {
            return;
         }

         YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
         int added = 0;
         for(String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key) || def.get(key) == null || cur.contains(key)) {
               continue;
            }

            cur.set(key, def.get(key));
            try {
               cur.setComments(key, def.getComments(key));
            } catch (Throwable ignored) {
            }

            ++added;
         }

         if (oldVersion < 3) {
            // v3 protects beds and anvils; add them to existing protected-blocks lists.
            List<String> list = new java.util.ArrayList(cur.getStringList("protected-blocks"));
            for(String m : ADDED_IN_V3) {
               if (!list.contains(m)) {
                  list.add(m);
                  ++added;
               }
            }

            cur.set("protected-blocks", list);
         }

         if (oldVersion < CURRENT_CONFIG_VERSION || added > 0) {
            File backup = new File(this.plugin.getDataFolder(), "config.yml.bak-v" + oldVersion);
            Files.copy(this.configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            cur.set("config-version", CURRENT_CONFIG_VERSION);
            cur.save(this.configFile);
            this.plugin.getLogger().info("Config migrated v" + oldVersion + " -> v" + CURRENT_CONFIG_VERSION + " (" + added + " new keys added, backup: " + backup.getName() + ")");
         }
      } catch (IOException | RuntimeException e) {
         this.plugin.getLogger().warning("Config migration failed, using the file as-is: " + e.getMessage());
      }
   }

   public static boolean isOre(Material m) {
      return m == Material.ANCIENT_DEBRIS || m.name().endsWith("_ORE");
   }

   /** True if this block type at this height should be hidden at all. */
   public boolean shouldProtect(Material type, int y) {
      if (!this.protectedBlocks.contains(type)) {
         return false;
      }

      if (!this.hideOres && isOre(type)) {
         return false;
      }

      return !(this.yRulesEnabled && isOre(type) && y >= this.yRulesSkipAboveY);
   }

   public boolean isYRulesEnabled() { return this.yRulesEnabled; }
   public double getYRulesOreRevealRadius() { return this.yRulesOreRevealRadius; }
   public boolean isViolationsEnabled() { return this.violationsEnabled; }
   public int getViolationThreshold() { return this.violationThreshold; }
   public int getViolationWindowSeconds() { return this.violationWindowSeconds; }
   public int getViolationCooldownSeconds() { return this.violationCooldownSeconds; }
   public double getViolationChainDistance() { return this.violationChainDistance; }
   public boolean isViolationLogConsole() { return this.violationLogConsole; }
   public String getDiscordWebhook() { return this.discordWebhook == null ? "" : this.discordWebhook.trim(); }
   public boolean isBlockEntityHideUpdates() { return this.blockEntityHideUpdates; }
   public boolean isTpsBackoffEnabled() { return this.tpsBackoffEnabled; }
   public double getMsptThreshold() { return this.msptThreshold; }
   public int getMaxMultiplier() { return this.maxMultiplier; }
   public int getMaxEntitiesPerPlayer() { return this.maxEntitiesPerPlayer; }
   public int getMaxBlockChecksPerScan() { return this.maxBlockChecksPerScan; }
   public boolean isAntiEspHideItems() { return this.antiEspHideItems; }
   public boolean isAntiEspHideProjectiles() { return this.antiEspHideProjectiles; }
   public boolean isAntiEspHideItemFrames() { return this.antiEspHideItemFrames; }
   public boolean isChunkRewriteEnabled() { return this.chunkRewriteEnabled; }
   public boolean isAntiEspHidePaintings() { return this.antiEspHidePaintings; }
   public boolean isAntiEspHideLeashKnots() { return this.antiEspHideLeashKnots; }
   public boolean isAntiEspHideBoats() { return this.antiEspHideBoats; }
   public boolean isAntiEspHideMinecarts() { return this.antiEspHideMinecarts; }
   public boolean isTeamVisible() { return this.teamVisible; }
   public boolean isFriendsEnabled() { return this.friendsEnabled; }
   public boolean isBedrockDisableJitter() { return this.bedrockDisableJitter; }
   public int getBedrockScanMultiplier() { return this.bedrockScanMultiplier; }
   public boolean isAfkEnabled() { return this.afkEnabled; }
   public int getAfkIdleSeconds() { return this.afkIdleSeconds; }
   public int getAfkMultiplier() { return this.afkMultiplier; }
   public boolean isAfkSkipSpectators() { return this.afkSkipSpectators; }
   public int getMinWorkerThreads() { return this.minWorkerThreads; }
   public boolean isDirtyChunksEnabled() { return this.dirtyChunksEnabled; }
   public double getDirtyMoveDistance() { return this.dirtyMoveDistance; }
   public double getDirtyNearRadius() { return this.dirtyNearRadius; }
   public boolean isAntiEspHideVillagers() { return this.antiEspHideVillagers; }
   public boolean isJitterEnabled() { return this.jitterEnabled; }
   public double getJitterMinDistance() { return this.jitterMinDistance; }
   public int getJitterInterval() { return this.jitterInterval; }
   public double getJitterBlocks() { return this.jitterBlocks; }
   public boolean isAntiEspHideArmorStands() { return this.antiEspHideArmorStands; }

   public boolean isEnabledWorld(World world) {
      return this.enabledWorlds.contains(world.getName());
   }

   private void loadMaterialSet(List<String> source, EnumSet<Material> target) {
      target.clear();

      for(String name : source) {
         try {
            target.add(Material.valueOf(name.toUpperCase()));
         } catch (IllegalArgumentException var6) {
            this.plugin.getLogger().warning("Invalid Material in config: " + name);
         }
      }

   }

   private void loadEntityTypeSet(List<String> source, EnumSet<EntityType> target) {
      target.clear();

      for(String name : source) {
         try {
            target.add(EntityType.valueOf(name.toUpperCase()));
         } catch (IllegalArgumentException var6) {
            this.plugin.getLogger().warning("Invalid EntityType in config: " + name);
         }
      }

   }

   public FileConfiguration getConfig() {
      return this.config;
   }

   public double getRevealRadius(World world) {
      return world != null && this.worldRadiusOverrides.containsKey(world.getName()) ? (Double)this.worldRadiusOverrides.get(world.getName()) : this.revealRadius;
   }

   public double getRevealRadius() {
      return this.revealRadius;
   }

   public boolean isShowThanksMessage() {
      return this.showThanksMessage;
   }

   public boolean isFrustumCulling() {
      return this.frustumCulling;
   }

   public int getVisibilityBuffer() {
      return this.visibilityBuffer;
   }

   public long getScanInterval() {
      return this.scanInterval;
   }

   public int getWorkerThreads() {
      return this.workerThreads;
   }

   public String getFakeBlockMode() {
      return this.fakeBlockMode;
   }

   public EnumSet<Material> getProtectedBlocks() {
      return this.protectedBlocks;
   }

   public boolean isAntiEspEnabled() {
      return this.antiEspEnabled;
   }

   public double getAntiEspPlayerRadius() { return this.antiEspPlayerRadius; }
   public long getAntiEspHideDelayMs() { return this.antiEspHideDelayMs; }
   public double getAntiEspPeekPadding() { return this.antiEspPeekPadding; }
   public int getAntiEspMinVisibleSamples() { return this.antiEspMinVisibleSamples; }
   public double getAntiEspNearRevealRadius() { return this.antiEspNearRevealRadius; }
   public double getAntiEspThirdPersonReach() { return this.antiEspThirdPersonReach; }
   public boolean isAntiEspFailClosed() { return this.antiEspFailClosed; }
   public boolean isLeavesBlockSight() { return this.leavesBlockSight; }

   public double getAntiEspRevealRadius() {
      return this.antiEspRevealRadius;
   }

   public boolean isAntiEspObfuscatePlayers() {
      return this.antiEspObfuscatePlayers;
   }

   public boolean isAntiEspObfuscateMobs() {
      return this.antiEspObfuscateMobs;
   }

   public boolean isAntiEspHideNameplates() {
      return this.antiEspHideNameplates;
   }

   public boolean isAntiEspHideInvisibleEntities() {
      return this.antiEspHideInvisibleEntities;
   }

   public EnumSet<EntityType> getAntiEspExtraEntityHide() {
      return this.antiEspExtraEntityHide;
   }
}
