SELECT "date_dim"."month" AS "month", "date_dim"."year" AS "year", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
FROM "catalog_sales" JOIN "date_dim"
GROUP BY "date_dim"."month", "date_dim"."year"
ORDER BY "date_dim"."year", "date_dim"."month"
