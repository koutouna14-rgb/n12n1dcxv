package com.github.tejaslamba2006.stopesp.manager;

import com.github.tejaslamba2006.stopesp.StopESP;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Mutual friends. Two players see each other through walls only when BOTH added the other,
 * so nobody can friend everyone to get ESP on them.
 */
public class FriendManager {
   private final StopESP plugin;
   private final File file;
   private final Map<UUID, Set<UUID>> friends = new ConcurrentHashMap();

   public FriendManager(StopESP plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "friends.yml");
      this.load();
   }

   private void load() {
      if (!this.file.exists()) {
         return;
      }

      YamlConfiguration yml = YamlConfiguration.loadConfiguration(this.file);

      for(String key : yml.getKeys(false)) {
         try {
            UUID owner = UUID.fromString(key);
            Set<UUID> set = ConcurrentHashMap.newKeySet();

            for(String s : yml.getStringList(key)) {
               set.add(UUID.fromString(s));
            }

            this.friends.put(owner, set);
         } catch (IllegalArgumentException ignored) {
         }
      }

   }

   public synchronized void save() {
      YamlConfiguration yml = new YamlConfiguration();

      for(Map.Entry<UUID, Set<UUID>> e : this.friends.entrySet()) {
         if (!e.getValue().isEmpty()) {
            yml.set(((UUID)e.getKey()).toString(), e.getValue().stream().map(UUID::toString).toList());
         }
      }

      try {
         this.plugin.getDataFolder().mkdirs();
         yml.save(this.file);
      } catch (IOException e) {
         this.plugin.getLogger().warning("Could not save friends.yml: " + e.getMessage());
      }

   }

   public void add(UUID owner, UUID friend) {
      ((Set)this.friends.computeIfAbsent(owner, (k) -> ConcurrentHashMap.newKeySet())).add(friend);
      this.save();
   }

   public void remove(UUID owner, UUID friend) {
      Set<UUID> set = (Set)this.friends.get(owner);
      if (set != null) {
         set.remove(friend);
         this.save();
      }

   }

   public Set<UUID> list(UUID owner) {
      Set<UUID> set = (Set)this.friends.get(owner);
      return set == null ? new HashSet() : new HashSet(set);
   }

   public boolean areMutualFriends(UUID a, UUID b) {
      Set<UUID> fa = (Set)this.friends.get(a);
      Set<UUID> fb = (Set)this.friends.get(b);
      return fa != null && fb != null && fa.contains(b) && fb.contains(a);
   }
}
