SELECT "receipt_id", "line_id", "received_date", "item", "quantity", "condition"
FROM (
  SELECT *
  FROM "return_receipt"
) AS "_ttrp_part"
