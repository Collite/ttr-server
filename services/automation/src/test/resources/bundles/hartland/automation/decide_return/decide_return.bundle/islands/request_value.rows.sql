SELECT "request_number", SUM("quantity" * "unit_price") AS "request_value"
FROM "return_request_line"
GROUP BY "request_number"
