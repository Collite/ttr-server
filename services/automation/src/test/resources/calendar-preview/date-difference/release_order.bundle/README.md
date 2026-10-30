# release_order

## What this recipe decides

- `release`: calendar fixture

## What it reads

- `er.entity.date_dim`
- `er.entity.order`

## What it does

- `verdict.release` → `notify_customer`: `customer` = `42`, `template` = `cast(:as_of - placed as string)`

## Parameters


## Mode

`per-candidate`
