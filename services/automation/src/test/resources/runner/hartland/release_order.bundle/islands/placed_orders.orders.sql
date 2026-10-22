SELECT "order_number", "customer", "channel", "fulfilling_warehouse", "value", "placed_date", "state"
FROM "order"
WHERE "state" IN ('held', 'placed')
