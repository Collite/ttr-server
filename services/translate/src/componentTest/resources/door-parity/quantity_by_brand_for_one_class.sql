SELECT "item"."brand" AS "brand", SUM("catalog_sales"."quantity") AS "quantity"
FROM "catalog_sales" JOIN "item"
WHERE "item"."class" = 'men''s'
GROUP BY "item"."brand"
