SELECT "date_dim"."d_year", "date_dim"."d_moy", SUM("store_sales"."ss_sales_price") AS "total_sales"
FROM "store_sales"
    INNER JOIN "date_dim" ON "store_sales"."ss_sold_date_sk" = "date_dim"."d_date_sk"
WHERE "date_dim"."d_year" = 2002
GROUP BY "date_dim"."d_year", "date_dim"."d_moy"
ORDER BY "date_dim"."d_moy" NULLS FIRST
