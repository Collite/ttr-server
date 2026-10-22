SELECT "order_number", "invoice_id", "due", "amount" - "paid" AS "open_amount"
FROM (SELECT "t0"."order_number", "t0"."customer", "t0"."channel", "t0"."fulfilling_warehouse", "t0"."value", "t0"."placed_date", "t0"."state", "invoice"."invoice_id", "invoice"."order_number" AS "order_number0", "invoice"."issued", "invoice"."due", "invoice"."amount", "invoice"."paid", "invoice"."status"
        FROM (SELECT "order_number", "customer", "channel", "fulfilling_warehouse", "value", "placed_date", "state"
                FROM "order"
                WHERE "state" IN ('held', 'placed')) AS "t0"
            INNER JOIN "invoice" ON "t0"."customer" = "invoice"."customer") AS "t1"
WHERE "t1"."status" = 'open' AND "t1"."due" < :as_of
