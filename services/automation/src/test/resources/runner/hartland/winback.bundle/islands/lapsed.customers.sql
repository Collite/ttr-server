SELECT "t6"."customer" AS "sk", "customer"."hdemo", "t6"."orders_before"
FROM (SELECT "t1"."customer", "t1"."orders_before"
        FROM (SELECT "customer", COUNT("order_number") AS "orders_before"
                FROM "order"
                WHERE "placed_date" >= :as_of_minus_quiet_months_months_minus_12_months AND "placed_date" <= :as_of_minus_quiet_months_months
                GROUP BY "customer"
                HAVING COUNT("order_number") >= :active_orders) AS "t1"
            LEFT JOIN (SELECT "customer", 1 AS "_ttrp_exists"
                FROM "order"
                WHERE "placed_date" >= :as_of_minus_quiet_months_months AND "placed_date" <= :as_of
                GROUP BY "customer") AS "t4" ON "t1"."customer" = "t4"."customer"
        WHERE "t4"."_ttrp_exists" IS NULL) AS "t6"
    INNER JOIN "customer" ON "t6"."customer" = "customer"."sk"
