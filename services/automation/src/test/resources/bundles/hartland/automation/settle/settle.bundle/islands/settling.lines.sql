SELECT l.line_id, l.request_number, l.item, l.quantity, l.way, r.customer
FROM   er.entity.return_request_line l JOIN er.entity.return_request r ON r.request_number = l.request_number
WHERE  l.state IN ('settling', 'goods_received')
