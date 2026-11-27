package com.github.tejaslamba2006.stopesp.manager;

import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.util.PerformanceGovernor;
import com.github.tejaslamba2006.stopesp.util.ScanGate;
import com.github.tejaslamba2006.stopesp.util.Scheduler;
import com.github.tejaslamba2006.stopesp.util.TransparentBlocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Boat;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class AntiEspManager {
   private final StopESP plugin;
   private final DeepShieldConfig dsConfig;
   private Scheduler.Task scanTask;
   private boolean enabled = true;
   private long tickGate = 0L;
   private long scanCounter = 0L;
   private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
   private final ConcurrentHashMap<UUID, Map<UUID, LosCache>> losCache = new ConcurrentHashMap();
   private final ConcurrentHashMap<UUID, Integer> scanRotation = new ConcurrentHashMap();
   private final ConcurrentHashMap<UUID, Map<UUID, Entity>> hiddenEntities = new ConcurrentHashMap();
   private final ConcurrentHashMap<UUID, Scoreboard> nameplateBoards = new ConcurrentHashMap();
   private final ConcurrentHashMap<UUID, Map<UUID, Long>> lastSeen = new ConcurrentHashMap();

   public AntiEspManager(StopESP plugin, DeepShieldConfig dsConfig) {
      this.plugin = plugin;
      this.dsConfig = dsConfig;
   }

   public void startScan() {
      this.purgeNameplateTeams();
      this.enabled = true;
      if (this.scanTask != null) {
         this.scanTask.cancel();
      }

      this.tickGate = 0L;
      this.scanTask = Scheduler.runTimer(this.plugin, () -> {
         if (this.enabled) {
            long needed = this.dsConfig.getScanInterval() * (long)PerformanceGovernor.multiplier(this.dsConfig);
            if (++this.tickGate >= needed) {
               this.tickGate = 0L;
               ++this.scanCounter;

               for(Player player : Bukkit.getOnlinePlayers()) {
                  if (this.dsConfig.isEnabledWorld(player.getWorld()) && !player.hasPermission("stopesp.bypass") && !ScanGate.shouldSkip(this.dsConfig, player, this.scanCounter)) {
                     Scheduler.runEntity(this.plugin, player, () -> this.scanPlayer(player));
                  }
               }

            }
         }
      }, 1L, 1L);
   }

   private static final class Candidate {
      final Entity entity;
      final double distSq;

      Candidate(Entity entity, double distSq) {
         this.entity = entity;
         this.distSq = distSq;
      }
   }

   /** Immutable per-entity data captured on the entity thread so rays can run off-thread. */
   private static final class Snap {
      final Entity entity;
      final UUID id;
      final double x;
      final double y;
      final double z;
      final double eyeHeight;
      final double height;
      final boolean living;
      final boolean forceVisible;
      final boolean invisible;
      final boolean cached;
      final boolean cachedVisible;
      final int cachedAge;
      boolean losVisible;

      Snap(Entity entity, Location loc, boolean forceVisible, boolean invisible, LosCache cache, double vx, double vy, double vz, double yaw, double pitch) {
         this.entity = entity;
         this.id = entity.getUniqueId();
         this.x = loc.getX();
         this.y = loc.getY();
         this.z = loc.getZ();
         this.living = entity instanceof LivingEntity;
         this.eyeHeight = this.living ? ((LivingEntity)entity).getEyeHeight() : 0.0;
         this.height = entity.getHeight();
         this.forceVisible = forceVisible;
         this.invisible = invisible;
         boolean reuse = cache != null && cache.age < 10 && near(cache.ex, this.x) && near(cache.ey, this.y) && near(cache.ez, this.z) && near(cache.vx, vx) && near(cache.vy, vy) && near(cache.vz, vz) && Math.abs(cache.yaw - yaw) < 1.5 && Math.abs(cache.pitch - pitch) < 1.5;
         this.cached = reuse;
         this.cachedVisible = reuse && cache.visible;
         this.cachedAge = reuse ? cache.age + 1 : 0;
      }

      private static boolean near(double a, double b) {
         return Math.abs(a - b) < 0.02;
      }
   }

   /** Last computed line-of-sight result, reused while neither viewer nor entity moved. */
   private static final class LosCache {
      final double ex;
      final double ey;
      final double ez;
      final double vx;
      final double vy;
      final double vz;
      final boolean visible;
      final int age;
      final double yaw;
      final double pitch;

      LosCache(double ex, double ey, double ez, double vx, double vy, double vz, boolean visible, int age, double yaw, double pitch) {
         this.ex = ex;
         this.ey = ey;
         this.ez = ez;
         this.vx = vx;
         this.vy = vy;
         this.vz = vz;
         this.visible = visible;
         this.age = age;
         this.yaw = yaw;
         this.pitch = pitch;
      }
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   private AntiXrayManager xray() {
      try {
         return this.plugin.getAntiXrayModule().getManager();
      } catch (RuntimeException e) {
         return null;
      }
   }

   /** Phase 1 (entity thread): gather cheap data. Phase 2 (worker): rays. Phase 3 (entity thread): apply. */
   private void scanPlayer(Player player) {
      if (!player.isOnline() || !this.inFlight.add(player.getUniqueId())) {
         return;
      }

      boolean handedOff = false;

      try {
         double radius = this.dsConfig.getAntiEspRevealRadius();
         World world = player.getWorld();
         Map<UUID, Entity> playerHidden = (Map)this.hiddenEntities.computeIfAbsent(player.getUniqueId(), (k) -> new ConcurrentHashMap());
         Set<UUID> nearbyUuids = new HashSet();
         Location playerLoc = player.getLocation();
         double px = playerLoc.getX();
         double py = playerLoc.getY();
         double pz = playerLoc.getZ();
         List<Candidate> candidates = new ArrayList();

         // Players are checked out to player-radius (not just reveal-radius): anything outside the checked
         // radius is shown unconditionally, which is exactly what ESP/nametag mods exploit on far players.
         double playerRadius = this.dsConfig.getAntiEspPlayerRadius();
         double playerRadiusSq = playerRadius * playerRadius;
         double radiusSq = radius * radius;
         for(Entity entity : world.getNearbyEntities(player.getBoundingBox().expand(playerRadius), (e) -> {
            if (e.equals(player) || !this.shouldObfuscate(e)) {
               return false;
            }
            double d = e.getLocation().distanceSquared(playerLoc);
            return e instanceof Player ? d <= playerRadiusSq : d <= radiusSq;
         })) {
            nearbyUuids.add(entity.getUniqueId());
            candidates.add(new Candidate(entity, entity.getLocation().distanceSquared(playerLoc)));
         }

         int budget = this.dsConfig.getMaxEntitiesPerPlayer();
         List<Candidate> toCheck;
         List<Candidate> skipped = new ArrayList();
         if (budget > 0 && candidates.size() > budget) {
            candidates.sort(Comparator.comparingDouble((c) -> c.distSq));
            toCheck = new ArrayList(candidates.subList(0, budget));
            List<Candidate> rest = candidates.subList(budget, candidates.size());
            int slice = Math.max(1, budget / 2);
            int offset = this.scanRotation.getOrDefault(player.getUniqueId(), 0) % rest.size();
            boolean[] picked = new boolean[rest.size()];

            for(int i = 0; i < slice && i < rest.size(); ++i) {
               int idx = (offset + i) % rest.size();
               picked[idx] = true;
               toCheck.add(rest.get(idx));
            }

            for(int i = 0; i < rest.size(); ++i) {
               if (!picked[i]) {
                  skipped.add(rest.get(i));
               }
            }

            this.scanRotation.put(player.getUniqueId(), (offset + slice) % rest.size());
         } else {
            toCheck = candidates;
         }

         Location eye = player.getEyeLocation();
         double ex = eye.getX();
         double ey = eye.getY();
         double ez = eye.getZ();
         Map<UUID, LosCache> cache = (Map)this.losCache.computeIfAbsent(player.getUniqueId(), (k) -> new ConcurrentHashMap());
         List<Snap> snaps = new ArrayList();

         double nearR = this.dsConfig.getAntiEspNearRevealRadius();
         double nearSq = nearR * nearR;
         for(Candidate c : toCheck) {
            Entity entity = c.entity;
            boolean own = entity instanceof Projectile projectile && player.equals(projectile.getShooter()) || entity.getPassengers().contains(player) || this.isTrusted(player, entity)
                  || nearR > 0.0 && entity instanceof Player && c.distSq <= nearSq;
            boolean invisible = this.dsConfig.isAntiEspHideInvisibleEntities() && this.isConsideredInvisible(entity);
            snaps.add(new Snap(entity, entity.getLocation(), own, invisible, (LosCache)cache.get(entity.getUniqueId()), ex, ey, ez, eye.getYaw(), eye.getPitch()));
         }

         AntiXrayManager xray = this.xray();
         if (xray == null || !xray.hasIndex(world)) {
            // No occlusion index yet: fall back to the (slower) main-thread world raycast.
            for(Snap snap : snaps) {
               snap.losVisible = this.canSeeEntity(player, snap.entity);
            }

            this.apply(player, snaps, skipped, nearbyUuids, playerHidden, cache, ex, ey, ez, eye.getYaw(), eye.getPitch());
            return;
         }

         // Peek padding: also test from slightly left/right of the eye so players show up the moment you
         // could peek round a corner, instead of popping in late.
         final double peekPad = this.dsConfig.getAntiEspPeekPadding();
         final double peekYaw = Math.toRadians(eye.getYaw());
         final double peekX = -Math.cos(peekYaw);
         final double peekZ = -Math.sin(peekYaw);
         final double reach = this.dsConfig.getAntiEspThirdPersonReach();
         handedOff = xray.submitAsync(() -> {
            try {
               // Extra viewpoints: where the F5 camera would be (behind the eye, and in front for the front view),
               // pulled in when a wall is in the way, like the vanilla camera. Computed once per scan.
               double[][] origins = new double[2][];
               if (reach > 0.0) {
                  double pitchRad = Math.toRadians(eye.getPitch());
                  double dirX = -Math.sin(peekYaw) * Math.cos(pitchRad);
                  double dirY = -Math.sin(pitchRad);
                  double dirZ = Math.cos(peekYaw) * Math.cos(pitchRad);
                  for(int side = 0; side < 2; ++side) {
                     double sign = side == 0 ? -1.0 : 1.0;
                     for(double f = 1.0; f >= 0.5; f -= 0.25) {
                        double cx = ex + sign * dirX * reach * f;
                        double cy = ey + sign * dirY * reach * f;
                        double cz = ez + sign * dirZ * reach * f;
                        if (xray.isPathClear(world, ex, ey, ez, cx, cy, cz)) {
                           origins[side] = new double[]{cx, cy, cz};
                           break;
                        }
                     }
                  }
               }

               for(Snap snap : snaps) {
                  if (snap.cached) {
                     snap.losVisible = snap.cachedVisible;
                     continue;
                  }

                  boolean vis = this.losAsync(xray, world, ex, ey, ez, snap)
                        || (peekPad > 0.0 && (this.losAsync(xray, world, ex + peekX * peekPad, ey, ez + peekZ * peekPad, snap)
                        || this.losAsync(xray, world, ex - peekX * peekPad, ey, ez - peekZ * peekPad, snap)));
                  for(int i = 0; i < 2 && !vis; ++i) {
                     if (origins[i] != null) {
                        vis = this.losAsync(xray, world, origins[i][0], origins[i][1], origins[i][2], snap);
                     }
                  }

                  snap.losVisible = vis;
               }
            } catch (Throwable t) {
               this.plugin.getLogger().warning("Async ESP scan failed: " + t);
               this.inFlight.remove(player.getUniqueId());
               return;
            }

            Scheduler.runEntity(this.plugin, player, () -> {
               try {
                  if (player.isOnline()) {
                     this.apply(player, snaps, skipped, nearbyUuids, playerHidden, cache, ex, ey, ez, eye.getYaw(), eye.getPitch());
                  }
               } finally {
                  this.inFlight.remove(player.getUniqueId());
               }

            });
         });
         if (!handedOff) {
            for(Snap snap : snaps) {
               snap.losVisible = this.canSeeEntity(player, snap.entity);
            }

            this.apply(player, snaps, skipped, nearbyUuids, playerHidden, cache, ex, ey, ez, eye.getYaw(), eye.getPitch());
         }
      } finally {
         if (!handedOff) {
            this.inFlight.remove(player.getUniqueId());
         }

      }
   }

   /** Teammates and mutual friends are never hidden from each other. */
   private boolean isTrusted(Player viewer, Entity entity) {
      if (!(entity instanceof Player other)) {
         return false;
      }

      if (this.dsConfig.isFriendsEnabled() && this.plugin.getFriendManager().areMutualFriends(viewer.getUniqueId(), other.getUniqueId())) {
         return true;
      }

      if (this.dsConfig.isTeamVisible()) {
         org.bukkit.scoreboard.Scoreboard board = viewer.getScoreboard();
         org.bukkit.scoreboard.Team mine = board.getEntryTeam(viewer.getName());
         org.bukkit.scoreboard.Team theirs = board.getEntryTeam(other.getName());
         // Ignore our own nameplate-hiding team: it is not a real player team.
         return mine != null && theirs != null && mine.equals(theirs) && !mine.getName().startsWith("stopesp");
      }

      return false;
   }

   private boolean losAsync(AntiXrayManager xray, World world, double ex, double ey, double ez, Snap s) {
      final int need = this.dsConfig.getAntiEspMinVisibleSamples();
      int seen = 0;
      if (s.living) {
         // Centre line: head, feet, middle.
         double mid = (s.eyeHeight + 0.1) / 2.0;
         if (xray.isPathClear(world, ex, ey, ez, s.x, s.y + s.eyeHeight, s.z) && ++seen >= need) {
            return true;
         }

         if (xray.isPathClear(world, ex, ey, ez, s.x, s.y + 0.1, s.z) && ++seen >= need) {
            return true;
         }

         if (xray.isPathClear(world, ex, ey, ez, s.x, s.y + mid, s.z) && ++seen >= need) {
            return true;
         }

         // Hitbox corners (head, middle, feet). One lone corner no longer reveals the whole player (and the
         // nametag a cheat draws above their head); `need` of the 15 points must be visible.
         double[] heights = new double[]{s.eyeHeight, mid, 0.1};
         for(double h : heights) {
            for(int sx = -1; sx <= 1; sx += 2) {
               for(int sz = -1; sz <= 1; sz += 2) {
                  if (xray.isPathClear(world, ex, ey, ez, s.x + sx * 0.3, s.y + h, s.z + sz * 0.3) && ++seen >= need) {
                     return true;
                  }
               }
            }
         }

         return false;
      }

      // Non-living entities (items, frames, boats...) keep the original two-point test.
      return xray.isPathClear(world, ex, ey, ez, s.x, s.y + s.height / 2.0, s.z) || xray.isPathClear(world, ex, ey, ez, s.x, s.y, s.z);
   }

   /** Entity thread: hide/show based on the computed results. */
   private void apply(Player player, List<Snap> snaps, List<Candidate> skipped, Set<UUID> nearbyUuids, Map<UUID, Entity> playerHidden, Map<UUID, LosCache> cache, double ex, double ey, double ez, double yaw, double pitch) {
      for(Snap snap : snaps) {
         Entity entity = snap.entity;
         if (!snap.cached) {
            cache.put(snap.id, new LosCache(snap.x, snap.y, snap.z, ex, ey, ez, snap.losVisible, 0, yaw, pitch));
         } else {
            cache.put(snap.id, new LosCache(snap.x, snap.y, snap.z, ex, ey, ez, snap.losVisible, snap.cachedAge, yaw, pitch));
         }

         if (entity.isValid()) {
            boolean canSee = snap.forceVisible || snap.losVisible && !snap.invisible;
            long graceMs = this.dsConfig.getAntiEspHideDelayMs();
            if (graceMs > 0L && entity instanceof Player) {
               Map<UUID, Long> seen = this.lastSeen.computeIfAbsent(player.getUniqueId(), (k) -> new ConcurrentHashMap());
               long now = System.currentTimeMillis();
               if (snap.losVisible && !snap.invisible) {
                  seen.put(snap.id, now);
               } else if (!canSee && !playerHidden.containsKey(snap.id)) {
                  Long t = seen.get(snap.id);
                  if (t != null && now - t < graceMs) {
                     canSee = true; // just lost sight: keep them for a moment so they don't flicker
                  }
               }
            }

            if (canSee) {
               if (playerHidden.remove(snap.id) != null) {
                  player.showEntity(this.plugin, entity);
               }
            } else if (playerHidden.put(snap.id, entity) == null) {
               player.hideEntity(this.plugin, entity);
            }
         }
      }

      // Not checked this scan: keep old state, but hide never-seen entities by default (fail closed).
      for(Candidate c : skipped) {
         if (c.entity.isValid() && playerHidden.put(c.entity.getUniqueId(), c.entity) == null) {
            player.hideEntity(this.plugin, c.entity);
         }
      }

      cache.keySet().retainAll(nearbyUuids);
      Map<UUID, Long> seenMap = this.lastSeen.get(player.getUniqueId());
      if (seenMap != null) {
         seenMap.keySet().retainAll(nearbyUuids);
      }

      playerHidden.entrySet().removeIf((entry) -> {
         UUID uuid = (UUID)entry.getKey();
         if (!nearbyUuids.contains(uuid)) {
            Entity entity = (Entity)entry.getValue();
            if (entity.isValid()) {
               player.showEntity(this.plugin, entity);
            }

            return true;
         } else {
            return false;
         }
      });

      // Nameplate hiding via a scoreboard team was removed: hidden players are not sent to the client at
      // all (so there is no nameplate to hide), and putting players into a "stopesp_hidden" team on the
      // shared main scoreboard pulled them out of their rank/tab-sorting team and broke the tab list.
   }

   /**
    * Fail-closed: hide every obfuscated player around {@code viewer} right now, before the entity tracker has
    * a chance to send them, and drop cached line-of-sight results so the next scan has to prove visibility.
    * Call on the viewer's entity thread (join, respawn, teleport, world change).
    */
   public void failCloseViewer(Player viewer, Location around) {
      if (!this.enabled || !this.dsConfig.isAntiEspFailClosed() || !viewer.isOnline() || viewer.hasPermission("stopesp.bypass")
            || around.getWorld() == null || !this.dsConfig.isEnabledWorld(around.getWorld()) || !this.dsConfig.isAntiEspObfuscatePlayers()) {
         return;
      }

      Map<UUID, Entity> hidden = this.hiddenEntities.computeIfAbsent(viewer.getUniqueId(), (k) -> new ConcurrentHashMap());
      Map<UUID, LosCache> cache = this.losCache.get(viewer.getUniqueId());
      if (cache != null) {
         cache.clear();
      }

      Map<UUID, Long> seen = this.lastSeen.get(viewer.getUniqueId());
      if (seen != null) {
         seen.clear();
      }

      double r = this.dsConfig.getAntiEspPlayerRadius();
      double rSq = r * r;
      for(Player other : around.getWorld().getPlayers()) {
         if (!other.equals(viewer) && other.getLocation().distanceSquared(around) <= rSq && !this.nearEnough(other.getLocation(), around) && !this.isTrusted(viewer, other) && hidden.put(other.getUniqueId(), other) == null) {
            viewer.hideEntity(this.plugin, other);
         }
      }
   }

   private boolean nearEnough(Location a, Location b) {
      double r = this.dsConfig.getAntiEspNearRevealRadius();
      return r > 0.0 && a.getWorld() == b.getWorld() && a.distanceSquared(b) <= r * r;
   }

   /** Fail-closed from the other side: {@code target} just joined/teleported, so hide it from everyone first. */
   public void failCloseTarget(Player target, Location around) {
      if (!this.enabled || !this.dsConfig.isAntiEspFailClosed() || !this.dsConfig.isAntiEspObfuscatePlayers()) {
         return;
      }

      double r = this.dsConfig.getAntiEspPlayerRadius();
      double rSq = r * r;
      for(Player viewer : around.getWorld().getPlayers()) {
         if (viewer.equals(target) || viewer.getLocation().distanceSquared(around) > rSq || this.nearEnough(viewer.getLocation(), around) || viewer.hasPermission("stopesp.bypass") || !this.dsConfig.isEnabledWorld(viewer.getWorld()) || this.isTrusted(viewer, target)) {
            continue;
         }

         Map<UUID, LosCache> cache = this.losCache.get(viewer.getUniqueId());
         if (cache != null) {
            cache.remove(target.getUniqueId());
         }

         Map<UUID, Long> seen = this.lastSeen.get(viewer.getUniqueId());
         if (seen != null) {
            seen.remove(target.getUniqueId());
         }

         Map<UUID, Entity> hidden = this.hiddenEntities.computeIfAbsent(viewer.getUniqueId(), (k) -> new ConcurrentHashMap());
         if (hidden.put(target.getUniqueId(), target) == null) {
            viewer.hideEntity(this.plugin, target);
         }
      }
   }

   /** Removes the old "stopesp_hidden" team (it is saved with the world's scoreboard) from every scoreboard. */
   private void purgeNameplateTeams() {
      Scheduler.run(this.plugin, () -> {
         try {
            if (Bukkit.getScoreboardManager() != null) {
               Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("stopesp_hidden");
               if (t != null) {
                  t.unregister();
               }
            }

            for(Player online : Bukkit.getOnlinePlayers()) {
               Team t = online.getScoreboard().getTeam("stopesp_hidden");
               if (t != null) {
                  t.unregister();
               }
            }
         } catch (IllegalStateException | IllegalArgumentException ignored) {
         }
      });
   }

   private boolean shouldObfuscate(Entity entity) {
      if (this.dsConfig.getAntiEspExtraEntityHide().contains(entity.getType())) {
         return true;
      } else if (entity instanceof Player) {
         return this.dsConfig.isAntiEspObfuscatePlayers();
      } else if (entity instanceof ArmorStand) {
         return this.dsConfig.isAntiEspHideArmorStands() || this.dsConfig.isAntiEspObfuscateMobs();
      } else if (entity instanceof Painting) {
         return this.dsConfig.isAntiEspHidePaintings();
      } else if (entity instanceof LeashHitch) {
         return this.dsConfig.isAntiEspHideLeashKnots();
      } else if (entity instanceof Boat) {
         return this.dsConfig.isAntiEspHideBoats();
      } else if (entity instanceof Minecart) {
         return this.dsConfig.isAntiEspHideMinecarts();
      } else if (entity instanceof AbstractVillager) {
         return this.dsConfig.isAntiEspHideVillagers() || this.dsConfig.isAntiEspObfuscateMobs();
      } else if (entity instanceof Item) {
         return this.dsConfig.isAntiEspHideItems();
      } else if (entity instanceof Projectile) {
         return this.dsConfig.isAntiEspHideProjectiles();
      } else if (entity instanceof ItemFrame) {
         return this.dsConfig.isAntiEspHideItemFrames();
      } else {
         return entity instanceof LivingEntity ? this.dsConfig.isAntiEspObfuscateMobs() : false;
      }
   }

   private boolean canSeeEntity(Player player, Entity entity) {
      Location eyeLocation = player.getEyeLocation();
      if (entity instanceof LivingEntity living) {
         Location entityEye = living.getEyeLocation();
         if (this.hasLineOfSightThroughTransparents(eyeLocation, entityEye)) {
            return true;
         }

         Location entityFeet = entity.getLocation().clone().add((double)0.0F, 0.1, (double)0.0F);
         if (this.hasLineOfSightThroughTransparents(eyeLocation, entityFeet)) {
            return true;
         }

         double halfHeight = (living.getEyeHeight() + 0.1) / (double)2.0F;
         Location entityMid = entity.getLocation().clone().add((double)0.0F, halfHeight, (double)0.0F);
         if (this.hasLineOfSightThroughTransparents(eyeLocation, entityMid)) {
            return true;
         }
      } else {
         Location entityLoc = entity.getLocation();
         double height = entity.getHeight();
         Location center = entityLoc.clone().add((double)0.0F, height / (double)2.0F, (double)0.0F);
         if (this.hasLineOfSightThroughTransparents(eyeLocation, center)) {
            return true;
         }

         if (this.hasLineOfSightThroughTransparents(eyeLocation, entityLoc)) {
            return true;
         }
      }

      return false;
   }

   private boolean hasLineOfSightThroughTransparents(Location start, Location end) {
      if (start.getWorld() != end.getWorld()) {
         return false;
      } else {
         Vector direction = end.toVector().subtract(start.toVector());
         double totalDistance = direction.length();
         if (totalDistance < 0.01) {
            return true;
         } else {
            direction.normalize();
            Location current = start.clone();
            double distanceTraveled = (double)0.0F;
            int maxIterations = 100;

            while(true) {
               if (distanceTraveled < totalDistance && maxIterations-- > 0) {
                  double remaining = totalDistance - distanceTraveled;
                  RayTraceResult result = start.getWorld().rayTraceBlocks(current, direction, remaining, FluidCollisionMode.NEVER, false);
                  if (result != null && result.getHitBlock() != null) {
                     Block hitBlock = result.getHitBlock();
                     Material hitMaterial = hitBlock.getType();
                     if (!TransparentBlocks.isTransparent(hitMaterial)) {
                        return false;
                     }

                     Vector hitPoint = result.getHitPosition();
                     double hitDistance = hitPoint.distance(current.toVector());
                     distanceTraveled += hitDistance + 0.05;
                     current = start.clone().add(direction.clone().multiply(distanceTraveled));
                     continue;
                  }

                  return true;
               }

               return true;
            }
         }
      }
   }

   private boolean isConsideredInvisible(Entity entity) {
      if (entity instanceof Player p) {
         return p.isInvisible() || p.getGameMode() == GameMode.SPECTATOR;
      }
      return entity instanceof LivingEntity living && living.isInvisible();
   }

   // ponytail: server-side nametag suppression via a per-viewer scoreboard team (NEVER visibility).
   // A cheat that force-shows the entity still can't read the floating name. Runs on the main thread.
   private void applyNameplateHiding(Player viewer, Map<UUID, Entity> hidden) {
      Set<String> hiddenNames = new HashSet();
      for(Entity entity : hidden.values()) {
         if (entity instanceof Player p && p.isValid()) {
            hiddenNames.add(p.getName());
         }
      }

      Scheduler.run(this.plugin, () -> {
         if (!viewer.isOnline()) {
            return;
         }
         Scoreboard board = viewer.getScoreboard();
         this.nameplateBoards.put(viewer.getUniqueId(), board);
         Team team = board.getTeam("stopesp_hidden");
         if (team == null) {
            team = board.registerNewTeam("stopesp_hidden");
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
         }

         for(String entry : new HashSet<>(team.getEntries())) {
            if (!hiddenNames.contains(entry)) {
               team.removeEntry(entry);
            }
         }

         for(String name : hiddenNames) {
            if (!team.hasEntry(name)) {
               team.addEntry(name);
            }
         }
      });
   }

   private void clearNameplateHiding(Player viewer) {
      Scoreboard board = this.nameplateBoards.remove(viewer.getUniqueId());
      if (board != null) {
         Scheduler.run(this.plugin, () -> {
            Team team = board.getTeam("stopesp_hidden");
            if (team != null) {
               team.unregister();
            }
         });
      }
   }

   public void stopTask() {
      this.enabled = false;
      if (this.scanTask != null) {
         this.scanTask.cancel();
      }

      for(Map.Entry<UUID, Map<UUID, Entity>> entry : this.hiddenEntities.entrySet()) {
         Player player = Bukkit.getPlayer((UUID)entry.getKey());
         if (player != null && player.isOnline()) {
            for(Entity entity : entry.getValue().values()) {
               if (entity.isValid()) {
                  player.showEntity(this.plugin, entity);
               }
            }

            this.clearNameplateHiding(player);
         }
      }

      this.hiddenEntities.clear();
   }

   /** True if Anti-ESP is currently hiding {@code target} from {@code viewer}. Safe to call off-thread. */
   public boolean isHiddenByAntiEsp(UUID viewer, UUID target) {
      Map<UUID, Entity> hidden = (Map)this.hiddenEntities.get(viewer);
      return hidden != null && hidden.containsKey(target);
   }

   /** Call when a player leaves so their real tab-list removal is not blocked. */
   public void forgetTarget(UUID target) {
      for(Map<UUID, Entity> hidden : this.hiddenEntities.values()) {
         hidden.remove(target);
      }

   }

   public void cleanupPlayer(Player player) {
      this.lastSeen.remove(player.getUniqueId());
      this.scanRotation.remove(player.getUniqueId());
      this.losCache.remove(player.getUniqueId());
      this.inFlight.remove(player.getUniqueId());
      this.clearNameplateHiding(player);
      Map<UUID, Entity> playerHidden = (Map)this.hiddenEntities.remove(player.getUniqueId());
      if (playerHidden != null && player.isOnline()) {
         for(Entity entity : playerHidden.values()) {
            if (entity.isValid()) {
               player.showEntity(this.plugin, entity);
            }
         }
      }

   }
}
