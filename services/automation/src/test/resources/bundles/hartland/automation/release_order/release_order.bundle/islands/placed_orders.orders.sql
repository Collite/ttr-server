SELECT o.order_number, o.customer, o.channel, o.fulfilling_warehouse, o.value, o.placed_date, o.state
FROM   er.entity.order o
WHERE  o.state IN ('placed', 'held')
