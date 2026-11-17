# Platform question

A free, Apache-2.0 task contract and Camunda 7 element template for asking a
Tatrman platform a read-only question from an external workflow engine.

This folder contains an interface, configuration schema, examples, two template
localisations, and one generic BPMN process. **No service in this repository
answers this interface yet.** The bridge follows in OL W9; native automation
support is separate work. Installing the template alone does not supply an HTTP
service.

- [`contract.md`](contract.md): HTTP endpoints, configuration, variables, limits,
  answer envelope, identity, and Camunda binding.
- [`config.schema.json`](config.schema.json): parsed configuration object. Validate
  YAML after parsing it; the request envelope is not the schema's subject.
- [`examples/valid`](examples/valid) and [`examples/invalid`](examples/invalid):
  four valid and seven invalid examples, including lane exclusivity, row-prefix
  safety, and nonempty, distinct column selections.
- [`camunda7/tatrman-question.json`](camunda7/tatrman-question.json): English.
- [`camunda7/tatrman-dotaz.json`](camunda7/tatrman-dotaz.json): original Czech id
  and texts, with the same safety repairs as the English template.
- [`samples/question.bpmn`](samples/question.bpmn): start → query → end, looking
  up the generic `item` entity by `item_id` and deriving `item_count`.

## Use with Camunda 7

Copy either template to your diagram's `.camunda/element-templates/` directory,
reload Desktop Modeler, and select it on a service task. The sample already
carries the English template bindings and diagram coordinates. Change its model
names to those served by your platform, and supply `item_id` when starting it.

Use an engine with Connect, Spin, and Groovy (the Camunda Tomcat distribution
includes them). The inherited binding targets Camunda 7.14 and 7.20; this
publication's CI checks template structure, not a running engine. No custom
Java is required. Camunda 8 is outside this package.

Configure the engine JVM system properties `tatrman.url` (the full
`/automation/query` endpoint) and `tatrman.token` (the service credential). The
endpoint is a hidden template property evaluated at execution time and has no
default host. Neither value belongs in BPMN or a process variable. Set Tomcat's
`VersionLoggerListener` to `logArgs="false"` to keep JVM credentials out of its
startup log. Set `jobExecutorDeploymentAware="false"` in `bpm-platform.xml` for
the single-engine deployment described by the source contract, and use the
data source's time zone for the engine.
An unset or blank endpoint/credential property causes an engine incident naming
the property before any HTTP call, rather than a business error result.

Enter literal YAML or JSON in **Configuration**, comma/whitespace-separated
process variable names in **Parameters**, and an answer variable name in
**Result** (default `tatrman_vysledek`). Use `{name}` for bound parameters;
`${…}` is not a configuration placeholder. The template omits absent/null
process parameters, retains empty strings, and serializes dates in the engine's
zone as `yyyy-MM-dd'T'HH:mm:ss`. SQL values are bound by the platform, never
interpolated into the configuration.

The end listener stores the answer as Spin JSON, sets `tatrman_status` and
`tatrman_chyba`, and exports each derived variable. Lists/objects use Spin JSON;
scalars use native values, with JSON-serialized objects for text over 4,000
characters and integers beyond Long. A decimal uses a native Double only when
the finite Double's decimal text round-trips unchanged. Otherwise it stays an
exact BigDecimal Object using built-in Java serialization
(`application/x-java-serialized-object`). This uses the JDK BigDecimal class and
requires no custom class. In Camunda 7.20, `javaSerializationFormatEnabled`
defaults to `false` and restricts pre-serialized Java values supplied through the
API; it does not block the engine from serializing a BigDecimal object stored by
the template. No change to that setting is needed. The HTTP answer JSON is
unchanged. Invalid or `tatrman_`-prefixed
answer-variable names are skipped with a warning, so service data cannot
overwrite the answer status. Service JSON errors reach the listener;
transport failures create an asynchronous job incident for retry in Cockpit.
The inherited connector uses 5-second connection/pool and 130-second read
timeouts; its service's source budgets are 60 seconds for data and 120 for Golem.

## Validate

In the ttr-server repository, from its root:

```sh
python3 -m venv build/platform-question-venv
build/platform-question-venv/bin/pip install jsonschema==4.25.1
build/platform-question-venv/bin/python -m unittest discover -s connectors/platform-question -v
```

In the extracted archive, from its parent directory:

```sh
cd platform-question
python3 -m venv .venv
.venv/bin/pip install jsonschema==4.25.1
.venv/bin/python -m unittest discover -s . -v
```

The checks run offline once the validator is installed. Both templates are
validated against the vendored official Camunda schema, and every example is
checked against the configuration schema. Schema validation does not resolve
served model names, core references, frame columns, SQL safety, placeholders, or
runtime cardinality; an implementation must perform those contract checks.

## Origin and release

Copied once from `ai-platform` commit
`4d0242eefbc0df292be2bd9fb96e6d47b71f8f3b`, file
`services/aip-camunda/camunda/tatrman-dotaz.c7.json`. Collite publishes this
contract and template as contract owner under OL D-38. The source repository
is read only for this stage; no source refs or deployment are changed. The
English localisation translates the display name, labels, description, and
non-service-response diagnostic; scripts and portable engine property keys
otherwise retain their source behavior except for the OL R344-A safety repairs:
both copies retain non-round-tripping decimals exactly through built-in Java
serialization, reject missing engine properties, and guard
exported variable names. No source repository is changed by those repairs.

`platform-question/v0.11.0` identifies a GitHub release with this folder as one
archive. Pushing the tag runs `platform-question.yml`: validate the package,
build the archive, run its tests again from the unpacked directory, then publish
the GitHub release with the connector's changelog entry. The image release lane
excludes this tag. The archive contains no bridge, engine distribution, customer
process, or model.
See the repository's [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
The vendored Camunda schema retains its own MIT licence; see
[`vendor/README.md`](vendor/README.md).
