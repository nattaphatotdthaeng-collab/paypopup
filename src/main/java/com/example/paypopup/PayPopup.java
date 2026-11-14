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
    // Popups below this amount are ignored (10m)
    private static final double MIN_VALUE = 10_000_000;
    // Where each tier starts: 0 silver, 1 gold, 2 diamond, 3 rainbow, 4 nuke, 5 black hole
    private static final double[] TIER_MIN = {10e6, 25e6, 50.1e6, 101e6, 252e6, 502e6};

    private static final int MAX_VISIBLE = 5;   // popups on screen at once (extra ones wait in a queue)
    private static final int MARGIN = 10;       // gap from the right edge of the screen
    private static final int TOP = 12;          // gap from the top of the screen
    private static final int GAP = 10;          // gap between stacked popups
    private static final int DIGIT_SPACING = 2; // extra pixels between the characters of the amount

    private static final long SLIDE_IN = 600;
    private static final long FADE_OUT = 700;
    private static final long NUKE_IMPACT = 2500;   // when the missile hits
    private static final long BH_COLLAPSE = 3900;   // when the black hole starts to collapse
    private static final long BH_BLAST = 4700;      // when the black hole explodes
    private static final long[] PANEL_DELAY = {0, 0, 0, 0, 2700, BH_BLAST};
    private static final long[] DURATIONS = {4500, 4900, 5500, 6100, 8200, 10000};
    private static final int[] COIN_COUNTS = {0, 14, 20, 28, 0, 0};
    private static final String[] HEADERS = {"SILVER", "GOLD", "DIAMOND", "RAINBOW", "NUKE", "SINGULARITY"};
    private static final int[][] BODY = {
        {20, 22, 28}, {26, 21, 13}, {10, 24, 32}, {22, 17, 30}, {38, 13, 9}, {18, 9, 32}
    };
    private static final String[] DIGITS = {
        "01110,10001,10011,10101,11001,10001,01110", "00100,01100,00100,00100,00100,00100,01110",
        "01110,10001,00001,00010,00100,01000,11111", "11110,00001,00001,01110,00001,00001,11110",
        "00010,00110,01010,10010,11111,00010,00010", "11111,10000,11110,00001,00001,10001,01110",
        "00110,01000,10000,11110,10001,10001,01110", "11111,00001,00010,00100,01000,01000,01000",
        "01110,10001,10001,01110,10001,10001,01110", "01110,10001,10001,01111,00001,00010,01100"
    };

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
        final float[][] coins = new float[60][5];    // angle, speed, size, spin, phase
        final float[][] confetti = new float[70][5]; // xFrac, delay, fallSpeed, size, hue
        final float[][] sparks = new float[8][3];    // xFrac, yFrac, phase
        final float[][] disk = new float[460][5];    // angle, radius (x hole size), spin, size, jitter
        final float[][] stars = new float[70][4];    // angle, radius, size, phase
    }

    private static final List<Popup> active = new ArrayList<>();
    private static final List<Popup> pending = new ArrayList<>();
    private static long lastRender = 0;
    private static String lastKey = "";
    private static long lastKeyTime = 0;

    private PayPopup() {}

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

        long now = System.currentTimeMillis();
        String key = player + "|" + amt.trim();
        if (key.equals(lastKey) && now - lastKeyTime < 300) return; // ignore accidental duplicates
        lastKey = key;
        lastKeyTime = now;

        Popup p = new Popup();
        p.from = player;
        p.orig = amt.trim();
        p.suffix = suf;
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

        // Sound schedule: {time ms, type (0 = orb ping, 1 = level up), pitch}
        List<float[]> ev = new ArrayList<>();
        switch (p.tier) {
            case 0:
                ev.add(new float[]{0, 0, 1.0f});
                break;
            case 1:
                ev.add(new float[]{0, 0, 1.2f});
                break;
            case 2:
                ev.add(new float[]{0, 0, 1.5f});
                ev.add(new float[]{0, 1, 1.4f});
                break;
            case 3:
                ev.add(new float[]{0, 1, 1.0f});
                ev.add(new float[]{120, 0, 1.3f});
                ev.add(new float[]{240, 0, 1.6f});
                break;
            case 4:
                for (int ms = 0; ms < 2400; ms += 400) ev.add(new float[]{ms, 0, (ms / 400) % 2 == 0 ? 0.7f : 1.0f});
                for (int ms = 1500; ms < 2450; ms += 55) ev.add(new float[]{ms, 0, 2.0f - (ms - 1500) / 950f * 1.5f});
                ev.add(new float[]{NUKE_IMPACT, 1, 0.5f});
                ev.add(new float[]{NUKE_IMPACT, 0, 0.5f});
                ev.add(new float[]{NUKE_IMPACT + 70, 1, 0.5f});
                ev.add(new float[]{NUKE_IMPACT + 500, 0, 0.5f});
                ev.add(new float[]{NUKE_IMPACT + 1300, 1, 0.5f});
                break;
            default:
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
        // little ticking sounds while the number counts up
        if (p.tier >= 1) {
            long base = PANEL_DELAY[p.tier] + 250;
            for (int i = 0; i < 7; i++) ev.add(new float[]{base + i * 95, 0, 1.0f + i * 0.12f});
        }
        ev.sort((x, y) -> Float.compare(x[0], y[0]));
        p.events = ev.toArray(new float[0][]);

        if (active.size() < MAX_VISIBLE) {
            p.start = now;
            active.add(p);
        } else if (pending.size() < 20) {
            pending.add(p);
        }
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
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastRender) / 1000f));
        lastRender = now;
        if (active.isEmpty() && pending.isEmpty()) return;

        // retire finished popups, promote queued ones
        Iterator<Popup> it = active.iterator();
        while (it.hasNext()) {
            Popup p = it.next();
            if (now - p.start > p.duration) it.remove();
        }
        while (active.size() < MAX_VISIBLE && !pending.isEmpty()) {
            Popup p = pending.remove(0);
            p.start = now;
            active.add(p);
        }
        if (active.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // sounds + sizes
        for (Popup p : active) {
            long t = now - p.start;
            while (p.nextEvent < p.events.length && p.events[p.nextEvent][0] <= t) {
                play(p.events[p.nextEvent][1], p.events[p.nextEvent][2]);
                p.nextEvent++;
            }
            layout(font, p);
        }

        // stack the visible panels down the right side (a leaving popup keeps its slot while it slides out)
        float cursor = TOP;
        float k = 1f - (float) Math.exp(-dt * 10f);
        for (Popup p : active) {
            long tp = now - p.start - PANEL_DELAY[p.tier];
            if (tp < 0) continue;
            if (!p.placed) { p.slotY = cursor; p.placed = true; }
            else p.slotY += (cursor - p.slotY) * k;
            cursor += p.h + GAP;
        }

        // screen-wide dim + flash, shared so several popups don't stack them
        float dimA = 0f, flashA = 0f;
        int fr = 255, fgc = 255, fb = 255;
        for (Popup p : active) {
            long t = now - p.start;
            long tp = t - PANEL_DELAY[p.tier];
            if (p.tier == 5) {
                float d = Math.min(1f, t / 700f);
                if (t > BH_BLAST) d = Math.max(0f, 1f - (t - BH_BLAST) / 700f);
                dimA = Math.max(dimA, 195f * d);
                if (t >= BH_BLAST) {
                    float fp = (t - BH_BLAST) / 650f;
                    if (fp < 1f && 255f * (1f - fp) > flashA) { flashA = 255f * (1f - fp); fr = 255; fgc = 255; fb = 255; }
                }
            } else if (p.tier == 4) {
                if (t >= NUKE_IMPACT) {
                    float fp = (t - NUKE_IMPACT) / 900f;
                    if (fp < 1f && 255f * (1f - fp) > flashA) { flashA = 255f * (1f - fp); fr = 255; fgc = 246; fb = 224; }
                }
            } else if (tp < 320) {
                float[] st = {0.06f, 0.12f, 0.18f, 0.26f};
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
            if (p.tier == 4) nuke(g, font, p, t, sw, sh);
            if (p.tier == 5) blackHole(g, font, p, t, sw, sh);
            if (tp >= 0 && p.tier >= 3) {
                float pulse = 0.75f + 0.25f * (float) Math.sin(tp / 110.0);
                edgeGlow(g, sw, sh, tp, a, accent(p.tier, tp, pulse, a), 0.22f);
            }
            if (tp >= 0 && p.tier == 3) confetti(g, p, tp, sw, sh, a);
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
        String title = "+$" + p.orig;
        int textW = Math.max(spacedWidth(font, title) + 2,
                Math.max(font.width("from " + p.from), font.width(HEADERS[p.tier])));
        p.h = 46;
        p.w = 10 + 24 + 10 + textW + 14;
    }

    // ===== PANEL =====
    private static void panel(GuiGraphicsExtractor g, Font font, Popup p, long tp, int sw, int a) {
        int tier = p.tier;
        int w = p.w, h = p.h;

        // slide in from the right (with a little overshoot), slide back out while fading
        float sp = Math.min(1f, tp / (float) SLIDE_IN);
        int xOff = (int) ((1f - backOut(sp)) * (w + 40));
        xOff += (int) ((1f - a / 255f) * (w * 0.7f));
        int x = sw - MARGIN - w + xOff;
        int y = (int) p.slotY;

        float pulse = 0.75f + 0.25f * (float) Math.sin(tp / 130.0);
        int accent = accent(tier, tp, pulse, a);
        int[] bc = BODY[tier];

        // soft glow (gold and above)
        if (tier >= 1) {
            for (int i = 3; i >= 1; i--) {
                int ga = (int) (a * 0.06f * (4 - i) * pulse);
                if (ga > 2) rrect(g, x - 1 - i * 2, y - 1 - i * 2, x + w + 1 + i * 2, y + h + 1 + i * 2, withAlpha(accent, ga));
            }
        }

        // thin colored border + dark body
        rrect(g, x - 1, y - 1, x + w + 1, y + h + 1, accent);
        rrect(g, x, y, x + w, y + h, argb(a, bc[0], bc[1], bc[2]));
        g.fill(x + 2, y + 1, x + w - 2, y + h / 2, argb((int) (a * 0.05f), 255, 255, 255));

        // nuke: hazard stripes along the top
        if (tier == 4) {
            for (int sx = x + 3; sx < x + w - 3; sx += 8) {
                g.fill(sx, y + 2, Math.min(sx + 4, x + w - 3), y + 4, argb((int) (a * 0.9f), 255, 200, 30));
            }
        }

        // occasional shine sweep
        if ((tier == 1 || tier == 2 || tier == 3 || tier == 5) && tp > 700) {
            float sps = ((tp - 700) % 2600) / 900f;
            if (sps < 1f) {
                float sx = x - 20 + sps * (w + 40);
                for (int r = 2; r < h - 2; r += 2) {
                    int off = (h - r) / 3;
                    int x0 = Math.max(x + 2, (int) sx + off);
                    int x1 = Math.min(x + w - 2, (int) sx + off + 10);
                    if (x1 > x0) g.fill(x0, y + r, x1, y + r + 2, argb((int) (a * 0.13f), 255, 255, 255));
                }
            }
        }

        // diamond sparkles
        if (tier == 2 && tp > 300) {
            for (int i = 0; i < 5; i++) {
                float[] c = p.sparks[i];
                float tw = (float) Math.sin(tp / 190.0 + c[2]);
                if (tw > 0.35f) {
                    int px = x - 6 + (int) (c[0] * (w + 12));
                    int py = y - 6 + (int) (c[1] * (h + 12));
                    int s = 1 + (int) (tw * 3);
                    int sa = (int) (a * tw);
                    g.fill(px - s, py, px + s + 1, py + 1, argb(sa, 235, 250, 255));
                    g.fill(px, py - s, px + 1, py + s + 1, argb(sa, 235, 250, 255));
                }
            }
        }

        // lights running round the border (rainbow, black hole)
        if (tier == 3 || tier == 5) chase(g, x, y, w, h, tp, a);

        // player face (real skin once it has loaded)
        int fx = x + 10, fy = y + (h - 24) / 2;
        g.fill(fx - 1, fy - 1, fx + 25, fy + 25, accent);
        drawFace(g, SkinFaces.DEFAULT_FACE, fx, fy, 3, a);
        int[] real = SkinFaces.get(p.from);
        if (real != null) {
            if (p.faceT0 < 0) p.faceT0 = tp;
            float fa = Math.min(1f, (tp - p.faceT0) / 300f);
            drawFace(g, real, fx, fy, 3, (int) (a * fa));
        }
        if (tier == 5) { // a tiny orbiting spark round the face
            double ang = tp / 220.0;
            int ox = fx + 12 + (int) (Math.cos(ang) * 19);
            int oy = fy + 12 + (int) (Math.sin(ang) * 15);
            g.fill(ox, oy, ox + 2, oy + 2, argb(a, 255, 210, 255));
        }

        // text
        int tx = x + 44;
        long countStart = 250;
        long countLen = 650;
        float cp = Math.min(1f, Math.max(0f, (tp - countStart) / (float) countLen));
        cp = 1f - (1f - cp) * (1f - cp) * (1f - cp);
        long hopStart = countStart + countLen;
        int bounce = (tp > hopStart && tp < hopStart + 250)
                ? -(int) (2 * Math.sin(Math.PI * (tp - hopStart) / 250.0)) : 0;
        String title = "+$" + shown(p, cp);

        g.text(font, HEADERS[tier], tx, y + 7, accent);
        spacedText(g, font, title, tx + 1, y + 20 + bounce, argb(a, 8, 8, 10), false);   // shadow
        spacedText(g, font, title, tx, y + 19 + bounce, amountColor(tier, tp, a), true);
        g.text(font, "from ", tx, y + 33, argb(a, 140, 146, 162));
        g.text(font, p.from, tx + font.width("from "), y + 33, argb(a, 236, 239, 246));

        // thin timer bar
        long span = p.duration - PANEL_DELAY[tier] - FADE_OUT - SLIDE_IN;
        float rem = 1f - Math.min(1f, Math.max(0f, (tp - SLIDE_IN) / (float) Math.max(1, span)));
        g.fill(x + 3, y + h - 3, x + w - 3, y + h - 2, argb((int) (a * 0.12f), 255, 255, 255));
        g.fill(x + 3, y + h - 3, x + 3 + (int) ((w - 6) * rem), y + h - 2, withAlpha(accent, (int) (a * 0.75f)));

        // small coin spray (gold, diamond, rainbow)
        if (p.coinCount > 0 && tp > 150) {
            float tt = (tp - 150) / 1000f;
            int ox = fx + 12;
            int oy = fy + 12;
            for (int i = 0; i < p.coinCount; i++) {
                float[] c = p.coins[i];
                float vx = ((float) Math.cos(c[4]) - 0.45f) * c[1] * 1.3f;
                float vy = (float) Math.sin(c[4]) * c[1] * 0.9f;
                float px = ox + vx * tt;
                float py = oy + vy * tt + 260f * tt * tt;
                float flip = Math.abs((float) Math.cos(c[4] + c[3] * tt));
                int s = Math.max(2, (int) (c[2] * 0.8f));
                int fw = Math.max(1, (int) (s * flip));
                int ca = (int) (a * Math.max(0f, 1f - tt / 2.2f));
                if (ca > 6) g.fill((int) px, (int) py, (int) px + fw, (int) py + s, coinColor(tier, flip > 0.5f, i, ca));
            }
        }
    }

    private static void drawFace(GuiGraphicsExtractor g, int[] face, int fx, int fy, int scale, int a) {
        if (a < 4) return;
        for (int j = 0; j < 8; j++) {
            for (int i = 0; i < 8; i++) {
                int c = face[j * 8 + i];
                g.fill(fx + i * scale, fy + j * scale, fx + (i + 1) * scale, fy + (j + 1) * scale, withAlpha(c, a));
            }
        }
    }

    // ===== SPACED TEXT =====
    private static int spacedWidth(Font font, String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += font.width(String.valueOf(s.charAt(i))) + DIGIT_SPACING;
        return Math.max(0, w - DIGIT_SPACING);
    }

    private static void spacedText(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, boolean bold) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            g.text(font, ch, cx, y, color);
            if (bold && !Character.isLetter(s.charAt(i))) g.text(font, ch, cx + 1, y, color);
            cx += font.width(ch) + DIGIT_SPACING;
        }
    }

    // ===== BORDER LIGHTS / EDGE GLOW =====
    private static void chase(GuiGraphicsExtractor g, int x, int y, int w, int h, long tp, int a) {
        int x0 = x - 1, y0 = y - 1, pw = w + 2, ph = h + 2;
        int len = 2 * (pw + ph);
        for (int s = 0; s < 2; s++) {
            float head = (tp * 0.2f + s * len / 2f) % len;
            for (int k = 0; k < 14; k++) {
                float d = head - k * 2f;
                if (d < 0) d += len;
                int px, py;
                if (d < pw) { px = x0 + (int) d; py = y0; }
                else if (d < pw + ph) { px = x0 + pw; py = y0 + (int) (d - pw); }
                else if (d < 2 * pw + ph) { px = x0 + pw - (int) (d - pw - ph); py = y0 + ph; }
                else { px = x0; py = y0 + ph - (int) (d - 2 * pw - ph); }
                int ca = (int) (a * (1f - k / 14f) * 0.85f);
                if (ca > 4) g.fill(px - 1, py - 1, px + 2, py + 2, argb(ca, 255, 255, 255));
            }
        }
    }

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

    // ===== NUKE =====
    private static void nuke(GuiGraphicsExtractor g, Font font, Popup p, long t, int sw, int sh) {
        float k = sh / 270f;
        int cx = (int) (sw * 0.42f + p.bhOffset * 0.3f);
        int gy = (int) (sh * 0.74f);
        float endFade = Math.min(1f, Math.max(0f, (p.duration - t) / 1500f));

        if (t < NUKE_IMPACT) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(t / 110.0);
            edgeGlow(g, sw, sh, t, 255, argb(255, 255, 40, 30), 0.40f + 0.50f * pulse);

            // warning banner
            hazardBar(g, 0, sw, 8, 3, t / 50, 255);
            g.fill(0, 11, sw, 31, argb(150, 70, 0, 0));
            hazardBar(g, 0, sw, 31, 3, t / 50, 255);
            if ((t % 600) < 420) {
                String msg = "!! NUCLEAR LAUNCH DETECTED !!";
                int mx = sw / 2 - font.width(msg) / 2;
                g.text(font, msg, mx, 17, argb(255, 255, 90, 70));
                g.text(font, msg, mx + 1, 17, argb(255, 255, 90, 70));
            }

            // countdown 3 - 2 - 1
            int digit = 3 - (int) (t / 800);
            if (digit >= 1) {
                float ph = (t % 800) / 800f;
                float pop = 1.35f - 0.35f * Math.min(1f, ph * 4f);
                int s = Math.max(3, Math.round(5 * k * pop));
                int da = (int) (255 * (ph > 0.85f ? (1f - ph) / 0.15f : 1f));
                bigDigit(g, digit, cx - (5 * s) / 2, gy - (int) (140 * k), s, da);
            }

            // target reticle on the ground
            float prog = t / (float) NUKE_IMPACT;
            int rx = (int) ((55 - 35 * prog) * k);
            int ry = Math.max(2, (int) (rx * 0.28f));
            int ra = (int) (200 * (0.5f + 0.5f * pulse));
            ellipseRing(g, cx, gy, rx, ry, argb(ra, 255, 60, 50), 2);
            ellipseRing(g, cx, gy, rx / 2, Math.max(1, ry / 2), argb(ra, 255, 60, 50), 2);
            g.fill(cx - rx - 10, gy, cx + rx + 10, gy + 1, argb(ra, 255, 60, 50));
            g.fill(cx, gy - ry - 6, cx + 1, gy + ry + 6, argb(ra, 255, 60, 50));

            // falling missile
            if (t >= 1500) {
                float m = (t - 1500) / 1000f;
                for (int j = 28; j >= 0; j--) {
                    float mm = m - j * 0.012f;
                    if (mm < 0f) continue;
                    int px = cx + (int) ((1f - mm) * 90 * k);
                    int py = (int) (-10 + (gy + 10) * mm * mm);
                    float f = j / 28f;
                    int sz = Math.max(1, 3 - j / 10);
                    g.fill(px - sz, py - sz, px + sz, py + sz,
                            argb((int) (230 * (1f - f)), 255, (int) (235 - 150 * f), (int) (130 - 110 * f)));
                }
                int hx = cx + (int) ((1f - m) * 90 * k);
                int hy = (int) (-10 + (gy + 10) * m * m);
                g.fill(hx - 3, hy - 9, hx + 3, hy + 4, argb(255, 205, 205, 215));
                g.fill(hx - 2, hy + 4, hx + 2, hy + 8, argb(255, 255, 70, 50));
                g.fill(hx - 5, hy - 1, hx - 3, hy + 4, argb(255, 160, 160, 175));
                g.fill(hx + 3, hy - 1, hx + 5, hy + 4, argb(255, 160, 160, 175));
            }
            return;
        }

        long u = t - NUKE_IMPACT;
        float su = u / 1000f;

        // warm sky tint
        float tint = Math.max(0f, 1f - u / 3600f) * endFade;
        if (tint > 0.01f) g.fill(0, 0, sw, sh, argb((int) (75 * tint), 255, 120, 30));

        // shockwave rings along the ground
        for (int i = 0; i < 3; i++) {
            float st = (su - i * 0.18f) / 1.9f;
            if (st < 0f || st > 1f) continue;
            float e = 1f - (1f - st) * (1f - st);
            int rr = (int) (e * sw * 0.65f);
            int al = (int) (220 * (1f - st) * endFade);
            if (al > 5) ellipseRing(g, cx, gy, rr, Math.max(2, (int) (rr * 0.16f)),
                    argb(al, 255, i == 0 ? 255 : 190, i == 0 ? 220 : 120), 3);
        }
        float st2 = su / 0.9f;
        if (st2 < 1f) {
            float e2 = 1f - (1f - st2) * (1f - st2);
            ringPts(g, cx, gy - (int) (20 * k), e2 * sw * 0.45f, argb((int) (230 * (1f - st2)), 255, 250, 235), 3);
        }

        // flying debris
        if (su < 2.2f) {
            for (int i = 0; i < 50; i++) {
                float[] c = p.coins[i];
                float dx = (float) Math.cos(c[4]) * c[1] * 1.5f * su * k;
                float dy = (-Math.abs((float) Math.sin(c[4])) * c[1] * 2.0f * su + 230f * su * su) * k;
                int pa = (int) (255 * (1f - su / 2.2f) * endFade);
                int s = Math.max(1, (int) (c[2] * 0.7f));
                if (pa > 6) g.fill(cx + (int) dx, gy + (int) dy, cx + (int) dx + s, gy + (int) dy + s,
                        argb(pa, 255, i % 3 == 0 ? 220 : 130, i % 3 == 0 ? 90 : 30));
            }
        }

        // the mushroom cloud
        float e = easeOut(Math.min(1f, su / 3.4f));
        float stemH = 105f * k * e;
        float stemW = (9f + 8f * e) * k;
        float capR = (7f + 45f * e) * k;
        float heat0 = Math.max(0f, 1f - su / 3.2f);
        int ca = (int) (235 * endFade);

        for (int dy = 0; dy < (int) stemH; dy += 2) {
            float frac = dy / Math.max(1f, stemH);
            float wob = (float) Math.sin(t / 180.0 + dy * 0.12) * 1.5f * k;
            int wdt = (int) (stemW * (1.25f - 0.55f * frac) + wob);
            float heat = Math.max(0f, heat0 * (1f - frac * 0.9f));
            g.fill(cx - wdt, gy - dy - 2, cx + wdt, gy - dy, withAlpha(mixRgb(0x50463F, 0xFF8C28, heat), ca));
        }

        float fr = (10f + 32f * easeOut(Math.min(1f, su / 1.0f))) * k * Math.max(0.25f, 1f - Math.max(0f, su - 1.8f) / 4f);
        int fy = gy - (int) (fr * 0.55f);
        disc(g, cx, fy, (int) fr + 1, withAlpha(0x7A2410, (int) (ca * 0.9f)));
        disc(g, cx, fy, (int) fr, withAlpha(0xFF5A14, ca));
        disc(g, cx, fy, (int) (fr * 0.72f), withAlpha(0xFFA528, ca));
        disc(g, cx, fy, (int) (fr * 0.42f), withAlpha(mixRgb(0xFFF2B0, 0xFFC050, 1f - heat0), ca));

        if (su > 0.35f) {
            int capY = gy - (int) stemH - (int) (capR * 0.25f);
            int n = 9;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    double ang = i * 2 * Math.PI / n;
                    float rr = capR * 0.38f * (1f + 0.12f * (float) Math.sin(t / 240.0 + i * 1.7));
                    int px = cx + (int) (Math.cos(ang) * capR * 1.0f);
                    int py = capY + (int) (Math.sin(ang) * capR * 0.34f);
                    float low = (float) (0.5 + 0.5 * Math.sin(ang));
                    float heat = Math.min(1f, heat0 * 0.8f + low * 0.55f * Math.max(0f, 1f - su / 6f));
                    int col = pass == 0 ? 0x2A221E
                            : mixRgb(mixRgb(0x7D7064, 0x50463F, 1f - low), 0xFF8C28, Math.max(0f, heat));
                    disc(g, px, py, (int) rr + (pass == 0 ? 1 : 0), withAlpha(col, ca));
                }
                int domeCol = pass == 0 ? 0x2A221E : 0x7D7064;
                disc(g, cx, capY - (int) (capR * 0.15f), (int) (capR * 0.5f) + (pass == 0 ? 1 : 0), withAlpha(domeCol, ca));
            }
            disc(g, cx, capY + (int) (capR * 0.28f), (int) (capR * 0.5f),
                    withAlpha(0xFF7A1E, (int) (ca * 0.55f * Math.max(0.15f, heat0))));
        }
    }

    // ===== BLACK HOLE =====
    private static void blackHole(GuiGraphicsExtractor g, Font font, Popup p, long t, int sw, int sh) {
        if (t > BH_BLAST + 1800) return;
        float k = sh / 270f;
        int cx = (int) (sw * 0.40f + p.bhOffset * 0.5f);
        int cy = (int) (sh * 0.44f);

        if (t < BH_BLAST) {
            float vis = Math.min(1f, t / 700f);
            float grow = Math.min(1f, t / 1000f);
            float collapse = t < BH_COLLAPSE ? 1f : Math.max(0f, 1f - (t - BH_COLLAPSE) / (float) (BH_BLAST - BH_COLLAPSE));
            float rush = 1f - collapse;
            float R = 22f * k * grow * collapse;
            float baseR = 22f * k * grow;

            starfield(g, p, cx, cy, t, k, vis, rush);

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

    private static void ellipseRing(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color, int size) {
        int n = Math.max(16, (int) (Math.PI * (rx + ry) / (size * 1.2f)));
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            int px = cx + (int) (Math.cos(ang) * rx);
            int py = cy + (int) (Math.sin(ang) * ry);
            g.fill(px, py, px + size, py + size, color);
        }
    }

    private static void hazardBar(GuiGraphicsExtractor g, int x0, int x1, int y, int h, long shift, int a) {
        int off = (int) (shift % 16);
        for (int x = x0 - 16 + off; x < x1; x += 16) {
            g.fill(Math.max(x0, x), y, Math.min(x1, x + 8), y + h, argb(a, 255, 200, 30));
            g.fill(Math.max(x0, x + 8), y, Math.min(x1, x + 16), y + h, argb(a, 25, 20, 10));
        }
    }

    private static void bigDigit(GuiGraphicsExtractor g, int digit, int x, int y, int s, int a) {
        if (a < 6) return;
        String[] rows = DIGITS[digit].split(",");
        for (int pass = 0; pass < 2; pass++) {
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < 5; c++) {
                    if (rows[r].charAt(c) != '1') continue;
                    if (pass == 0) g.fill(x + c * s - 1, y + r * s - 1, x + (c + 1) * s + 1, y + (r + 1) * s + 1, argb((int) (a * 0.35f), 255, 40, 30));
                    else g.fill(x + c * s, y + r * s, x + (c + 1) * s, y + (r + 1) * s, argb(a, 255, 235, 225));
                }
            }
        }
    }

    private static void rrect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int c) {
        g.fill(x1 + 2, y1, x2 - 2, y1 + 1, c);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, c);
        g.fill(x1, y1 + 2, x2, y2 - 2, c);
        g.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, c);
        g.fill(x1 + 2, y2 - 1, x2 - 2, y2, c);
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

    // ===== COLOR / MATH HELPERS =====
    private static int coinColor(int tier, boolean bright, int idx, int ca) {
        switch (tier) {
            case 0: return bright ? argb(ca, 215, 222, 235) : argb(ca, 130, 138, 150);
            case 1: return bright ? argb(ca, 255, 215, 60) : argb(ca, 200, 140, 20);
            case 2: return bright ? argb(ca, 170, 245, 255) : argb(ca, 60, 170, 225);
            case 3: return hsv(idx / 20f, ca);
            case 4: return bright ? argb(ca, 255, 190, 60) : argb(ca, 215, 60, 40);
            default: return bright ? argb(ca, 215, 170, 255) : argb(ca, 110, 60, 200);
        }
    }

    private static int accent(int tier, long t, float pulse, int a) {
        switch (tier) {
            case 0: return argb(a, (int) (200 * pulse), (int) (207 * pulse), (int) (220 * pulse));
            case 1: return argb(a, (int) (255 * pulse), (int) (200 * pulse), (int) (40 * pulse));
            case 2: {
                float s = 0.5f + 0.5f * (float) Math.sin(t / 260.0);
                return argb(a, (int) (110 + 110 * s), (int) (225 + 30 * s), 255);
            }
            case 3: return hsv(t / 900f, a);
            case 4: return argb(a, 255, (int) (70 + 60 * pulse), 25);
            default: return argb(a, (int) (165 * pulse), (int) (20 + 80 * pulse), (int) (255 * pulse));
        }
    }

    private static int amountColor(int tier, long t, int a) {
        switch (tier) {
            case 0: return argb(a, 235, 240, 250);
            case 1: return argb(a, 255, 225, 90);
            case 2: return argb(a, 170, 245, 255);
            case 3: return hsv(t / 700f + 0.3f, a);
            case 4: return argb(a, 255, 175, 70);
            default: return argb(a, 225, 175, 255);
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
