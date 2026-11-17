package ru.lumqera.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable whitelist of finite, configurable shop offers, independent from Bukkit. */
final class ShopCatalog {
    record Offer(String id, String material, int amount, long price) { }
    record Quote(Offer offer, int bundles, int items, long coins) { }
    private final Map<String, Offer> offers = new LinkedHashMap<>();
    ShopCatalog(Collection<Offer> raw) {
        if (raw == null) throw new IllegalArgumentException("Missing offers");
        for (Offer offer : raw) {
            if (offer == null || offer.id() == null || !offer.id().matches("[a-z0-9_]{2,24}")
                || offer.material() == null || !offer.material().matches("[A-Z0-9_]{2,64}")
                || offer.amount() < 1 || offer.amount() > 64 || offer.price() < 1
                || offers.putIfAbsent(offer.id(), offer) != null)
                throw new IllegalArgumentException("Invalid or duplicate offer");
        }
    }
    Collection<Offer> offers() { return java.util.List.copyOf(offers.values()); }
    Quote quote(String raw, int bundles, int maxBundles) {
        if (raw == null || bundles < 1 || bundles > maxBundles) return null;
        Offer offer = offers.get(raw.toLowerCase(Locale.ROOT));
        if (offer == null) return null;
        try { return new Quote(offer, bundles, Math.multiplyExact(offer.amount(), bundles), Math.multiplyExact(offer.price(), bundles)); }
        catch (ArithmeticException ex) { return null; }
    }
}
