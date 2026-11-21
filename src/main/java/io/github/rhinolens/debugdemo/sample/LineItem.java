package io.github.rhinolens.debugdemo.sample;

/** A Java object scripts read through Rhino's Java interop. */
public class LineItem {

    private final String sku;
    private final int quantity;
    private final double unitPrice;

    public LineItem(String sku, int quantity, double unitPrice) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    @Override
    public String toString() {
        return sku + " x" + quantity + " @ " + unitPrice;
    }
}
