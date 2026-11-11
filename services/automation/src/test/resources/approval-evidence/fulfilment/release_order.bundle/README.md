# release_order

## What this recipe decides

- `release`: approved writer fixture

## What it reads

- `er.entity.obligation`
- `er.entity.order`

## What it does

- `verdict.release` → `release`: `customer` = `42`, `held_for` = `"Sales"`, `order` = `order_number`, `value` = `100.00`. would release order {order} as {state}
- `verdict.release` → `hold`: `department` = `"Sales"`, `order` = `order_number`. would hold order {order} for {department}
- `verdict.release` → `fulfil`: `kind` = `"inbound"`, `line` = `9007199254740993`, `to` = `"fulfilled"`
- `verdict.release` → `fulfil`: `kind` = `"inbound"`, `line` = `9007199254740993`, `to` = `"fulfilled"`

## Parameters

- Price tolerance (`tolerance`) = `0.01` (decimal)
- Confirmation threshold (`confirmation_threshold`) = `50000` (decimal)
- `inbound_reminder` = `14` (days)

## Mode

`per-candidate`
