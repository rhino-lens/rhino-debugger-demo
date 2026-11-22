// Pricing rule: total the order and apply the customer's discount.
// Java objects in scope: order, pricing, orderRepository; lens = Lens script API.

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
order.status = "PRICED";                       // '=' on a bean property
order.getNotes().add("priced by " + lens.report().ruleName());
orderRepository.save(order);                   // CRUD-style service call
lens.mark("pricing done");
finalTotal;
