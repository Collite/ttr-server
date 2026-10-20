WITH returned AS (
    SELECT rr.customer, SUM(rl.quantity * rl.unit_price) AS returned_value
    FROM   er.entity.return_request_line rl JOIN er.entity.return_request rr ON rr.request_number = rl.request_number
    WHERE  rr.requested_date >= :as_of - 365 AND rl.state <> 'rejected'
    GROUP  BY rr.customer
),
bought AS (
    SELECT s.customer, SUM(s.ext_sales_price) AS bought_value
    FROM   er.entity.sales_line s
    WHERE  s.sold_date >= :as_of - 365
    GROUP  BY s.customer
)
SELECT q.line_id, COALESCE(r.returned_value, 0) / NULLIF(b.bought_value, 0) AS share
FROM   lines q
LEFT JOIN returned r ON r.customer = q.customer
LEFT JOIN bought   b ON b.customer = q.customer
