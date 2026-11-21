package io.github.rhinolens.debugdemo.sample;

/** A Java service scripts call. */
public class PricingService {

    /** GOLD gets 10%, SILVER 5%; orders over 50 get 2 more off. */
    public double discountFor(String tier, double total) {
        double rate = switch (tier) {
            case "GOLD" -> 0.10;
            case "SILVER" -> 0.05;
            default -> 0.0;
        };
        double discount = total * rate;
        if (total > 50) {
            discount += 2;
        }
        return discount;
    }

    public double round(double amount) {
        return Math.round(amount * 100) / 100.0;
    }

    @Override
    public String toString() {
        return "PricingService";
    }
}
