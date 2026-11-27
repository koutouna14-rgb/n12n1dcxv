package com.github.tejaslamba2006.stopesp.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.PacketType.Play.Server;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.manager.AntiEspManager;
import com.github.tejaslamba2006.stopesp.manager.DeepShieldConfig;
import com.github.tejaslamba2006.stopesp.util.BedrockSupport;
import com.github.tejaslamba2006.stopesp.util.Scheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Radar protection. For players that are visible but far from the viewer, relative movement
 * packets are batched (delay) and the batch is sent with a small random offset (jitter).
 * The offset is tracked and cancelled out on the next batch, so the error never accumulates.
 * Teleports, position syncs and spawns are exact server packets: they reset our state.
 */
public class AntiEspMovementJitter implements Listener {
   private static final int MAX_PENDING = 24000;
   private final StopESP plugin;
   private final DeepShieldConfig cfg;
   private final AntiEspManager esp;
   private final Map<Integer, Player> playersByEntityId = new ConcurrentHashMap();
   private final Map<Long, State> states = new ConcurrentHashMap();

   /** Per viewer->target pending movement, in protocol units (1/4096 block). */
   private static final class State {
      int accX;
      int accY;
      int accZ;
      int errX;
      int errY;
      int errZ;
      int count;

      boolean dirty() {
         return this.count > 0 || this.errX != 0 || this.errY != 0 || this.errZ != 0;
      }

      void reset() {
         this.accX = this.accY = this.accZ = 0;
         this.errX = this.errY = this.errZ = 0;
         this.count = 0;
      }
   }

   public AntiEspMovementJitter(StopESP plugin, DeepShieldConfig cfg, AntiEspManager esp) {
      this.plugin = plugin;
      this.cfg = cfg;
      this.esp = esp;
   }

   public void register() {
      for(Player p : Bukkit.getOnlinePlayers()) {
         this.playersByEntityId.put(p.getEntityId(), p);
      }

      Bukkit.getPluginManager().registerEvents(this, this.plugin);
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, new PacketType[]{Server.REL_ENTITY_MOVE, Server.REL_ENTITY_MOVE_LOOK}) {
         public void onPacketSending(PacketEvent event) {
            AntiEspMovementJitter.this.onMove(event);
         }
      });
      List<PacketType> resets = new ArrayList();

      // Looked up by name so a missing packet type in an older ProtocolLib doesn't break loading.
      for(String name : new String[]{"ENTITY_TELEPORT", "ENTITY_POSITION_SYNC", "SPAWN_ENTITY", "NAMED_ENTITY_SPAWN"}) {
         try {
            resets.add((PacketType)Server.class.getField(name).get(null));
         } catch (ReflectiveOperationException | RuntimeException ignored) {
         }
      }

      if (!resets.isEmpty()) {
         ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, resets) {
            public void onPacketSending(PacketEvent event) {
               Player viewer = event.getPlayer();
               Integer id = (Integer)event.getPacket().getIntegers().readSafely(0);
               if (viewer != null && id != null) {
                  AntiEspMovementJitter.this.states.remove(key(viewer.getEntityId(), id));
               }
            }
         });
      }

   }

   private static long key(int viewerId, int targetId) {
      return (long)viewerId << 32 | (long)targetId & 0xFFFFFFFFL;
   }

   private static int clampShort(int v) {
      return Math.max(-32768, Math.min(32767, v));
   }

   private void onMove(PacketEvent event) {
      if (!this.esp.isEnabled() || !this.cfg.isJitterEnabled()) {
         return;
      }

      Player viewer = event.getPlayer();
      PacketContainer packet = event.getPacket();
      Integer id = (Integer)packet.getIntegers().readSafely(0);
      if (viewer == null || id == null) {
         return;
      }

      Player target = (Player)this.playersByEntityId.get(id);
      if (target == null || target == viewer || target.getWorld() != viewer.getWorld()) {
         return;
      }

      if (this.cfg.isBedrockDisableJitter() && (BedrockSupport.isBedrock(viewer) || BedrockSupport.isBedrock(target))) {
         return;
      }

      double dx = viewer.getX() - target.getX();
      double dy = viewer.getY() - target.getY();
      double dz = viewer.getZ() - target.getZ();
      double min = this.cfg.getJitterMinDistance();
      boolean far = dx * dx + dy * dy + dz * dz > min * min;
      long k = key(viewer.getEntityId(), id);
      State st = far ? (State)this.states.computeIfAbsent(k, (key) -> new State()) : (State)this.states.get(k);
      if (st == null) {
         return;
      }

      synchronized(st) {
         int mx = (Short)packet.getShorts().read(0);
         int my = (Short)packet.getShorts().read(1);
         int mz = (Short)packet.getShorts().read(2);
         if (!far) {
            if (st.dirty()) {
               // Came into range: send everything we held back and undo the jitter offset.
               packet.getShorts().write(0, (short)clampShort(st.accX + mx - st.errX));
               packet.getShorts().write(1, (short)clampShort(st.accY + my - st.errY));
               packet.getShorts().write(2, (short)clampShort(st.accZ + mz - st.errZ));
               st.reset();
            }

            return;
         }

         st.accX += mx;
         st.accY += my;
         st.accZ += mz;
         boolean overflow = Math.abs(st.accX) > MAX_PENDING || Math.abs(st.accY) > MAX_PENDING || Math.abs(st.accZ) > MAX_PENDING;
         if (++st.count < this.cfg.getJitterInterval() && !overflow) {
            event.setCancelled(true);
            return;
         }

         int j = (int)(this.cfg.getJitterBlocks() * 4096.0);
         ThreadLocalRandom rnd = ThreadLocalRandom.current();
         int newX = j > 0 ? rnd.nextInt(-j, j + 1) : 0;
         int newY = j > 0 ? rnd.nextInt(-j / 2, j / 2 + 1) : 0;
         int newZ = j > 0 ? rnd.nextInt(-j, j + 1) : 0;
         packet.getShorts().write(0, (short)clampShort(st.accX + newX - st.errX));
         packet.getShorts().write(1, (short)clampShort(st.accY + newY - st.errY));
         packet.getShorts().write(2, (short)clampShort(st.accZ + newZ - st.errZ));
         st.accX = st.accY = st.accZ = 0;
         st.errX = newX;
         st.errY = newY;
         st.errZ = newZ;
         st.count = 0;
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      this.playersByEntityId.put(event.getPlayer().getEntityId(), event.getPlayer());
   }

   // A respawn creates a new server-side player with a new entity id.
   @EventHandler(priority = EventPriority.MONITOR)
   public void onRespawn(PlayerRespawnEvent event) {
      Player p = event.getPlayer();
      Scheduler.runEntity(this.plugin, p, () -> {
         this.playersByEntityId.values().removeIf((x) -> x == p);
         this.playersByEntityId.put(p.getEntityId(), p);
      });
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onQuit(PlayerQuitEvent event) {
      Player p = event.getPlayer();
      BedrockSupport.forget(p.getUniqueId());
      int id = p.getEntityId();
      this.playersByEntityId.values().removeIf((x) -> x == p);
      this.states.keySet().removeIf((k) -> (int)(k >> 32) == id || (int)(long)k == id);
   }
}
