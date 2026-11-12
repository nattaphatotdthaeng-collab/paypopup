package com.example.paypopup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

public class PayPopupClient implements ClientModInitializer {
    public static final String MOD_ID = "paypopup";

    // Matches e.g. "Steve paid you $2.5m" or "[VIP] Steve paid you $1,500"
    private static final Pattern PAID_YOU = Pattern.compile(
            "(\\w{1,16}) paid you \\$?\\s*([\\d,]+(?:\\.\\d+)?\\s?[kKmMbBtT]?)");

    @Override
    public void onInitializeClient() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "popup"),
                PayPopup::render);

        // GAME covers system messages; the boolean is true when it's an action bar message.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> check(message.getString()));
    }

    private static void check(String text) {
        Matcher m = PAID_YOU.matcher(text);
        if (m.find()) {
            PayPopup.trigger(m.group(1), m.group(2).replace(" ", ""));
        }
    }
}
