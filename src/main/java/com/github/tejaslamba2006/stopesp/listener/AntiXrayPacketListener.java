package com.github.tejaslamba2006.stopesp.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.PacketType.Play.Server;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.MultiBlockChangeInfo;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import org.bukkit.Material;
import com.github.tejaslamba2006.stopesp.StopESP;
import com.github.tejaslamba2006.stopesp.chunk.ChunkPacketRewriter;
import com.github.tejaslamba2006.stopesp.manager.AntiXrayManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class AntiXrayPacketListener {
   private final StopESP plugin;
   private final AntiXrayManager manager;
   private final ChunkPacketRewriter rewriter;

   public AntiXrayPacketListener(StopESP plugin, AntiXrayManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.rewriter = new ChunkPacketRewriter(plugin, manager);
   }

   public void register() {
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.NORMAL, new PacketType[]{Server.TILE_ENTITY_DATA}) {
         public void onPacketSending(PacketEvent event) {
            AntiXrayManager mgr = AntiXrayPacketListener.this.manager;
            if (mgr.isEnabled() && mgr.getDsConfig().isBlockEntityHideUpdates()) {
               Player player = event.getPlayer();
               if (mgr.getDsConfig().isEnabledWorld(player.getWorld())) {
                  BlockPosition pos = (BlockPosition)event.getPacket().getBlockPositionModifier().readSafely(0);
                  if (pos != null && mgr.isHiddenProtected(player, pos.getX(), pos.getY(), pos.getZ())) {
                     event.setCancelled(true);
                  }
               }
            }
         }
      });
      // Vanilla block updates (furnace lit, bed occupied, lectern book...) carry the REAL block state.
      // Replace them with the fake block while the block is still hidden from this player.
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, new PacketType[]{Server.BLOCK_CHANGE}) {
         public void onPacketSending(PacketEvent event) {
            AntiXrayManager mgr = AntiXrayPacketListener.this.manager;
            Player player = event.getPlayer();
            if (!mgr.isEnabled() || !mgr.getDsConfig().isEnabledWorld(player.getWorld()) || player.hasPermission("stopesp.bypass")) {
               return;
            }

            BlockPosition pos = (BlockPosition)event.getPacket().getBlockPositionModifier().readSafely(0);
            WrappedBlockData data = (WrappedBlockData)event.getPacket().getBlockData().readSafely(0);
            if (pos == null || data == null) {
               return;
            }

            Material type = data.getType();
            if (mgr.getDsConfig().shouldProtect(type, pos.getY()) && mgr.isHiddenProtected(player, pos.getX(), pos.getY(), pos.getZ())) {
               PacketContainer copy = event.getPacket().deepClone(); // packets can be shared between players
               copy.getBlockData().write(0, WrappedBlockData.createData(mgr.fakeMaterial(player.getWorld(), pos.getX(), pos.getY(), pos.getZ())));
               event.setPacket(copy);
            }
         }
      });
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, new PacketType[]{Server.MULTI_BLOCK_CHANGE}) {
         public void onPacketSending(PacketEvent event) {
            AntiXrayManager mgr = AntiXrayPacketListener.this.manager;
            Player player = event.getPlayer();
            if (!mgr.isEnabled() || !mgr.getDsConfig().isEnabledWorld(player.getWorld()) || player.hasPermission("stopesp.bypass")) {
               return;
            }

            MultiBlockChangeInfo[] infos = (MultiBlockChangeInfo[])event.getPacket().getMultiBlockChangeInfoArrays().readSafely(0);
            if (infos == null) {
               return;
            }

            PacketContainer copy = null;
            MultiBlockChangeInfo[] target = null;
            for(int i = 0; i < infos.length; ++i) {
               MultiBlockChangeInfo info = infos[i];
               WrappedBlockData d = info.getData();
               if (d != null && mgr.getDsConfig().shouldProtect(d.getType(), info.getY())
                     && mgr.isHiddenProtected(player, info.getAbsoluteX(), info.getY(), info.getAbsoluteZ())) {
                  if (copy == null) {
                     copy = event.getPacket().deepClone(); // packets can be shared between players
                     target = (MultiBlockChangeInfo[])copy.getMultiBlockChangeInfoArrays().read(0);
                  }

                  target[i].setData(WrappedBlockData.createData(mgr.fakeMaterial(player.getWorld(), info.getAbsoluteX(), info.getY(), info.getAbsoluteZ())));
               }
            }

            if (copy != null) {
               copy.getMultiBlockChangeInfoArrays().write(0, target);
               event.setPacket(copy);
            }
         }
      });
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGHEST, new PacketType[]{Server.MAP_CHUNK}) {
         public void onPacketSending(PacketEvent event) {
            if (AntiXrayPacketListener.this.manager.isEnabled()) {
               Player player = event.getPlayer();
               if (AntiXrayPacketListener.this.manager.getDsConfig().isEnabledWorld(player.getWorld())) {
                  PacketContainer packet = event.getPacket();
                  Integer chunkX = (Integer)packet.getIntegers().readSafely(0);
                  Integer chunkZ = (Integer)packet.getIntegers().readSafely(1);
                  if (chunkX != null && chunkZ != null && !player.hasPermission("stopesp.bypass")) {
                     boolean handled = AntiXrayPacketListener.this.manager.getDsConfig().isChunkRewriteEnabled() && AntiXrayPacketListener.this.rewriter.tryRewrite(event, player, chunkX, chunkZ);
                     if (!handled) {
                        AntiXrayPacketListener.this.manager.hideAllInChunk(player, chunkX, chunkZ);
                     }
                  }
               }
            }
         }
      });
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.NORMAL, new PacketType[]{Server.NAMED_SOUND_EFFECT}) {
         public void onPacketSending(PacketEvent event) {
            if (AntiXrayPacketListener.this.manager.isEnabled()) {
               if (AntiXrayPacketListener.this.manager.getDsConfig().isEnabledWorld(event.getPlayer().getWorld())) {
                  PacketContainer packet = event.getPacket();
                  Integer rawX = (Integer)packet.getIntegers().readSafely(0);
                  Integer rawY = (Integer)packet.getIntegers().readSafely(1);
                  Integer rawZ = (Integer)packet.getIntegers().readSafely(2);
                  if (rawX != null && rawY != null && rawZ != null) {
                     double x = (double)rawX / (double)8.0F;
                     double y = (double)rawY / (double)8.0F;
                     double z = (double)rawZ / (double)8.0F;
                     Location soundLoc = new Location(event.getPlayer().getWorld(), x, y, z);
                     if (!AntiXrayPacketListener.this.manager.hasLoSAsync(event.getPlayer(), event.getPlayer().getEyeLocation(), soundLoc, false)) {
                        event.setCancelled(true);
                     }

                  }
               }
            }
         }
      });
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.NORMAL, new PacketType[]{Server.WORLD_EVENT}) {
         public void onPacketSending(PacketEvent event) {
            if (AntiXrayPacketListener.this.manager.isEnabled()) {
               if (AntiXrayPacketListener.this.manager.getDsConfig().isEnabledWorld(event.getPlayer().getWorld())) {
                  PacketContainer packet = event.getPacket();
                  Integer effectId = (Integer)packet.getIntegers().readSafely(0);
                  if (effectId != null) {
                     if (effectId == 1021 || effectId == 1022) {
                        BlockPosition pos = (BlockPosition)packet.getBlockPositionModifier().readSafely(0);
                        if (pos == null) {
                           return;
                        }

                        Location loc = new Location(event.getPlayer().getWorld(), (double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
                        if (!AntiXrayPacketListener.this.manager.hasLoSAsync(event.getPlayer(), event.getPlayer().getEyeLocation(), loc, false)) {
                           event.setCancelled(true);
                        }
                     }

                  }
               }
            }
         }
      });
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.NORMAL, new PacketType[]{Server.WORLD_PARTICLES}) {
         public void onPacketSending(PacketEvent event) {
            if (AntiXrayPacketListener.this.manager.isEnabled()) {
               if (AntiXrayPacketListener.this.manager.getDsConfig().isEnabledWorld(event.getPlayer().getWorld())) {
                  PacketContainer packet = event.getPacket();
                  Double x = (Double)packet.getDoubles().readSafely(0);
                  Double y = (Double)packet.getDoubles().readSafely(1);
                  Double z = (Double)packet.getDoubles().readSafely(2);
                  if (x != null && y != null && z != null) {
                     Location particleLoc = new Location(event.getPlayer().getWorld(), x, y, z);
                     if (!(event.getPlayer().getEyeLocation().distanceSquared(particleLoc) < (double)1.0F)) {
                        if (!AntiXrayPacketListener.this.manager.hasLoSAsync(event.getPlayer(), event.getPlayer().getEyeLocation(), particleLoc, false)) {
                           event.setCancelled(true);
                        }

                     }
                  }
               }
            }
         }
      });
   }
}
