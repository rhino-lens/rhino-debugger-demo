package io.github.rhinolens.debugdemo.sample;

import java.util.ArrayList;
import java.util.List;

/** A CRUD-style service scripts call; Lens records each call (recognised by the "Repository" suffix). */
public class OrderRepository {

    private final List<String> saved = new ArrayList<>();

    public Order save(Order order) {
        saved.add(order.toString());
        return order;
    }

    public int count() {
        return saved.size();
    }

    @Override
    public String toString() {
        return "OrderRepository(saved=" + saved.size() + ")";
    }
}
