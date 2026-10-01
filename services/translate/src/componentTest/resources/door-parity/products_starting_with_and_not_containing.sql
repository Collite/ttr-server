SELECT "item"."product_name" AS "product_name"
FROM "item"
WHERE "item"."product_name" LIKE {p0} || '%' AND NOT ("item"."product_name" LIKE '%' || {p1} || '%')
