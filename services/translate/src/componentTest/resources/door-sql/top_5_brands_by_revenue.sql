SELECT "item"."brand" AS "brand", SUM("catalog_sales"."ext_sales_price") AS "ext_sales_price"
FROM "catalog_sales" JOIN "item"
GROUP BY "item"."brand"
ORDER BY "ext_sales_price" DESC NULLS LAST
LIMIT 5
