$version: "2.0"
namespace org.adk4s.structured.test.inventoryitem

structure InventoryItem {
    @required sku: String
    @required name: String
    quantity: Integer
    location: String
    reorderLevel: Integer
    price: Float
    currency: String
}
