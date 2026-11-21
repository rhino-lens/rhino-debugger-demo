package io.github.rhinolens.debugdemo.sample;

import java.util.ArrayList;
import java.util.List;

/** A Java object scripts read and change. */
public class Order {

    private final String customerTier;
    private final List<LineItem> items = new ArrayList<>();
    private double finalTotal;

    public Order(String customerTier) {
        this.customerTier = customerTier;
    }

    public Order add(LineItem item) {
        items.add(item);
        return this;
    }

    public String getCustomerTier() {
        return customerTier;
    }

    public List<LineItem> getItems() {
        return items;
    }

    public double getFinalTotal() {
        return finalTotal;
    }

    public void setFinalTotal(double finalTotal) {
        this.finalTotal = finalTotal;
    }

    /** A fresh order for each debug session, since scripts change it. */
    public static Order sample() {
        return new Order("GOLD")
                .add(new LineItem("MUG-RED", 2, 12.50))
                .add(new LineItem("TEA-200G", 1, 8.99))
                .add(new LineItem("SPOON", 4, 1.25));
    }

    @Override
    public String toString() {
        return "Order{tier=" + customerTier + ", items=" + items.size() + ", finalTotal=" + finalTotal + "}";
    }
}
