#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""MW-P1 disposable bp-dsk certification. Receipts/fixtures belong under build/.

Uses a separate namespace, public images and synthetic Git/filesystem/HTTP inputs.
Never reads a Secret or changes the serving metadata Deployment. Requires git,
Helm, kubectl and grpcurl. --prepare-only renders inputs without creating resources.
"""

import argparse
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
NAMESPACE = "mw-p1-cert"
SERVICE = "org.tatrman.meta.v1.MetadataService/"


def run(*args, data=None, cwd=ROOT):
    return subprocess.check_output(args, input=data, cwd=cwd, stderr=subprocess.PIPE).decode()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def proto_json(value):
    """grpcurl builds may preserve proto field spelling; compare one JSON spelling."""
    if isinstance(value, list):
        return [proto_json(item) for item in value]
    if isinstance(value, dict):
        return {re.sub(r"_([a-z])", lambda match: match[1].upper(), key): proto_json(item)
                for key, item in value.items()}
    return value


def wait_for(read, accept, seconds=180):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = read()
            if accept(last):
                return last
        except (OSError, urllib.error.URLError, subprocess.CalledProcessError):
            pass
        time.sleep(1)
    raise RuntimeError(f"timed out; last result: {last}")


def prepare(out, image, kube):
    fixtures = out / "fixtures"
    fixtures.mkdir(parents=True)
    tree = fixtures / "work"
    tree.mkdir()
    model = tree / "model"
    model.mkdir()
    text = '''package cert
model er
def entity A { tags: ["live"], attributes: [def attribute id { type: int, isKey: true }] }
def entity B { attributes: [def attribute id { type: int, isKey: true }] }
def relation authored_link { tags: ["live"], from: er.entity.A, to: er.entity.B,
  cardinality: { from: "0..*", to: "1" },
  join: [{ from: er.entity.A.id, to: er.entity.B.id }] }
'''
    (model / "cert").mkdir()
    (model / "cert/model.ttrm").write_text(text)
    for path, body in {
        "reports/live/report.yaml": "id: live\ntitle: {en: Live}\nversion: 1\nprogram: er.query.live\n",
        "reports/live/template.xlsx": "synthetic workbook bytes\n",
        "forms/live.yaml": "target: {entity: er.entity.A}\nfields: [{name: id, required: true}]\n",
        "layouts/live.ttrg": "graph live { model: er, objects: [er.entity.A, er.entity.B] }\n",
    }.items():
        target = model / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
    run("git", "init", "-q", "-b", "main", str(tree))
    run("git", "config", "user.name", "MW certification fixture", cwd=tree)
    run("git", "config", "user.email", "fixture@example.invalid", cwd=tree)
    # Synthetic fixture commits, deliberately not the user's development identity.
    def commit(message):
        run("git", "add", ".", cwd=tree)
        env = dict(os.environ, GIT_AUTHOR_DATE="2026-11-01T00:00:00Z", GIT_COMMITTER_DATE="2026-11-01T00:00:00Z")
        subprocess.check_call(["git", "-c", "commit.gpgsign=false", "commit", "-qm", message], cwd=tree, env=env)
        return run("git", "rev-parse", "HEAD", cwd=tree).strip()
    refs = {"main": commit("valid default")}
    run("git", "checkout", "-qb", "preview", cwd=tree)
    (model / "cert/preview.ttrm").write_text("package cert\nmodel er\ndef entity Preview { attributes: [def attribute id { type: int }] }\n")
    refs["preview"] = commit("valid preview")
    run("git", "checkout", "-qb", "broken", "main", cwd=tree)
    (model / "cert/broken.ttrm").write_text("package cert\nmodel er\ndef entity Broken { @@@ invalid\n")
    refs["broken"] = commit("refused preview")
    run("git", "checkout", "-q", "main", cwd=tree)
    bare = fixtures / "repo.git"
    run("git", "clone", "-q", "--bare", str(tree), str(bare))
    # ConfigMap volumes cannot retain empty directories; loose refs preserve refs/heads for JGit.
    for ref, sha in refs.items():
        target = bare / "refs/heads" / ref
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(sha + "\n")
    files = {"repo.git/" + str(p.relative_to(bare)): p.read_bytes()
             for p in bare.rglob("*") if p.is_file() and "hooks" not in p.relative_to(bare).parts}
    files["fs/filesystem/model.ttrm"] = b"package filesystem\nmodel er\ndef entity Local { attributes: [def attribute id { type: int }] }\n"
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w:gz") as tar:
        body = b"package archive\nmodel er\ndef entity Archived { attributes: [def attribute id { type: int }] }\n"
        member = tarfile.TarInfo("archive/model.ttrm")
        member.size = len(body)
        tar.addfile(member, io.BytesIO(body))
    files["http/model.tar.gz"] = archive.getvalue()
    sources = [
        {"id": "git", "type": "git", "remote-uri": "file:///cert/repo.git", "branch": "main", "subdirectory": "model",
         "local-checkout-dir": "/tmp/mw-p1-cert-checkout"},
        {"id": "local", "type": "filesystem", "path": "/cert/fs"},
        {"id": "archive", "type": "http", "url": "http://127.0.0.1:8080/model.tar.gz"},
    ]
    files["application.conf"] = ('include classpath("application.conf")\nmetadata.sources = ' + json.dumps(sources) + "\n").encode()
    items = [{"key": f"f{i}", "path": name} for i, name in enumerate(sorted(files))]
    config = {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "fixture", "namespace": NAMESPACE},
              "binaryData": {f"f{i}": base64.b64encode(files[name]).decode() for i, name in enumerate(sorted(files))}}
    save(out / "fixture.json", config)
    values = {"image": {"repository": image.split("@")[0], "tag": "unused"}, "secretEnv": [],
              "resources": {"requests": {"cpu": "25m", "memory": "256Mi"}},
              "extraEnv": [{"name": "JAVA_TOOL_OPTIONS", "value": "-Dconfig.file=/cert/application.conf"},
                           {"name": "GRPC_REFLECTION_ENABLED", "value": "true"},
                           {"name": "METADATA_REFRESH_INTERVAL_SECONDS", "value": "0"}]}
    save(out / "values.json", values)
    rendered = run("helm", "template", "mw-cert", "services/metadata/k8s", "-n", NAMESPACE,
                   "-f", str(out / "values.json"), "--show-only", "templates/deployment.yaml")
    (out / "chart-deployment.yaml").write_text(rendered)
    deployment = json.loads(run(*kube, "create", "--dry-run=client", "--validate=false", "-f", "-", "-o", "json", data=rendered.encode()))
    deployment["metadata"]["namespace"] = NAMESPACE
    pod = deployment["spec"]["template"]["spec"]
    pod["containers"][0]["image"] = image
    pod["containers"][0]["volumeMounts"] = [{"name": "fixture", "mountPath": "/cert", "readOnly": True}]
    pod["containers"].append({"name": "archive-server", "image": "registry.k8s.io/e2e-test-images/busybox:1.29-4",
                              "command": ["httpd", "-f", "-p", "8080", "-h", "/cert/http"],
                              "volumeMounts": [{"name": "fixture", "mountPath": "/cert", "readOnly": True}],
                              "resources": {"requests": {"cpu": "5m", "memory": "8Mi"}, "limits": {"memory": "32Mi"}}})
    # Projected ConfigMap directories are symlinks. LocalFsStorage intentionally walks regular
    # directories, so materialize the synthetic tree before starting either reader.
    pod["initContainers"] = [{"name": "materialize-fixture", "image": "registry.k8s.io/e2e-test-images/busybox:1.29-4",
                               "command": ["sh", "-ec", "cp -RL /input/* /cert/"],
                               "volumeMounts": [{"name": "fixture-input", "mountPath": "/input", "readOnly": True},
                                                {"name": "fixture", "mountPath": "/cert"}],
                               "resources": {"requests": {"cpu": "5m", "memory": "8Mi"}, "limits": {"memory": "32Mi"}}}]
    pod["volumes"] = [{"name": "fixture-input", "configMap": {"name": "fixture", "items": items}},
                      {"name": "fixture", "emptyDir": {}}]
    save(out / "deployment.json", deployment)
    save(out / "fixture-refs.json", refs)
    return deployment, refs


@contextlib.contextmanager
def forward(kube, name, out):
    sockets = [socket.socket(), socket.socket()]
    for sock in sockets:
        sock.bind(("127.0.0.1", 0))
    ports = [sock.getsockname()[1] for sock in sockets]
    for sock in sockets:
        sock.close()
    with (out / "port-forward.log").open("a") as log:
        proc = subprocess.Popen([*kube, "-n", NAMESPACE, "port-forward", "deployment/" + name,
                                 f"{ports[0]}:7260", f"{ports[1]}:7261"], stdout=log, stderr=log)
        try:
            yield ports
        finally:
            proc.terminate()
            proc.wait(timeout=15)


def certify(kube, deployment, refs, out, record):
    name = deployment["metadata"]["name"]
    with forward(kube, name, out) as (http_port, grpc_port):
        def http(path, code=200, method="GET"):
            request = urllib.request.Request(f"http://127.0.0.1:{http_port}" + path, method=method)
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    status, body = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, body = error.code, error.read()
            assert status == code, (path, status, body.decode()[:500])
            return json.loads(body) if body.startswith((b"{", b"[")) else body.decode()
        def rpc(method, request=None):
            return proto_json(json.loads(run("grpcurl", "-max-time", "10", "-plaintext", "-emit-defaults", "-d",
                                             json.dumps(request or {}), f"127.0.0.1:{grpc_port}", SERVICE + method)))
        wait_for(lambda: http("/status"), lambda result: result.get("ready"))
        status = http("/status")
        save(out / "status-default.json", status)
        assert not status["allow_non_default_branch"]
        sources = {s["id"]: s for s in status["sources"]}
        assert all(sources[s]["loaded"] for s in ("git", "local", "archive")), sources
        assert sources["git"]["commit"] == refs["main"], sources
        assert {sources[s]["source_type"] for s in ("git", "local", "archive")} == {"git", "filesystem", "http"}
        record("three declared source types loaded with exact Git main commit")
        grpc = rpc("GetStatus")
        save(out / "grpc-status-default.json", grpc)
        assert grpc["modelVersion"] == status["model_version"]
        assert grpc["serving"]["modelVersion"] == status["serving"]["model_version"]
        assert {s["id"] for s in grpc["sources"]} >= {"git", "local", "archive"}
        record("HTTP/GetStatus serving version and configured source ids agree")
        refused = http("/refresh?branch=preview", 409, "POST")
        assert refused["status"] == "rejected"
        assert http("/status")["model_version"] == status["model_version"]
        rpc_refused = rpc("Refresh", {"branch": "preview"})
        assert rpc_refused["status"] == "rejected"
        record("HTTP and RPC preview gate refuse without changing served version")
        selected = rpc("ResolveTags", {"tags": ["live"]})
        save(out / "resolve-tags.json", selected)
        assert len(selected["objects"]) == 5 and selected["complete"], selected
        assert selected["packages"] == ["cert"]
        assert not rpc("ResolveTags", {"tags": ["unknown"]})["objects"]
        vocab = http("/model/vocabulary?tags=live")
        assert sorted(e["name"] for e in vocab["entities"]) == ["A", "B"]
        assert all(len(e["attributes"]) == 1 for e in vocab["entities"])
        http("/model/vocabulary?agent=legacy", 400)
        record("exact five-object closure, package cover, unknown tag, vocabulary and agent refusal")
        bundle = rpc("GetModel", {"packages": ["cert"]})["model"]
        assert len(bundle["relations"]) == 1
        relation = bundle["relations"][0]
        snapshot = rpc("GetSnapshot")["snapshot"]
        entry = next(e for e in snapshot["objects"] if "relation" in e)
        detail = rpc("GetObject", {"qualifiedName": entry["objectDescriptor"]["qualifiedName"]})["relation"]
        assert relation == entry["relation"] == detail and relation["name"] == "authored_link"
        assert len(relation["joinPairs"]) == 1
        record("authored relation name/endpoints/cardinality/join pairs agree across three RPC surfaces")
        assert http("/model/index")["modelVersion"] == status["model_version"]
        assert http("/model/graph")["nodes"]
        assert http("/model/object?qname=er.entity.A")["object"]["qname"] == "er.entity.A"
        assert http("/model/reports")["reports"][0]["id"] == "live"
        report = http("/model/report?id=live")
        assert hashlib.sha256(base64.b64decode(report["template"])).hexdigest() == report["sha256"]
        form = http("/model/forms")["forms"][0]
        assert form["name"] == "live" and form["sha"] == refs["main"] and form["form"]["fields"][0]["required"]
        assert http("/model/layouts")["layouts"][0]["name"] == "live"
        assert len(http("/model/layout?name=live")["objects"]) == 2
        for path, code in (("/model/report", 400), ("/model/report?id=missing", 404), ("/model/layout", 400), ("/model/layout?name=missing", 404)):
            http(path, code)
        record("index/graph/object, authored report/form/layout and 400/404 read contracts")
    # Gate-on checks use a new candidate Pod; they never change the serving workload.
    container = deployment["spec"]["template"]["spec"]["containers"][0]
    container["env"].append({"name": "METADATA_ALLOW_NON_DEFAULT_BRANCH", "value": "true"})
    run(*kube, "apply", "-f", "-", data=json.dumps(deployment).encode())
    run(*kube, "-n", NAMESPACE, "rollout", "status", "deployment/" + name, "--timeout=180s")
    with forward(kube, name, out) as (http_port, grpc_port):
        def request(path, code=200):
            try:
                with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{http_port}" + path, method="POST"), timeout=30) as response:
                    status, body = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, body = error.code, error.read()
            assert status == code, (path, status, body.decode()[:500])
            return json.loads(body)
        wait_for(lambda: urllib.request.urlopen(f"http://127.0.0.1:{http_port}/ready", timeout=5).status, lambda code: code == 200)
        request("/refresh?branch=bad..ref", 400)
        record("gate-on malformed ref refused with HTTP 400")
        preview = request("/refresh?branch=preview")
        assert preview["status"] == "ok" and preview["last_attempt"]["commit"] == refs["preview"], preview
        broken = request("/refresh?branch=broken")
        save(out / "refused-refresh.json", broken)
        assert broken["status"] == "failed" and broken["kept_previous"], broken
        assert broken["last_attempt"]["branch"] == "broken"
        assert broken["serving"]["model_version"] == preview["serving"]["model_version"]
        retained = request("/refresh?source_id=git")
        assert retained["status"] == "ok" and retained["last_attempt"]["commit"] == refs["preview"], retained
        restored = request("/refresh?branch=main")
        assert restored["status"] == "ok" and restored["last_attempt"]["commit"] == refs["main"], restored
        record("gate-on preview, strict refusal, exact tracked-ref rollback and default recovery")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, choices=["bp-dsk"])
    parser.add_argument("--image", required=True, help="ghcr.io/collite/metadata@sha256:<digest>")
    parser.add_argument("--output", type=Path, required=True, help="new directory below this worktree's build/")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"ghcr\.io/collite/metadata@sha256:[0-9a-f]{64}", args.image):
        parser.error("use the immutable published metadata digest")
    out = args.output.resolve()
    if not out.is_relative_to(ROOT / "build") or out.exists():
        parser.error("output must be a new directory below build/")
    out.mkdir(parents=True)
    kube = ["kubectl", "--context", args.context, "--request-timeout=30s"]
    deployment, refs = prepare(out, args.image, kube)
    if args.prepare_only:
        print(f"prepared {out}; no resources created")
        return
    results = []
    def record(message):
        results.append(message)
        print("PASS " + message, flush=True)
    before = json.loads(run(*kube, "-n", "ttr-server", "get", "deployment", "metadata", "-o", "json"))
    save(out / "serving-before.json", before)
    # Refuse namespace reuse: cleanup must never delete somebody else's resources.
    namespace = {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": NAMESPACE, "labels": {"purpose": "mw-p1-certification"}}}
    run(*kube, "create", "-f", "-", data=json.dumps(namespace).encode())
    succeeded = False
    try:
        run(*kube, "apply", "-f", str(out / "fixture.json"))
        refusal = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": "no-source", "namespace": NAMESPACE},
                   "spec": {"restartPolicy": "Never", "containers": [{"name": "metadata", "image": args.image,
                       "env": [{"name": "METADATA_GIT_REMOTE_URI", "value": ""}],
                       "resources": {"requests": {"cpu": "25m", "memory": "128Mi"}, "limits": {"memory": "512Mi"}}}]}}
        run(*kube, "create", "-f", "-", data=json.dumps(refusal).encode())
        pod = wait_for(lambda: json.loads(run(*kube, "-n", NAMESPACE, "get", "pod", "no-source", "-o", "json")),
                       lambda p: (p.get("status", {}).get("containerStatuses") or [{}])[0].get("state", {}).get("terminated") is not None)
        save(out / "no-source.json", pod)
        assert pod["status"]["containerStatuses"][0]["state"]["terminated"]["exitCode"] == 1
        log = run(*kube, "-n", NAMESPACE, "logs", "no-source")
        (out / "no-source.log").write_text(log)
        assert "no_model_source_configured" in log
        assert all(marker not in log for marker in ("[OTEL-CONFIG]", "Responding at", "Metadata gRPC server started"))
        record("missing-source image exits 1 before telemetry/listeners")
        run(*kube, "apply", "-f", str(out / "deployment.json"))
        run(*kube, "-n", NAMESPACE, "rollout", "status", "deployment/" + deployment["metadata"]["name"], "--timeout=240s")
        certify(kube, deployment, refs, out, record)
        succeeded = True
    finally:
        with contextlib.suppress(subprocess.CalledProcessError):
            (out / "candidate.log").write_text(run(*kube, "-n", NAMESPACE, "logs", "deployment/" + deployment["metadata"]["name"], "-c", "metadata"))
            save(out / "candidate-pods.json", json.loads(run(*kube, "-n", NAMESPACE, "get", "pods", "-o", "json")))
        run(*kube, "delete", "namespace", NAMESPACE, "--wait=true", "--timeout=180s")
        after = json.loads(run(*kube, "-n", "ttr-server", "get", "deployment", "metadata", "-o", "json"))
        save(out / "serving-after.json", after)
        assert after["spec"] == before["spec"] and after["metadata"]["uid"] == before["metadata"]["uid"]
        assert after["status"].get("readyReplicas") == after["spec"]["replicas"]
        record("temporary namespace removed; serving metadata spec/identity unchanged and ready")
        save(out / "results.json", {"image": args.image, "context": args.context, "refs": refs,
                                    "passed_all": succeeded, "passed": results})
    print(f"PASS {len(results)} certification groups; receipts: {out}")


if __name__ == "__main__":
    main()
