SELECT "date_dim"."month" AS "month", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
FROM "catalog_sales" JOIN "date_dim"
WHERE "date_dim"."cal_date" >= TIMESTAMP '2025-01-01 00:00:00' AND "date_dim"."cal_date" < TIMESTAMP '2026-01-01 00:00:00'
GROUP BY "date_dim"."month"
ORDER BY "date_dim"."month"
