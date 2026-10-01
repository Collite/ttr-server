SELECT "item"."brand" AS "brand", "date_dim"."year" AS "year", SUM("catalog_sales"."quantity") AS "quantity"
FROM "catalog_sales" JOIN "item" JOIN "date_dim"
GROUP BY "item"."brand", "date_dim"."year"
