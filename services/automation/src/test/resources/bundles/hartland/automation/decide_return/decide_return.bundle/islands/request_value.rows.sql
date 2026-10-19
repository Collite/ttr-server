SELECT l.request_number, SUM(l.quantity * l.unit_price) AS request_value
FROM   er.entity.return_request_line l GROUP BY l.request_number
