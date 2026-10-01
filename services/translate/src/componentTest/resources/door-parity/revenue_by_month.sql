SELECT "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
FROM "catalog_sales" JOIN "date_dim"
GROUP BY "date_dim"."month"
