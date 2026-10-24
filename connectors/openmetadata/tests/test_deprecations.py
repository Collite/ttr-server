# SPDX-License-Identifier: Apache-2.0
from ttr_connect_openmetadata.mapping import deprecations
from ttr_connect_openmetadata.model import Delta, Plan


def test_missing_and_delta_removed_exclude_live_renames() -> None:
    delta = Delta.model_validate(
        {
            "since": "s1",
            "removed": ["md.measure.gone", "cn.kind.old", "cn.kind.still"],
            "renamed": [{"from": "cn.kind.old", "to": "cn.kind.new"}],
        }
    )
    result = deprecations(
        {"cn.kind.old", "er.entity.lost", "cn.kind.still", "db.retail.old", "db.retail.old.id"},
        {"cn.kind.new", "cn.kind.still"},
        delta,
    )
    assert [(op.kind, op.qname) for op in result] == [
        ("table", "db.retail.old"),
        ("column", "db.retail.old.id"),
        ("glossaryTerm", "er.entity.lost"),
        ("metric", "md.measure.gone"),
    ]


def test_unresolved_rename_still_deprecates_and_union_deduplicated() -> None:
    delta = Delta.model_validate(
        {
            "since": "s1",
            "removed": ["cn.kind.old", "cn.kind.old"],
            "renamed": [{"from": "cn.kind.old", "to": "cn.kind.missing"}],
        }
    )
    assert len(deprecations({"cn.kind.old"}, set(), delta)) == 1
    assert deprecations(set(), set()) == []


def test_no_delete_operation_in_schema() -> None:
    assert "delete" not in str(Plan.model_json_schema()).lower()


def test_unmaterialized_structural_ids_are_not_glossary_deprecations() -> None:
    assert (
        deprecations(
            {
                "pkg.cn",
                "area.retail",
                "md.dimension.Calendar.day",
                "md.cubelet.purchases",
                "er2db_entity.sales",
                "er.relation.sale_buyer",
            },
            set(),
        )
        == []
    )
