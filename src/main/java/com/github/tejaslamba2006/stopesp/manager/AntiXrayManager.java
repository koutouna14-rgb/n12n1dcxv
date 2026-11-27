package com.github.tejaslamba2006.stopesp.manager;

import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.util.BlockKey;
import com.github.tejaslamba2006.stopesp.util.PerformanceGovernor;
import com.github.tejaslamba2006.stopesp.util.ScanGate;
import com.github.tejaslamba2006.stopesp.util.Scheduler;
import com.github.tejaslamba2006.stopesp.util.TransparentBlocks;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class AntiXrayManager {
   private final StopESP plugin;
   private final DeepShieldConfig dsConfig;
   private boolean enabled = true;
   private final Map<UUID, Map<Long, ChunkData>> chunkMap = new ConcurrentHashMap();
   private final Map<UUID, PlayerData> playerData = new ConcurrentHashMap();
   private volatile ThreadPoolExecutor workerPool;
   private volatile ScheduledExecutorService poolMonitor;
   private int maxThreads;
   private int minThreads;
   private Scheduler.Task obfuscationTask;
   private int tickCounter = 0;
   private long tickGate = 0L;

   public AntiXrayManager(StopESP plugin, DeepShieldConfig dsConfig) {
      this.plugin = plugin;
      this.dsConfig = dsConfig;
      this.createPool();
   }

   /** Builds the worker pool: starts small and grows (up to the max) only while work is queuing up. */
   private synchronized void createPool() {
      int max = this.dsConfig.getWorkerThreads();
      if (max <= 0) {
         max = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
      }

      this.maxThreads = max;
      this.minThreads = Math.max(1, Math.min(this.dsConfig.getMinWorkerThreads(), max));
      AtomicInteger counter = new AtomicInteger();
      ThreadFactory factory = (r) -> {
         Thread t = new Thread(r, "StopESP-Worker-" + counter.incrementAndGet());
         t.setDaemon(true);
         return t;
      };
      ThreadPoolExecutor pool = new ThreadPoolExecutor(this.minThreads, this.minThreads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue(), factory);
      pool.allowCoreThreadTimeOut(true);
      this.workerPool = pool;
      ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor((r) -> {
         Thread t = new Thread(r, "StopESP-PoolMonitor");
         t.setDaemon(true);
         return t;
      });
      monitor.scheduleWithFixedDelay(this::adjustPool, 2L, 2L, TimeUnit.SECONDS);
      this.poolMonitor = monitor;
   }

   private void adjustPool() {
      try {
         ThreadPoolExecutor pool = this.workerPool;
         int core = pool.getCorePoolSize();
         int queued = pool.getQueue().size();
         int active = pool.getActiveCount();
         int cap = this.maxThreads;
         if (PerformanceGovernor.multiplier(this.dsConfig) >= 3) {
            cap = Math.max(this.minThreads, this.maxThreads / 2); // server is struggling: leave CPU to it
         }

         int target = core;
         if (core > cap) {
            target = cap;
         } else if (queued > core * 4 && core < cap) {
            target = core + 1;
         } else if (queued == 0 && active * 2 < core && core > this.minThreads) {
            target = core - 1;
         }

         if (target > core) {
            pool.setMaximumPoolSize(target);
            pool.setCorePoolSize(target);
         } else if (target < core) {
            pool.setCorePoolSize(target);
            pool.setMaximumPoolSize(target);
         }
      } catch (Throwable ignored) {
      }

   }

   public void startScan() {
      this.enabled = true;
      if (this.workerPool.isShutdown()) {
         this.createPool();
      }

      if (this.obfuscationTask != null) {
         this.obfuscationTask.cancel();
      }

      if (!Scheduler.isFolia()) {
         for(World world : Bukkit.getWorlds()) {
            for(Chunk chunk : world.getLoadedChunks()) {
               this.indexChunk(chunk);
            }
         }
      } else {
         for(Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;

            for(int x = -8; x <= 8; ++x) {
               for(int z = -8; z <= 8; ++z) {
                  int tx = cx + x;
                  int tz = cz + z;
                  World world = player.getWorld();
                  Scheduler.runRegion(this.plugin, new Location(world, (double)(tx << 4), (double)0.0F, (double)(tz << 4)), () -> {
                     if (world.isChunkLoaded(tx, tz)) {
                        this.indexChunk(world.getChunkAt(tx, tz));
                     }

                  });
               }
            }
         }
      }

      long interval = this.dsConfig.getScanInterval();
      this.obfuscationTask = Scheduler.runTimer(this.plugin, () -> {
         if (this.enabled) {
            long needed = this.dsConfig.getScanInterval() * (long)PerformanceGovernor.multiplier(this.dsConfig);
            if (++this.tickGate < needed) {
               return;
            }

            this.tickGate = 0L;
            ++this.tickCounter;
            Map<UUID, PlayerUpdateSnapshot> snapshots = new HashMap();

            for(Player player : Bukkit.getOnlinePlayers()) {
               if (this.dsConfig.isEnabledWorld(player.getWorld()) && !player.hasPermission("stopesp.bypass") && !ScanGate.shouldSkip(this.dsConfig, player, (long)this.tickCounter)) {
                  Location eye = player.getEyeLocation();
                  snapshots.put(player.getUniqueId(), new PlayerUpdateSnapshot(player, eye, eye.getBlockX() >> 4, eye.getBlockZ() >> 4, false));
               }
            }

            for(PlayerUpdateSnapshot snap : snapshots.values()) {
               PlayerData data = (PlayerData)this.playerData.computeIfAbsent(snap.player.getUniqueId(), (k) -> new PlayerData());
               if (data.processing.compareAndSet(false, true)) {
                  this.workerPool.submit(() -> {
                     try {
                        this.updateForPlayerAsync(snap);
                     } finally {
                        data.processing.set(false);
                     }

                  });
               }
            }

         }
      }, 1L, 1L);
   }

   public void stopTask() {
      this.enabled = false;
      if (this.obfuscationTask != null) {
         this.obfuscationTask.cancel();
      }

      this.poolMonitor.shutdownNow();
      this.workerPool.shutdown();

      try {
         if (!this.workerPool.awaitTermination(2L, TimeUnit.SECONDS)) {
            this.workerPool.shutdownNow();
         }
      } catch (InterruptedException var3) {
         this.workerPool.shutdownNow();
      }

      for(Player player : Bukkit.getOnlinePlayers()) {
         this.revealAll(player);
      }

   }

   public void updateForPlayerAsync(PlayerUpdateSnapshot snap) {
      Player player = snap.player;
      if (!player.hasPermission("stopesp.bypass")) {
         Location pLoc = snap.eyeLoc;
         PlayerData data = (PlayerData)this.playerData.computeIfAbsent(player.getUniqueId(), (k) -> new PlayerData());
         long now = System.currentTimeMillis();
         boolean force = snap.force || data.forceUpdate.getAndSet(false);
         if (!force) {
            if (now - data.lastUpdateTime < 50L) {
               return;
            }

            if (data.lastUpdateLoc != null && data.lastUpdateLoc.getWorld() == pLoc.getWorld()) {
               double distSq = data.lastUpdateLoc.distanceSquared(pLoc);
               double yawDiff = (double)Math.abs(data.lastUpdateLoc.getYaw() - pLoc.getYaw());
               double pitchDiff = (double)Math.abs(data.lastUpdateLoc.getPitch() - pLoc.getPitch());
               if (distSq < 0.0025 && yawDiff < (double)1.0F && pitchDiff < (double)1.0F) {
                  return;
               }
            }
         }

         data.lastUpdateLoc = pLoc.clone();
         data.lastUpdateTime = now;
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(player.getWorld().getUID());
         if (worldIndex != null) {
            double worldRevealRadius = this.dsConfig.getRevealRadius(player.getWorld());
            int radius = (int)Math.ceil(worldRevealRadius / (double)16.0F) + 1;
            int minHeight = player.getWorld().getMinHeight();
            int maxHeight = player.getWorld().getMaxHeight();
            double revealRadSq = worldRevealRadius * worldRevealRadius;
            Map<Long, BlockData> totalChanges = new HashMap();
            World scanWorld = player.getWorld();
            this.syncWorld(data, scanWorld);
            boolean dirtyOn = this.dsConfig.isDirtyChunksEnabled() && !force;
            double dirtyMoveSq = this.dsConfig.getDirtyMoveDistance() * this.dsConfig.getDirtyMoveDistance();
            double dirtyNearSq = this.dsConfig.getDirtyNearRadius() * this.dsConfig.getDirtyNearRadius();
            Map<Long, Integer> evaluatedVersions = new HashMap();
            Set<Long> unsettledChunks = new HashSet();
            boolean hasVelocity = player.getVelocity().lengthSquared() > 0.01;
            Location futureLoc = hasVelocity ? pLoc.clone().add(player.getVelocity().multiply(3)) : null;
            Vector playerDir = pLoc.getDirection();
            Map<Long, ChunkData> allProtectedChunk = new HashMap();
            Set<Long> directlyVisible = new HashSet();
            List<LosCandidate> candidates = new ArrayList();
            Set<Long> unevaluated = new HashSet();

            for(int x = -radius; x <= radius; ++x) {
               for(int z = -radius; z <= radius; ++z) {
                  int chunkX = snap.chunkX + x;
                  int chunkZ = snap.chunkZ + z;
                  long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);
                  ChunkData chunkData = (ChunkData)worldIndex.get(chunkKey);
                  if (chunkData != null && !chunkData.protectedBlocks.isEmpty()) {
                     if (dirtyOn && this.canSkipChunk(data, chunkKey, chunkData, pLoc, chunkX, chunkZ, dirtyMoveSq, dirtyNearSq)) {
                        continue;
                     }

                     evaluatedVersions.put(chunkKey, chunkData.version);
                     BitSet protectedBits = chunkData.protectedBlocks;

                     for(int i = protectedBits.nextSetBit(0); i >= 0; i = protectedBits.nextSetBit(i + 1)) {
                        int relY = i >> 8;
                        int blockY = relY + minHeight;
                        int relX = i >> 4 & 15;
                        int relZ = i & 15;
                        int blockX = (chunkX << 4) + relX;
                        int blockZ = (chunkZ << 4) + relZ;
                        long blockKey = BlockKey.pack(blockX, blockY, blockZ);
                        allProtectedChunk.put(blockKey, chunkData);
                        double ddx = (double)blockX - pLoc.getX();
                        double ddy = (double)blockY - pLoc.getY();
                        double ddz = (double)blockZ - pLoc.getZ();
                        double distSq = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (!(distSq > this.radiusSqFor(chunkData, blockKey, revealRadSq)) && !this.isBuried(worldIndex, chunkX, chunkZ, relX, blockY, relZ, minHeight, maxHeight)) {
                           if (this.dsConfig.isFrustumCulling() && distSq > (double)9.0F) {
                              Vector blockDir = (new Vector((double)blockX + (double)0.5F - pLoc.getX(), (double)blockY + (double)0.5F - pLoc.getY(), (double)blockZ + (double)0.5F - pLoc.getZ())).normalize();
                              if (playerDir.dot(blockDir) < -0.2) {
                                 continue;
                              }
                           }

                           candidates.add(new LosCandidate(blockKey, new Location(scanWorld, (double)blockX, (double)blockY, (double)blockZ), distSq));
                        }
                     }
                  }
               }
            }

            int checkBudget = this.dsConfig.getMaxBlockChecksPerScan();
            if (checkBudget > 0 && candidates.size() > checkBudget) {
               candidates.sort(Comparator.comparingDouble((c) -> c.distSq));
            }

            int checks = 0;

            for(LosCandidate cand : candidates) {
               if (checkBudget > 0 && checks >= checkBudget) {
                  unevaluated.add(cand.key);
               } else {
                  ++checks;
                  boolean canSee = this.hasLoSAsync(worldIndex, pLoc, cand.loc, true);
                  if (!canSee && hasVelocity) {
                     canSee = this.hasLoSAsync(worldIndex, futureLoc, cand.loc, true);
                  }

                  if (canSee) {
                     directlyVisible.add(cand.key);
                  }
               }
            }

            Set<Long> finalVisible = new HashSet(directlyVisible);
            Set<Long> currentLayer = new HashSet(directlyVisible);

            Set<Long> nextLayer;
            for(int[][] offsets = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}; !currentLayer.isEmpty(); currentLayer = nextLayer) {
               nextLayer = new HashSet();

               for(long key : currentLayer) {
                  int bx = BlockKey.x(key);
                  int bz = BlockKey.z(key);
                  int by = BlockKey.y(key);

                  for(int[] off : offsets) {
                     int ax = bx + off[0];
                     int ay = by + off[1];
                     int az = bz + off[2];
                     long adjKey = BlockKey.pack(ax, ay, az);
                     // Only pull in the other half of a multi-block (bed halves, double chests): the
                     // neighbour must be the SAME material, and we never chain further than that.
                     if (!finalVisible.contains(adjKey) && allProtectedChunk.containsKey(adjKey)
                           && directlyVisible.contains(key)) {
                        BlockData a = (BlockData)allProtectedChunk.get(key).originalBlockData.get(key);
                        BlockData b = (BlockData)allProtectedChunk.get(adjKey).originalBlockData.get(adjKey);
                        if (a != null && b != null && a.getMaterial() == b.getMaterial()) {
                           finalVisible.add(adjKey);
                           nextLayer.add(adjKey);
                        }
                     }
                  }
               }
            }

            int bufferThreshold = this.dsConfig.getVisibilityBuffer();

            for(Map.Entry<Long, ChunkData> entry : allProtectedChunk.entrySet()) {
               long blockKey = (Long)entry.getKey();
               ChunkData chunkData = (ChunkData)entry.getValue();
               if (!finalVisible.contains(blockKey) && unevaluated.contains(blockKey)) {
                  unsettledChunks.add(chunkData.chunkKey);
                  continue;
               }

               if (finalVisible.contains(blockKey)) {
                  data.visibilityBuffer.remove(blockKey);
                  if (data.visibleBlocks.add(blockKey)) {
                     BlockData realData = (BlockData)chunkData.originalBlockData.get(blockKey);
                     totalChanges.put(blockKey, realData != null ? realData : scanWorld.getBlockAt(BlockKey.x(blockKey), BlockKey.y(blockKey), BlockKey.z(blockKey)).getBlockData());
                  }
               } else {
                  int failCount = (Integer)data.visibilityBuffer.getOrDefault(blockKey, 0) + 1;
                  data.visibilityBuffer.put(blockKey, failCount);
                  if (failCount >= bufferThreshold) {
                     if (data.visibleBlocks.remove(blockKey)) {
                        totalChanges.put(blockKey, this.fakeMaterial(scanWorld, BlockKey.x(blockKey), BlockKey.y(blockKey), BlockKey.z(blockKey)).createBlockData());
                     }
                  } else if (data.visibleBlocks.contains(blockKey)) {
                     unsettledChunks.add(chunkData.chunkKey);
                  }
               }
            }

            for(Map.Entry<Long, Integer> ev : evaluatedVersions.entrySet()) {
               data.chunkEval.put((Long)ev.getKey(), new ChunkEval((Integer)ev.getValue(), pLoc, !unsettledChunks.contains(ev.getKey())));
            }

            if (!totalChanges.isEmpty()) {
               Map<Location, BlockData> out = new HashMap();

               for(Map.Entry<Long, BlockData> c : totalChanges.entrySet()) {
                  long k = (Long)c.getKey();
                  out.put(new Location(scanWorld, (double)BlockKey.x(k), (double)BlockKey.y(k), (double)BlockKey.z(k)), (BlockData)c.getValue());
               }

               player.sendMultiBlockChange(out);
            }

         }
      }
   }

   private double radiusSqFor(ChunkData chunkData, long key, double defaultSq) {
      if (!this.dsConfig.isYRulesEnabled()) {
         return defaultSq;
      }

      BlockData original = (BlockData)chunkData.originalBlockData.get(key);
      if (original != null && DeepShieldConfig.isOre(original.getMaterial())) {
         double r = this.dsConfig.getYRulesOreRevealRadius();
         return Math.min(defaultSq, r * r);
      }

      return defaultSq;
   }

   /** True if this block is hidden-type for the player and the client has NOT been shown the real block. */
   public boolean isHiddenProtected(Player player, int x, int y, int z) {
      World world = player.getWorld();
      Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(world.getUID());
      if (worldIndex == null) {
         return false;
      }

      ChunkData chunkData = (ChunkData)worldIndex.get(Chunk.getChunkKey(x >> 4, z >> 4));
      int relY = y - world.getMinHeight();
      if (chunkData == null || relY < 0) {
         return false;
      }

      if (!chunkData.protectedBlocks.get(relY << 8 | (x & 15) << 4 | z & 15)) {
         return false;
      }

      PlayerData data = (PlayerData)this.playerData.get(player.getUniqueId());
      return data == null || !world.getUID().equals(data.worldUid) || !data.visibleBlocks.contains(BlockKey.pack(x, y, z));
   }

   private static final class LosCandidate {
      final long key;
      final Location loc;
      final double distSq;

      LosCandidate(long key, Location loc, double distSq) {
         this.key = key;
         this.loc = loc;
         this.distSq = distSq;
      }
   }

   private boolean isBuried(Map<Long, ChunkData> worldIndex, int cx, int cz, int relX, int y, int relZ, int minHeight, int maxHeight) {
      if (y > minHeight && y < maxHeight - 1) {
         ChunkData center = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx, cz));
         if (center == null) {
            return false;
         } else if (center.isOccluding(relX, y + 1, relZ) && center.isOccluding(relX, y - 1, relZ)) {
            if (relX < 15) {
               if (!center.isOccluding(relX + 1, y, relZ)) {
                  return false;
               }
            } else {
               ChunkData east = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx + 1, cz));
               if (east == null || !east.isOccluding(0, y, relZ)) {
                  return false;
               }
            }

            if (relX > 0) {
               if (!center.isOccluding(relX - 1, y, relZ)) {
                  return false;
               }
            } else {
               ChunkData west = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx - 1, cz));
               if (west == null || !west.isOccluding(15, y, relZ)) {
                  return false;
               }
            }

            if (relZ < 15) {
               if (!center.isOccluding(relX, y, relZ + 1)) {
                  return false;
               }
            } else {
               ChunkData south = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx, cz + 1));
               if (south == null || !south.isOccluding(relX, y, 0)) {
                  return false;
               }
            }

            if (relZ > 0) {
               if (!center.isOccluding(relX, y, relZ - 1)) {
                  return false;
               }
            } else {
               ChunkData north = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx, cz - 1));
               if (north == null || !north.isOccluding(relX, y, 15)) {
                  return false;
               }
            }

            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   /** The protected-block bitset for a chunk (bit = relY << 8 | x << 4 | z), or null if not indexed yet. */
   public BitSet getProtectedBits(World world, int cx, int cz) {
      Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(world.getUID());
      if (worldIndex == null) {
         return null;
      }

      ChunkData data = (ChunkData)worldIndex.get(Chunk.getChunkKey(cx, cz));
      return data == null ? null : data.protectedBlocks;
   }

   /** True if the client of this player has already been shown the real block at this key. */
   public boolean isRevealed(Player player, long blockKey) {
      PlayerData data = (PlayerData)this.playerData.get(player.getUniqueId());
      return data != null && player.getWorld().getUID().equals(data.worldUid) && data.visibleBlocks.contains(blockKey);
   }

   public void forceUpdate(Player player) {
      ((PlayerData)this.playerData.computeIfAbsent(player.getUniqueId(), (k) -> new PlayerData())).forceUpdate.set(true);
   }

   public Material fakeMaterial(World world, int y) {
      return this.getFakeMaterial(world, y);
   }

   /** True once this world has an occlusion index, i.e. async line-of-sight checks are possible. */
   public boolean hasIndex(World world) {
      return this.chunkMap.get(world.getUID()) != null;
   }

   /** Thread-safe ray test between two points using the occlusion index (no Bukkit world access). */
   public boolean isPathClear(World world, double ex, double ey, double ez, double tx, double ty, double tz) {
      Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(world.getUID());
      return worldIndex == null ? true : this.isPathClearAsync(worldIndex, new Location(world, ex, ey, ez), tx, ty, tz);
   }

   /** Runs work on the shared worker pool. Returns false if the pool is shut down. */
   public boolean submitAsync(Runnable task) {
      try {
         this.workerPool.submit(task);
         return true;
      } catch (java.util.concurrent.RejectedExecutionException e) {
         return false;
      }
   }

   public boolean hasLoSAsync(Player player, Location eye, Location target, boolean isBlock) {
      if (eye != null && target != null && eye.getWorld() == target.getWorld()) {
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(eye.getWorld().getUID());
         return worldIndex == null ? false : this.hasLoSAsync(worldIndex, eye, target, isBlock);
      } else {
         return false;
      }
   }

   public boolean hasLoSAsync(Map<Long, ChunkData> worldIndex, Location eye, Location target, boolean isBlock) {
      if (eye.getWorld() != target.getWorld()) {
         return false;
      } else {
         if (isBlock) {
            if (this.isPathClearAsync(worldIndex, eye, target.getX() + (double)0.5F, target.getY() + (double)0.5F, target.getZ() + (double)0.5F)) {
               return true;
            }

            double[][] faces = new double[][]{{-0.05, (double)0.5F, (double)0.5F}, {1.05, (double)0.5F, (double)0.5F}, {(double)0.5F, -0.05, (double)0.5F}, {(double)0.5F, 1.05, (double)0.5F}, {(double)0.5F, (double)0.5F, -0.05}, {(double)0.5F, (double)0.5F, 1.05}};

            for(double[] off : faces) {
               if (this.isPathClearAsync(worldIndex, eye, target.getX() + off[0], target.getY() + off[1], target.getZ() + off[2])) {
                  return true;
               }
            }

            double[][] corners = new double[][]{{-0.05, -0.05, -0.05}, {1.05, -0.05, -0.05}, {-0.05, 1.05, -0.05}, {-0.05, -0.05, 1.05}, {1.05, 1.05, -0.05}, {1.05, -0.05, 1.05}, {-0.05, 1.05, 1.05}, {1.05, 1.05, 1.05}};

            for(double[] off : corners) {
               if (this.isPathClearAsync(worldIndex, eye, target.getX() + off[0], target.getY() + off[1], target.getZ() + off[2])) {
                  return true;
               }
            }
         } else {
            double tx = target.getX();
            double ty = target.getY();
            double tz = target.getZ();
            if (this.isPathClearAsync(worldIndex, eye, tx + (double)0.5F, ty + (double)0.5F, tz + (double)0.5F)) {
               return true;
            }

            double lo = 0.1;
            double hi = 0.9;
            if (this.isPathClearAsync(worldIndex, eye, tx + lo, ty + lo, tz + lo)) {
               return true;
            }

            if (this.isPathClearAsync(worldIndex, eye, tx + hi, ty + hi, tz + hi)) {
               return true;
            }

            if (this.isPathClearAsync(worldIndex, eye, tx + lo, ty + hi, tz + lo)) {
               return true;
            }

            if (this.isPathClearAsync(worldIndex, eye, tx + hi, ty + lo, tz + hi)) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean isPathClearAsync(Map<Long, ChunkData> worldIndex, Location start, double tx, double ty, double tz) {
      double x = start.getX();
      double y = start.getY();
      double z = start.getZ();
      int ix = (int)Math.floor(x);
      int iy = (int)Math.floor(y);
      int iz = (int)Math.floor(z);
      int targetX = (int)Math.floor(tx);
      int targetY = (int)Math.floor(ty);
      int targetZ = (int)Math.floor(tz);
      double dx = tx - x;
      double dy = ty - y;
      double dz = tz - z;
      double rayLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
      int stepX = dx > (double)0.0F ? 1 : (dx < (double)0.0F ? -1 : 0);
      int stepY = dy > (double)0.0F ? 1 : (dy < (double)0.0F ? -1 : 0);
      int stepZ = dz > (double)0.0F ? 1 : (dz < (double)0.0F ? -1 : 0);
      double deltaX = stepX != 0 ? Math.abs((double)1.0F / dx) : Double.MAX_VALUE;
      double deltaY = stepY != 0 ? Math.abs((double)1.0F / dy) : Double.MAX_VALUE;
      double deltaZ = stepZ != 0 ? Math.abs((double)1.0F / dz) : Double.MAX_VALUE;
      double maxX = stepX > 0 ? (Math.floor(x) + (double)1.0F - x) * deltaX : (x - Math.floor(x)) * deltaX;
      double maxY = stepY > 0 ? (Math.floor(y) + (double)1.0F - y) * deltaY : (y - Math.floor(y)) * deltaY;
      double maxZ = stepZ > 0 ? (Math.floor(z) + (double)1.0F - z) * deltaZ : (z - Math.floor(z)) * deltaZ;
      int limit = (int)(Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) + 8; // scales with distance (was a fixed 120 that returned 'visible' on far targets)
      int minHeight = start.getWorld().getMinHeight();
      int maxHeight = start.getWorld().getMaxHeight();
      ChunkData lastChunk = null;
      long lastChunkKey = Long.MIN_VALUE;
      boolean firstStep = true;
      double currentT = (double)0.0F;

      while(limit-- > 0) {
         if (ix == targetX && iy == targetY && iz == targetZ) {
            return true;
         }

         if (iy < minHeight || iy >= maxHeight) {
            return false;
         }

         long chunkKey = Chunk.getChunkKey(ix >> 4, iz >> 4);
         ChunkData chunkData;
         if (chunkKey == lastChunkKey) {
            chunkData = lastChunk;
         } else {
            chunkData = (ChunkData)worldIndex.get(chunkKey);
            lastChunk = chunkData;
            lastChunkKey = chunkKey;
         }

         double nextT = Math.min(maxX, Math.min(maxY, maxZ));
         if (chunkData != null && chunkData.isOccluding(ix & 15, iy, iz & 15)) {
            double distInVoxel = (nextT - currentT) * rayLength;
            if (distInVoxel > 0.15 && (!firstStep || ix != (int)Math.floor(x) || iy != (int)Math.floor(y) || iz != (int)Math.floor(z))) {
               return ix == targetX && iy == targetY && iz == targetZ;
            }
         }

         firstStep = false;
         currentT = nextT;
         if (maxX < maxY) {
            if (maxX < maxZ) {
               ix += stepX;
               maxX += deltaX;
            } else {
               iz += stepZ;
               maxZ += deltaZ;
            }
         } else if (maxY < maxZ) {
            iy += stepY;
            maxY += deltaY;
         } else {
            iz += stepZ;
            maxZ += deltaZ;
         }
      }

      return true;
   }

   public void hideAllInChunk(Player player, int cx, int cz) {
      World world = player.getWorld();
      Scheduler.runRegion(this.plugin, new Location(world, (double)(cx << 4), (double)0.0F, (double)(cz << 4)), () -> {
         if (world.isChunkLoaded(cx, cz)) {
            Chunk chunk = world.getChunkAt(cx, cz);
            this.indexChunk(chunk);
            if (player.isOnline()) {
               Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(world.getUID());
               if (worldIndex != null) {
                  long chunkKey = Chunk.getChunkKey(cx, cz);
                  ChunkData chunkData = (ChunkData)worldIndex.get(chunkKey);
                  if (chunkData != null && !chunkData.protectedBlocks.isEmpty()) {
                     int minHeight = world.getMinHeight();
                     Map<Location, BlockData> changes = new HashMap();
                     PlayerData data = (PlayerData)this.playerData.computeIfAbsent(player.getUniqueId(), (k) -> new PlayerData());
                     this.syncWorld(data, world);
                     BitSet protectedBits = chunkData.protectedBlocks;

                     for(int i = protectedBits.nextSetBit(0); i >= 0; i = protectedBits.nextSetBit(i + 1)) {
                        int relY = i >> 8;
                        int blockY = relY + minHeight;
                        int relX = i >> 4 & 15;
                        int relZ = i & 15;
                        int wx = (cx << 4) + relX;
                        int wz = (cz << 4) + relZ;
                        if (!data.visibleBlocks.contains(BlockKey.pack(wx, blockY, wz))) {
                           changes.put(new Location(world, (double)wx, (double)blockY, (double)wz), this.fakeMaterial(world, wx, blockY, wz).createBlockData());
                        }
                     }

                     if (!changes.isEmpty()) {
                        player.sendMultiBlockChange(changes);
                     }

                     data.forceUpdate.set(true);
                  }
               }
            }
         }
      });
   }

   public void hideAllInChunk(Player player, Chunk chunk) {
      this.hideAllInChunk(player, chunk.getX(), chunk.getZ());
   }

   public void revealAll(Player player) {
      if (player.isOnline() && this.dsConfig.isEnabledWorld(player.getWorld())) {
         Location eye = player.getEyeLocation();
         this.updateForPlayerAsync(new PlayerUpdateSnapshot(player, eye, player.getChunk().getX(), player.getChunk().getZ(), true));
      }
   }

   public void revealInstant(Location origin) {
      if (origin != null && origin.getWorld() != null) {
         for(Player player : origin.getWorld().getPlayers()) {
            if (!(player.getLocation().distanceSquared(origin) > (double)1600.0F)) {
               PlayerData data = (PlayerData)this.playerData.computeIfAbsent(player.getUniqueId(), (k) -> new PlayerData());
               data.forceUpdate.set(true);
               if (data.processing.compareAndSet(false, true)) {
                  Location eye = player.getEyeLocation();
                  PlayerUpdateSnapshot snap = new PlayerUpdateSnapshot(player, eye, player.getChunk().getX(), player.getChunk().getZ(), true);
                  this.workerPool.submit(() -> {
                     try {
                        this.updateForPlayerAsync(snap);
                     } finally {
                        data.processing.set(false);
                     }

                  });
               }
            }
         }

      }
   }

   /** Occluder for line of sight: solid blocks, plus leaves when anti-esp.leaves-block-sight is on. */
   private boolean blocksSight(Material type) {
      if (type.isOccluding() && !TransparentBlocks.isTransparent(type)) {
         return true;
      }

      return this.dsConfig.isLeavesBlockSight() && type.name().endsWith("_LEAVES");
   }

   public void indexChunk(Chunk chunk) {
      if (this.dsConfig.isEnabledWorld(chunk.getWorld())) {
         UUID worldUid = chunk.getWorld().getUID();
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.computeIfAbsent(worldUid, (k) -> new ConcurrentHashMap());
         long chunkKey = chunk.getChunkKey();
         if (!worldIndex.containsKey(chunkKey)) {
            int minHeight = chunk.getWorld().getMinHeight();
            int maxHeight = chunk.getWorld().getMaxHeight();
            ChunkSnapshot snapshot = chunk.getChunkSnapshot();
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();
            World world = chunk.getWorld();
            final boolean contextMode = this.dsConfig.getFakeBlockMode().equalsIgnoreCase("CONTEXT");
            this.workerPool.submit(() -> {
               if (!worldIndex.containsKey(chunkKey)) {
                  ChunkData chunkData = new ChunkData(chunkKey, minHeight);
                  boolean hasProtected = false;

                  for(int y = minHeight; y < maxHeight; ++y) {
                     int relY = y - minHeight;

                     for(int x = 0; x < 16; ++x) {
                        for(int z = 0; z < 16; ++z) {
                           Material type = snapshot.getBlockType(x, y, z);
                           int bitIndex = relY << 8 | x << 4 | z;
                           if (this.blocksSight(type)) {
                              chunkData.occludingBlocks.set(bitIndex);
                           }

                           if (this.dsConfig.shouldProtect(type, y)) {
                              chunkData.protectedBlocks.set(bitIndex);
                              hasProtected = true;
                              chunkData.originalBlockData.put(BlockKey.pack((chunkX << 4) + x, y, (chunkZ << 4) + z), snapshot.getBlockData(x, y, z));
                              if (contextMode) {
                                 Material fake = this.contextFake(snapshot, x, y, z, minHeight, maxHeight);
                                 if (fake != null) {
                                    chunkData.fakeBlocks.put(BlockKey.pack((chunkX << 4) + x, y, (chunkZ << 4) + z), fake);
                                 }
                              }
                           }
                        }
                     }
                  }

                  if (hasProtected || !chunkData.occludingBlocks.isEmpty()) {
                     worldIndex.put(chunkKey, chunkData);
                  }

               }
            });
         }
      }
   }

   public void removeChunk(Chunk chunk) {
      Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(chunk.getWorld().getUID());
      if (worldIndex != null) {
         worldIndex.remove(chunk.getChunkKey());
      }

   }

   public void addBlock(Player player, Block block) {
      if (this.dsConfig.isEnabledWorld(block.getWorld())) {
         UUID worldUid = block.getWorld().getUID();
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.computeIfAbsent(worldUid, (k) -> new ConcurrentHashMap());
         long chunkKey = block.getChunk().getChunkKey();
         ChunkData chunkData = (ChunkData)worldIndex.computeIfAbsent(chunkKey, (k) -> new ChunkData(chunkKey, block.getWorld().getMinHeight()));
         int relY = block.getY() - block.getWorld().getMinHeight();
         int bitIndex = relY << 8 | (block.getX() & 15) << 4 | block.getZ() & 15;
         if (this.blocksSight(block.getType())) {
            chunkData.occludingBlocks.set(bitIndex);
         } else {
            chunkData.occludingBlocks.clear(bitIndex);
         }

         if (this.dsConfig.shouldProtect(block.getType(), block.getY())) {
            chunkData.protectedBlocks.set(bitIndex);
            chunkData.originalBlockData.put(BlockKey.pack(block.getX(), block.getY(), block.getZ()), block.getBlockData());
            if (player != null) {
               PlayerData data = (PlayerData)this.playerData.get(player.getUniqueId());
               if (data != null) {
                  this.syncWorld(data, block.getWorld());
                  data.visibleBlocks.add(BlockKey.pack(block.getX(), block.getY(), block.getZ()));
               }
            }
         }

         ++chunkData.version;

         this.revealInstant(block.getLocation());
      }
   }

   public void removeBlock(Block block) {
      if (this.dsConfig.isEnabledWorld(block.getWorld())) {
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(block.getWorld().getUID());
         if (worldIndex != null) {
            long chunkKey = block.getChunk().getChunkKey();
            ChunkData chunkData = (ChunkData)worldIndex.get(chunkKey);
            if (chunkData != null) {
               int relY = block.getY() - block.getWorld().getMinHeight();
               int bitIndex = relY << 8 | (block.getX() & 15) << 4 | block.getZ() & 15;
               chunkData.occludingBlocks.clear(bitIndex);
               chunkData.protectedBlocks.clear(bitIndex);
               chunkData.originalBlockData.remove(BlockKey.pack(block.getX(), block.getY(), block.getZ()));
               ++chunkData.version;
            }

            this.revealInstant(block.getLocation());
         }
      }
   }

   /** Natural full blocks a hidden block may be disguised as in CONTEXT mode (never ores, never anything see-through). */
   private static final Set<Material> FAKE_CANDIDATES = EnumSet.of(
         Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.PODZOL,
         Material.MYCELIUM, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SANDSTONE, Material.RED_SANDSTONE,
         Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CLAY, Material.NETHERRACK,
         Material.BASALT, Material.BLACKSTONE, Material.END_STONE, Material.TERRACOTTA, Material.CALCITE,
         Material.DRIPSTONE_BLOCK, Material.MOSS_BLOCK);

   private static final int[][] NEIGHBOURS = new int[][]{{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

   /**
    * CONTEXT mode: look at the natural blocks touching this one (in the same chunk) and disguise it as the most
    * common one, so a hidden chest under grass reads as grass, in a dirt tunnel as dirt, in deepslate as deepslate.
    * Returns null when nothing natural is adjacent (caller falls back to the per-dimension default).
    */
   private Material contextFake(ChunkSnapshot snapshot, int x, int y, int z, int minHeight, int maxHeight) {
      EnumMap<Material, Integer> counts = new EnumMap<>(Material.class);
      Material best = null;
      int bestCount = 0;
      for(int[] n : NEIGHBOURS) {
         int nx = x + n[0];
         int ny = y + n[1];
         int nz = z + n[2];
         if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < minHeight || ny >= maxHeight) {
            continue;
         }

         Material m = snapshot.getBlockType(nx, ny, nz);
         if (!FAKE_CANDIDATES.contains(m)) {
            continue;
         }

         int c = counts.merge(m, 1, Integer::sum);
         if (c > bestCount) { // strict: on a tie the earlier neighbour (the block below) wins
            best = m;
            bestCount = c;
         }
      }

      return best;
   }

   /** The fake block for this exact position. All send paths use this so a block is always disguised the same way. */
   public Material fakeMaterial(World world, int x, int y, int z) {
      if (world != null && this.dsConfig.getFakeBlockMode().equalsIgnoreCase("CONTEXT")) {
         Map<Long, ChunkData> worldIndex = (Map)this.chunkMap.get(world.getUID());
         ChunkData cd = worldIndex == null ? null : (ChunkData)worldIndex.get(Chunk.getChunkKey(x >> 4, z >> 4));
         Material m = cd == null ? null : cd.fakeBlocks.get(BlockKey.pack(x, y, z));
         if (m != null) {
            return m;
         }
      }

      return this.getFakeMaterial(world, y);
   }

   private Material getFakeMaterial(World world, int blockY) {
      String mode = this.dsConfig.getFakeBlockMode();
      if (!mode.equalsIgnoreCase("AUTO") && !mode.equalsIgnoreCase("CONTEXT")) {
         try {
            return Material.valueOf(mode.toUpperCase());
         } catch (IllegalArgumentException var4) {
         }
      }

      if (world == null) {
         return Material.STONE;
      } else {
         switch (world.getEnvironment()) {
            case NETHER -> {
               return Material.NETHERRACK;
            }
            case THE_END -> {
               return Material.END_STONE;
            }
            default -> {
               return blockY < 0 ? Material.DEEPSLATE : Material.STONE;
            }
         }
      }
   }

   public void cleanupPlayer(Player player) {
      this.playerData.remove(player.getUniqueId());
      com.github.tejaslamba2006.stopesp.util.ActivityTracker.remove(player.getUniqueId());
   }

   public void cleanupChunk(Chunk chunk) {
      long chunkKey = chunk.getChunkKey();
      UUID worldUid = chunk.getWorld().getUID();
      this.workerPool.submit(() -> {
         for(PlayerData data : this.playerData.values()) {
            if (worldUid.equals(data.worldUid)) {
               data.visibleBlocks.removeIf((k) -> BlockKey.chunkKey(k) == chunkKey);
               data.visibilityBuffer.keySet().removeIf((k) -> BlockKey.chunkKey(k) == chunkKey);
               data.chunkEval.remove(chunkKey);
            }
         }

      });
   }

   public String[] getDebugInfo(Player player) {
      PlayerData data = (PlayerData)this.playerData.get(player.getUniqueId());
      if (data == null) {
         return new String[]{"No tracking data (player never scanned)."};
      } else {
         String[] var10000 = new String[]{"Visible blocks: " + data.visibleBlocks.size(), "Visibility buffer entries: " + data.visibilityBuffer.size(), "Scan processing: " + data.processing.get(), "Force update queued: " + data.forceUpdate.get(), "Has bypass perm: " + player.hasPermission("stopesp.bypass"), null, null};
         DeepShieldConfig var10003 = this.dsConfig;
         var10000[5] = "World enabled: " + var10003.isEnabledWorld(player.getWorld());
         var10003 = this.dsConfig;
         var10000[6] = "Reveal radius: " + var10003.getRevealRadius(player.getWorld()) + " blocks";
         return var10000;
      }
   }

   public DeepShieldConfig getDsConfig() {
      return this.dsConfig;
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public static class ChunkData {
      final long chunkKey;
      final int worldMinHeight;
      final BitSet protectedBlocks = new BitSet();
      final BitSet occludingBlocks = new BitSet();
      final Map<Long, BlockData> originalBlockData = new ConcurrentHashMap();
      final Map<Long, Material> fakeBlocks = new ConcurrentHashMap();
      volatile int version;

      public ChunkData(long chunkKey, int worldMinHeight) {
         this.chunkKey = chunkKey;
         this.worldMinHeight = worldMinHeight;
      }

      public boolean isOccluding(int x, int y, int z) {
         if (y < this.worldMinHeight) {
            return true;
         } else {
            int relY = y - this.worldMinHeight;
            return this.occludingBlocks.get(relY << 8 | x << 4 | z);
         }
      }
   }

   private static class PlayerData {
      Location lastUpdateLoc;
      long lastUpdateTime;
      volatile UUID worldUid;
      final Set<Long> visibleBlocks = ConcurrentHashMap.newKeySet();
      final Map<Long, Integer> visibilityBuffer = new ConcurrentHashMap();
      final Map<Long, ChunkEval> chunkEval = new ConcurrentHashMap();
      final AtomicBoolean processing = new AtomicBoolean(false);
      final AtomicBoolean forceUpdate = new AtomicBoolean(false);
   }

   /** What the player's view of a chunk looked like when it was last fully evaluated. */
   private static final class ChunkEval {
      final int version;
      final double x;
      final double y;
      final double z;
      final float yaw;
      final boolean settled;

      ChunkEval(int version, Location at, boolean settled) {
         this.version = version;
         this.x = at.getX();
         this.y = at.getY();
         this.z = at.getZ();
         this.yaw = at.getYaw();
         this.settled = settled;
      }
   }

   /** Block keys are not world-specific, so a player's tracking is reset when they change world. */
   private void syncWorld(PlayerData data, World world) {
      UUID uid = world.getUID();
      if (!uid.equals(data.worldUid)) {
         data.visibleBlocks.clear();
         data.visibilityBuffer.clear();
         data.chunkEval.clear();
         data.worldUid = uid;
      }

   }

   private boolean canSkipChunk(PlayerData data, long chunkKey, ChunkData chunkData, Location eye, int chunkX, int chunkZ, double moveSq, double nearSq) {
      ChunkEval ev = (ChunkEval)data.chunkEval.get(chunkKey);
      if (ev != null && ev.settled && ev.version == chunkData.version) {
         double minX = (double)(chunkX << 4);
         double minZ = (double)(chunkZ << 4);
         double dx = eye.getX() < minX ? minX - eye.getX() : (eye.getX() > minX + 16.0 ? eye.getX() - minX - 16.0 : 0.0);
         double dz = eye.getZ() < minZ ? minZ - eye.getZ() : (eye.getZ() > minZ + 16.0 ? eye.getZ() - minZ - 16.0 : 0.0);
         if (dx * dx + dz * dz < nearSq) {
            return false;
         } else {
            double mx = eye.getX() - ev.x;
            double my = eye.getY() - ev.y;
            double mz = eye.getZ() - ev.z;
            if (mx * mx + my * my + mz * mz > moveSq) {
               return false;
            } else {
               return !this.dsConfig.isFrustumCulling() || Math.abs((eye.getYaw() - ev.yaw + 540.0F) % 360.0F - 180.0F) <= 30.0F;
            }
         }
      } else {
         return false;
      }
   }

   private static class PlayerUpdateSnapshot {
      final Player player;
      final Location eyeLoc;
      final int chunkX;
      final int chunkZ;
      final boolean force;

      PlayerUpdateSnapshot(Player player, Location eyeLoc, int chunkX, int chunkZ, boolean force) {
         this.player = player;
         this.eyeLoc = eyeLoc;
         this.chunkX = chunkX;
         this.chunkZ = chunkZ;
         this.force = force;
      }
   }
}
