package com.example.paypopup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
    // Where each tier starts: 0 silver, 1 gold, 2 diamond, 3 rainbow, 4 jackpot, 5 black hole
    private static final double[] TIER_MIN = {10e6, 25e6, 50.1e6, 101e6, 252e6, 502e6};

    private static final long SLIDE_IN = 550;
    private static final long FADE_OUT = 700;
    private static final long BH_BLAST = 3700; // when the black hole explodes
    private static final long[] DURATIONS = {4200, 4600, 5200, 5800, 8000, 8600};
    private static final int[] COIN_COUNTS = {20, 32, 40, 50, 60, 50};
    private static final String[] HEADERS = {
        "PAYMENT RECEIVED", "GOLD PAYMENT", "DIAMOND PAYMENT", "MEGA PAYMENT", "JACKPOT!!!", "SINGULARITY"
    };
    private static final String[] SYMBOLS = {"7", "$", "#", "*", "@", "%", "&"};
    private static final int[][] BODY = {
        {20, 22, 28}, {26, 21, 12}, {10, 24, 32}, {20, 16, 28}, {34, 10, 12}, {18, 8, 30}
    };

    // ===== STATE =====
    private static long duration = 4200;
    private static long start = -1;
    private static String from = "";
    private static String orig = "";
    private static String suffix = "";
    private static double num;
    private static int decimals;
    private static boolean comma;
    private static int tier;
    private static int coinCount;
    private static float[][] events = new float[0][];
    private static int nextEvent = 0;

    private static final float[][] coins = new float[60][5];    // angle, speed, size, spin, phase
    private static final float[][] confetti = new float[70][5]; // xFrac, delay, fallSpeed, size, hue
    private static final float[][] sparks = new float[18][3];   // xFrac, yFrac, phase
    private static final float[][] disk = new float[110][4];    // angle, radius, spin, size

    private PayPopup() {}

    // ===== TRIGGER =====
    public static void trigger(String player, String amt) {
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

        from = player;
        orig = amt.trim();
        suffix = suf;
        num = n;
        comma = hasComma;
        int dot = clean.indexOf('.');
        decimals = dot < 0 ? 0 : clean.length() - dot - 1;

        tier = 0;
        for (int i = 0; i < TIER_MIN.length; i++) {
            if (value >= TIER_MIN[i]) tier = i;
        }
        coinCount = COIN_COUNTS[tier];
        duration = DURATIONS[tier];

        Random r = new Random();
        for (float[] c : coins) {
            c[0] = (float) (-Math.PI + r.nextDouble() * Math.PI);
            c[1] = 70f + r.nextFloat() * 130f;
            c[2] = 3f + r.nextFloat() * 3f;
            c[3] = 6f + r.nextFloat() * 10f;
            c[4] = r.nextFloat() * 6.28f;
        }
        for (float[] c : confetti) {
            c[0] = r.nextFloat();
            c[1] = r.nextFloat() * 600f;
            c[2] = 90f + r.nextFloat() * 130f;
            c[3] = 3f + r.nextFloat() * 3f;
            c[4] = r.nextFloat();
        }
        for (float[] c : sparks) {
            c[0] = r.nextFloat();
            c[1] = r.nextFloat();
            c[2] = r.nextFloat() * 6.28f;
        }
        for (float[] c : disk) {
            c[0] = r.nextFloat() * 6.28f;
            c[1] = 45f + r.nextFloat() * 90f;
            c[2] = 300f / c[1];
            c[3] = 2f + r.nextFloat() * 2f;
        }

        // Sound schedule: {time ms, type (0 = orb ping, 1 = level up), pitch}
        List<float[]> ev = new ArrayList<>();
        switch (tier) {
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
                for (int ms = 100; ms < 2600; ms += 130) ev.add(new float[]{ms, 0, 1.9f});
                ev.add(new float[]{900, 0, 1.0f});
                ev.add(new float[]{1500, 0, 1.25f});
                ev.add(new float[]{2100, 0, 1.5f});
                ev.add(new float[]{2700, 1, 1.0f});
                for (int i = 0; i < 6; i++) ev.add(new float[]{2750 + i * 110, 0, 1.2f + i * 0.15f});
                break;
            default:
                ev.add(new float[]{0, 1, 0.5f});
                ev.add(new float[]{1400, 1, 0.6f});
                ev.add(new float[]{2800, 1, 0.7f});
                for (int i = 0; i < 7; i++) ev.add(new float[]{3000 + i * 100, 0, 0.8f + i * 0.2f});
                ev.add(new float[]{BH_BLAST, 1, 0.5f});
                ev.add(new float[]{BH_BLAST, 0, 0.5f});
                ev.add(new float[]{BH_BLAST + 200, 1, 1.0f});
                ev.add(new float[]{BH_BLAST + 300, 0, 1.4f});
                break;
        }
        ev.sort((p, q) -> Float.compare(p[0], q[0]));
        events = ev.toArray(new float[0][]);
        nextEvent = 0;

        start = System.currentTimeMillis();
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
    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (start < 0) return;
        long t = System.currentTimeMillis() - start;
        if (t > duration) { start = -1; return; }

        while (nextEvent < events.length && events[nextEvent][0] <= t) {
            play(events[nextEvent][1], events[nextEvent][2]);
            nextEvent++;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        float alpha = 1f;
        long fadeStart = duration - FADE_OUT;
        if (t > fadeStart) alpha = 1f - (t - fadeStart) / (float) FADE_OUT;
        int a = Math.max(4, (int) (alpha * 255));

        if (tier == 5) blackHole(g, font, t, sw, sh);

        long tp = t - (tier == 5 ? BH_BLAST : 0);
        if (tp < 0) return;

        // Screen flashes
        if (tier < 4 && tp < 320) {
            float[] st = {0.08f, 0.15f, 0.22f, 0.30f};
            int fa = (int) (st[tier] * 255 * (1f - tp / 320f));
            if (fa > 2) g.fill(0, 0, sw, sh, argb(fa, 240, 240, 255));
        }
        if (tier == 4 && tp >= 2700 && tp < 3200) {
            int fa = (int) (0.4f * 255 * (1f - (tp - 2700) / 500f));
            if (fa > 2) g.fill(0, 0, sw, sh, argb(fa, 255, 225, 120));
        }

        panel(g, font, tp, sw, sh, a);
    }

    // ===== PANEL =====
    private static void panel(GuiGraphicsExtractor g, Font font, long tp, int sw, int sh, int a) {
        boolean jack = tier == 4;
        String finalTitle = "+$" + orig;
        String sub = "from " + from;
        String header = HEADERS[tier];
        int textW = Math.max(font.width(finalTitle) + 1, Math.max(font.width(sub), font.width(header)));
        int h = jack ? 56 : 46;
        int w = jack ? 10 + 68 + 10 + textW + 14 : textW + 62;
        int x = (sw - w) / 2;
        int restY = jack ? 22 : 16;

        float p = Math.min(1f, tp / (float) SLIDE_IN);
        int y = (int) (-h - 10 + (restY + h + 10) * backOut(p));

        float pulse = 0.75f + 0.25f * (float) Math.sin(tp / 110.0);
        int accent = accent(tp, pulse, a);

        // Shockwave rings
        if (tier >= 1) {
            for (int k = 0; k < 3; k++) {
                float rt = (tp - 150 - k * 240) / 950f;
                if (rt < 0f || rt > 1f) continue;
                float e = 1f - (1f - rt) * (1f - rt) * (1f - rt);
                int grow = (int) (6 + e * 80);
                int ra = (int) (a * (1f - rt) * 0.75f);
                if (ra > 4) {
                    outline(g, x - grow, restY - (int) (grow * 0.6f),
                            x + w + grow, restY + h + (int) (grow * 0.6f), 2, withAlpha(accent, ra));
                }
            }
        }

        // Glow
        for (int i = 6; i >= 1; i--) {
            int ga = (int) (a * 0.045f * (7 - i) * pulse);
            if (ga > 2) g.fill(x - 2 - i * 2, y - 2 - i * 2, x + w + 2 + i * 2, y + h + 2 + i * 2, withAlpha(accent, ga));
        }

        // Border + gradient body
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, accent);
        int[] bc = BODY[tier];
        int bodyA = (int) (a * 0.95f);
        for (int r = 0; r < h; r += 2) {
            float k = 1f + 0.9f * r / h;
            g.fill(x, y + r, x + w, Math.min(y + h, y + r + 2),
                    argb(bodyA, (int) (bc[0] * k), (int) (bc[1] * k), (int) (bc[2] * k)));
        }

        // Shimmer sweep
        long period = tier == 2 ? 900 : 1400;
        if (tp > 500) {
            float sp = ((tp - 500) % period) / (period / 2f);
            if (sp < 1f) {
                float sx = x - 30 + sp * (w + 60);
                for (int r = 0; r < h - 3; r += 2) {
                    int off = (h - r) / 3;
                    int x0 = Math.max(x, (int) sx + off);
                    int x1 = Math.min(x + w, (int) sx + off + 14);
                    if (x1 > x0) g.fill(x0, y + r, x1, y + r + 2, argb((int) (a * 0.20f), 255, 255, 255));
                }
            }
        }

        // Icon or slot reels
        if (jack) {
            reels(g, font, tp, x, y, a, accent);
            marquee(g, tp, x, y, w, h, a);
        } else {
            icon(g, x + 20, y + h / 2, tp, a);
        }

        // Text
        int tx = jack ? x + 10 + 68 + 10 : x + 40;
        int row0 = jack ? 8 : 5;
        int row1 = jack ? 23 : 17;
        int row2 = jack ? 40 : 30;

        long countStart = jack ? 2700 : 250;
        long countLen = jack ? 900 : 650;
        float cp = Math.min(1f, Math.max(0f, (tp - countStart) / (float) countLen));
        cp = 1f - (1f - cp) * (1f - cp) * (1f - cp);
        long hopStart = countStart + countLen;
        int bounce = (tp > hopStart && tp < hopStart + 250)
                ? -(int) (3 * Math.sin(Math.PI * (tp - hopStart) / 250.0)) : 0;

        String title;
        if (jack && tp < 2700) title = "+$" + "?".repeat(Math.max(1, orig.length()));
        else title = "+$" + shown(cp);

        int amountColor = amountColor(tp, a);
        int headerColor = jack && tp >= 2700 ? (((tp / 110) % 2 == 0) ? argb(a, 255, 235, 80) : argb(a, 255, 255, 255))
                : withAlpha(accent, a);
        g.text(font, header, tx, y + row0, headerColor);
        g.text(font, title, tx + 1, y + row1 + 1 + bounce, argb(a, 10, 10, 10));
        g.text(font, title, tx, y + row1 + bounce, amountColor);
        g.text(font, title, tx + 1, y + row1 + bounce, amountColor); // faux bold
        g.text(font, sub, tx, y + row2, argb(a, 225, 225, 225));

        // Timer bar
        long span = duration - (tier == 5 ? BH_BLAST : 0) - FADE_OUT - SLIDE_IN;
        float rem = 1f - Math.min(1f, Math.max(0f, (tp - SLIDE_IN) / (float) Math.max(1, span)));
        g.fill(x, y + h - 3, x + w, y + h, argb((int) (a * 0.6f), 0, 0, 0));
        g.fill(x, y + h - 3, x + (int) (w * rem), y + h, accent);

        // Sparkles
        if (tp > 300) {
            for (float[] c : sparks) {
                float tw = (float) Math.sin(tp / 180.0 + c[2]);
                if (tw > 0.3f) {
                    int px = x - 12 + (int) (c[0] * (w + 24));
                    int py = y - 12 + (int) (c[1] * (h + 24));
                    int s = 1 + (int) (tw * (tier == 2 ? 6 : 3));
                    int sa = (int) (a * tw);
                    g.fill(px - s, py, px + s + 1, py + 1, argb(sa, 255, 255, 235));
                    g.fill(px, py - s, px + 1, py + s + 1, argb(sa, 255, 255, 235));
                }
            }
        }

        // Coin burst / fountain
        if (jack) {
            if (tp > 2700) fountain(g, tp - 2700, x + w / 2, restY + h, a);
        } else if (tp > 120) {
            float tt = (tp - 120) / 1000f;
            int ox = x + w / 2;
            int oy = restY + h / 2;
            for (int i = 0; i < coinCount; i++) {
                float[] c = coins[i];
                float px = ox + (float) Math.cos(c[0]) * c[1] * tt * 1.7f;
                float py = oy + (float) Math.sin(c[0]) * c[1] * tt * 1.2f + 280f * tt * tt;
                float flip = Math.abs((float) Math.cos(c[4] + c[3] * tt));
                int s = (int) c[2];
                int fw = Math.max(1, (int) (s * flip));
                int ca = (int) (a * Math.max(0f, 1f - tt / 2.6f));
                if (ca > 6) {
                    g.fill((int) px, (int) py, (int) px + fw, (int) py + s, coinColor(flip > 0.5f, i, ca));
                    if (flip > 0.8f) g.fill((int) px, (int) py, (int) px + 1, (int) py + 1, argb(ca, 255, 255, 220));
                }
            }
        }

        // Confetti
        if (tier == 3) confetti(g, tp, sw, sh, a);
        if (tier == 4 && tp > 2700) confetti(g, tp - 2700, sw, sh, a);
    }

    // ===== JACKPOT =====
    private static void reels(GuiGraphicsExtractor g, Font font, long tp, int x, int y, int a, int accent) {
        int rx0 = x + 10;
        int ry = y + 14;
        int rw = 20, rh = 28, gap = 4;
        boolean done = tp >= 2700;
        for (int i = 0; i < 3; i++) {
            int rx = rx0 + i * (rw + gap);
            long stopT = 900 + i * 600L;
            boolean stopped = tp >= stopT;
            g.fill(rx - 1, ry - 1, rx + rw + 1, ry + rh + 1, accent);
            int winG = done && ((tp / 120) % 2 == 0) ? 40 : 10;
            g.fill(rx, ry, rx + rw, ry + rh, argb(a, 14 + winG, 8 + winG / 2, 10));
            g.fill(rx, ry, rx + rw, ry + rh / 2, argb((int) (a * 0.10f), 255, 255, 255));
            if (stopped) {
                boolean blink = (tp - stopT < 260) && ((tp / 50) % 2 == 0);
                int col = blink ? argb(a, 255, 255, 255) : argb(a, 255, 70, 70);
                // big pixel "7"
                g.fill(rx + 4, ry + 6, rx + 16, ry + 9, col);
                for (int j = 0; j < 14; j++) {
                    int x0 = rx + 12 - (int) (j * 0.5f);
                    g.fill(x0, ry + 9 + j, x0 + 3, ry + 10 + j, col);
                }
            } else {
                String sym = SYMBOLS[(int) ((tp / 65 + i * 3) % SYMBOLS.length)];
                int sx = rx + (rw - font.width(sym)) / 2;
                int sy = ry + (rh - 8) / 2;
                g.text(font, sym, sx, sy, argb((int) (a * 0.75f), 235, 235, 235));
            }
        }
    }

    private static void marquee(GuiGraphicsExtractor g, long tp, int x, int y, int w, int h, int a) {
        int k = 0;
        long step = tp >= 2700 ? tp / 90 : tp / 130;
        for (int bx = x - 4; bx <= x + w + 2; bx += 9) {
            bulb(g, bx, y - 6, k++, step, a);
            bulb(g, bx, y + h + 3, k++, step, a);
        }
        for (int by = y + 3; by <= y + h - 2; by += 9) {
            bulb(g, x - 6, by, k++, step, a);
            bulb(g, x + w + 3, by, k++, step, a);
        }
    }

    private static void bulb(GuiGraphicsExtractor g, int bx, int by, int k, long step, int a) {
        boolean on = ((k + step) % 2) == 0;
        int col = (k % 2 == 0) ? argb(a, 255, 215, 60) : argb(a, 255, 80, 80);
        if (!on) col = argb((int) (a * 0.25f), 120, 90, 40);
        g.fill(bx, by, bx + 3, by + 3, col);
    }

    private static void fountain(GuiGraphicsExtractor g, long ft, int ox, int oy, int a) {
        for (int i = 0; i < coinCount; i++) {
            float[] c = coins[i];
            float ct = ((ft - c[4] * 120f) % 1900f) / 1000f;
            if (ct < 0f) continue;
            float vx = (float) Math.cos(c[0]) * c[1] * 1.1f;
            float vy = (float) (-Math.sin(c[0])) * c[1] * 1.9f + 70f;
            float px = ox + vx * ct;
            float py = oy - vy * ct + 330f * ct * ct;
            float flip = Math.abs((float) Math.cos(c[4] + c[3] * ct));
            int s = (int) c[2];
            int fw = Math.max(1, (int) (s * flip));
            g.fill((int) px, (int) py, (int) px + fw, (int) py + s, coinColor(flip > 0.5f, i, a));
        }
    }

    // ===== BLACK HOLE =====
    private static void blackHole(GuiGraphicsExtractor g, Font font, long t, int sw, int sh) {
        if (t > 4800) return;
        int cx = sw / 2;
        int cy = (int) (sh * 0.32f);

        float dim = Math.min(1f, t / 600f);
        if (t > BH_BLAST) dim = Math.max(0f, 1f - (t - BH_BLAST) / 600f);
        if (dim > 0f) g.fill(0, 0, sw, sh, argb((int) (170 * dim), 0, 0, 10));

        float grow = Math.min(1f, t / 700f);
        float collapse = t < 3000 ? 1f : Math.max(0f, 1f - (t - 3000) / 700f);
        float R = 24f * grow * collapse;

        if (t < BH_BLAST) {
            diskParticles(g, cx, cy, t, dim, false);
            if (R >= 1f) {
                ringPts(g, cx, cy, R + 8f, argb((int) (70 * dim), 255, 140, 40), 2);
                ringPts(g, cx, cy, R + 5f, argb((int) (140 * dim), 255, 200, 120), 2);
                ringPts(g, cx, cy, R + 2f, argb((int) (230 * dim), 255, 255, 255), 2);
                int ri = (int) R;
                for (int dy = -ri; dy <= ri; dy++) {
                    int half = (int) Math.sqrt((double) ri * ri - (double) dy * dy);
                    g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, 0xFF000000);
                }
            }
            diskParticles(g, cx, cy, t, dim, true);

            if (t > 500 && t < 3500 && ((t / 260) % 2 == 0)) {
                String msg = t < 2300 ? "ANOMALY DETECTED" : "SINGULARITY COLLAPSING";
                g.text(font, msg, cx - font.width(msg) / 2, Math.max(4, cy - 62), argb(255, 255, 80, 80));
            }
        } else {
            float rt = (t - BH_BLAST) / 1000f;

            // white flash
            float fp = (t - BH_BLAST) / 450f;
            if (fp < 1f) g.fill(0, 0, sw, sh, argb((int) (255 * (1f - fp)), 255, 255, 255));

            // shockwaves
            for (int k = 0; k < 3; k++) {
                float st = (t - BH_BLAST - k * 140) / 1000f;
                if (st < 0f || st > 1f) continue;
                float e = 1f - (1f - st) * (1f - st);
                int ra = (int) (200 * (1f - st));
                if (ra > 4) ringPts(g, cx, cy, e * 320f, argb(ra, k == 1 ? 255 : 210, k == 1 ? 255 : 160, 255), 3);
            }

            // debris blast
            if (rt < 1.6f) {
                for (int i = 0; i < coinCount; i++) {
                    float[] c = coins[i];
                    float dx = (float) Math.cos(c[4]) * c[1] * 2.6f * rt;
                    float dyy = (float) Math.sin(c[4]) * c[1] * 2.1f * rt + 120f * rt * rt;
                    int s = (int) c[2];
                    int da = (int) (255 * Math.max(0f, 1f - rt / 1.6f));
                    if (da > 6) {
                        g.fill(cx + (int) dx, cy + (int) dyy, cx + (int) dx + s, cy + (int) dyy + s,
                                (i % 2 == 0) ? argb(da, 215, 170, 255) : argb(da, 255, 255, 255));
                    }
                }
            }
        }
    }

    private static void diskParticles(GuiGraphicsExtractor g, int cx, int cy, long t, float dim, boolean front) {
        float sec = t / 1000f;
        float infall = t < 3000 ? 1f - 0.5f * (t / 3000f) : 0.5f * Math.max(0f, 1f - (t - 3000) / 700f);
        for (float[] c : disk) {
            float r = c[1] * infall;
            if (r < 3f) continue;
            float ang = c[0] + c[2] * sec * (1f + sec * 0.4f);
            float sn = (float) Math.sin(ang);
            if ((sn >= 0f) != front) continue;
            int px = cx + (int) (Math.cos(ang) * r);
            int py = cy + (int) (sn * r * 0.32f);
            float f = Math.min(1f, r / 130f);
            int col = argb((int) (235 * dim), 255, (int) (110 + (1f - f) * 140), (int) (30 + (1f - f) * 200));
            int sz = (int) c[3];
            g.fill(px, py, px + sz, py + sz, col);
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

    // ===== ICONS =====
    private static void icon(GuiGraphicsExtractor g, int cx, int cy, long t, int a) {
        if (tier == 2) {
            // diamond
            float br = 1f + 0.12f * (float) Math.sin(t / 200.0);
            int R = (int) (10 * br);
            for (int dy = -R; dy <= R; dy++) {
                int half = R - Math.abs(dy);
                int col = dy < 0 ? argb(a, 190, 245, 255) : argb(a, 80, 185, 235);
                g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, col);
            }
            g.fill(cx, cy - R, cx + 1, cy + R + 1, argb((int) (a * 0.5f), 255, 255, 255));
            int gl = (int) (2 + 2 * Math.sin(t / 150.0));
            g.fill(cx - 4 - gl, cy - 4, cx - 4 + gl + 1, cy - 3, argb(a, 255, 255, 255));
            g.fill(cx - 4, cy - 4 - gl, cx - 3, cy - 4 + gl + 1, argb(a, 255, 255, 255));
            return;
        }
        if (tier == 5) {
            // mini black hole
            ringPts(g, cx, cy, 10f, argb(a, 170, 90, 255), 2);
            for (int dy = -7; dy <= 7; dy++) {
                int half = (int) Math.sqrt(49.0 - dy * dy);
                g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, argb(a, 0, 0, 0));
            }
            double ang = t / 180.0;
            int px = cx + (int) (Math.cos(ang) * 13);
            int py = cy + (int) (Math.sin(ang) * 5);
            g.fill(px, py, px + 2, py + 2, argb(a, 255, 210, 140));
            return;
        }
        // spinning coin (silver, gold, rainbow)
        float spin = Math.abs((float) Math.cos(t / 220.0));
        int hw = Math.max(1, (int) (8 * spin));
        int c1, c2, c3;
        if (tier == 0) {
            c1 = argb(a, 140, 148, 160); c2 = argb(a, 205, 212, 225); c3 = argb(a, 240, 244, 252);
        } else if (tier == 3) {
            c1 = argb(a, 60, 60, 80); c2 = hsv(t / 700f, a); c3 = argb(a, 255, 255, 255);
        } else {
            c1 = argb(a, 200, 140, 20); c2 = argb(a, 255, 205, 40); c3 = argb(a, 255, 235, 120);
        }
        g.fill(cx - hw, cy - 9, cx + hw, cy + 9, c1);
        if (hw > 2) {
            g.fill(cx - hw + 1, cy - 8, cx + hw - 1, cy + 8, c2);
            if (hw > 4) g.fill(cx - hw + 3, cy - 6, cx + hw - 3, cy + 6, c3);
        }
    }

    // ===== HELPERS =====
    private static void confetti(GuiGraphicsExtractor g, long ct0, int sw, int sh, int a) {
        for (float[] c : confetti) {
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

    private static int coinColor(boolean bright, int idx, int ca) {
        switch (tier) {
            case 0: return bright ? argb(ca, 215, 222, 235) : argb(ca, 130, 138, 150);
            case 1: return bright ? argb(ca, 255, 215, 60) : argb(ca, 200, 140, 20);
            case 2: return bright ? argb(ca, 170, 245, 255) : argb(ca, 60, 170, 225);
            case 3: return hsv(idx / 20f, ca);
            case 4: return bright ? argb(ca, 255, 215, 60) : argb(ca, 215, 60, 60);
            default: return bright ? argb(ca, 215, 170, 255) : argb(ca, 110, 60, 200);
        }
    }

    private static int accent(long t, float pulse, int a) {
        switch (tier) {
            case 0: return argb(a, (int) (205 * pulse), (int) (212 * pulse), (int) (225 * pulse));
            case 1: return argb(a, (int) (255 * pulse), (int) (200 * pulse), (int) (40 * pulse));
            case 2: {
                float s = 0.5f + 0.5f * (float) Math.sin(t / 260.0);
                return argb(a, (int) (110 + 110 * s), (int) (225 + 30 * s), 255);
            }
            case 3: return hsv(t / 900f, a);
            case 4: return ((t / 140) % 2 == 0) ? argb(a, 255, 205, 50) : argb(a, 255, 70, 70);
            default: return argb(a, (int) (165 * pulse), (int) (20 + 80 * pulse), (int) (255 * pulse));
        }
    }

    private static int amountColor(long t, int a) {
        switch (tier) {
            case 0: return argb(a, 235, 240, 250);
            case 1: return argb(a, 255, 225, 90);
            case 2: return argb(a, 170, 245, 255);
            case 3: return hsv(t / 700f + 0.3f, a);
            case 4: return ((t / 120) % 2 == 0) ? argb(a, 255, 235, 80) : argb(a, 255, 255, 255);
            default: return argb(a, 225, 175, 255);
        }
    }

    private static String shown(float p) {
        if (p >= 1f || num <= 0) return orig;
        double v = num * p;
        String body;
        if (decimals > 0) body = String.format(Locale.ROOT, "%." + decimals + "f", v);
        else if (comma) body = String.format(Locale.ROOT, "%,d", (long) v);
        else body = Long.toString((long) v);
        return body + suffix;
    }

    private static float backOut(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float d = x - 1f;
        return 1f + c3 * d * d * d + c1 * d * d;
    }

    private static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int th, int c) {
        g.fill(x1, y1, x2, y1 + th, c);
        g.fill(x1, y2 - th, x2, y2, c);
        g.fill(x1, y1 + th, x1 + th, y2 - th, c);
        g.fill(x2 - th, y1 + th, x2, y2 - th, c);
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
}
