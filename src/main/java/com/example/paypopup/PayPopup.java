package com.example.paypopup;

import java.util.Random;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

public final class PayPopup {
    private static final long DURATION = 4000;   // total ms on screen
    private static final long SLIDE_IN = 450;    // ms to slide down
    private static final long FADE_OUT = 600;    // ms to fade at the end
    private static final int COINS = 22;

    private static long start = -1;
    private static String from = "";
    private static String amount = "";
    private static final float[][] coins = new float[COINS][3]; // angle, speed, size

    private PayPopup() {}

    public static void trigger(String player, String amt) {
        from = player;
        amount = amt;
        start = Util.getMillis();

        Random r = new Random();
        for (float[] c : coins) {
            c[0] = (float) (-Math.PI + r.nextDouble() * Math.PI); // upward fan
            c[1] = 60f + r.nextFloat() * 110f;                    // px/sec
            c[2] = 2f + r.nextFloat() * 3f;                       // size
        }

        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f));
    }

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (start < 0) return;
        long t = Util.getMillis() - start;
        if (t > DURATION) { start = -1; return; }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        String title = "+$" + amount;
        String sub = "from " + from;
        int w = Math.max(font.width(title), font.width(sub)) + 40;
        int h = 38;
        int x = (screenW - w) / 2;
        int restY = 14;

        // Slide in with ease-out, then hold
        float slideP = Math.min(1f, t / (float) SLIDE_IN);
        float eased = 1f - (1f - slideP) * (1f - slideP) * (1f - slideP);
        int y = (int) (-h + (restY + h) * eased);

        // Fade out at the end
        float alpha = 1f;
        long fadeStart = DURATION - FADE_OUT;
        if (t > fadeStart) alpha = 1f - (t - fadeStart) / (float) FADE_OUT;
        int a = Math.max(4, (int) (alpha * 255));

        // Pulsing gold border
        float pulse = 0.65f + 0.35f * (float) Math.sin(t / 120.0);
        int gold = argb(a, (int) (255 * pulse), (int) (200 * pulse), 40);

        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, gold);                // border
        g.fill(x, y, x + w, y + h, argb((int) (a * 0.92f), 24, 20, 12)); // body

        // Coin icon (simple pixel coin) on the left
        int cx = x + 14, cy = y + h / 2;
        g.fill(cx - 6, cy - 6, cx + 6, cy + 6, argb(a, 255, 205, 40));
        g.fill(cx - 4, cy - 4, cx + 4, cy + 4, argb(a, 255, 235, 120));
        g.fill(cx - 1, cy - 4, cx + 1, cy + 4, argb(a, 210, 150, 20));

        // Text
        int tx = x + 30;
        g.text(font, title, tx, y + 8, argb(a, 120, 255, 120));
        g.text(font, sub, tx, y + 22, argb(a, 220, 220, 220));

        // Coin burst from the popup
        if (t > 150) {
            float tt = (t - 150) / 1000f;
            int originX = x + w / 2;
            int originY = y + h / 2;
            for (float[] c : coins) {
                float px = originX + (float) Math.cos(c[0]) * c[1] * tt * 1.6f;
                float py = originY + (float) Math.sin(c[0]) * c[1] * tt + 220f * tt * tt;
                int s = (int) c[2];
                int ca = (int) (a * Math.max(0f, 1f - tt / 2.4f));
                if (ca > 4) {
                    g.fill((int) px, (int) py, (int) px + s, (int) py + s, argb(ca, 255, 215, 60));
                }
            }
        }
    }

    private static int argb(int a, int r, int gr, int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (gr & 0xFF) << 8 | (b & 0xFF);
    }
}
