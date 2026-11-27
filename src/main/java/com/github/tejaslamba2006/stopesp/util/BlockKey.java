package com.github.tejaslamba2006.stopesp.util;

/** Packs a block position into one long: x (26 bits) | z (26 bits) << 26 | y (12 bits) << 52. */
public final class BlockKey {
   private BlockKey() {
   }

   public static long pack(int x, int y, int z) {
      return (long)x & 67108863L | ((long)z & 67108863L) << 26 | ((long)y & 4095L) << 52;
   }

   public static int x(long key) {
      int v = (int)(key & 67108863L);
      return v >= 33554432 ? v - 67108864 : v;
   }

   public static int z(long key) {
      int v = (int)(key >> 26 & 67108863L);
      return v >= 33554432 ? v - 67108864 : v;
   }

   public static int y(long key) {
      int v = (int)(key >> 52 & 4095L);
      return v >= 2048 ? v - 4096 : v;
   }

   public static long chunkKey(long key) {
      return (long)(x(key) >> 4) & 4294967295L | ((long)(z(key) >> 4) & 4294967295L) << 32;
   }
}
