SELECT "item"."product_name" AS "product_name"
FROM "item"
WHERE NOT ("item"."product_name" LIKE '%' || {p0} || '%')
