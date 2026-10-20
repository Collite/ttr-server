SELECT l.line_id, l.request_number, l.item, l.quantity, l.unit_price, l.quantity * l.unit_price AS line_value,
       l.reason, l.requested_resolution, l.requested_variant,
       r.customer, r.order_number, r.requested_date
FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
WHERE  l.state = 'received'
