package com.github.tejaslamba2006.stopesp.chunk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the block-state palettes inside a serialized chunk buffer (the "data" array of a
 * chunk packet) without touching anything else. Pure Java, no server classes.
 *
 * Chunk buffer = one section after another (bottom to top):
 *   short nonAirBlockCount | blockStates PalettedContainer(4096) | biomes PalettedContainer(64)
 * PalettedContainer = byte bitsPerEntry | palette | data longs (big endian, entries never span longs)
 * Newer protocol versions dropped the VarInt length in front of the data longs, so BOTH layouts are
 * supported and the right one is detected by parsing the whole buffer: only the correct layout ends
 * exactly on the last byte. If nothing parses cleanly the buffer is left alone (fail safe).
 */
public final class ChunkSectionRewriter {
   private ChunkSectionRewriter() {
   }

   /** Tells the rewriter which sections to touch and what to put at which position. */
   public interface Plan {
      boolean needsWork(int section);

      /** Block-state id to place at this section-local index (y<<8 | z<<4 | x), or -1 to keep it. */
      int replacement(int section, int localIndex);
   }

   public static final class Result {
      /** The new buffer, or null if parsing failed (nothing was changed). */
      public final byte[] buffer;
      public final int changedSections;
      /** Sections that needed work but could not be rewritten (left as they were). */
      public final int skippedSections;
      public final boolean lengthPrefixed;

      Result(byte[] buffer, int changed, int skipped, boolean lengthPrefixed) {
         this.buffer = buffer;
         this.changedSections = changed;
         this.skippedSections = skipped;
         this.lengthPrefixed = lengthPrefixed;
      }

      public boolean parsed() {
         return this.buffer != null;
      }
   }

   private static final class Container {
      int bits;
      int[] palette; // null = direct (global) palette
      boolean single;
      int dataStart;
      int dataLongs;
      int end;
   }

   private static final class Section {
      int start;
      int blockStart;
      int biomeStart;
      int end;
      Container blocks;
   }

   public static Result rewrite(byte[] buf, int sectionCount, Plan plan) {
      // Prefer the newer layout (no length prefix), then the older one.
      for(boolean prefixed : new boolean[]{false, true}) {
         List<Section> sections = parse(buf, sectionCount, prefixed);
         if (sections != null) {
            return build(buf, sections, plan, prefixed);
         }
      }

      return new Result(null, 0, 0, false);
   }

   private static Result build(byte[] buf, List<Section> sections, Plan plan, boolean prefixed) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length + 256);
      int changed = 0;
      int skipped = 0;

      for(int i = 0; i < sections.size(); ++i) {
         Section s = (Section)sections.get(i);
         if (!plan.needsWork(i)) {
            out.write(buf, s.start, s.end - s.start);
            continue;
         }

         byte[] newBlocks = rewriteBlocks(buf, s, i, plan, prefixed);
         if (newBlocks == null) {
            ++skipped;
            out.write(buf, s.start, s.end - s.start);
         } else {
            if (newBlocks.length == 0) {
               out.write(buf, s.start, s.end - s.start); // nothing to change in this section
            } else {
               out.write(buf, s.start, s.blockStart - s.start); // block count short
               out.write(newBlocks, 0, newBlocks.length);
               out.write(buf, s.biomeStart, s.end - s.biomeStart); // biomes verbatim
               ++changed;
            }
         }
      }

      return new Result(out.toByteArray(), changed, skipped, prefixed);
   }

   /** Returns the new block-state container, an empty array if nothing changed, or null if it can't be done. */
   private static byte[] rewriteBlocks(byte[] buf, Section s, int sectionIndex, Plan plan, boolean prefixed) {
      Container c = s.blocks;
      int[] ids = decode(buf, c, 4096);
      if (ids == null) {
         return null;
      }

      boolean any = false;
      List<Integer> newIds = new ArrayList();

      for(int j = 0; j < 4096; ++j) {
         int r = plan.replacement(sectionIndex, j);
         if (r >= 0 && ids[j] != r) {
            ids[j] = r;
            any = true;
            if (!newIds.contains(r)) {
               newIds.add(r);
            }
         }
      }

      if (!any) {
         return new byte[0];
      }

      Writer w = new Writer();
      if (c.palette == null) {
         // Direct palette: keep the same width, just different values.
         w.byteValue(c.bits);
         if (prefixed) {
            w.varInt(longsFor(4096, c.bits));
         }

         pack(w, ids, c.bits);
         return w.toBytes();
      }

      List<Integer> palette = new ArrayList();
      Map<Integer, Integer> index = new HashMap();

      for(int id : c.palette) {
         if (!index.containsKey(id)) {
            index.put(id, palette.size());
         }

         palette.add(id);
      }

      for(int id : newIds) {
         if (!index.containsKey(id)) {
            index.put(id, palette.size());
            palette.add(id);
         }
      }

      int bits = Math.max(4, c.single ? 4 : c.bits);
      while((1 << bits) < palette.size()) {
         ++bits;
      }

      if (bits > 8) {
         return null; // would need the global palette, whose width we don't know here
      }

      int[] values = new int[4096];

      for(int j = 0; j < 4096; ++j) {
         values[j] = (Integer)index.get(ids[j]);
      }

      w.byteValue(bits);
      w.varInt(palette.size());

      for(int id : palette) {
         w.varInt(id);
      }

      if (prefixed) {
         w.varInt(longsFor(4096, bits));
      }

      pack(w, values, bits);
      return w.toBytes();
   }

   private static int[] decode(byte[] buf, Container c, int entries) {
      int[] ids = new int[entries];
      if (c.single) {
         java.util.Arrays.fill(ids, c.palette[0]);
         return ids;
      }

      int perLong = 64 / c.bits;
      long mask = (1L << c.bits) - 1L;

      for(int i = 0; i < entries; ++i) {
         int longIndex = i / perLong;
         if (longIndex >= c.dataLongs) {
            return null;
         }

         long l = readLong(buf, c.dataStart + longIndex * 8);
         int v = (int)(l >>> i % perLong * c.bits & mask);
         if (c.palette != null) {
            if (v >= c.palette.length) {
               return null;
            }

            v = c.palette[v];
         }

         ids[i] = v;
      }

      return ids;
   }

   private static void pack(Writer w, int[] values, int bits) {
      int perLong = 64 / bits;
      int longs = longsFor(values.length, bits);

      for(int l = 0; l < longs; ++l) {
         long v = 0L;

         for(int k = 0; k < perLong; ++k) {
            int i = l * perLong + k;
            if (i >= values.length) {
               break;
            }

            v |= (long)values[i] << k * bits;
         }

         w.longValue(v);
      }

   }

   private static int longsFor(int entries, int bits) {
      int perLong = 64 / bits;
      return (entries + perLong - 1) / perLong;
   }

   // ---- parsing ----

   private static List<Section> parse(byte[] buf, int sectionCount, boolean prefixed) {
      try {
         int[] pos = new int[]{0};
         List<Section> list = new ArrayList();

         for(int i = 0; i < sectionCount; ++i) {
            Section s = new Section();
            s.start = pos[0];
            pos[0] += 2; // block count
            s.blockStart = pos[0];
            s.blocks = container(buf, pos, 4096, 8, prefixed);
            if (s.blocks == null) {
               return null;
            }

            s.biomeStart = pos[0];
            if (container(buf, pos, 64, 3, prefixed) == null) {
               return null;
            }

            s.end = pos[0];
            list.add(s);
         }

         return pos[0] == buf.length ? list : null;
      } catch (RuntimeException e) {
         return null;
      }
   }

   private static Container container(byte[] buf, int[] pos, int entries, int maxIndirectBits, boolean prefixed) {
      Container c = new Container();
      c.bits = buf[pos[0]++] & 255;
      if (c.bits > 32) {
         return null;
      }

      if (c.bits == 0) {
         c.single = true;
         c.palette = new int[]{varInt(buf, pos)};
         if (prefixed) {
            if (varInt(buf, pos) != 0) {
               return null;
            }
         }

         c.dataLongs = 0;
         c.end = pos[0];
         return c;
      }

      if (c.bits <= maxIndirectBits) {
         int n = varInt(buf, pos);
         if (n < 1 || n > entries) {
            return null;
         }

         c.palette = new int[n];

         for(int i = 0; i < n; ++i) {
            c.palette[i] = varInt(buf, pos);
         }
      }

      int expected = longsFor(entries, c.bits);
      if (prefixed && varInt(buf, pos) != expected) {
         return null;
      }

      c.dataStart = pos[0];
      c.dataLongs = expected;
      pos[0] += expected * 8;
      if (pos[0] > buf.length) {
         return null;
      }

      c.end = pos[0];
      return c;
   }

   private static int varInt(byte[] buf, int[] pos) {
      int value = 0;
      int shift = 0;

      while(true) {
         int b = buf[pos[0]++];
         value |= (b & 127) << shift;
         if ((b & 128) == 0) {
            return value;
         }

         shift += 7;
         if (shift > 35) {
            throw new IllegalStateException("VarInt too long");
         }
      }
   }

   private static long readLong(byte[] b, int o) {
      return ((long)b[o] & 255L) << 56 | ((long)b[o + 1] & 255L) << 48 | ((long)b[o + 2] & 255L) << 40 | ((long)b[o + 3] & 255L) << 32 | ((long)b[o + 4] & 255L) << 24 | ((long)b[o + 5] & 255L) << 16 | ((long)b[o + 6] & 255L) << 8 | (long)b[o + 7] & 255L;
   }

   private static final class Writer {
      private final ByteArrayOutputStream out = new ByteArrayOutputStream();

      void byteValue(int v) {
         this.out.write(v);
      }

      void varInt(int value) {
         while((value & -128) != 0) {
            this.out.write(value & 127 | 128);
            value >>>= 7;
         }

         this.out.write(value);
      }

      void longValue(long v) {
         for(int i = 7; i >= 0; --i) {
            this.out.write((int)(v >>> i * 8) & 255);
         }

      }

      byte[] toBytes() {
         return this.out.toByteArray();
      }
   }
}
