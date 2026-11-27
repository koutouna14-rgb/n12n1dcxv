package com.github.tejaslamba2006.stopesp.chunk;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.manager.AntiXrayManager;
import com.github.tejaslamba2006.stopesp.util.BlockKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Hides protected blocks INSIDE the chunk packet, per player, before it is sent, so the real blocks
 * never reach the client (not even for a moment, and not for a packet sniffer).
 *
 * It works on a clone of the packet (chunk packets can be shared between players), edits only the
 * block-state palettes of sections that contain protected blocks, and fails safe: if anything about
 * the packet looks unexpected the packet is left alone and the older "hide right after sending"
 * path takes over. After repeated failures with no success the feature switches itself off.
 */
public class ChunkPacketRewriter {
   private static final int MAX_FAILURES_BEFORE_DISABLE = 5;
   private final StopESP plugin;
   private final AntiXrayManager manager;
   private volatile boolean disabled;
   private volatile boolean initialized;
   private Field packetChunkDataField;
   private Field dataBufferField;
   private Method blockGetId;
   private Object unsafe;
   private Method allocateInstance;
   private final Map<Material, Integer> fakeIds = new ConcurrentHashMap();
   private final AtomicInteger failures = new AtomicInteger();
   private final AtomicInteger successes = new AtomicInteger();

   public ChunkPacketRewriter(StopESP plugin, AntiXrayManager manager) {
      this.plugin = plugin;
      this.manager = manager;
   }

   /** True when the chunk needs no further hiding (fully handled, or nothing to hide). */
   public boolean tryRewrite(PacketEvent event, Player player, int chunkX, int chunkZ) {
      if (this.disabled) {
         return false;
      }

      try {
         if (!this.initialized && !this.init()) {
            return false;
         }

         World world = player.getWorld();
         BitSet protectedBits = this.manager.getProtectedBits(world, chunkX, chunkZ);
         if (protectedBits == null) {
            return false; // not indexed yet: let the post-send path handle it
         }

         if (protectedBits.isEmpty()) {
            return true;
         }

         PacketContainer clone = event.getPacket().shallowClone();
         Object nmsPacket = clone.getHandle();
         Object chunkData = this.packetChunkDataField.get(nmsPacket);
         byte[] buffer = (byte[])this.dataBufferField.get(chunkData);
         if (buffer == null) {
            return this.fail("chunk buffer was null");
         }

         int minHeight = world.getMinHeight();
         int sections = (world.getMaxHeight() - minHeight) >> 4;
         int cx = chunkX;
         int cz = chunkZ;
         ChunkSectionRewriter.Result result = ChunkSectionRewriter.rewrite(buffer, sections, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int section) {
               int from = section << 12;
               int next = protectedBits.nextSetBit(from);
               return next >= 0 && next < from + 4096;
            }

            public int replacement(int section, int localIndex) {
               int ly = localIndex >> 8;
               int lz = localIndex >> 4 & 15;
               int lx = localIndex & 15;
               int bit = (section * 16 + ly) << 8 | lx << 4 | lz;
               if (!protectedBits.get(bit)) {
                  return -1;
               }

               int y = minHeight + section * 16 + ly;
               int x = (cx << 4) + lx;
               int z = (cz << 4) + lz;
               if (ChunkPacketRewriter.this.manager.isRevealed(player, BlockKey.pack(x, y, z))) {
                  return -1; // this player already sees the real block
               }

               return ChunkPacketRewriter.this.fakeId(world, x, y, z);
            }
         });
         if (!result.parsed()) {
            return this.fail("could not parse the chunk buffer layout");
         }

         if (result.changedSections > 0) {
            Object copy = this.copyWithBuffer(chunkData, result.buffer);
            this.packetChunkDataField.set(nmsPacket, copy);
            event.setPacket(clone);
         }

         this.successes.incrementAndGet();
         this.failures.set(0);
         this.manager.forceUpdate(player);
         return result.skippedSections == 0;
      } catch (Throwable t) {
         return this.fail(t.toString());
      }
   }

   private boolean fail(String why) {
      int n = this.failures.incrementAndGet();
      if (n == 1 || n == MAX_FAILURES_BEFORE_DISABLE) {
         this.plugin.getLogger().warning("Chunk packet rewrite skipped (" + why + "); using the post-send fallback.");
      }

      if (n >= MAX_FAILURES_BEFORE_DISABLE && this.successes.get() == 0) {
         this.disabled = true;
         this.plugin.getLogger().warning("Chunk packet rewrite disabled: it never worked on this server version. Hiding still works, but real blocks may show briefly when a chunk loads.");
      }

      return false;
   }

   private synchronized boolean init() {
      if (this.initialized) {
         return !this.disabled;
      }

      try {
         // Locate the chunk-data object inside the packet and the byte[] inside that.
         Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
         for(Field f : packetClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().getSimpleName().endsWith("LevelChunkPacketData")) {
               f.setAccessible(true);
               this.packetChunkDataField = f;
            }
         }

         if (this.packetChunkDataField == null) {
            throw new IllegalStateException("chunk data field not found");
         }

         for(Field f : this.packetChunkDataField.getType().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == byte[].class) {
               f.setAccessible(true);
               this.dataBufferField = f;
            }
         }

         if (this.dataBufferField == null) {
            throw new IllegalStateException("buffer field not found");
         }

         // Block.getId(BlockState): global block-state id.
         Object stoneState = WrappedBlockData.createData(Material.STONE).getHandle();
         Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
         for(Method m : blockClass.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == Integer.TYPE && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(stoneState)) {
               m.setAccessible(true);
               this.blockGetId = m;
               break;
            }
         }

         if (this.blockGetId == null) {
            throw new IllegalStateException("Block.getId not found");
         }

         // Sanity gate: STONE is global state 1 and AIR is 0 in every modern version.
         int stone = (Integer)this.blockGetId.invoke(null, stoneState);
         int air = (Integer)this.blockGetId.invoke(null, WrappedBlockData.createData(Material.AIR).getHandle());
         if (stone != 1 || air != 0) {
            throw new IllegalStateException("unexpected block ids (stone=" + stone + ", air=" + air + ")");
         }

         Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
         Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
         theUnsafe.setAccessible(true);
         this.unsafe = theUnsafe.get(null);
         this.allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
         this.initialized = true;
         this.plugin.getLogger().info("Chunk packet rewrite is active (real blocks are hidden before the chunk is sent).");
         return true;
      } catch (Throwable t) {
         this.initialized = true;
         this.disabled = true;
         this.plugin.getLogger().warning("Chunk packet rewrite unavailable (" + t + "). Hiding still works; real blocks may show briefly when a chunk loads.");
         return false;
      }
   }

   private int fakeId(World world, int x, int y, int z) {
      Material m = this.manager.fakeMaterial(world, x, y, z);
      Integer cached = (Integer)this.fakeIds.get(m);
      if (cached != null) {
         return cached;
      }

      try {
         int id = (Integer)this.blockGetId.invoke(null, WrappedBlockData.createData(m).getHandle());
         this.fakeIds.put(m, id);
         return id;
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException("could not resolve fake block id for " + m, e);
      }
   }

   /** Copies the shared chunk-data object so this player's packet can carry its own buffer. */
   private Object copyWithBuffer(Object original, byte[] newBuffer) throws ReflectiveOperationException {
      Object copy = this.allocateInstance.invoke(this.unsafe, original.getClass());

      for(Class<?> c = original.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
         for(Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
               f.setAccessible(true);
               f.set(copy, f.equals(this.dataBufferField) ? newBuffer : f.get(original));
            }
         }
      }

      return copy;
   }
}
