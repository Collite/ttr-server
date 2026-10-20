SELECT l.line_id, l.request_number, l.item, l.quantity, l.way, r.customer, ob.id AS obligation_id, ob.due, ob.state AS obligation_state
FROM   er.entity.return_request_line l
JOIN   er.entity.return_request r ON r.request_number = l.request_number
JOIN   er.entity.obligation ob    ON ob.entity = 'return_request_line' AND ob.key = l.line_id AND ob.kind = 'inbound'
                                  AND ob.state IN ('open', 'overdue', 'reopened')
WHERE  l.state = 'awaiting_goods'
