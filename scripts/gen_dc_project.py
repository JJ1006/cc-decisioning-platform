#!/usr/bin/env python3
"""Builds a Decision Center import archive for the ccApproval decision service.

Formats were copied from a Decision Center export of the Miniloan sample (see docs/runbooks/dc-api.md).
Inputs, all in git:
  rules/xom/src/main/java          XOM beans -> BOM + vocabulary (and the classes from rules/xom/target/classes)
  rules/cc-approval-rules/rules    rule packages with one plain-text BAL file per rule (*.brl)
  rules/cc-approval-rules/decision-tables/*.csv   decision tables
  rules/cc-approval-ds/decision-service.yaml     (parsed with a tiny reader: name, version, agent properties)
Output: tmp/cc-approval.zip   (one decision service project "cc-approval")

    python3 scripts/gen_dc_project.py [out.zip]
"""

import csv
import os
import pathlib
import re
import sys
import uuid
import zipfile
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parent.parent
XOM_SRC = ROOT / "rules/xom/src/main/java/ca/maplenorthern/cc/xom"
XOM_CLASSES = ROOT / "rules/xom/target/classes"
RULES = pathlib.Path(
    os.environ.get("RULES_SRC") or ROOT / "rules/cc-approval-rules"
)  # RULES_SRC: a copy with a changed rule (evals)
DS = ROOT / "rules/cc-approval-ds"
PKG = "ca.maplenorthern.cc.xom"
PROJECT = "cc-approval"
XOM_NAME = "cc-xom"
RULESET = "ccApproval"
RULEAPP = "ccApprovalApp"
OPERATION = "ccApprovalOperation"
VARSET = "ccApprovalParameters"
PACKAGES = ["eligibility", "risk", "limit", "limitcap", "product", "reasons"]
NS = uuid.UUID(
    "6f1d5c86-0d3b-4c64-9a53-4a1d9b0f0c11"
)  # stable uuids so re-imports are comparable


def uid(name):
    return str(uuid.uuid5(NS, name))


# ----------------------------------------------------------------- XOM -> BOM + voc
HIDDEN = {
    ("Applicant", "dateOfBirth"),
    ("Applicant", "sin"),
}  # not used by rules; LocalDate/PII
METHODS = {
    "Application": [("double", "tdsRatio", [], "{total debt service ratio} of {this}")],
    "Decision": [
        (
            "void",
            "addReasonCode",
            [f"{PKG}.ReasonCode"],
            "add reason code {0} to {this}",
        ),
        ("void", "escalate", [f"{PKG}.Outcome"], "escalate {this} to {0}"),
        ("void", "addFiredRule", ["string"], "record that rule {0} fired in {this}"),
    ],
}
LABELS = {  # concept and attribute wording, kept identical to docs/rules-catalog.md
    "Application": "application",
    "Applicant": "applicant",
    "Address": "address",
    "Employment": "employment",
    "CreditBureauReport": "bureau report",
    "Decision": "decision",
    "Province": "province",
    "Outcome": "outcome",
    "Product": "product",
    "AprTier": "APR tier",
    "ResidencyStatus": "residency status",
    "EmploymentStatus": "employment status",
    "Channel": "channel",
    "ReasonCode": "reason code",
}
ATTR_LABELS = {
    "bureau": "bureau report",
    "ageYears": "age in years",
    "inquiriesLast6Months": "inquiries in the last 6 months",
    "fileAgeMonths": "file age in months",
    "score": "bureau score",
    "status": "employment status",
    "aprTier": "APR tier",
    "tdsRatio": "total debt service ratio",
}


def words(camel):
    return ATTR_LABELS.get(camel) or re.sub(
        r"([A-Z])", lambda m: " " + m.group(1).lower(), camel
    )


def bom_type(t):
    t = t.strip()
    if t == "String":
        return "string"
    if t.startswith("java.") or t in ("int", "boolean", "double", "long"):
        return t
    return f"{PKG}.{t}"


def parse_xom():
    classes, enums = {}, {}
    for f in sorted(XOM_SRC.glob("*.java")):
        src = re.sub(r"/\*.*?\*/", "", f.read_text(), flags=re.DOTALL)
        name = f.stem
        if re.search(r"public enum " + name, src):
            body = src[src.index("{") + 1 :]
            head = body.split(";")[0]
            consts = re.findall(
                r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)", head, flags=re.MULTILINE
            )
            readonly = []
            m = re.search(r"public (int|String) get(\w+)\(\)", src)
            if m:
                readonly.append((m.group(1), m.group(2)[0].lower() + m.group(2)[1:]))
            enums[name] = (consts, readonly)
        else:
            fields = re.findall(
                r"^\s*private ([\w.<>]+) (\w+)(?: = [^;]+)?;", src, flags=re.MULTILINE
            )
            classes[name] = [(t, n) for t, n in fields if (name, n) not in HIDDEN]
    return classes, enums


def attr_decl(cls, t, n, readonly=False):
    ro = "readonly " if readonly else ""
    m = re.match(r"java\.util\.List<(.+)>", t)
    if m:
        el = bom_type(m.group(1))
        return f"    {ro}java.util.List {n} domain 0,* class {el};"
    return f"    {ro}{bom_type(t)} {n};"


def build_bom(classes, enums):
    out = [
        "",
        "#loadGetterSetterAsProperties",
        f'property origin "xom:/{PROJECT}/{XOM_NAME}"',
        f'property uuid "{uid("bom")}"',
        f"package {PKG};",
        "",
        "",
    ]
    for name in sorted(enums):
        consts, readonly = enums[name]
        out.append(f"class {name}\n        extends java.lang.Enum\n{{")
        out += [f"    static final {name} {c};" for c in consts]
        out += [attr_decl(name, t, n, True) for t, n in readonly]
        out += ["}", ""]
    for name in sorted(classes):
        out.append(f"class {name}\n{{")
        out += [attr_decl(name, t, n) for t, n in classes[name]]
        out.append(f"    {name}();")
        for ret, mname, params, _ in METHODS.get(name, []):
            plist = ", ".join(f"{p} arg{i + 1}" for i, p in enumerate(params))
            out.append(f"    {ret} {mname}({plist});")
        out += ["}", ""]
    return "\n".join(out) + "\n"


def build_voc(classes, enums):
    out = ["# Vocabulary Properties", f"uuid = {uid('voc')}", ""]
    for name in sorted(classes):
        out.append(f"# {PKG}.{name}")
        out.append(f"{PKG}.{name}#concept.label = {LABELS[name]}")
        for t, n in classes[name]:
            w = words(n)
            if t.startswith("java.util.List"):
                w = words(n)
            out.append(
                f"{PKG}.{name}.{n}#phrase.action = set the {w} of {{this}} to {{{w}}}"
            )
            out.append(f"{PKG}.{name}.{n}#phrase.navigation = {{{w}}} of {{this}}")
        out.append(f"{PKG}.{name}.{name}()#phrase.action = a blank {LABELS[name]}")
        for ret, mname, params, phrase in METHODS.get(name, []):
            sig = ",".join(p if p != "string" else "java.lang.String" for p in params)
            kind = "navigation" if ret != "void" else "action"
            out.append(f"{PKG}.{name}.{mname}({sig})#phrase.{kind} = {phrase}")
        out.append("")
    for name in sorted(enums):
        consts, readonly = enums[name]
        out.append(f"# {PKG}.{name}")
        out.append(f"{PKG}.{name}#concept.label = {LABELS[name]}")
        for c in consts:
            out.append(f"{PKG}.{name}.{c}#instance.label = {c}")
        for t, n in readonly:
            out.append(
                f"{PKG}.{name}.{n}#phrase.navigation = {{{words(n)}}} of {{this}}"
            )
        out.append("")
    return "\n".join(out)


# ----------------------------------------------------------------- rules
def brl_xml(name, text):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<ilog.rules.studio.model.brl:ActionRule xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" '
        'xmlns:ilog.rules.studio.model.brl="http://ilog.rules.studio/model/brl.ecore">\n'
        f"  <name>{escape(name)}</name>\n  <uuid>{uid('rule:' + name)}</uuid>\n  <locale>en_US</locale>\n"
        f"  <definition><![CDATA[{text.strip()}]]></definition>\n"
        "</ilog.rules.studio.model.brl:ActionRule>\n"
    )


def rulepackage_xml(name):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<ilog.rules.studio.model.base:RulePackage xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" '
        'xmlns:ilog.rules.studio.model.base="http://ilog.rules.studio/model/base.ecore">\n'
        f"  <name>{name}</name>\n  <uuid>{uid('pkg:' + name)}</uuid>\n"
        "</ilog.rules.studio.model.base:RulePackage>\n"
    )


# ----------------------------------------------------------------- decision tables
SCORE = "the bureau score of the bureau report of 'the application'"
TDS = "the total debt service ratio of 'the application'"
INCOME = "the annual income of the employment of 'the application'"


def cdata(s):
    return f"<![CDATA[{s}]]>"


def param(v):
    return f"<Param>{cdata(v)}</Param>"


def action_set(actions):
    """actions: list of (DefId, value or None)."""
    rows = []
    for d, v in actions:
        if v is None:
            rows.append(f'<Action DefId="{d}" Enabled="false"><Expression/></Action>')
        else:
            rows.append(
                f'<Action DefId="{d}"><Expression>{param(v)}</Expression></Action>'
            )
    return "<ActionSet>" + "".join(rows) + "</ActionSet>"


def dt_xml(name, conditions, actions, contents, resources):
    cdefs = "".join(
        f'<ConditionDefinition Id="C{i}"><ExpressionDefinition><Text>{cdata(t)}</Text></ExpressionDefinition></ConditionDefinition>'
        for i, t in enumerate(conditions)
    )
    adefs = "".join(
        f'<ActionDefinition Id="A{i}"><ExpressionDefinition><Text>{cdata(t)}</Text></ExpressionDefinition></ActionDefinition>'
        for i, t in enumerate(actions)
    )
    res = "".join(f'<Data Name="{k}">{cdata(v)}</Data>' for k, v in resources.items())
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<ilog.rules.studio.model.dt:DecisionTable xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" '
        'xmlns:ilog.rules.studio.model.dt="http://ilog.rules.studio/model/dt.ecore">\n'
        f"  <name>{name}</name>\n  <uuid>{uid('dt:' + name)}</uuid>\n  <locale>en_US</locale>\n  <definition>\n"
        '<DT xmlns="http://schemas.ilog.com/Rules/7.0/DecisionTable" Version="7.0">\n'
        '  <Body xmlns="http://schemas.ilog.com/Rules/7.0/DecisionTable">\n'
        "    <Properties>\n"
        f'      <Property Name="Lock.ApplyLocking" Type="xs:boolean">{cdata("false")}</Property>\n'
        f'      <Property Name="UI.RenderBoolean" Type="xs:boolean">{cdata("true")}</Property>\n'
        f'      <Property Name="UI.ShowLockIcon" Type="xs:boolean">{cdata("false")}</Property>\n'
        f'      <Property Name="UI.ShowRuleView" Type="xs:boolean">{cdata("false")}</Property>\n'
        "    </Properties>\n"
        f"    <Structure><ConditionDefinitions>{cdefs}</ConditionDefinitions><ActionDefinitions>{adefs}</ActionDefinitions></Structure>\n"
        f"    <Contents>{contents}</Contents>\n  </Body>\n"
        f'  <Resources DefaultLocale="en_US" xmlns="http://schemas.ilog.com/Rules/7.0/DecisionTable"><ResourceSet Locale="en_US">{res}</ResourceSet></Resources>\n'
        "</DT>\n</definition>\n</ilog.rules.studio.model.dt:DecisionTable>\n"
    )


def partition(def_id, nodes):
    """nodes: list of ((p1, p2), inner_xml)"""
    body = "".join(
        f"<Condition><Expression>{param(a)}{param(b)}</Expression>{inner}</Condition>"
        for (a, b), inner in nodes
    )
    return f'<Partition DefId="{def_id}">{body}</Partition>'


def read_csv(path):
    lines = [l for l in path.read_text().splitlines() if l and not l.startswith("#")]
    return list(csv.DictReader(lines))


def tds_bands(text):
    """'<=0.35' | '>0.35' | '>0.42 and <=0.50' -> (lower exclusive, upper inclusive)."""
    lo, hi = -1.0, 1000.0
    for op, val in re.findall(r"(<=|>)\s*([\d.]+)", text):
        if op == "<=":
            hi = float(val)
        else:
            lo = float(val)
    return lo, hi


def fmt(x):
    return f"{x:g}"


def score_tds_table():
    rows = read_csv(RULES / "decision-tables/ScoreTdsOutcome.csv")
    by_score = {}
    for r in rows:
        by_score.setdefault((int(r["score_min"]), int(r["score_max"])), []).append(r)
    score_nodes = []
    for (smin, smax), rs in sorted(by_score.items()):
        leaves = []
        for r in rs:
            if r["tds_range"] == "any":
                lo, hi = -1.0, 1000.0
            else:
                lo, hi = tds_bands(r["tds_range"])
            if r["escalate"]:
                acts = [
                    ("A0", r["escalate"]),
                    ("A1", r["reason_code"]),
                    ("A2", f'"{r["fired_rule"]}"'),
                ]
            else:
                acts = [("A0", None), ("A1", None), ("A2", None)]
            leaves.append(((fmt(lo), fmt(hi)), action_set(acts)))
        score_nodes.append(((str(smin), str(smax + 1)), partition("C1", leaves)))
    contents = partition("C0", score_nodes)
    return dt_xml(
        "ScoreTdsOutcome",
        [
            f"{SCORE} is at least <min> and less than <max>",
            f"{TDS} is more than <min> and at most <max>",
        ],
        [
            "escalate 'the decision' to <an outcome>",
            "add reason code <a reason code> to 'the decision'",
            "record that rule <a string> fired in 'the decision'",
        ],
        contents,
        {
            "Definitions(C0)#HeaderText": "bureau score",
            "Definitions(C0)[0]#HeaderText": "min",
            "Definitions(C0)[1]#HeaderText": "max",
            "Definitions(C1)#HeaderText": "total debt service ratio",
            "Definitions(C1)[0]#HeaderText": "above",
            "Definitions(C1)[1]#HeaderText": "up to",
            "Definitions(A0)#HeaderText": "outcome",
            "Definitions(A1)#HeaderText": "reason code",
            "Definitions(A2)#HeaderText": "rule",
        },
    )


def income_limit_table():
    rows = read_csv(RULES / "decision-tables/IncomeScoreLimit.csv")
    by_income = {}
    for r in rows:
        by_income.setdefault((int(r["income_min"]), r["income_max"]), []).append(r)
    inc_nodes = []
    for (imin, imax), rs in sorted(by_income.items()):
        leaves = []
        for r in rs:
            acts = [("A0", r["credit_limit"]), ("A1", f'"{r["fired_rule"]}"')]
            leaves.append(
                ((r["score_min"], str(int(r["score_max"]) + 1)), action_set(acts))
            )
        top = str(int(imax) + 1) if imax else "1000000000"
        inc_nodes.append(((str(imin), top), partition("C1", leaves)))
    contents = partition("C0", inc_nodes)
    return dt_xml(
        "IncomeScoreLimit",
        [
            f"{INCOME} is at least <min> and less than <max>",
            f"{SCORE} is at least <min> and less than <max>",
        ],
        [
            "set the credit limit of 'the decision' to <a number>",
            "record that rule <a string> fired in 'the decision'",
        ],
        contents,
        {
            "Definitions(C0)#HeaderText": "annual income",
            "Definitions(C0)[0]#HeaderText": "min",
            "Definitions(C0)[1]#HeaderText": "max",
            "Definitions(C1)#HeaderText": "bureau score",
            "Definitions(C1)[0]#HeaderText": "min",
            "Definitions(C1)[1]#HeaderText": "max",
            "Definitions(A0)#HeaderText": "credit limit",
            "Definitions(A1)#HeaderText": "rule",
        },
    )


# ----------------------------------------------------------------- ruleflow
def ruleflow_xml():
    tasks = {
        "eligibility": "RetePlus",
        "risk": "RetePlus",
        "limit": "RetePlus",
        "limitcap": "RetePlus",
        "product": "RetePlus",
        "reasons": "RetePlus",
    }
    task_xml = '<StartTask Identifier="start"/>\n'  # the OUT variable is created by its initial value (see var_xml)
    for t, mode in tasks.items():
        task_xml += (
            f'      <RuleTask ExecutionMode="{mode}" ExitCriteria="None" Identifier="{t}" Ordering="Default">'
            f'<RuleList><Package Name="{t}"/></RuleList></RuleTask>\n'
        )
    task_xml += '      <StopTask Identifier="stop"/>'
    order = [
        "start",
        "eligibility",
        "risk",
        "limit",
        "limitcap",
        "product",
        "reasons",
        "stop",
    ]
    nodes = "".join(
        f'      <TaskNode Identifier="node_{t}" Task="{t}"/>\n' for t in order
    )
    guard = cdata("the outcome of 'the decision' is null ")

    def tr(i, a, b, cond=False):
        c = f'<Conditions Language="bal">{guard}</Conditions>' if cond else ""
        end = f">{c}</Transition>" if cond else "/>"
        return f'      <Transition Identifier="transition_{i}" Source="node_{a}" Target="node_{b}"{end}\n'

    trans = (
        tr(0, "start", "eligibility")
        + tr(1, "eligibility", "risk", True)
        + tr(2, "eligibility", "stop")
        + tr(3, "risk", "limit", True)
        + tr(4, "risk", "stop")
        + tr(5, "limit", "limitcap")
        + tr(6, "limitcap", "product")
        + tr(7, "product", "reasons")
        + tr(8, "reasons", "stop")
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<ilog.rules.studio.model.ruleflow:RuleFlow xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" '
        'xmlns:ilog.rules.studio.model.ruleflow="http://ilog.rules.studio/model/ruleflow.ecore">\n'
        f"  <name>ccApproval</name>\n  <uuid>{uid('rfl')}</uuid>\n  <locale>en_US</locale>\n  <categories>any</categories>\n"
        '  <rfModel>\n<Ruleflow xmlns="http://schemas.ilog.com/Rules/7.0/Ruleflow">\n  <Body>\n    <TaskList>\n      '
        + task_xml
        + "\n    </TaskList>\n    <NodeList>\n"
        + nodes
        + "    </NodeList>\n    <TransitionList>\n"
        + trans
        + "    </TransitionList>\n  </Body>\n  <Properties>\n    <imports>"
        + cdata(f"use {PKG};\n")
        + "</imports>\n  </Properties>\n</Ruleflow>\n</rfModel>\n</ilog.rules.studio.model.ruleflow:RuleFlow>\n"
    )


# ----------------------------------------------------------------- project files
def project_xml():
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
\t<name>{PROJECT}</name>
\t<comment></comment>
\t<projects>
\t\t<project>{XOM_NAME}</project>
\t</projects>
\t<buildSpec>
\t\t<buildCommand>
\t\t\t<name>ilog.rules.studio.model.ruleBuilder</name>
\t\t\t<arguments>
\t\t\t</arguments>
\t\t</buildCommand>
\t</buildSpec>
\t<natures>
\t\t<nature>ilog.rules.studio.model.decisionProject</nature>
\t\t<nature>ilog.rules.studio.model.operationProject</nature>
\t\t<nature>ilog.rules.studio.model.ruleNature</nature>
\t</natures>
</projectDescription>
"""


def ruleproject_xml():
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<ilog.rules.studio.model.base:RuleProject xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:com.ibm.rules.studio.model.decisionservice="http://com.ibm.rules.studio/model/decisionservice.ecore" xmlns:com.ibm.rules.studio.model.validation="http://com.ibm.rules.studio/model/validation.ecore" xmlns:ilog.rules.studio.model.base="http://ilog.rules.studio/model/base.ecore" xmlns:ilog.rules.studio.model.bom="http://ilog.rules.studio/model/bom.ecore" xmlns:ilog.rules.studio.model.query="http://ilog.rules.studio/model/query.ecore" xmlns:ilog.rules.studio.model.rule="http://ilog.rules.studio/model/rule.ecore" xmlns:ilog.rules.studio.model.xom="http://ilog.rules.studio/model/xom.ecore" buildMode="DecisionEngine" isADecisionService="true" migrationFlag="3" MigratedToOperationId="{uid("dop")}">
  <name>{PROJECT}</name>
  <uuid>{uid("project")}</uuid>
  <outputLocation>output</outputLocation>
  <categories>any</categories>
  <paths xsi:type="ilog.rules.studio.model.bom:BOMPath" pathID="BOM">
    <entries xsi:type="ilog.rules.studio.model.bom:BOMEntry" name="model" url="platform:/{PROJECT}/bom/model.bom" origin="xom:/{PROJECT}/{XOM_NAME}"/>
  </paths>
  <paths xsi:type="ilog.rules.studio.model.xom:XOMPath" pathID="XOM">
    <entries xsi:type="ilog.rules.studio.model.xom:LibraryXOMPathEntry" name="org.eclipse.jdt.launching.JRE_CONTAINER" url="file:org.eclipse.jdt.launching.JRE_CONTAINER" kind="LIBRARY"/>
    <entries xsi:type="ilog.rules.studio.model.xom:SystemXOMPathEntry" name="{XOM_NAME}" url="platform:/{XOM_NAME}" kind="JAVA_PROJECT"/>
  </paths>
  <modelFolders xsi:type="ilog.rules.studio.model.base:SourceFolder">
    <name>rules</name>
  </modelFolders>
  <modelFolders xsi:type="ilog.rules.studio.model.rule:TemplateFolder">
    <name>templates</name>
  </modelFolders>
  <modelFolders xsi:type="ilog.rules.studio.model.bom:BOMFolder">
    <name>bom</name>
  </modelFolders>
  <modelFolders xsi:type="ilog.rules.studio.model.query:QueryFolder">
    <name>queries</name>
  </modelFolders>
  <modelFolders xsi:type="ilog.rules.studio.model.base:ResourceFolder">
    <name>resources</name>
  </modelFolders>
  <modelFolders xsi:type="com.ibm.rules.studio.model.decisionservice:OperationFolder">
    <name>deployment</name>
  </modelFolders>
  <modelFolders xsi:type="com.ibm.rules.studio.model.validation:ValidationFolder">
    <name>.validation</name>
  </modelFolders>
</ilog.rules.studio.model.base:RuleProject>
"""


def var_xml():
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<ilog.rules.studio.model.base:VariableSet xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:ilog.rules.studio.model.base="http://ilog.rules.studio/model/base.ecore">
  <name>{VARSET}</name>
  <uuid>{uid("var")}</uuid>
  <variables name="application" type="{PKG}.Application" initialValue="" verbalization="the application"/>
  <variables name="decision" type="{PKG}.Decision" initialValue="new {PKG}.Decision()" verbalization="the decision"/>
</ilog.rules.studio.model.base:VariableSet>
"""


def dop_xml():
    href = f"../rules/{VARSET}.var#{uid('var')}"
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<com.ibm.rules.studio.model.decisionservice:Operation xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:com.ibm.rules.studio.model.decisionservice="http://com.ibm.rules.studio/model/decisionservice.ecore" xmlns:ilog.rules.studio.model.query.extractor="http://ilog.rules.studio/model/query/extractor.ecore" rulesetName="{RULESET}" usingRuleflow="true" ruleflowName="ccApproval" usingExtractor="true">
  <name>{OPERATION}</name>
  <uuid>{uid("dop")}</uuid>
  <referencedVariables variableName="application" variableSetName="{VARSET}">
    <variableSet href="{href}"/>
  </referencedVariables>
  <referencedVariables variableName="decision" variableSetName="{VARSET}" direction="OUT">
    <variableSet href="{href}"/>
  </referencedVariables>
  <ruleflow href="../rules/ccApproval.rfl#{uid("rfl")}"/>
  <extractor xsi:type="ilog.rules.studio.model.query.extractor:QueryExtractor" name="{OPERATION}_extractor" validator="Default Validator"/>
  <targetRuleProject href="../../{PROJECT}#{uid("project")}"/>
</com.ibm.rules.studio.model.decisionservice:Operation>
"""


def ds_properties():
    props = {"agent.enabled": "true", "agent.description": ""}
    text = (DS / "ruleset.properties").read_text()
    for line in text.splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def dep_xml():
    base = {
        "ruleset.bom.enabled": "true",
        "ruleset.version": os.environ.get("RULESET_VERSION", "1.0"),
        "com.ibm.rules.engine.bytecode.generation": "true",
        "ruleset.trace.enabled": "false",
        "ruleset.debug.enabled": "false",
        "ruleset.status": "enabled",
    }
    base.update(ds_properties())
    props = "".join(
        f'    <properties key="{k}">\n      <value>{cdata(v)}</value>\n    </properties>\n'
        for k, v in base.items()
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<com.ibm.rules.studio.model.decisionservice:Deployment xmi:version="2.0" xmlns:xmi="http://www.omg.org/XMI" xmlns:com.ibm.rules.studio.model.decisionservice="http://com.ibm.rules.studio/model/decisionservice.ecore" ruleAppName="{RULEAPP}" managingXom="true">
  <eAnnotations source="ilog.rules.custom_properties">
    <details key="Dep_Groups" value="rtsAdministrator,rtsConfigManager"/>
    <details key="Dep_SnapshotCreationKind" value="ALWAYS"/>
  </eAnnotations>
  <name>{RULESET}</name>
  <uuid>{uid("dep")}</uuid>
  <targets label="Decision Service Execution"/>
  <operations operationName="{OPERATION}">
    <operation href="{OPERATION}.dop#{uid("dop")}"/>
{props}  </operations>
  <versionPolicies label="Increment minor version numbers" ruleset="INCREMENT_MINOR" recurrent="true">
    <description>{cdata("Updates the minor version for each ruleset. Makes the new version available but retains previous versions.")}</description>
  </versionPolicies>
  <versionPolicies label="Use the base version numbers" recurrent="true">
    <description>{cdata("Uses the numbers provided in the deployment configuration. Replaces the latest version of each ruleset with this release. Used for hot fixes or development.")}</description>
  </versionPolicies>
  <versionPolicies label="The user can define the version numbers" ruleset="MANUAL" default="true" recurrent="true">
    <description>{cdata("Allows you to enter your own version numbers. Used for hot fixes or updates to an earlier release.")}</description>
  </versionPolicies>
</com.ibm.rules.studio.model.decisionservice:Deployment>
"""


B2XA = f"""
<b2x:translation xmlns:b2x="http://schemas.ilog.com/JRules/1.3/Translation" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://schemas.ilog.com/JRules/1.3/Translation ilog/rules/schemas/1_3/b2x.xsd">
    <id>{uid("b2xa")}</id>
    <lang>ARL</lang>
</b2x:translation>"""


def xom_zip_bytes():
    import io

    if not XOM_CLASSES.exists():
        sys.exit("build the XOM first: mvn -B -ntp -pl rules/xom package")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(XOM_CLASSES.rglob("*.class")):
            z.write(f, f.relative_to(XOM_CLASSES).as_posix())
    return buf.getvalue()


def main():
    out = pathlib.Path(
        sys.argv[1] if len(sys.argv) > 1 else ROOT / "tmp/cc-approval.zip"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    classes, enums = parse_xom()
    files = {
        ".project": project_xml(),
        ".ruleproject": ruleproject_xml(),
        "bom/model.bom": build_bom(classes, enums),
        "bom/model_en.voc": build_voc(classes, enums),
        "bom/model.b2xa": B2XA,
        f"rules/{VARSET}.var": var_xml(),
        "rules/ccApproval.rfl": ruleflow_xml(),
        f"deployment/{OPERATION}.dop": dop_xml(),
        f"deployment/{RULESET}.dep": dep_xml(),
        "rules/risk/ScoreTdsOutcome.dta": score_tds_table(),
        "rules/limit/IncomeScoreLimit.dta": income_limit_table(),
    }
    for pkg in PACKAGES:
        files[f"rules/{pkg}/.rulepackage"] = rulepackage_xml(pkg)
        for brl in (
            sorted((RULES / "rules" / pkg).glob("*.brl"))
            if (RULES / "rules" / pkg).exists()
            else []
        ):
            files[f"rules/{pkg}/{brl.stem}.brl"] = brl_xml(brl.stem, brl.read_text())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for d in ("templates/", "queries/", ".validation/"):
            z.writestr(f"{PROJECT}/{d}", "")
        z.writestr(f"{PROJECT}/resources/xom-libraries/{XOM_NAME}.zip", xom_zip_bytes())
        for name, content in files.items():
            z.writestr(f"{PROJECT}/{name}", content)
    print(f"wrote {out} ({len(files)} project files)")


if __name__ == "__main__":
    main()
