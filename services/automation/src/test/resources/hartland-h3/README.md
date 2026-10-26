# Frozen Hartland H3 preview fixture

Source: Collite/ttr-demo at `9f6fff1aa4bc0aadb1452f469ebe417bc732c933`, accepted AU writer-default adoption stage.
The complete H3 bundle was built with released ttrp 0.18.11. SOURCE.json pins each selected file's SHA-256.
The selection keeps all model `.ttrm` files, the real automation action declarations, and the complete H3 bundle.
Other recipes, automation deployments/programs, connection credentials and live data are excluded.

HartlandH3PreviewSpec runs this exact full recipe with the real loader, approval verifier, parameter bindings,
staging and planner. Only Query result tables are faked. It asserts the complete proposal and deferred buyer ask,
relative working-day count, replay/idempotence, empty selection and absence of task/obligation writes.
It does not dispatch the nested action, resolve its dependency closure, capture approved rows, group/create tasks,
consult a calendar or compute an absolute due instant. Those belong to later task/dispatch/calendar stages.

To verify or restore the immutable source files from a local ttr-demo clone containing that commit:

    python3 refresh.py /path/to/ttr-demo --check
    python3 refresh.py /path/to/ttr-demo

The script reads committed Git blobs, checks all hashes before any writes, and never reads the clone's working tree.
Tests use the committed snapshot and require no external checkout, optional property, network or skipped profile.
