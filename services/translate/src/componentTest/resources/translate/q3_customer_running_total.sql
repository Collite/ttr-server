SELECT "customer"."c_customer_id", "date_dim"."d_date", CASE WHEN (COUNT("store_sales"."ss_net_paid") OVER (PARTITION BY "customer"."c_customer_sk" ORDER BY "date_dim"."d_date" NULLS FIRST RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)) > 0 THEN SUM("store_sales"."ss_net_paid") OVER (PARTITION BY "customer"."c_customer_sk" ORDER BY "date_dim"."d_date" NULLS FIRST RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) ELSE NULL END AS "running_total"
FROM "store_sales"
    INNER JOIN "customer" ON "store_sales"."ss_customer_sk" = "customer"."c_customer_sk"
    INNER JOIN "date_dim" ON "store_sales"."ss_sold_date_sk" = "date_dim"."d_date_sk"
WHERE "date_dim"."d_year" = 2002
