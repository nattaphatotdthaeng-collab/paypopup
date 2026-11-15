package com.example.paypopup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Inflater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public final class PayPopup {
    // ===== SETTINGS =====
    // Payments below this amount are ignored (5m)
    private static final double MIN_VALUE = 5_000_000;

    // The tiers, and where each one starts
    private static final int GREEN = 0, GOLD = 1, DIAMOND = 2, RUBY = 3, JACKPOT = 4, HOLE = 5;
    private static final double[] TIER_MIN = {5e6, 25e6, 50e6, 100e6, 250e6, 500e6};

    private static final int MAX_VISIBLE = 8;    // most popups on screen at once (the rest wait in a queue)
    private static final int MARGIN = 8;         // gap from the right edge of the screen
    private static final int TOP = 10;           // gap from the top of the screen
    private static final int GAP = 4;            // gap between stacked popups
    private static final int DIGIT_SPACING = 0;  // extra pixels between the characters of the amount (0 = normal Minecraft text)
    private static final int PANEL_H = 29;       // height of a panel
    private static final int FACE = 20;          // size of the player face
    private static final boolean SCREEN_SHAKE = true;  // black hole: gently shake your view (set to false to switch off)

    private static final long SLIDE_IN = 600;
    private static final long FADE_OUT = 700;
    private static final long RUBY_SHOW = 1300;     // how long the ruby stays on the screen
    private static final long JACKPOT_WIN = 3800;   // when the wheel stops
    private static final long BH_COLLAPSE = 3900;   // when the black hole starts to collapse
    private static final long BH_BLAST = 4700;      // when the black hole explodes
    //                                          green gold diamond ruby jackpot hole
    private static final long[] PANEL_DELAY = {0, 0, 0, 0, JACKPOT_WIN, BH_BLAST};
    private static final long[] DURATIONS = {4000, 4600, 5200, 6000, 9500, 10000};
    private static final int[] COIN_COUNTS = {0, 0, 0, 0, 60, 0};

    // ===== STATE =====
    private static final class Popup {
        String from = "", orig = "", suffix = "";
        double num;
        int decimals;
        boolean comma;
        int tier, coinCount;
        long duration;
        long start = -1;
        float[][] events = new float[0][];
        int nextEvent = 0;
        float slotY = 0f;
        boolean placed = false;
        long faceT0 = -1;
        float bhOffset;
        int w, h;
        final float[][] coins = new float[60][5];     // angle, speed, size, spin, phase
        final float[][] confetti = new float[70][5];  // xFrac, delay, fallSpeed, size, hue
        final float[][] sparks = new float[5][3];     // xFrac, yFrac, phase
        final float[][] disk = new float[460][5];     // angle, radius (x hole size), spin, size, jitter
        final float[][] stars = new float[70][4];     // angle, radius, size, phase
        final float[] segHue = new float[12];         // jackpot wheel: random colour of each segment
        int winSeg, winRgb;                           // jackpot wheel: where it lands and that colour
        float wheelTurns;                             // jackpot wheel: how far it spins in total
    }

    private static final List<Popup> active = new ArrayList<>();
    private static final List<Popup> pending = new ArrayList<>();
    private static long clockOverride = -1;   // only used by my preview/test tools, -1 = real clock
    private static long lastRender = 0;
    private static String lastKey = "";
    private static long lastKeyTime = 0;
    private static float shakeYaw = 0f, shakePitch = 0f;  // how far we are currently turning your view

    private PayPopup() {}

    private static long nowMs() {
        return clockOverride >= 0 ? clockOverride : System.currentTimeMillis();
    }

    private static int padOf(int tier) {
        return 0;
    }

    private static int slotNeed(int tier) {
        return PANEL_H + 2 * padOf(tier) + GAP;
    }

    // ===== TRIGGER =====
    public static synchronized void trigger(String player, String amt) {
        String s = amt.trim();
        if (s.isEmpty()) return;

        char last = s.charAt(s.length() - 1);
        String suf = "";
        double mult = 1;
        if (Character.isLetter(last)) {
            suf = String.valueOf(last);
            s = s.substring(0, s.length() - 1);
            switch (Character.toLowerCase(last)) {
                case 'k': mult = 1e3; break;
                case 'm': mult = 1e6; break;
                case 'b': mult = 1e9; break;
                case 't': mult = 1e12; break;
                default: break;
            }
        }
        boolean hasComma = s.contains(",");
        String clean = s.replace(",", "").trim();
        double n;
        try {
            n = Double.parseDouble(clean);
        } catch (Exception e) {
            return;
        }
        double value = n * mult + 0.5;
        if (value < MIN_VALUE) return;

        long now = nowMs();
        String key = player + "|" + amt.trim();
        if (key.equals(lastKey) && now - lastKeyTime < 300) return; // ignore accidental duplicates
        lastKey = key;
        lastKeyTime = now;

        Popup p = new Popup();
        p.from = player;
        String upSuf = suf.toUpperCase(Locale.ROOT);
        String raw = amt.trim();
        p.orig = suf.isEmpty() ? raw : raw.substring(0, raw.length() - 1) + upSuf; // "10m" shows as "10M"
        p.suffix = upSuf;
        p.num = n;
        p.comma = hasComma;
        int dot = clean.indexOf('.');
        p.decimals = dot < 0 ? 0 : clean.length() - dot - 1;

        p.tier = 0;
        for (int i = 0; i < TIER_MIN.length; i++) {
            if (value >= TIER_MIN[i]) p.tier = i;
        }
        p.coinCount = COIN_COUNTS[p.tier];
        p.duration = DURATIONS[p.tier];

        SkinFaces.get(player); // start downloading the skin face right away

        Random r = new Random();
        p.bhOffset = (r.nextFloat() - 0.5f) * 100f;
        for (float[] c : p.coins) {
            c[0] = (float) (-Math.PI + r.nextDouble() * Math.PI);
            c[1] = 70f + r.nextFloat() * 130f;
            c[2] = 3f + r.nextFloat() * 3f;
            c[3] = 6f + r.nextFloat() * 10f;
            c[4] = r.nextFloat() * 6.28f;
        }
        for (float[] c : p.confetti) {
            c[0] = r.nextFloat();
            c[1] = r.nextFloat() * 600f;
            c[2] = 90f + r.nextFloat() * 130f;
            c[3] = 3f + r.nextFloat() * 3f;
            c[4] = r.nextFloat();
        }
        for (float[] c : p.sparks) {
            c[0] = r.nextFloat();
            c[1] = r.nextFloat();
            c[2] = r.nextFloat() * 6.28f;
        }
        for (float[] c : p.disk) {
            c[0] = r.nextFloat() * 6.28f;
            c[1] = 1.7f + 4.3f * (float) Math.pow(r.nextFloat(), 1.3);
            c[2] = 6f / (float) Math.pow(c[1], 1.5);
            c[3] = 1f + r.nextFloat() * 1.6f;
            c[4] = r.nextFloat() * 2f - 1f;
        }
        for (float[] c : p.stars) {
            c[0] = r.nextFloat() * 6.28f;
            c[1] = 40f + r.nextFloat() * 260f;
            c[2] = r.nextFloat() < 0.25f ? 2f : 1f;
            c[3] = r.nextFloat() * 6.28f;
        }

        if (p.tier == JACKPOT) {
            // random colours for the wheel (all different, shuffled), and a random winner
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < 12; i++) order.add(i);
            java.util.Collections.shuffle(order, r);
            float shift = r.nextFloat();
            for (int i = 0; i < 12; i++) p.segHue[i] = (order.get(i) / 12f + shift) % 1f;
            p.winSeg = r.nextInt(12);
            p.wheelTurns = (float) ((7 + r.nextFloat() * 2) * 2 * Math.PI);
            p.winRgb = hueRgb(p.segHue[p.winSeg]);
        }

        // Sound schedule: {time ms, type (0 = orb ping, 1 = level up), pitch}
        List<float[]> ev = new ArrayList<>();
        switch (p.tier) {
            case GREEN:
                ev.add(new float[]{0, 0, 1.0f});
                break;
            case GOLD:
                ev.add(new float[]{0, 0, 1.2f});
                break;
            case DIAMOND:
                ev.add(new float[]{0, 0, 1.5f});
                ev.add(new float[]{0, 1, 1.4f});
                break;
            case RUBY:
                ev.add(new float[]{0, 1, 0.8f});
                ev.add(new float[]{110, 0, 1.1f});
                ev.add(new float[]{230, 0, 1.4f});
                break;
            case JACKPOT:
                for (int k2 = 0; k2 < 4; k2++) ev.add(new float[]{k2 * 520, 0, 0.7f + k2 * 0.08f}); // dice bounces
                int lastSeg = segUnder(wheelAngle(p, 0));
                long lastTick = -100;
                for (long ms = 8; ms <= JACKPOT_WIN; ms += 8) { // a click every time the wheel passes a segment
                    int sg = segUnder(wheelAngle(p, ms));
                    if (sg != lastSeg) {
                        if (ms - lastTick >= 55) { ev.add(new float[]{ms, 0, 1.7f + (sg % 2) * 0.25f}); lastTick = ms; }
                        lastSeg = sg;
                    }
                }
                ev.add(new float[]{JACKPOT_WIN, 1, 1.0f});
                ev.add(new float[]{JACKPOT_WIN + 120, 1, 1.5f});
                for (int i = 0; i < 6; i++) ev.add(new float[]{JACKPOT_WIN + 50 + i * 110, 0, 1.2f + i * 0.15f});
                break;
            default: // HOLE
                ev.add(new float[]{0, 1, 0.5f});
                ev.add(new float[]{1400, 1, 0.6f});
                ev.add(new float[]{2800, 1, 0.7f});
                for (int i = 0; i < 7; i++) ev.add(new float[]{BH_COLLAPSE + i * 110, 0, 0.8f + i * 0.2f});
                ev.add(new float[]{BH_BLAST, 1, 0.5f});
                ev.add(new float[]{BH_BLAST, 0, 0.5f});
                ev.add(new float[]{BH_BLAST + 200, 1, 1.0f});
                ev.add(new float[]{BH_BLAST + 300, 0, 1.4f});
                break;
        }
        // little ticking sounds while the number counts up (the jackpot has its own)
        if (p.tier >= GOLD && p.tier != JACKPOT) {
            long base = PANEL_DELAY[p.tier] + 250;
            for (int i = 0; i < 7; i++) ev.add(new float[]{base + i * 95, 0, 1.0f + i * 0.12f});
        }
        ev.sort((x, y) -> Float.compare(x[0], y[0]));
        p.events = ev.toArray(new float[0][]);

        if (pending.size() < 40) pending.add(p);
    }

    private static void play(float type, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (type > 0.5f) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, pitch));
        } else {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch));
        }
    }

    // ===== RENDER =====
    public static synchronized void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        long now = nowMs();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastRender) / 1000f));
        lastRender = now;
        if (active.isEmpty() && pending.isEmpty()) {
            if (shakeYaw != 0f || shakePitch != 0f) shake(Minecraft.getInstance(), 0f, 0f);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // retire finished popups
        Iterator<Popup> it = active.iterator();
        while (it.hasNext()) {
            Popup p = it.next();
            if (now - p.start > p.duration) it.remove();
        }
        // bring in queued popups while there is room on the screen
        int used = 0;
        for (Popup q : active) used += slotNeed(q.tier);
        while (!pending.isEmpty() && active.size() < MAX_VISIBLE) {
            Popup q = pending.get(0);
            int need = slotNeed(q.tier);
            if (!active.isEmpty() && used + need > sh - TOP - 6) break;
            pending.remove(0);
            q.start = now;
            active.add(q);
            used += need;
        }
        if (active.isEmpty()) { shake(mc, 0f, 0f); return; }

        // sounds + sizes
        for (Popup p : active) {
            long t = now - p.start;
            while (p.nextEvent < p.events.length && p.events[p.nextEvent][0] <= t) {
                play(p.events[p.nextEvent][1], p.events[p.nextEvent][2]);
                p.nextEvent++;
            }
            layout(font, p);
        }

        // black hole: gently shake the view, harder and harder as it pulls
        float shS = 0f;
        long shT = 0;
        if (SCREEN_SHAKE) {
            for (Popup p : active) {
                if (p.tier != HOLE) continue;
                float inten = holeShake(now - p.start);
                if (inten > shS) { shS = inten; shT = now - p.start; }
            }
        }
        shake(mc, shS * 1.4f * (float) (Math.sin(shT * 0.037) * 0.6 + Math.sin(shT * 0.091 + 1.3) * 0.4),
                  shS * 0.9f * (float) (Math.sin(shT * 0.044 + 2.1) * 0.5 + Math.sin(shT * 0.117) * 0.5));

        // stack the visible panels down the right side (a leaving popup keeps its slot while it slides out)
        float cursor = TOP;
        float k = 1f - (float) Math.exp(-dt * 10f);
        for (Popup p : active) {
            long tp = now - p.start - PANEL_DELAY[p.tier];
            if (tp < 0) continue;
            if (!p.placed) { p.slotY = cursor; p.placed = true; }
            else p.slotY += (cursor - p.slotY) * k;
            cursor += slotNeed(p.tier);
        }

        // screen-wide dim + flash, shared so several popups don't stack them
        float dimA = 0f, flashA = 0f;
        int fr = 255, fgc = 255, fb = 255;
        for (Popup p : active) {
            long t = now - p.start;
            long tp = t - PANEL_DELAY[p.tier];
            if (p.tier == HOLE) {
                float d = Math.min(1f, t / 700f);
                if (t > BH_BLAST) d = Math.max(0f, 1f - (t - BH_BLAST) / 700f);
                dimA = Math.max(dimA, 195f * d);
                if (t >= BH_BLAST) {
                    float fp = (t - BH_BLAST) / 650f;
                    if (fp < 1f && 255f * (1f - fp) > flashA) { flashA = 255f * (1f - fp); fr = 255; fgc = 255; fb = 255; }
                }
            } else if (p.tier == JACKPOT) {
                float d = t < JACKPOT_WIN ? Math.min(1f, t / 400f) : Math.max(0f, 1f - (t - JACKPOT_WIN) / 800f);
                dimA = Math.max(dimA, 120f * d);
                if (t >= JACKPOT_WIN) {
                    float fp = (t - JACKPOT_WIN) / 600f;
                    if (fp < 1f && 180f * (1f - fp) > flashA) {
                        flashA = 180f * (1f - fp);
                        fr = (p.winRgb >> 16) & 255; fgc = (p.winRgb >> 8) & 255; fb = p.winRgb & 255;
                    }
                }
            } else if (p.tier == RUBY && t < 450) {
                float fa = 0.25f * 255f * (1f - t / 450f);
                if (fa > flashA) { flashA = fa; fr = 255; fgc = 40; fb = 80; }
            } else if (tp < 320) {
                float[] st = {0.03f, 0.08f, 0.12f, 0.16f};
                float fa = st[p.tier] * 255f * (1f - tp / 320f);
                if (fa > flashA) { flashA = fa; fr = 240; fgc = 240; fb = 255; }
            }
        }
        if (dimA > 2f) g.fill(0, 0, sw, sh, argb((int) dimA, 0, 0, 10));
        if (flashA > 2f) g.fill(0, 0, sw, sh, argb((int) flashA, fr, fgc, fb));

        // scenes behind the panels
        for (Popup p : active) {
            long t = now - p.start;
            long tp = t - PANEL_DELAY[p.tier];
            int a = alphaFor(p, t);
            if (p.tier == HOLE) blackHole(g, font, p, t, sw, sh);
            if (p.tier == RUBY) rubyScene(g, p, t, sw, sh);
            if (p.tier == JACKPOT) jackpotScene(g, font, p, t, sw, sh);
            if (tp >= 0) {
                float pulse = 0.75f + 0.25f * (float) Math.sin(tp / 110.0);
                if (p.tier == RUBY) edgeGlow(g, sw, sh, tp, a, accent(p, tp, pulse, a), 0.18f);
                else if (p.tier >= JACKPOT) edgeGlow(g, sw, sh, tp, a, accent(p, tp, pulse, a), 0.22f);
                if (p.tier == JACKPOT) confetti(g, p, tp, sw, sh, a);
            }
        }

        // the panels themselves
        for (Popup p : active) {
            long t = now - p.start;
            long tp = t - PANEL_DELAY[p.tier];
            if (tp < 0) continue;
            panel(g, font, p, tp, sw, alphaFor(p, t));
        }
    }

    private static int alphaFor(Popup p, long t) {
        float alpha = 1f;
        long fadeStart = p.duration - FADE_OUT;
        if (t > fadeStart) alpha = 1f - (t - fadeStart) / (float) FADE_OUT;
        return Math.max(4, (int) (alpha * 255));
    }

    private static void layout(Font font, Popup p) {
        int textW = Math.max(spacedWidth(font, "+$" + p.orig), font.width(p.from));
        p.h = PANEL_H;
        // bar 3 + gap 3 + face + gap 4 + text + right padding 13
        p.w = 3 + 3 + FACE + 4 + textW + 13;
    }

    // ===== PANEL (looks like the reference screenshot) =====
    private static void panel(GuiGraphicsExtractor g, Font font, Popup p, long tp, int sw, int a) {
        int tier = p.tier;
        boolean jack = tier == JACKPOT;
        int w = p.w, h = p.h;

        // slide in from the right (with a little overshoot), slide back out while fading
        float sp = Math.min(1f, tp / (float) SLIDE_IN);
        int xOff = (int) ((1f - backOut(sp)) * (w + 40));
        xOff += (int) ((1f - a / 255f) * (w * 0.7f));
        int x = sw - MARGIN - w + xOff;
        int y = (int) p.slotY + padOf(tier);

        float pulse = 0.75f + 0.25f * (float) Math.sin(tp / 130.0);
        int bar = accent(p, tp, pulse, a);

        // glow around the panel, in the colour of the bar
        float gs = tier == GREEN ? 0.8f : 1.2f;
        for (int i = 6; i >= 1; i--) {
            int ga = (int) (a * 0.035f * (7 - i) * pulse * gs);
            if (ga > 1) g.fill(x - i * 2, y - i * 2, x + w + i * 2, y + h + i * 2, withAlpha(bar, ga));
        }

        // near-black body, thin grey lines on the top and bottom, bright bar down the left side
        g.fill(x + 3, y, x + w, y + h, argb((int) (a * 0.92f), 15, 13, 15));
        int line = argb((int) (a * 0.65f), 118, 124, 108);
        g.fill(x + 3, y, x + w, y + 1, line);
        g.fill(x + 3, y + h - 1, x + w, y + h, line);
        g.fill(x, y, x + 3, y + h, bar);
        for (int k2 = 0; k2 < 16; k2++) { // the bar's light spills into the panel
            g.fill(x + 3 + k2, y + 1, x + 4 + k2, y + h - 1, withAlpha(bar, (int) (a * 0.20f * (1f - k2 / 16f) * pulse)));
        }

        // diamond + ruby: a bright shine sweeps across the panel
        boolean gem = tier == DIAMOND || tier == RUBY;
        float gemSx = -9999f;
        if (gem && tp > 400) {
            float gpos = ((tp - 400) % 1500) / 800f;
            if (gpos < 1f) gemSx = x - 20 + gpos * (w + 40);
        }
        if (gem) gemShine(g, x, y, w, h, tp, a, tier, gemSx);

        // player face (real skin once it has loaded)
        int fx = x + 6, fy = y + (h - FACE) / 2;
        drawFace(g, SkinFaces.DEFAULT_FACE, fx, fy, FACE, a);
        int[] real = SkinFaces.get(p.from);
        if (real != null) {
            if (p.faceT0 < 0) p.faceT0 = tp;
            float fa = Math.min(1f, (tp - p.faceT0) / 300f);
            drawFace(g, real, fx, fy, FACE, (int) (a * fa));
        }
        if (gem && gemSx > -9000f) shineBandAt(g, fx, fy, fx + FACE, fy + FACE, gemSx, y, h, 10, a, 1f);
        // name (white) and amount (tier colour), plain Minecraft text like in the reference
        int tx = x + 6 + FACE + 4;
        long countStart = 250;
        long countLen = 650;
        float cp = Math.min(1f, Math.max(0f, (tp - countStart) / (float) countLen));
        cp = 1f - (1f - cp) * (1f - cp) * (1f - cp);
        String title = "+$" + shown(p, cp);
        int white = argb(a, 255, 255, 255);
        int amt = amountColor(p, tp, a);
        g.text(font, p.from, tx + 1, y + 7, shadowOf(white));   // Minecraft-style drop shadow
        g.text(font, p.from, tx, y + 6, white);
        spacedText(g, font, title, tx + 1, y + 18, shadowOf(amt));
        spacedText(g, font, title, tx, y + 17, amt);
    }

    private static int shadowOf(int color) {
        return (color & 0xFF000000) | ((color & 0x00FCFCFC) >> 2);
    }

    /** Draws the 8x8 face scaled to any size (rows and columns are rounded so it also works for 20 px). */
    private static void drawFace(GuiGraphicsExtractor g, int[] face, int fx, int fy, int size, int a) {
        if (a < 4) return;
        for (int j = 0; j < 8; j++) {
            int y0 = fy + (j * size) / 8, y1 = fy + ((j + 1) * size) / 8;
            for (int i = 0; i < 8; i++) {
                int x0 = fx + (i * size) / 8, x1 = fx + ((i + 1) * size) / 8;
                g.fill(x0, y0, x1, y1, withAlpha(face[j * 8 + i], a));
            }
        }
    }

    // ===== TEXT =====
    private static int spacedWidth(Font font, String s) {
        if (DIGIT_SPACING == 0) return font.width(s);
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += font.width(String.valueOf(s.charAt(i))) + DIGIT_SPACING;
        return Math.max(0, w - DIGIT_SPACING);
    }

    private static void spacedText(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        if (DIGIT_SPACING == 0) {
            g.text(font, s, x, y, color);
            return;
        }
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            g.text(font, ch, cx, y, color);
            cx += font.width(ch) + DIGIT_SPACING;
        }
    }

    // ===== SCREEN-EDGE GLOW =====
    private static void edgeGlow(GuiGraphicsExtractor g, int sw, int sh, long tp, int a, int accent, float strength) {
        float pulse = 0.6f + 0.4f * (float) Math.sin(tp / 260.0);
        for (int i = 0; i < 8; i++) {
            int ea = (int) (a * strength * pulse * (1f - i / 8f));
            if (ea < 3) continue;
            int c = withAlpha(accent, ea);
            int th = 3, o = i * 3;
            g.fill(o, o, sw - o, o + th, c);
            g.fill(o, sh - o - th, sw - o, sh - o, c);
            g.fill(o, o + th, o + th, sh - o - th, c);
            g.fill(sw - o - th, o + th, sw - o, sh - o - th, c);
        }
    }

    // ===== DIAMOND / RUBY SHINE =====
    private static void shineBandAt(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float sx,
                                    int panelY, int panelH, int bandW, int a, float amul) {
        for (int r = y0; r < y1; r += 2) {
            int off = (panelH - (r - panelY)) / 3;
            int b0 = Math.max(x0, (int) sx + off);
            int b1 = Math.min(x1, (int) sx + off + bandW);
            int ry1 = Math.min(y1, r + 2);
            if (b1 > b0) g.fill(b0, r, b1, ry1, argb((int) (a * 0.26f * amul), 255, 255, 255));
            int c0 = Math.max(x0, (int) sx + off + bandW / 2 - 1);
            int c1 = Math.min(x1, (int) sx + off + bandW / 2 + 2);
            if (c1 > c0) g.fill(c0, r, c1, ry1, argb((int) (a * 0.55f * amul), 255, 255, 255));
        }
    }

    private static void gemShine(GuiGraphicsExtractor g, int x, int y, int w, int h, long tp, int a, int tier, float sx) {
        if (sx > -9000f) shineBandAt(g, x + 3, y + 1, x + w, y + h - 1, sx, y, h, 14, a, 1f);
    }

    private static void flare(GuiGraphicsExtractor g, int cx, int cy, int size, int fa) {
        if (fa < 6) return;
        g.fill(cx - size, cy, cx + size + 1, cy + 1, argb(fa, 255, 255, 255));
        g.fill(cx, cy - size, cx + 1, cy + size + 1, argb(fa, 255, 255, 255));
        int da = (int) (fa * 0.6f);
        for (int d = 1; d <= size / 2; d++) {
            g.fill(cx - d, cy - d, cx - d + 1, cy - d + 1, argb(da, 255, 255, 255));
            g.fill(cx + d, cy - d, cx + d + 1, cy - d + 1, argb(da, 255, 255, 255));
            g.fill(cx - d, cy + d, cx - d + 1, cy + d + 1, argb(da, 255, 255, 255));
            g.fill(cx + d, cy + d, cx + d + 1, cy + d + 1, argb(da, 255, 255, 255));
        }
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, argb(fa, 255, 255, 255));
    }

    // ===== BLACK HOLE =====
    private static void blackHole(GuiGraphicsExtractor g, Font font, Popup p, long t, int sw, int sh) {
        if (t > BH_BLAST + 1800) return;
        float k = sh / 270f;
        int cx = sw / 2;
        int cy = sh / 2;

        if (t < BH_BLAST) {
            float vis = Math.min(1f, t / 700f);
            float grow = Math.min(1f, t / 1000f);
            float collapse = t < BH_COLLAPSE ? 1f : Math.max(0f, 1f - (t - BH_COLLAPSE) / (float) (BH_BLAST - BH_COLLAPSE));
            float rush = 1f - collapse;
            float R = 22f * k * grow * collapse;
            float baseR = 22f * k * grow;

            starfield(g, p, cx, cy, t, k, vis, rush);
            pullStreaks(g, p, cx, cy, t, vis, rush, sw, sh);

            // lens ripples contracting towards the hole
            for (int i = 0; i < 2; i++) {
                float ph = ((t / 1100f) + i * 0.5f) % 1f;
                float rr = (130f - 100f * ph) * k;
                int ra = (int) (60 * vis * Math.sin(ph * Math.PI) * (1f - rush));
                if (ra > 3) ringPts(g, cx, cy, rr, argb(ra, 170, 150, 255), 1);
            }

            diskParticles(g, p, cx, cy, t, baseR, vis, rush, false);
            if (R >= 2f) {
                ringPts(g, cx, cy, R + 5f, argb((int) (60 * vis), 255, 150, 60), 2);
                disc(g, cx, cy, (int) R, 0xFF000000);
                ringPts(g, cx, cy, R + 1.5f, argb((int) (235 * vis), 255, 255, 255), 1);
            }
            diskParticles(g, p, cx, cy, t, baseR, vis, rush, true);

            // bent light arcs over the top and under the bottom of the hole
            if (R >= 3f) {
                for (int i = 0; i < 80; i++) {
                    float f = i / 79f;
                    double angU = Math.PI + f * Math.PI;
                    float rU = R * 1.32f + (float) Math.sin(t / 140.0 + i) * 0.6f;
                    float edge = (float) Math.sin(f * Math.PI);
                    int al = (int) (230 * vis * edge * (0.7f + 0.3f * Math.sin(t / 110.0 + i * 0.7)));
                    if (al > 8) g.fill(cx + (int) (Math.cos(angU) * rU), cy + (int) (Math.sin(angU) * rU * 0.95f),
                            cx + (int) (Math.cos(angU) * rU) + 2, cy + (int) (Math.sin(angU) * rU * 0.95f) + 2,
                            argb(al, 255, 225, 165));
                    double angL = f * Math.PI;
                    float rL = R * 1.14f;
                    int al2 = (int) (130 * vis * edge);
                    if (al2 > 8) g.fill(cx + (int) (Math.cos(angL) * rL), cy + (int) (Math.sin(angL) * rL * 0.95f),
                            cx + (int) (Math.cos(angL) * rL) + 2, cy + (int) (Math.sin(angL) * rL * 0.95f) + 2,
                            argb(al2, 255, 200, 140));
                }
            }

            // polar jets
            if (t > 1500 && t < BH_COLLAPSE + 300 && R > 4f) {
                float jp = Math.min(1f, (t - 1500) / 900f);
                float jpulse = 0.75f + 0.25f * (float) Math.sin(t / 90.0);
                int len = (int) (75 * jp * k);
                for (int d = 0; d < len; d += 3) {
                    float f = d / (float) Math.max(1, len);
                    int jw = Math.max(1, 3 - (int) (f * 3));
                    int ja = (int) (190 * (1f - f) * jpulse * jp);
                    if (ja < 5) continue;
                    g.fill(cx - jw, cy - (int) R - d - 3, cx + jw, cy - (int) R - d, argb(ja, 160, 200, 255));
                    g.fill(cx - jw, cy + (int) R + d, cx + jw, cy + (int) R + d + 3, argb(ja, 160, 200, 255));
                }
            }

            // bright core in the last moments before the blast
            if (rush > 0.35f) {
                float cf = (rush - 0.35f) / 0.65f;
                int cr = (int) ((2f + 14f * cf) * k);
                disc(g, cx, cy, cr + 4, argb((int) (60 * cf), 200, 170, 255));
                disc(g, cx, cy, cr, argb((int) (255 * cf), 255, 255, 255));
            }

            // warning text
            String msg = null;
            if (t > 500 && t < 2400) msg = "ANOMALY DETECTED";
            else if (t >= 2400 && t < BH_COLLAPSE) msg = "GRAVITATIONAL COLLAPSE";
            else if (t >= BH_COLLAPSE) msg = "SINGULARITY IMMINENT";
            if (msg != null && ((t / 240) % 2 == 0 || t >= BH_COLLAPSE)) {
                int mx = cx - font.width(msg) / 2;
                g.text(font, msg, mx, 14, argb(255, 225, 195, 255));
                g.text(font, msg, mx + 1, 14, argb(255, 225, 195, 255));
            }
        } else {
            float rt = (t - BH_BLAST) / 1000f;

            // light cross flare
            float fe = Math.min(1f, rt / 0.35f);
            fe = 1f - (1f - fe) * (1f - fe);
            int al = (int) (230 * Math.max(0f, 1f - rt));
            if (al > 5) {
                int l1 = (int) (sw * 0.5f * fe), l2 = (int) (sh * 0.5f * fe);
                g.fill(cx - l1, cy - 1, cx + l1, cy + 1, argb(al, 255, 245, 255));
                g.fill(cx - 1, cy - l2, cx + 1, cy + l2, argb(al, 255, 245, 255));
            }

            // shockwaves
            for (int i = 0; i < 3; i++) {
                float st = (t - BH_BLAST - i * 140) / 1000f;
                if (st < 0f || st > 1f) continue;
                float e = 1f - (1f - st) * (1f - st);
                int ra = (int) (200 * (1f - st));
                if (ra > 4) ringPts(g, cx, cy, e * 320f, argb(ra, i == 1 ? 255 : 210, i == 1 ? 255 : 160, 255), 3);
            }

            // debris
            if (rt < 1.6f) {
                for (int i = 0; i < 50; i++) {
                    float[] c = p.coins[i];
                    float dx = (float) Math.cos(c[4]) * c[1] * 2.6f * rt;
                    float dyy = (float) Math.sin(c[4]) * c[1] * 2.1f * rt + 120f * rt * rt;
                    int s = (int) c[2];
                    int da = (int) (255 * Math.max(0f, 1f - rt / 1.6f));
                    if (da > 6) g.fill(cx + (int) dx, cy + (int) dyy, cx + (int) dx + s, cy + (int) dyy + s,
                            (i % 2 == 0) ? argb(da, 215, 170, 255) : argb(da, 255, 255, 255));
                }
            }
        }
    }

    private static void starfield(GuiGraphicsExtractor g, Popup p, int cx, int cy, long t, float k, float vis, float rush) {
        float sec = t / 1000f;
        float pull = 1f - 0.3f * Math.min(1f, t / (float) BH_COLLAPSE);
        for (float[] c : p.stars) {
            float r = c[1] * k * pull * (1f - rush);
            if (r < 3f) continue;
            float spin = 2.4f / (1f + c[1] / 70f);
            float ang = c[0] + spin * (sec + rush * 1.5f);
            float tw = 0.55f + 0.45f * (float) Math.sin(t / 200.0 + c[3]);
            for (int j = 0; j < 4; j++) {
                double aj = ang - j * 0.05f;
                int px = cx + (int) (Math.cos(aj) * r);
                int py = cy + (int) (Math.sin(aj) * r * 0.8f);
                int sa = (int) (200 * vis * tw * (1f - j * 0.27f));
                int sz = j == 0 ? (int) c[2] : 1;
                if (sa > 6) g.fill(px, py, px + sz, py + sz, argb(sa, 190, 210, 255));
            }
        }
    }

    private static void diskParticles(GuiGraphicsExtractor g, Popup p, int cx, int cy, long t, float baseR,
                                      float vis, float rush, boolean front) {
        float sec = t / 1000f;
        float settle = 1f - 0.2f * Math.min(1f, t / (float) BH_COLLAPSE);
        for (float[] c : p.disk) {
            float r = baseR * c[1] * settle * (1f - rush);
            if (r < 2f) continue;
            float ang = c[0] + c[2] * (sec + 0.125f * sec * sec) + c[2] * 3f * rush * rush;
            float sn = (float) Math.sin(ang);
            if ((sn >= 0f) != front) continue;
            float cs = (float) Math.cos(ang);
            int px = cx + (int) (cs * r);
            int py = cy + (int) (sn * r * 0.22f + c[4] * 1.5f);
            float f = Math.min(1f, Math.max(0f, (c[1] - 1.7f) / 4.3f));
            int col = f < 0.4f ? mixRgb(0xD2E6FF, 0xFFCD78, f / 0.4f) : mixRgb(0xFFCD78, 0xE65A28, (f - 0.4f) / 0.6f);
            float dop = 0.7f + 0.3f * (-cs);
            int pa = (int) (235 * vis * Math.min(1f, dop));
            int sz = Math.max(1, (int) c[3]);
            int streak = 1 + (int) (3f / Math.max(1f, c[1] * 0.7f));
            if (pa > 6) g.fill(px, py, px + sz + streak, py + sz, withAlpha(col, pa));
        }
    }

    // ===== JACKPOT PAYOUT =====
    private static void fountain(GuiGraphicsExtractor g, Popup p, long ft, int ox, int oy, int a) {
        for (int i = 0; i < p.coinCount; i++) {
            float[] c = p.coins[i];
            float ct = ((ft - c[4] * 120f) % 1900f) / 1000f;
            if (ct < 0f) continue;
            float vx = ((float) Math.cos(c[0]) - 0.6f) * c[1] * 1.1f;
            float vy = (float) (-Math.sin(c[0])) * c[1] * 0.9f + 20f;
            float px = ox + vx * ct;
            float py = oy - vy * ct + 330f * ct * ct;
            float flip = Math.abs((float) Math.cos(c[4] + c[3] * ct));
            int s = (int) c[2];
            int fw = Math.max(1, (int) (s * flip));
            g.fill((int) px, (int) py, (int) px + fw, (int) py + s, (flip > 0.5f ? hsv(c[4] / 6.28f, a) : argb(a, 255, 255, 255)));
        }
    }

    // ===== DRAWING HELPERS =====
    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        if (r < 1) return;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    private static void ringPts(GuiGraphicsExtractor g, int cx, int cy, float radius, int color, int size) {
        int n = Math.max(16, (int) (2 * Math.PI * radius / (size * 1.5f)));
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            int px = cx + (int) (Math.cos(ang) * radius);
            int py = cy + (int) (Math.sin(ang) * radius);
            g.fill(px, py, px + size, py + size, color);
        }
    }

    private static void confetti(GuiGraphicsExtractor g, Popup p, long ct0, int sw, int sh, int a) {
        for (float[] c : p.confetti) {
            float ct = (ct0 - c[1]) / 1000f;
            if (ct < 0f) continue;
            int px = (int) (c[0] * sw + Math.sin(ct * 3 + c[4] * 6) * 14);
            int py = (int) (-8 + c[2] * ct);
            if (py > sh) continue;
            int size = (int) c[3];
            int fw = Math.max(1, (int) (size * Math.abs(Math.cos(ct * 6 + c[4] * 10))));
            g.fill(px, py, px + fw, py + size, hsv(c[4], a));
        }
    }

    // ===== RUBY: the gem appears on the screen for a moment =====
    private static void rubyScene(GuiGraphicsExtractor g, Popup p, long t, int sw, int sh) {
        if (t >= RUBY_SHOW) return;
        float k = sh / 270f;
        int cx = sw / 2, cy = (int) (sh * 0.46f);
        float in = Math.min(1f, t / 260f);
        float out = t > RUBY_SHOW - 300 ? (t - (RUBY_SHOW - 300)) / 300f : 0f;
        int a = (int) (255 * (1f - out));
        if (a < 6) return;
        float size = 64f * k * backOut(in) * (1f + 0.12f * out);
        float pulse = 0.7f + 0.3f * (float) Math.sin(t / 90.0);

        // red glow behind the gem
        for (int i = 5; i >= 1; i--) disc(g, cx, cy, (int) (size * 0.6f) + i * (int) (6 * k), argb((int) (a * 0.07f * pulse), 255, 30, 70));
        // rotating rays of light
        float rayLen = 70f * k * in;
        for (int ray = 0; ray < 10; ray++) {
            double ang = ray * Math.PI / 5 + t / 700.0;
            for (float d = size * 0.55f; d < size * 0.55f + rayLen; d += 5f) {
                float f = (d - size * 0.55f) / Math.max(1f, rayLen);
                int al = (int) (a * 0.35f * (1f - f) * pulse);
                if (al < 4) continue;
                int px = cx + (int) (Math.cos(ang) * d), py = cy + (int) (Math.sin(ang) * d);
                g.fill(px, py, px + 2, py + 2, argb(al, 255, 90, 120));
            }
        }
        drawRuby(g, cx, cy, (int) size, a, t);
        // sparkles around it
        for (int i = 0; i < 6; i++) {
            double ang = i * 1.05 + t / 400.0;
            float tw = (float) Math.sin(t / 110.0 + i * 1.9);
            if (tw > 0.2f) flare(g, cx + (int) (Math.cos(ang) * size * 0.78f), cy + (int) (Math.sin(ang) * size * 0.66f),
                    2 + (int) (4 * tw), (int) (a * tw));
        }
    }

    private static void drawRuby(GuiGraphicsExtractor g, int cx, int cy, int S, int a, long t) {
        if (S < 8) return;
        int crown = (int) (S * 0.36f);
        int top = cy - S / 2;
        int edge = argb(a, 90, 0, 20);
        float sweepX = cx - S / 2f - 12 + ((t % 700) / 700f) * (S + 24);
        for (int i = 0; i < S; i++) {
            float half;
            if (i < crown) half = S * (0.31f + 0.19f * (i / (float) crown));
            else half = S * 0.5f * (1f - (i - crown) / (float) (S - crown));
            int h2 = (int) half;
            if (h2 < 1) continue;
            int y = top + i;
            if (i == 0 || i == crown || i == S - 1) { // top edge, girdle line and the tip
                g.fill(cx - h2, y, cx + h2, y + 1, edge);
                continue;
            }
            int in = Math.max(1, h2 - 2);
            int cw = (int) (h2 * 0.42f);
            boolean table = i < crown * 0.28f;
            int left = table ? argb(a, 235, 60, 95) : argb(a, 175, 10, 45);
            int mid = table ? argb(a, 255, 150, 175) : argb(a, 255, 70, 110);
            int right = table ? argb(a, 200, 30, 70) : argb(a, 125, 0, 30);
            g.fill(cx - h2, y, cx - in, y + 1, edge);
            g.fill(cx + in, y, cx + h2, y + 1, edge);
            g.fill(cx - in, y, cx - cw, y + 1, left);
            g.fill(cx - cw, y, cx + cw, y + 1, mid);
            g.fill(cx + cw, y, cx + in, y + 1, right);
            if (i < crown && i > 2 && i % 3 != 0) { // a glint on the left of the crown
                g.fill(cx - in + 2 + i / 3, y, cx - in + 4 + i / 3, y + 1, argb((int) (a * 0.55f), 255, 255, 255));
            }
            int b0 = Math.max(cx - in, (int) sweepX + (S - i) / 3); // shine sweeping across
            int b1 = Math.min(cx + in, (int) sweepX + (S - i) / 3 + 9);
            if (b1 > b0) g.fill(b0, y, b1, y + 1, argb((int) (a * 0.40f), 255, 255, 255));
        }
    }

    // ===== JACKPOT: dice + a glowing wheel with random colours =====
    private static final int SEGS = 12;
    private static final int[][][] PIPS = {
        {{1, 1}}, {{0, 0}, {2, 2}}, {{0, 0}, {1, 1}, {2, 2}}, {{0, 0}, {2, 0}, {0, 2}, {2, 2}},
        {{0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2}}, {{0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2}}
    };

    private static float wheelFinal(Popup p) {
        return (float) (-Math.PI / 2 - (p.winSeg + 0.5) * (2 * Math.PI / SEGS));
    }

    /** Angle of the wheel at time t: it spins fast and slows down until it stops exactly on the winning segment. */
    private static float wheelAngle(Popup p, long t) {
        float u = Math.min(1f, t / (float) JACKPOT_WIN);
        float e = 1f - (float) Math.pow(1f - u, 3.2);
        return wheelFinal(p) - p.wheelTurns * (1f - e);
    }

    private static int segUnder(float theta) { // which segment is under the pointer at the top
        double rel = (-Math.PI / 2 - theta) % (2 * Math.PI);
        if (rel < 0) rel += 2 * Math.PI;
        return (int) (rel / (2 * Math.PI / SEGS)) % SEGS;
    }

    private static int hueRgb(float h) {
        return hsvFull(h, 0.85f, 1f, 255) & 0xFFFFFF;
    }

    private static int dieFace(long t, int id) { // faces flicker while it tumbles, then it lands on a six
        if (t >= 1700) return 6;
        double v = Math.sin((t / 80) * 12.9898 + id * 78.233) * 43758.5453;
        return 1 + (int) (Math.abs(v) % 6);
    }

    private static void jackpotScene(GuiGraphicsExtractor g, Font font, Popup p, long t, int sw, int sh) {
        float k = sh / 270f;
        int cx = sw / 2, cy = (int) (sh * 0.46f);
        float endFade = Math.min(1f, Math.max(0f, (p.duration - t) / 1500f));
        float appear = Math.min(1f, t / 500f);
        int a = (int) (255 * endFade * Math.min(1f, t / 300f));
        if (a < 6) return;
        int R = (int) (46 * k * backOut(appear));
        float pulse = 0.7f + 0.3f * (float) Math.sin(t / 140.0);
        float theta = wheelAngle(p, t);
        int under = segUnder(theta);
        boolean won = t >= JACKPOT_WIN;
        int litRgb = won ? p.winRgb : hueRgb(p.segHue[under]);
        int lr = (litRgb >> 16) & 255, lg = (litRgb >> 8) & 255, lb = litRgb & 255;

        // coloured light around the edges of the screen
        edgeGlow(g, sw, sh, t, (int) (a * 0.8f), argb(255, lr, lg, lb), won ? 0.30f : 0.18f);

        // glow behind the wheel, in the colour of the segment under the pointer
        for (int i = 7; i >= 1; i--) {
            disc(g, cx, cy, R + i * (int) (7 * k), argb((int) (a * 0.05f * pulse * (won ? 1.4f : 1f)), lr, lg, lb));
        }

        wheel(g, p, cx, cy, R, theta, under, a, pulse);

        // light bulbs round the rim, each in one of the wheel's random colours
        for (int i = 0; i < 24; i++) {
            double ang = i * 2 * Math.PI / 24 - Math.PI / 2;
            int bx = cx + (int) (Math.cos(ang) * (R + 4 * k));
            int by = cy + (int) (Math.sin(ang) * (R + 4 * k));
            boolean on = won ? (((t / 90) % 2 == 0) == (i % 2 == 0)) : ((i + t / 110) % 2 == 0);
            int rgb = hueRgb(p.segHue[(i / 2) % SEGS]);
            if (on) g.fill(bx - 2, by - 2, bx + 3, by + 3, withAlpha(rgb, (int) (a * 0.25f)));
            g.fill(bx - 1, by - 1, bx + 2, by + 2, on ? withAlpha(rgb, a) : argb((int) (a * 0.25f), 90, 80, 60));
        }

        // pointer at the top
        int pH = (int) (16 * k), pW = (int) (7 * k);
        int py0 = cy - R - (int) (12 * k);
        for (int r = 0; r < pH; r++) {
            int half = (int) (pW * (1f - r / (float) pH));
            g.fill(cx - half - 1, py0 + r, cx + half + 2, py0 + r + 1, argb(a, 40, 30, 20));
            g.fill(cx - half, py0 + r, cx + half + 1, py0 + r + 1, argb(a, 255, 235, 120));
        }

        // two dice that bounce in, tumble, and land on double six
        int ds = (int) (22 * k);
        int ground = cy + (int) (R * 0.62f);
        for (int id = 0; id < 2; id++) {
            long dt = Math.max(0, t - id * 140L);
            int side = id == 0 ? -1 : 1;
            float slide = (1f - easeOut(Math.min(1f, dt / 1500f))) * 90f * k;
            int dx = cx + side * (R + (int) (46 * k) + (int) slide) - ds / 2;
            float yOff = 0f;
            if (dt < 1560) {
                float bt = dt / 520f;
                int bn = (int) bt;
                float f = bt - bn;
                yOff = 95f * k * (float) Math.pow(0.42, bn) * 4f * f * (1f - f);
            }
            int dy = ground - ds - (int) yOff;
            int jit = dt < 1700 ? (int) Math.round(Math.sin(dt / 25.0 + id) * 1.5) : 0;
            drawDie(g, dx + jit, dy, ds, dieFace(dt, id), a, pulse);
        }

        // banner
        if (!won || t < JACKPOT_WIN + 3000) {
            String msg = won ? "JACKPOT!!!" : "ROLL THE DICE";
            if (won || (t / 400) % 2 == 0) {
                int mx = cx - font.width(msg) / 2;
                int col = won ? argb(a, lr, lg, lb) : argb((int) (a * 0.85f), 255, 235, 150);
                g.text(font, msg, mx, 14, col);
                g.text(font, msg, mx + 1, 14, col);
            }
        }

        // the win: shockwaves in the winning colour, and coins and confetti in random colours
        if (won) {
            for (int i = 0; i < 4; i++) {
                float st = (t - JACKPOT_WIN - i * 150) / 1100f;
                if (st < 0f || st > 1f) continue;
                float e = 1f - (1f - st) * (1f - st);
                int ra = (int) (210 * (1f - st) * endFade);
                if (ra > 4) ringPts(g, cx, cy, R + e * sw * 0.5f, argb(ra, lr, lg, lb), 3);
            }
            fountain(g, p, t - JACKPOT_WIN, cx, cy, (int) (255 * endFade));
        }
    }

    private static void wheel(GuiGraphicsExtractor g, Popup p, int cx, int cy, int R, float theta, int under, int a, float pulse) {
        if (R < 4) return;
        double seg = 2 * Math.PI / SEGS;
        int hubR = Math.max(3, R / 7);
        int rimR2 = (R - 3) * (R - 3), hubR2 = hubR * hubR;
        int band1 = (int) (R * 0.40f), band2 = (int) (R * 0.72f);
        int b1sq = band1 * band1, b2sq = band2 * band2;
        int[] rgb = new int[SEGS];
        for (int i = 0; i < SEGS; i++) rgb[i] = hueRgb(p.segHue[i]);
        for (int dy = -R; dy <= R; dy++) {
            int half = (int) Math.sqrt((double) R * R - (double) dy * dy);
            int runStart = -half, runKey = Integer.MIN_VALUE, runColor = 0;
            for (int dx = -half; dx <= half + 1; dx++) {
                int key, color;
                if (dx > half) {
                    key = Integer.MAX_VALUE;
                    color = 0;
                } else {
                    int r2 = dx * dx + dy * dy;
                    if (r2 > rimR2) { key = -1; color = argb(a, 235, 190, 60); }
                    else if (r2 <= hubR2) { key = -2; color = argb(a, 255, 225, 110); }
                    else {
                        double rel = (Math.atan2(dy, dx) - theta) % (2 * Math.PI);
                        if (rel < 0) rel += 2 * Math.PI;
                        double sf = rel / seg;
                        int idx = (int) sf % SEGS;
                        double fr = sf - (int) sf;
                        if (fr < 0.04 || fr > 0.96) { key = -3; color = argb(a, 25, 20, 30); }
                        else {
                            int band = r2 < b1sq ? 0 : (r2 < b2sq ? 1 : 2);
                            boolean lit = idx == under;
                            key = idx * 8 + band * 2 + (lit ? 1 : 0);
                            float f = (band == 0 ? 0.62f : band == 1 ? 0.82f : 1f) * (lit ? 1.12f + 0.12f * pulse : 0.85f);
                            color = shade(rgb[idx], f, a);
                        }
                    }
                }
                if (key != runKey) {
                    if (runKey != Integer.MIN_VALUE && runKey != Integer.MAX_VALUE) {
                        g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
                    }
                    runStart = dx;
                    runKey = key;
                    runColor = color;
                }
            }
        }
    }

    private static int shade(int rgb, float f, int a) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int gg = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return argb(a, r, gg, b);
    }

    private static void drawDie(GuiGraphicsExtractor g, int x, int y, int ds, int face, int a, float pulse) {
        for (int i = 3; i >= 1; i--) {
            g.fill(x - i * 3, y - i * 3, x + ds + i * 3, y + ds + i * 3, argb((int) (a * 0.07f * pulse), 255, 225, 120));
        }
        g.fill(x + 1, y, x + ds - 1, y + 1, argb(a, 110, 100, 80));
        g.fill(x, y + 1, x + ds, y + ds - 1, argb(a, 110, 100, 80));
        g.fill(x + 1, y + ds - 1, x + ds - 1, y + ds, argb(a, 110, 100, 80));
        g.fill(x + 1, y + 1, x + ds - 1, y + ds - 2, argb(a, 250, 246, 232));
        g.fill(x + 2, y + 1, x + ds - 2, y + 3, argb(a, 255, 255, 255));
        int ps = Math.max(2, ds / 6);
        for (int[] q : PIPS[Math.max(1, Math.min(6, face)) - 1]) {
            int px = x + (int) (ds * (0.22f + 0.28f * q[0])) - ps / 2 + 1;
            int py = y + (int) (ds * (0.22f + 0.28f * q[1])) - ps / 2 + 1;
            g.fill(px, py, px + ps, py + ps, argb(a, 35, 30, 40));
        }
    }

    // ===== BLACK HOLE: things get pulled to the middle, and the view shakes =====
    private static void pullStreaks(GuiGraphicsExtractor g, Popup p, int cx, int cy, long t, float vis, float rush, int sw, int sh) {
        float inten = Math.min(1f, Math.max(0f, (t - 500) / 3400f)) * vis;
        if (inten < 0.02f) return;
        float rmax = (float) Math.hypot(sw, sh) * 0.5f;
        for (float[] c : p.stars) {
            float period = 1100f - 600f * inten - 300f * rush;
            float u = ((t + c[3] * 160f) % period) / period; // 0 at the edge of the screen, 1 in the middle
            float r = rmax * (1f - u * u);
            float trail = 14f + 60f * inten;
            for (int j = 0; j < 6; j++) {
                float rr = r + j * trail / 6f;
                if (rr > rmax) break;
                int px = cx + (int) (Math.cos(c[0]) * rr);
                int py = cy + (int) (Math.sin(c[0]) * rr);
                int al = (int) (255 * inten * (1f - j / 6f) * Math.sqrt(Math.sin(Math.PI * u)));
                int sz = j < 3 ? 2 : 1;
                if (al > 5) g.fill(px, py, px + sz, py + sz, argb(al, 200, 220, 255));
            }
        }
    }

    /** 0..1 while the black hole pulls (stronger and stronger), then one big kick at the blast. */
    private static float holeShake(long t) {
        if (t < 600) return 0f;
        if (t < BH_BLAST) return (float) Math.pow(Math.min(1f, (t - 600) / (float) (BH_BLAST - 600)), 1.4);
        if (t < BH_BLAST + 600) {
            float f = 1f - (t - BH_BLAST) / 600f;
            return 1.3f * f * f;
        }
        return 0f;
    }

    /** Turns your view by (yaw, pitch) degrees; the difference to last frame is applied, so it always returns to 0. */
    private static void shake(Minecraft mc, float yaw, float pitch) {
        if (mc.player == null) { shakeYaw = 0f; shakePitch = 0f; return; }
        float dy = yaw - shakeYaw, dp = pitch - shakePitch;
        if (dy != 0f || dp != 0f) {
            mc.player.setYRot(mc.player.getYRot() + dy);
            mc.player.setXRot(Math.max(-90f, Math.min(90f, mc.player.getXRot() + dp)));
        }
        shakeYaw = yaw;
        shakePitch = pitch;
    }

    private static int hsvFull(float h, float s, float v, int a) {
        h = h - (float) Math.floor(h);
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float pp = v * (1f - s), q = v * (1f - s * f), tt = v * (1f - s * (1f - f));
        float r, gr, b;
        switch (i % 6) {
            case 0: r = v; gr = tt; b = pp; break;
            case 1: r = q; gr = v; b = pp; break;
            case 2: r = pp; gr = v; b = tt; break;
            case 3: r = pp; gr = q; b = v; break;
            case 4: r = tt; gr = pp; b = v; break;
            default: r = v; gr = pp; b = q; break;
        }
        return argb(a, (int) (r * 255), (int) (gr * 255), (int) (b * 255));
    }

    // ===== COLOR / MATH HELPERS =====
    /** Colour of the bar on the left of the panel. */
    private static int accent(Popup p, long t, float pulse, int a) {
        int tier = p.tier;
        switch (tier) {
            case GREEN: return argb(a, 118, 226, 124);
            case GOLD: return argb(a, 255, 196, 48);
            case DIAMOND: {
                float s = 0.5f + 0.5f * (float) Math.sin(t / 260.0);
                return argb(a, (int) (110 + 110 * s), (int) (225 + 30 * s), 255);
            }
            case RUBY: return argb(a, (int) (205 + 50 * pulse), (int) (20 + 30 * pulse), (int) (55 + 25 * pulse));
            case JACKPOT: {
                float f = 0.8f + 0.2f * pulse;
                return argb(a, (int) (((p.winRgb >> 16) & 255) * f), (int) (((p.winRgb >> 8) & 255) * f), (int) ((p.winRgb & 255) * f));
            }
            default: return argb(a, (int) (165 * pulse), (int) (20 + 80 * pulse), (int) (255 * pulse));
        }
    }

    /** Colour of the amount text. */
    private static int amountColor(Popup p, long t, int a) {
        int tier = p.tier;
        switch (tier) {
            case GREEN: return argb(a, 85, 255, 85);
            case GOLD: return argb(a, 255, 205, 60);
            case DIAMOND: return argb(a, 120, 235, 255);
            case RUBY: return argb(a, 255, 95, 120);
            case JACKPOT: return withAlpha(mixRgb(p.winRgb, 0xFFFFFF, 0.35f), a);
            default: return argb(a, 205, 150, 255);
        }
    }

    private static String shown(Popup p, float c) {
        if (c >= 1f || p.num <= 0) return p.orig;
        double v = p.num * c;
        String body;
        if (p.decimals > 0) body = String.format(Locale.ROOT, "%." + p.decimals + "f", v);
        else if (p.comma) body = String.format(Locale.ROOT, "%,d", (long) v);
        else body = Long.toString((long) v);
        return body + p.suffix;
    }

    private static float easeOut(float x) {
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    private static float backOut(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float d = x - 1f;
        return 1f + c3 * d * d * d + c1 * d * d;
    }

    private static int mixRgb(int c1, int c2, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (((c1 >> 16) & 0xFF) * (1f - f) + ((c2 >> 16) & 0xFF) * f);
        int gg = (int) (((c1 >> 8) & 0xFF) * (1f - f) + ((c2 >> 8) & 0xFF) * f);
        int b = (int) ((c1 & 0xFF) * (1f - f) + (c2 & 0xFF) * f);
        return (r << 16) | (gg << 8) | b;
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((Math.max(0, Math.min(255, a))) << 24);
    }

    private static int hsv(float h, int a) {
        h = h - (float) Math.floor(h);
        float s = 0.7f;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float pp = 1f - s, q = 1f - s * f, tt = 1f - s * (1f - f);
        float r, gr, b;
        if (i == 0) { r = 1; gr = tt; b = pp; }
        else if (i == 1) { r = q; gr = 1; b = pp; }
        else if (i == 2) { r = pp; gr = 1; b = tt; }
        else if (i == 3) { r = pp; gr = q; b = 1; }
        else if (i == 4) { r = tt; gr = pp; b = 1; }
        else { r = 1; gr = pp; b = q; }
        return argb(a, (int) (r * 255), (int) (gr * 255), (int) (b * 255));
    }

    private static int argb(int a, int r, int gr, int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (gr & 0xFF) << 8 | (b & 0xFF);
    }

    // ===== SKIN FACES (player head from the Minecraft skin) =====
    /**
     * Gets the 8x8 face (with the hat layer) of a player's Minecraft skin, using Mojang's official API.
     * Only the player name is sent to Mojang. Results are cached for the whole session.
     */
    static final class SkinFaces {
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();
        private static final Map<String, Long> TRIED = new ConcurrentHashMap<>();
        private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "paypopup-skins");
            t.setDaemon(true);
            return t;
        });

        /** A Steve-like face shown while the real one is loading (or if it can't be found). */
        public static final int[] DEFAULT_FACE = buildDefault();

        private SkinFaces() {}

        /** Returns 64 ARGB pixels (8x8) or null while the real face is still loading. Starts the lookup once. */
        public static int[] get(String name) {
            String k = name.toLowerCase(Locale.ROOT);
            int[] f = CACHE.get(k);
            if (f != null) return f;
            long now = System.currentTimeMillis();
            Long since = TRIED.get(k);
            if (since == null || now - since > 90_000) { // never tried, or an old failed try
                TRIED.put(k, now);
                POOL.submit(() -> load(name, k));
            }
            return null;
        }

        private static void load(String name, String key) {
            try {
                String prof = http("https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8));
                if (prof == null) return;
                String id = JsonParser.parseString(prof).getAsJsonObject().get("id").getAsString();

                String sess = http("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
                if (sess == null) return;
                JsonArray props = JsonParser.parseString(sess).getAsJsonObject().get("properties").getAsJsonArray();
                String skinUrl = null;
                for (JsonElement e : props) {
                    JsonObject o = e.getAsJsonObject();
                    if ("textures".equals(o.get("name").getAsString())) {
                        String json = new String(Base64.getDecoder().decode(o.get("value").getAsString()), StandardCharsets.UTF_8);
                        JsonObject tex = JsonParser.parseString(json).getAsJsonObject().get("textures").getAsJsonObject();
                        skinUrl = tex.get("SKIN").getAsJsonObject().get("url").getAsString();
                    }
                }
                if (skinUrl == null) return;
                byte[] png = httpBytes(skinUrl.replace("http://", "https://"));
                int[] face = faceFrom(png);
                if (face != null) CACHE.put(key, face);
            } catch (Exception ignored) {
                // will simply try again later
            }
        }

        private static String http(String url) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "FabricPayPopup/1.0").GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200 ? res.body() : null;
        }

        private static byte[] httpBytes(String url) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "FabricPayPopup/1.0").GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return res.statusCode() == 200 ? res.body() : null;
        }

        // ---------- skin -> face ----------
        static int[] faceFrom(byte[] png) {
            try {
                if (png == null) return null;
                Img im = decode(png);
                if (im == null || im.w < 64 || im.h < 16) return null;
                int s = im.w / 64; // 1 for normal skins, 2 for 128x128 HD skins
                int[] face = new int[64];
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int by = (8 + y) * s + s / 2;
                        int base = im.px[by * im.w + (8 + x) * s + s / 2] | 0xFF000000;
                        int hat = im.px[by * im.w + (40 + x) * s + s / 2];
                        face[y * 8 + x] = ((hat >>> 24) >= 128) ? (hat | 0xFF000000) : base;
                    }
                }
                return face;
            } catch (Exception e) {
                return null;
            }
        }

        private static final class Img {
            int w, h;
            int[] px;
        }

        private static int be32(byte[] d, int p) {
            return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
        }

        /** Small PNG reader: 8-bit RGB, RGBA and palette images, not interlaced (that covers Minecraft skins). */
        private static Img decode(byte[] d) throws Exception {
            if (d.length < 33 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') return null;
            int pos = 8, w = 0, h = 0, depth = 0, ctype = 0, interlace = 0;
            byte[] pal = null, trns = null;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            while (pos + 8 <= d.length) {
                int len = be32(d, pos);
                String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
                int ds = pos + 8;
                if (len < 0 || ds + len > d.length) break;
                if (type.equals("IHDR")) {
                    w = be32(d, ds);
                    h = be32(d, ds + 4);
                    depth = d[ds + 8] & 0xFF;
                    ctype = d[ds + 9] & 0xFF;
                    interlace = d[ds + 12] & 0xFF;
                } else if (type.equals("PLTE")) {
                    pal = Arrays.copyOfRange(d, ds, ds + len);
                } else if (type.equals("tRNS")) {
                    trns = Arrays.copyOfRange(d, ds, ds + len);
                } else if (type.equals("IDAT")) {
                    idat.write(d, ds, len);
                } else if (type.equals("IEND")) {
                    break;
                }
                pos = ds + len + 4;
            }
            if (depth != 8 || interlace != 0 || w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            int ch;
            if (ctype == 2) ch = 3;
            else if (ctype == 6) ch = 4;
            else if (ctype == 3) ch = 1;
            else return null;
            if (ctype == 3 && pal == null) return null;

            int rowBytes = w * ch;
            byte[] raw = new byte[(rowBytes + 1) * h];
            Inflater inf = new Inflater();
            inf.setInput(idat.toByteArray());
            int off = 0;
            while (off < raw.length && !inf.finished()) {
                int n = inf.inflate(raw, off, raw.length - off);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                off += n;
            }
            inf.end();
            if (off < raw.length) return null;

            Img im = new Img();
            im.w = w;
            im.h = h;
            im.px = new int[w * h];
            int[] cur = new int[rowBytes];
            int[] prev = new int[rowBytes];
            for (int y = 0; y < h; y++) {
                int base = y * (rowBytes + 1);
                int ft = raw[base] & 0xFF;
                for (int i = 0; i < rowBytes; i++) {
                    int x = raw[base + 1 + i] & 0xFF;
                    int a = i >= ch ? cur[i - ch] : 0;
                    int b = prev[i];
                    int c = i >= ch ? prev[i - ch] : 0;
                    int v;
                    switch (ft) {
                        case 0: v = x; break;
                        case 1: v = x + a; break;
                        case 2: v = x + b; break;
                        case 3: v = x + ((a + b) >> 1); break;
                        case 4: {
                            int pp = a + b - c;
                            int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                            v = x + ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
                            break;
                        }
                        default: return null;
                    }
                    cur[i] = v & 0xFF;
                }
                for (int xx = 0; xx < w; xx++) {
                    int argb;
                    if (ctype == 6) {
                        int o = xx * 4;
                        argb = (cur[o + 3] << 24) | (cur[o] << 16) | (cur[o + 1] << 8) | cur[o + 2];
                    } else if (ctype == 2) {
                        int o = xx * 3;
                        argb = 0xFF000000 | (cur[o] << 16) | (cur[o + 1] << 8) | cur[o + 2];
                    } else {
                        int idx = cur[xx];
                        int al = (trns != null && idx < trns.length) ? (trns[idx] & 0xFF) : 255;
                        int o = idx * 3;
                        if (o + 2 >= pal.length) return null;
                        argb = (al << 24) | ((pal[o] & 0xFF) << 16) | ((pal[o + 1] & 0xFF) << 8) | (pal[o + 2] & 0xFF);
                    }
                    im.px[y * w + xx] = argb;
                }
                int[] tmp = prev;
                prev = cur;
                cur = tmp;
            }
            return im;
        }

        private static int[] buildDefault() {
            String[] art = {
                "HHHHHHHH",
                "HHHHHHHH",
                "HSSSSSSH",
                "SSSSSSSS",
                "SWESSEWS",
                "SSSNNSSS",
                "SSMMMMSS",
                "SSMMMMSS"
            };
            int[] face = new int[64];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c;
                    switch (art[y].charAt(x)) {
                        case 'H': c = 0xFF3C2814; break;
                        case 'W': c = 0xFFFFFFFF; break;
                        case 'E': c = 0xFF463282; break;
                        case 'N': c = 0xFF965F41; break;
                        case 'M': c = 0xFF6E3C2D; break;
                        default: c = 0xFFC68664; break;
                    }
                    face[y * 8 + x] = c;
                }
            }
            return face;
        }
    }
}
