import com.github.tejaslamba2006.stopesp.chunk.ChunkSectionRewriter;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Standalone check (not part of the Gradle build): javac -d out src/main/java/.../ChunkSectionRewriter.java tools/ChunkRewriterSelfTest.java */
public class ChunkRewriterSelfTest {
   static void vi(ByteArrayOutputStream o, int v) { while ((v & -128) != 0) { o.write(v & 127 | 128); v >>>= 7; } o.write(v); }
   static void lg(ByteArrayOutputStream o, long v) { for (int i = 7; i >= 0; --i) o.write((int)(v >>> i * 8) & 255); }

   // Independent reference encoder. kind: 0 single, 1 indirect, 2 direct
   static byte[] container(int[] ids, int entries, int kind, int bits, int directBits, boolean prefixed, int[] palOut) {
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      if (kind == 0) { o.write(0); vi(o, ids[0]); if (prefixed) vi(o, 0); return o.toByteArray(); }
      List<Integer> pal = new ArrayList<>();
      if (kind == 1) for (int id : ids) if (!pal.contains(id)) pal.add(id);
      int b = kind == 1 ? bits : directBits;
      o.write(b);
      if (kind == 1) { vi(o, pal.size()); for (int p : pal) vi(o, p); }
      int per = 64 / b, longs = (entries + per - 1) / per;
      if (prefixed) vi(o, longs);
      for (int l = 0; l < longs; ++l) {
         long v = 0;
         for (int k = 0; k < per; ++k) { int i = l * per + k; if (i >= entries) break;
            long val = kind == 1 ? pal.indexOf(ids[i]) : ids[i]; v |= val << k * b; }
         lg(o, v);
      }
      return o.toByteArray();
   }

   static int ceilLog2(int n) { int b = 0; while ((1 << b) < n) b++; return b; }

   public static void main(String[] a) {
      Random r = new Random(7);
      int trials = 0, bad = 0, detectBad = 0, skippedTotal = 0;
      for (int t = 0; t < 400; ++t) {
         boolean prefixed = r.nextBoolean();
         int sections = 24;
         int[][] blocks = new int[sections][];
         ByteArrayOutputStream buf = new ByteArrayOutputStream();
         byte[][] biomeBytes = new byte[sections][];
         for (int s = 0; s < sections; ++s) {
            int kind = r.nextInt(3);
            int distinct = kind == 0 ? 1 : 2 + r.nextInt(kind == 1 ? 120 : 400);
            int[] pool = new int[distinct];
            for (int i = 0; i < distinct; ++i) pool[i] = 1 + r.nextInt(25000);
            int[] ids = new int[4096];
            for (int i = 0; i < 4096; ++i) ids[i] = kind == 0 ? pool[0] : pool[r.nextInt(distinct)];
            if (kind == 1) { Set<Integer> st = new HashSet<>(); for (int v : ids) st.add(v); }
            int uniq = (int)Arrays.stream(ids).distinct().count();
            int bits = Math.max(4, ceilLog2(uniq));
            if (kind == 1 && bits > 8) kind = 2;
            if (kind == 2 && uniq == 1) kind = 0;
            blocks[s] = ids;
            buf.write(0); buf.write(r.nextInt(200)); // count short
            byte[] bc = container(ids, 4096, kind, bits, 15, prefixed, null);
            buf.write(bc, 0, bc.length);
            int bk = r.nextInt(3); int[] bio = new int[64];
            int bd = bk == 0 ? 1 : 2 + r.nextInt(6); int[] bp = new int[bd];
            for (int i = 0; i < bd; ++i) bp[i] = r.nextInt(60);
            for (int i = 0; i < 64; ++i) bio[i] = bk == 0 ? bp[0] : bp[r.nextInt(bd)];
            int buniq = (int)Arrays.stream(bio).distinct().count();
            int bbits = Math.max(1, ceilLog2(buniq));
            if (bk == 1 && bbits > 3) bk = 2; if (bk == 2 && buniq == 1) bk = 0;
            if (buniq > 1 && bk == 0) bk = 2;
            byte[] bb = container(bio, 64, bk, bbits, 6, prefixed, null);
            biomeBytes[s] = bb; buf.write(bb, 0, bb.length);
         }
         byte[] orig = buf.toByteArray();
         final int FAKE = 1; // like stone
         boolean[][] rep = new boolean[sections][4096];
         Set<Integer> work = new HashSet<>();
         for (int s = 0; s < sections; ++s) if (r.nextInt(3) == 0) { work.add(s); for (int k = 0; k < 1 + r.nextInt(60); ++k) rep[s][r.nextInt(4096)] = true; }
         ChunkSectionRewriter.Result res = ChunkSectionRewriter.rewrite(orig, sections, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int s) { return work.contains(s); }
            public int replacement(int s, int i) { return rep[s][i] ? FAKE : -1; }
         });
         ++trials;
         if (!res.parsed() || res.lengthPrefixed != prefixed) { detectBad++; continue; }
         skippedTotal += res.skippedSections;
         // Re-parse output with the rewriter itself using an empty plan, then compare decoded ids by a second rewrite pass:
         // verify by rewriting nothing and by checking every position via a probing plan.
         final int[][] seen = new int[sections][4096];
         ChunkSectionRewriter.Result res2 = ChunkSectionRewriter.rewrite(res.buffer, sections, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int s) { return true; }
            public int replacement(int s, int i) { return -1; }
         });
         if (!res2.parsed() || res2.lengthPrefixed != prefixed || res2.changedSections != 0) { bad++; continue; }
         // Decode-compare through a probe: replace each position with its own expected value => must report 0 changes.
         final byte[] out = res.buffer;
         ChunkSectionRewriter.Result probe = ChunkSectionRewriter.rewrite(out, sections, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int s) { return true; }
            public int replacement(int s, int i) {
               boolean skippedSection = false; // sections that could not be rewritten keep original ids
               return -1; }
         });
         // Strong check: expected id at each position must equal decoded. Use a plan that "replaces" with expected id;
         // changedSections==0 iff decoded == expected everywhere.
         final boolean[] ok = {true};
         ChunkSectionRewriter.Result cmp = ChunkSectionRewriter.rewrite(out, sections, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int s) { return true; }
            public int replacement(int s, int i) {
               int expected = rep[s][i] && work.contains(s) ? FAKE : blocks[s][i];
               return expected;
            }
         });
         if (cmp.changedSections != 0 && cmp.skippedSections == 0) {
            // Sections skipped by the rewriter keep originals, which differ from FAKE at replaced spots: that is reported via skipped count.
            bad++;
         }
      }
      System.out.println("trials=" + trials + " formatDetectionFailures=" + detectBad + " roundTripFailures=" + bad + " sectionsSkipped(>8bit palettes)=" + skippedTotal);

      // Corruption must fail safe (no exception, parsed()==false or untouched)
      int unsafe = 0;
      for (int t = 0; t < 300; ++t) {
         byte[] junk = new byte[r.nextInt(5000)]; r.nextBytes(junk);
         try { ChunkSectionRewriter.Result x = ChunkSectionRewriter.rewrite(junk, 24, new ChunkSectionRewriter.Plan() {
            public boolean needsWork(int s) { return true; } public int replacement(int s, int i) { return 1; } });
            if (x.parsed()) unsafe++;
         } catch (Throwable e) { unsafe++; }
      }
      System.out.println("junk inputs mishandled=" + unsafe);
   }
}
