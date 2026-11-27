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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Entity hiding also makes the server remove the hidden player from the viewer's tab list.
 * We keep them listed: tab-list removals for players that Anti-ESP hid are stripped.
 * Real disconnects are unaffected (the quit handler forgets the player first).
 */
public class AntiEspPacketListener {
   private final StopESP plugin;
   private final AntiEspManager manager;

   public AntiEspPacketListener(StopESP plugin, AntiEspManager manager) {
      this.plugin = plugin;
      this.manager = manager;
   }

   public void register() {
      ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, new PacketType[]{Server.PLAYER_INFO_REMOVE}) {
         public void onPacketSending(PacketEvent event) {
            Player viewer = event.getPlayer();
            if (viewer != null) {
               PacketContainer packet = event.getPacket();
               List<UUID> removed = (List)packet.getUUIDLists().readSafely(0);
               if (removed != null && !removed.isEmpty()) {
                  List<UUID> keep = new ArrayList();

                  for(UUID id : removed) {
                     if (!AntiEspPacketListener.this.manager.isHiddenByAntiEsp(viewer.getUniqueId(), id)) {
                        keep.add(id);
                     }
                  }

                  if (keep.size() != removed.size()) {
                     if (keep.isEmpty()) {
                        event.setCancelled(true);
                     } else {
                        PacketContainer copy = packet.shallowClone();
                        copy.getUUIDLists().write(0, keep);
                        event.setPacket(copy);
                     }

                  }
               }
            }
         }
      });
   }
}
