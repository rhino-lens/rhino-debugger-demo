// Pricing rule: total the order and apply the customer's discount.
// Java objects in scope: order (Order), pricing (PricingService).

function lineTotal(item) {
  var amount = item.getQuantity() * item.getUnitPrice();
  return amount;
}

var total = 0;
var items = order.getItems();
for (var i = 0; i < items.size(); i++) {
  var item = items.get(i);
  var amount = lineTotal(item);
  print("line " + item.getSku() + ": " + amount);
  total += amount;
}

var discount = pricing.discountFor(order.getCustomerTier(), total);
var finalTotal = pricing.round(total - discount);
order.setFinalTotal(finalTotal);
print("final total: " + finalTotal);
finalTotal;
