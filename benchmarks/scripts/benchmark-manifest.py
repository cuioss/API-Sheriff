#!/usr/bin/env python3
"""Benchmark execution manifest: generate it from the build, then consume it in CI.

The CI coverage step used to carry a hand-written list of the benchmark goals that must
produce a summary document, plus a hand-written table of the goals deliberately not run.
Both mirrored `benchmarks/pom.xml` with nothing enforcing agreement, and both had already
drifted. This script removes the mirror: `generate` derives the goal set from the Maven
model itself, and `summarise` renders the CI job summary from what `generate` wrote.

`generate` reads three authoritative sources and nothing else:

  * `benchmarks/pom.xml` -- which `exec-maven-plugin` executions run a k6 script, in which
    order, and which property (if any) suppresses each one.
  * the resolved value of every such skip property, handed over by Maven on the command
    line so a `-Dskip.benchmark.upload.large=true` override is reflected. An execution
    whose skip property was not handed over is a hard error, so a new suppressible goal
    cannot be added without wiring its value through.
  * the k6 scripts themselves -- each script names the summary document it writes through
    its own `BENCHMARK_NAME`, so the manifest's goal names are the names k6 will actually
    use rather than a third copy of them.

A k6 script present in the script directory but referenced by no execution is reported as
`unwired` rather than dropped, so "deliberately not wired" never reads as "forgotten".

Manifest schema (`benchmarks/target/benchmark-execution-manifest.json`):

    {
      "schema_version": 1,
      "profile": "benchmark",
      "executions": [
        {"goal": "healthLiveCheck", "execution_id": "run-k6-health-live-benchmark",
         "script": "health_live.js", "state": "enabled",
         "skip_property": null, "reason": null},
        ...
      ]
    }

`state` is one of `enabled`, `skipped` or `unwired`.
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

#: The k6 script argument shape an execution passes to the containerised k6 runner.
SCRIPT_ARGUMENT = re.compile(r"^/scripts/(?P<name>[\w.\-]+\.js)$")

#: A script that names its summary document with a single module-level constant.
STATIC_NAME = re.compile(r"const\s+BENCHMARK_NAME\s*=\s*'(?P<name>[^']+)'")

#: A script that selects its summary name from a mode switch (passthrough_relay.js).
MODE_NAME = re.compile(r"case\s+'(?P<mode>[^']+)'\s*:\s*return\s*\{\s*benchmarkName:\s*'(?P<name>[^']+)'",
                       re.DOTALL)

#: The reason marker an unwired script carries in its file header.
UNWIRED_REASON = re.compile(r"@unwiredReason\s+(?P<reason>.+?)\s*(?:\n\s*\*\s*\n|\n\s*\*/)", re.DOTALL)

#: A `<skip>` element that defers to a property, e.g. `${skip.benchmark.upload.large}`.
PROPERTY_REFERENCE = re.compile(r"^\$\{(?P<name>[^}]+)\}$")

STATE_ENABLED = "enabled"
STATE_SKIPPED = "skipped"
STATE_UNWIRED = "unwired"


class ManifestError(RuntimeError):
    """A defect in the build model the manifest cannot paper over."""


def _text(element, path):
    """Returns the stripped text of ``path`` under ``element``, or ``None``."""
    if element is None:
        return None
    found = element.find(path, MAVEN_NS)
    if found is None or found.text is None:
        return None
    return found.text.strip()


def _local_name(tag):
    return tag.rsplit("}", 1)[-1]


def _profile(root, profile_id):
    for profile in root.findall("m:profiles/m:profile", MAVEN_NS):
        if _text(profile, "m:id") == profile_id:
            return profile
    raise ManifestError(f"benchmarks/pom.xml declares no <profile> with id '{profile_id}'")


def _module_properties(root):
    properties = {}
    container = root.find("m:properties", MAVEN_NS)
    if container is not None:
        for child in container:
            properties[_local_name(child.tag)] = (child.text or "").strip()
    return properties


def _exec_executions(profile):
    for plugin in profile.findall("m:build/m:plugins/m:plugin", MAVEN_NS):
        if _text(plugin, "m:artifactId") != "exec-maven-plugin":
            continue
        for execution in plugin.findall("m:executions/m:execution", MAVEN_NS):
            yield execution


def _script_argument(configuration):
    if configuration is None:
        return None
    for argument in configuration.findall("m:arguments/m:argument", MAVEN_NS):
        match = SCRIPT_ARGUMENT.match((argument.text or "").strip())
        if match:
            return match.group("name")
    return None


def _environment(configuration):
    environment = {}
    container = configuration.find("m:environmentVariables", MAVEN_NS)
    if container is not None:
        for child in container:
            environment[_local_name(child.tag)] = (child.text or "").strip()
    return environment


def _resolve_skip(configuration, execution_id, skip_properties):
    """Returns ``(skipped, skip_property)`` for one execution.

    An execution with no ``<skip>`` runs whenever the profile is active. An execution whose
    ``<skip>`` defers to a property is resolved against the values Maven handed over; a
    property that was not handed over is fatal rather than assumed false, so a new
    suppressible goal cannot silently report as enabled.
    """
    raw = _text(configuration, "m:skip")
    if raw is None:
        return False, None
    reference = PROPERTY_REFERENCE.match(raw)
    if not reference:
        return raw.lower() == "true", None
    name = reference.group("name")
    if name not in skip_properties:
        raise ManifestError(
            f"execution '{execution_id}' is suppressed by property '{name}', but that property's"
            f" resolved value was not handed over. Add"
            f" --skip-property {name}=${{{name}}} to the generate-benchmark-manifest execution"
            f" in benchmarks/pom.xml.")
    return skip_properties[name].lower() == "true", name


def _script_source(script_dir, script):
    path = os.path.join(script_dir, script)
    if not os.path.isfile(path):
        raise ManifestError(f"k6 script '{script}' is referenced by an execution but is not on disk at {path}")
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _goal_name(source, script, environment):
    """Resolves the summary-document name the script will write.

    A script naming itself with a single constant answers directly. A script selecting its
    name from a mode switch is resolved through the mode its execution passes in the
    environment; an unresolvable mode is fatal, because guessing would mislabel the goal.
    """
    static = STATIC_NAME.search(source)
    if static:
        return static.group("name")

    modes = {mode.lower(): name for mode, name in MODE_NAME.findall(source)}
    if not modes:
        raise ManifestError(
            f"k6 script '{script}' declares neither a BENCHMARK_NAME constant nor a mode switch,"
            f" so the summary document it writes cannot be derived")
    for value in environment.values():
        if value.lower() in modes:
            return modes[value.lower()]
    raise ManifestError(
        f"k6 script '{script}' selects its benchmark name from modes {sorted(modes)}, but its"
        f" execution passes none of them in the environment")


def _unwired_names(source, script):
    static = STATIC_NAME.search(source)
    if static:
        return [static.group("name")]
    names = sorted({name for _, name in MODE_NAME.findall(source)})
    if not names:
        raise ManifestError(
            f"k6 script '{script}' declares no benchmark name, so it cannot be reported as unwired")
    return names


def _unwired_reason(source):
    match = UNWIRED_REASON.search(source)
    if not match:
        return None
    return " ".join(part.strip().lstrip("*").strip() for part in match.group("reason").splitlines()).strip()


def generate(pom_path, script_dir, output_path, profile_id, skip_properties):
    root = ElementTree.parse(pom_path).getroot()
    profile = _profile(root, profile_id)
    properties = _module_properties(root)

    executions = []
    wired_scripts = set()

    for execution in _exec_executions(profile):
        configuration = execution.find("m:configuration", MAVEN_NS)
        script = _script_argument(configuration)
        if script is None:
            continue
        execution_id = _text(execution, "m:id") or "(unnamed)"
        wired_scripts.add(script)
        skipped, skip_property = _resolve_skip(configuration, execution_id, skip_properties)
        source = _script_source(script_dir, script)
        goal = _goal_name(source, script, _environment(configuration))
        reason = properties.get(f"{skip_property}.reason") if skip_property else None
        executions.append({
            "goal": goal,
            "execution_id": execution_id,
            "script": script,
            "state": STATE_SKIPPED if skipped else STATE_ENABLED,
            "skip_property": skip_property,
            "reason": reason if skipped else None,
        })

    for script in sorted(os.listdir(script_dir)):
        if not script.endswith(".js") or script in wired_scripts:
            continue
        source = _script_source(script_dir, script)
        reason = _unwired_reason(source)
        for goal in _unwired_names(source, script):
            executions.append({
                "goal": goal,
                "execution_id": None,
                "script": script,
                "state": STATE_UNWIRED,
                "skip_property": None,
                "reason": reason,
            })

    if not any(entry["state"] == STATE_ENABLED for entry in executions):
        raise ManifestError(
            f"the '{profile_id}' profile resolved to no executing k6 goal — refusing to write a"
            f" manifest that would make every goal's absence look expected")

    manifest = {"schema_version": 1, "profile": profile_id, "executions": executions}
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")

    executing = [entry["goal"] for entry in executions if entry["state"] == STATE_ENABLED]
    print(f"Benchmark execution manifest written to {output_path}: "
          f"{len(executing)} executing goal(s), "
          f"{sum(1 for e in executions if e['state'] == STATE_SKIPPED)} skipped, "
          f"{sum(1 for e in executions if e['state'] == STATE_UNWIRED)} unwired.")
    return 0


def _not_run_reason(entry):
    if entry["state"] == STATE_UNWIRED:
        return entry["reason"] or "Wired to no Maven goal; no reason declared — add an @unwiredReason tag to the script."
    if entry["reason"]:
        return entry["reason"]
    return (f"No reason declared — add a `{entry['skip_property']}.reason` property to benchmarks/pom.xml."
            if entry["skip_property"] else "No reason declared.")


def _skipped_by(entry):
    if entry["state"] == STATE_UNWIRED:
        return "not wired"
    return f"`{entry['skip_property']}`" if entry["skip_property"] else "an inline `<skip>`"


def summarise(manifest_path, results_dir, benchmark_outcome, summary_file):
    lines = []
    if not os.path.isfile(manifest_path):
        lines.append("## Benchmark coverage")
        lines.append("")
        lines.append(f"> **The execution manifest at `{manifest_path}` was never generated**, so which "
                     "goals were expected cannot be stated. The build did not reach the manifest step.")
        _emit(lines, summary_file)
        print(f"::error::benchmark execution manifest absent at {manifest_path}.")
        return 1 if benchmark_outcome == "success" else 0

    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    executions = manifest["executions"]

    expected = [entry for entry in executions if entry["state"] == STATE_ENABLED]
    not_run = [entry for entry in executions if entry["state"] != STATE_ENABLED]

    missing = []
    lines.append("## Benchmark coverage")
    lines.append("")
    lines.append("| Benchmark | Result |")
    lines.append("| --- | --- |")
    for entry in expected:
        goal = entry["goal"]
        if os.path.isfile(os.path.join(results_dir, f"{goal}-summary.json")):
            lines.append(f"| `{goal}` | ran |")
        else:
            lines.append(f"| `{goal}` | **DID NOT RUN** |")
            missing.append(goal)

    lines.append("")
    lines.append("### Deliberately not run")
    lines.append("")
    if not_run:
        lines.append("| Benchmark | Skipped by | Reason |")
        lines.append("| --- | --- | --- |")
        for entry in not_run:
            lines.append(f"| `{entry['goal']}` | {_skipped_by(entry)} | {_not_run_reason(entry)} |")
    else:
        lines.append(f"Every one of the {len(expected)} declared goals executes; none is suppressed or unwired.")

    if benchmark_outcome != "success":
        lines.append("")
        if missing:
            lines.append(f"> **Maven failed and the suite was TRUNCATED**: goals {', '.join(missing)} produced "
                         f"no summary because the run aborted before reaching them. The DID NOT RUN rows above "
                         f"are a consequence of that failure, not {len(missing)} independent problems — fix the "
                         f"reported Maven error first, then re-read this table.")
        else:
            lines.append("> **Maven failed AFTER every expected goal produced a summary.** No goal was lost to "
                         "truncation, so the failure lies outside goal execution (post-processing, the baseline "
                         "comparison, or container teardown). The results above are complete and usable.")

    _emit(lines, summary_file)

    print(f"Benchmark coverage: {len(missing)} of {len(expected)} expected summary document(s) absent "
          f"(benchmark step outcome: {benchmark_outcome}).")

    # Only fail on a missing summary when Maven itself reported success: when the benchmark step
    # already failed, its own error is the actionable signal and this step must not mask it.
    if missing and benchmark_outcome == "success":
        print(f"::error::{len(missing)} expected benchmark summary document(s) absent although the "
              f"benchmark step succeeded: {', '.join(missing)}.")
        return 1
    return 0


def _emit(lines, summary_file):
    text = "\n".join(lines) + "\n"
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(text)
    else:
        sys.stdout.write(text)


def _skip_property(raw):
    if "=" not in raw:
        raise argparse.ArgumentTypeError(f"--skip-property expects name=value, got '{raw}'")
    name, value = raw.split("=", 1)
    return name, value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate_parser = subparsers.add_parser(
        "generate", help="derive the benchmark execution manifest from the Maven model")
    generate_parser.add_argument("--pom", required=True, help="path to benchmarks/pom.xml")
    generate_parser.add_argument("--k6-script-dir", required=True, help="directory holding the k6 scripts")
    generate_parser.add_argument("--output", required=True, help="manifest output path")
    generate_parser.add_argument("--profile", default="benchmark", help="the Maven profile to read")
    generate_parser.add_argument("--skip-property", action="append", default=[], type=_skip_property,
                                 metavar="NAME=VALUE",
                                 help="a skip property's Maven-resolved value; repeatable")

    summarise_parser = subparsers.add_parser(
        "summarise", help="render the CI coverage summary from the manifest and the written results")
    summarise_parser.add_argument("--manifest", required=True, help="manifest path")
    summarise_parser.add_argument("--results-dir", required=True, help="directory holding k6 summary documents")
    summarise_parser.add_argument("--benchmark-outcome", required=True,
                                  help="the benchmark step's outcome, e.g. success or failure")
    summarise_parser.add_argument("--summary-file", default=None,
                                  help="file to append the markdown to (defaults to stdout)")

    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            return generate(args.pom, args.k6_script_dir, args.output, args.profile, dict(args.skip_property))
        return summarise(args.manifest, args.results_dir, args.benchmark_outcome, args.summary_file)
    except ManifestError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
