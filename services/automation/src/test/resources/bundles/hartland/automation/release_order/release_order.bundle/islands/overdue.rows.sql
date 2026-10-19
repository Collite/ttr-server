SELECT o.order_number, i.invoice_id, i.due, i.amount - i.paid AS open_amount
FROM   orders o JOIN er.entity.invoice i ON i.customer = o.customer
WHERE  i.status = 'open' AND i.due < :as_of
