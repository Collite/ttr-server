SELECT "line_id", "request_number", "line_no", "item", "quantity", "unit_price", "reason", "requested_resolution", "requested_variant", "state", "way", "customer", "customer0", "order_number", "channel", "requested_date", "state0"
FROM (
  SELECT *
  FROM (SELECT "return_request_line"."line_id", "return_request_line"."request_number", "return_request_line"."line_no", "return_request_line"."item", "return_request_line"."quantity", "return_request_line"."unit_price", "return_request_line"."reason", "return_request_line"."requested_resolution", "return_request_line"."requested_variant", "return_request_line"."state", "return_request_line"."way", "return_request_line"."customer", "return_request"."customer" AS "customer0", "return_request"."order_number", "return_request"."channel", "return_request"."requested_date", "return_request"."state" AS "state0"
          FROM "return_request_line"
              INNER JOIN "return_request" ON "return_request_line"."request_number" = "return_request"."request_number") AS "t"
  WHERE "t"."state" = 'awaiting_goods'
) AS "_ttrp_part"
