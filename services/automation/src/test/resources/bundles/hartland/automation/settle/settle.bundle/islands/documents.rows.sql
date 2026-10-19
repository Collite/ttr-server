SELECT q.line_id,
       cn.credit_note_id, cn.issued_date, p.payment_id, p.paid_date, sh.shipment_id, sh.shipped_date
FROM   lines q
LEFT JOIN er.entity.credit_note          cn ON cn.line_id = q.line_id
LEFT JOIN er.entity.refund_payment       p  ON p.credit_note_id = cn.credit_note_id
LEFT JOIN er.entity.replacement_shipment sh ON sh.line_id = q.line_id
