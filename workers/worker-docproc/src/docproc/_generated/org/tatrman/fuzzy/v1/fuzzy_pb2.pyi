from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SourceTag(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MEMBER: _ClassVar[SourceTag]
    VOCABULARY: _ClassVar[SourceTag]
    DECLARED: _ClassVar[SourceTag]
    METADATA: _ClassVar[SourceTag]
    LEARNED: _ClassVar[SourceTag]

class TargetClass(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    TARGET_CLASS_UNSPECIFIED: _ClassVar[TargetClass]
    TARGET_CLASS_MODEL_OBJECT: _ClassVar[TargetClass]
    TARGET_CLASS_MEMBER: _ClassVar[TargetClass]
    TARGET_CLASS_OPERATOR: _ClassVar[TargetClass]
    TARGET_CLASS_GROUNDING_TRIGGER: _ClassVar[TargetClass]
    TARGET_CLASS_STRING_PREDICATE: _ClassVar[TargetClass]
MEMBER: SourceTag
VOCABULARY: SourceTag
DECLARED: SourceTag
METADATA: SourceTag
LEARNED: SourceTag
TARGET_CLASS_UNSPECIFIED: TargetClass
TARGET_CLASS_MODEL_OBJECT: TargetClass
TARGET_CLASS_MEMBER: TargetClass
TARGET_CLASS_OPERATOR: TargetClass
TARGET_CLASS_GROUNDING_TRIGGER: TargetClass
TARGET_CLASS_STRING_PREDICATE: TargetClass

class BatchMatchRequest(_message.Message):
    __slots__ = ("spans", "locale")
    SPANS_FIELD_NUMBER: _ClassVar[int]
    LOCALE_FIELD_NUMBER: _ClassVar[int]
    spans: _containers.RepeatedCompositeFieldContainer[SpanQuery]
    locale: str
    def __init__(self, spans: _Optional[_Iterable[_Union[SpanQuery, _Mapping]]] = ..., locale: _Optional[str] = ...) -> None: ...

class SpanQuery(_message.Message):
    __slots__ = ("query", "categories", "limit")
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CATEGORIES_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    query: str
    categories: _containers.RepeatedScalarFieldContainer[str]
    limit: int
    def __init__(self, query: _Optional[str] = ..., categories: _Optional[_Iterable[str]] = ..., limit: _Optional[int] = ...) -> None: ...

class BatchMatchResponse(_message.Message):
    __slots__ = ("results",)
    RESULTS_FIELD_NUMBER: _ClassVar[int]
    results: _containers.RepeatedCompositeFieldContainer[FuzzyMatchResponse]
    def __init__(self, results: _Optional[_Iterable[_Union[FuzzyMatchResponse, _Mapping]]] = ...) -> None: ...

class FuzzyStatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class FuzzyStatusResponse(_message.Message):
    __slots__ = ("ready", "vocabulary_version", "categories", "warnings", "layer_versions", "engine_version")
    READY_FIELD_NUMBER: _ClassVar[int]
    VOCABULARY_VERSION_FIELD_NUMBER: _ClassVar[int]
    CATEGORIES_FIELD_NUMBER: _ClassVar[int]
    WARNINGS_FIELD_NUMBER: _ClassVar[int]
    LAYER_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    ENGINE_VERSION_FIELD_NUMBER: _ClassVar[int]
    ready: bool
    vocabulary_version: str
    categories: _containers.RepeatedCompositeFieldContainer[CategoryStatus]
    warnings: _containers.RepeatedCompositeFieldContainer[LoaderWarning]
    layer_versions: LayerVersions
    engine_version: str
    def __init__(self, ready: _Optional[bool] = ..., vocabulary_version: _Optional[str] = ..., categories: _Optional[_Iterable[_Union[CategoryStatus, _Mapping]]] = ..., warnings: _Optional[_Iterable[_Union[LoaderWarning, _Mapping]]] = ..., layer_versions: _Optional[_Union[LayerVersions, _Mapping]] = ..., engine_version: _Optional[str] = ...) -> None: ...

class CategoryStatus(_message.Message):
    __slots__ = ("category", "source", "size", "loaded_at_epoch_ms", "match_method", "entity_profile")
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    LOADED_AT_EPOCH_MS_FIELD_NUMBER: _ClassVar[int]
    MATCH_METHOD_FIELD_NUMBER: _ClassVar[int]
    ENTITY_PROFILE_FIELD_NUMBER: _ClassVar[int]
    category: str
    source: SourceTag
    size: int
    loaded_at_epoch_ms: int
    match_method: str
    entity_profile: EntityProfile
    def __init__(self, category: _Optional[str] = ..., source: _Optional[_Union[SourceTag, str]] = ..., size: _Optional[int] = ..., loaded_at_epoch_ms: _Optional[int] = ..., match_method: _Optional[str] = ..., entity_profile: _Optional[_Union[EntityProfile, _Mapping]] = ...) -> None: ...

class EntityProfile(_message.Message):
    __slots__ = ("members", "entities", "ner_model")
    MEMBERS_FIELD_NUMBER: _ClassVar[int]
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    NER_MODEL_FIELD_NUMBER: _ClassVar[int]
    members: int
    entities: _containers.RepeatedCompositeFieldContainer[EntityCount]
    ner_model: str
    def __init__(self, members: _Optional[int] = ..., entities: _Optional[_Iterable[_Union[EntityCount, _Mapping]]] = ..., ner_model: _Optional[str] = ...) -> None: ...

class EntityCount(_message.Message):
    __slots__ = ("label", "code", "members")
    LABEL_FIELD_NUMBER: _ClassVar[int]
    CODE_FIELD_NUMBER: _ClassVar[int]
    MEMBERS_FIELD_NUMBER: _ClassVar[int]
    label: str
    code: str
    members: int
    def __init__(self, label: _Optional[str] = ..., code: _Optional[str] = ..., members: _Optional[int] = ...) -> None: ...

class LoaderWarning(_message.Message):
    __slots__ = ("code", "category", "message")
    CODE_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    code: str
    category: str
    message: str
    def __init__(self, code: _Optional[str] = ..., category: _Optional[str] = ..., message: _Optional[str] = ...) -> None: ...

class MatchRequest(_message.Message):
    __slots__ = ("query", "category", "algorithm", "limit", "algorithms")
    QUERY_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    ALGORITHM_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    ALGORITHMS_FIELD_NUMBER: _ClassVar[int]
    query: str
    category: str
    algorithm: str
    limit: int
    algorithms: _containers.RepeatedCompositeFieldContainer[AlgorithmSpec]
    def __init__(self, query: _Optional[str] = ..., category: _Optional[str] = ..., algorithm: _Optional[str] = ..., limit: _Optional[int] = ..., algorithms: _Optional[_Iterable[_Union[AlgorithmSpec, _Mapping]]] = ...) -> None: ...

class AlgorithmSpec(_message.Message):
    __slots__ = ("algorithm", "min_score")
    ALGORITHM_FIELD_NUMBER: _ClassVar[int]
    MIN_SCORE_FIELD_NUMBER: _ClassVar[int]
    algorithm: str
    min_score: float
    def __init__(self, algorithm: _Optional[str] = ..., min_score: _Optional[float] = ...) -> None: ...

class FuzzyMatchResponse(_message.Message):
    __slots__ = ("matches", "is_error", "error", "matched_algorithm", "vocabulary_version", "layer_versions")
    MATCHES_FIELD_NUMBER: _ClassVar[int]
    IS_ERROR_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    MATCHED_ALGORITHM_FIELD_NUMBER: _ClassVar[int]
    VOCABULARY_VERSION_FIELD_NUMBER: _ClassVar[int]
    LAYER_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    matches: _containers.RepeatedCompositeFieldContainer[FuzzyMatch]
    is_error: bool
    error: str
    matched_algorithm: str
    vocabulary_version: str
    layer_versions: LayerVersions
    def __init__(self, matches: _Optional[_Iterable[_Union[FuzzyMatch, _Mapping]]] = ..., is_error: _Optional[bool] = ..., error: _Optional[str] = ..., matched_algorithm: _Optional[str] = ..., vocabulary_version: _Optional[str] = ..., layer_versions: _Optional[_Union[LayerVersions, _Mapping]] = ...) -> None: ...

class LayerVersions(_message.Message):
    __slots__ = ("lexicon_artifact_hash", "member_index_versions", "overlay_version")
    class MemberIndexVersionsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    LEXICON_ARTIFACT_HASH_FIELD_NUMBER: _ClassVar[int]
    MEMBER_INDEX_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    OVERLAY_VERSION_FIELD_NUMBER: _ClassVar[int]
    lexicon_artifact_hash: str
    member_index_versions: _containers.ScalarMap[str, str]
    overlay_version: str
    def __init__(self, lexicon_artifact_hash: _Optional[str] = ..., member_index_versions: _Optional[_Mapping[str, str]] = ..., overlay_version: _Optional[str] = ...) -> None: ...

class Provenance(_message.Message):
    __slots__ = ("producer", "method", "raw_score", "norm", "algorithm", "distance", "token_hits", "coverage")
    PRODUCER_FIELD_NUMBER: _ClassVar[int]
    METHOD_FIELD_NUMBER: _ClassVar[int]
    RAW_SCORE_FIELD_NUMBER: _ClassVar[int]
    NORM_FIELD_NUMBER: _ClassVar[int]
    ALGORITHM_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    TOKEN_HITS_FIELD_NUMBER: _ClassVar[int]
    COVERAGE_FIELD_NUMBER: _ClassVar[int]
    producer: str
    method: str
    raw_score: float
    norm: str
    algorithm: str
    distance: int
    token_hits: _containers.RepeatedCompositeFieldContainer[TokenHit]
    coverage: float
    def __init__(self, producer: _Optional[str] = ..., method: _Optional[str] = ..., raw_score: _Optional[float] = ..., norm: _Optional[str] = ..., algorithm: _Optional[str] = ..., distance: _Optional[int] = ..., token_hits: _Optional[_Iterable[_Union[TokenHit, _Mapping]]] = ..., coverage: _Optional[float] = ...) -> None: ...

class TokenHit(_message.Message):
    __slots__ = ("query_token", "candidate_token", "kind", "distance", "query_pos", "candidate_pos")
    QUERY_TOKEN_FIELD_NUMBER: _ClassVar[int]
    CANDIDATE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    QUERY_POS_FIELD_NUMBER: _ClassVar[int]
    CANDIDATE_POS_FIELD_NUMBER: _ClassVar[int]
    query_token: str
    candidate_token: str
    kind: str
    distance: int
    query_pos: int
    candidate_pos: int
    def __init__(self, query_token: _Optional[str] = ..., candidate_token: _Optional[str] = ..., kind: _Optional[str] = ..., distance: _Optional[int] = ..., query_pos: _Optional[int] = ..., candidate_pos: _Optional[int] = ...) -> None: ...

class FuzzyMatch(_message.Message):
    __slots__ = ("candidate_id", "candidate", "score", "category", "source", "target_ref", "provenance", "match_method", "uniqueness_margin", "auto_bindable", "target_class")
    CANDIDATE_ID_FIELD_NUMBER: _ClassVar[int]
    CANDIDATE_FIELD_NUMBER: _ClassVar[int]
    SCORE_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    TARGET_REF_FIELD_NUMBER: _ClassVar[int]
    PROVENANCE_FIELD_NUMBER: _ClassVar[int]
    MATCH_METHOD_FIELD_NUMBER: _ClassVar[int]
    UNIQUENESS_MARGIN_FIELD_NUMBER: _ClassVar[int]
    AUTO_BINDABLE_FIELD_NUMBER: _ClassVar[int]
    TARGET_CLASS_FIELD_NUMBER: _ClassVar[int]
    candidate_id: str
    candidate: str
    score: float
    category: str
    source: SourceTag
    target_ref: str
    provenance: Provenance
    match_method: str
    uniqueness_margin: float
    auto_bindable: bool
    target_class: TargetClass
    def __init__(self, candidate_id: _Optional[str] = ..., candidate: _Optional[str] = ..., score: _Optional[float] = ..., category: _Optional[str] = ..., source: _Optional[_Union[SourceTag, str]] = ..., target_ref: _Optional[str] = ..., provenance: _Optional[_Union[Provenance, _Mapping]] = ..., match_method: _Optional[str] = ..., uniqueness_margin: _Optional[float] = ..., auto_bindable: _Optional[bool] = ..., target_class: _Optional[_Union[TargetClass, str]] = ...) -> None: ...

class LookupRequest(_message.Message):
    __slots__ = ("term", "categories", "target_classes", "method_override", "max_candidates")
    TERM_FIELD_NUMBER: _ClassVar[int]
    CATEGORIES_FIELD_NUMBER: _ClassVar[int]
    TARGET_CLASSES_FIELD_NUMBER: _ClassVar[int]
    METHOD_OVERRIDE_FIELD_NUMBER: _ClassVar[int]
    MAX_CANDIDATES_FIELD_NUMBER: _ClassVar[int]
    term: str
    categories: _containers.RepeatedScalarFieldContainer[str]
    target_classes: _containers.RepeatedScalarFieldContainer[TargetClass]
    method_override: str
    max_candidates: int
    def __init__(self, term: _Optional[str] = ..., categories: _Optional[_Iterable[str]] = ..., target_classes: _Optional[_Iterable[_Union[TargetClass, str]]] = ..., method_override: _Optional[str] = ..., max_candidates: _Optional[int] = ...) -> None: ...

class LookupResponse(_message.Message):
    __slots__ = ("candidates", "layer_versions", "vocabulary_version", "applied_method_override", "unknown_categories")
    CANDIDATES_FIELD_NUMBER: _ClassVar[int]
    LAYER_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    VOCABULARY_VERSION_FIELD_NUMBER: _ClassVar[int]
    APPLIED_METHOD_OVERRIDE_FIELD_NUMBER: _ClassVar[int]
    UNKNOWN_CATEGORIES_FIELD_NUMBER: _ClassVar[int]
    candidates: _containers.RepeatedCompositeFieldContainer[FuzzyMatch]
    layer_versions: LayerVersions
    vocabulary_version: str
    applied_method_override: str
    unknown_categories: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, candidates: _Optional[_Iterable[_Union[FuzzyMatch, _Mapping]]] = ..., layer_versions: _Optional[_Union[LayerVersions, _Mapping]] = ..., vocabulary_version: _Optional[str] = ..., applied_method_override: _Optional[str] = ..., unknown_categories: _Optional[_Iterable[str]] = ...) -> None: ...
