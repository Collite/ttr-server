SELECT "date_dim"."year" AS "year", "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
FROM "catalog_sales" JOIN "date_dim"
WHERE "date_dim"."cal_date" >= DATE '2025-11-01' AND "date_dim"."cal_date" < DATE '2026-11-01'
GROUP BY "date_dim"."year", "date_dim"."month"
ORDER BY "date_dim"."year", "date_dim"."month"
