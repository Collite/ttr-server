SELECT "item", "warehouse", SUM("quantity") AS "sold", CAST(CAST(SUM("quantity") AS BIGINT) AS DECIMAL(19, 1)) / :demand_period AS "weekly_demand"
FROM (SELECT "t0"."item", "t0"."warehouse", "t0"."lines", "sales_line0"."order_number", "sales_line0"."line_no", "sales_line0"."quantity", "sales_line0"."unit_price", "sales_line0"."ext_sales_price", "sales_line0"."sold_date", "sales_line0"."customer", "sales_line0"."channel"
        FROM (SELECT "item", "warehouse", COUNT("line_no") AS "lines"
                FROM "sales_line"
                WHERE "sold_date" >= :as_of_minus_demand_period_weeks AND "sold_date" <= :as_of
                GROUP BY "item", "warehouse") AS "t0"
            INNER JOIN "sales_line" AS "sales_line0" ON "t0"."item" = "sales_line0"."item" AND "t0"."warehouse" = "sales_line0"."warehouse") AS "t1"
WHERE "t1"."sold_date" >= :as_of_minus_demand_period_weeks AND "t1"."sold_date" <= :as_of
GROUP BY "item", "warehouse"
