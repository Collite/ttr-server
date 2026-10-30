from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Severity(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SEVERITY_UNSPECIFIED: _ClassVar[Severity]
    INFO: _ClassVar[Severity]
    WARNING: _ClassVar[Severity]
    ERROR: _ClassVar[Severity]
SEVERITY_UNSPECIFIED: Severity
INFO: Severity
WARNING: Severity
ERROR: Severity

class ResponseMessage(_message.Message):
    __slots__ = ("severity", "code", "human_message", "source_file", "line", "column")
    SEVERITY_FIELD_NUMBER: _ClassVar[int]
    CODE_FIELD_NUMBER: _ClassVar[int]
    HUMAN_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FILE_FIELD_NUMBER: _ClassVar[int]
    LINE_FIELD_NUMBER: _ClassVar[int]
    COLUMN_FIELD_NUMBER: _ClassVar[int]
    severity: Severity
    code: str
    human_message: str
    source_file: str
    line: int
    column: int
    def __init__(self, severity: _Optional[_Union[Severity, str]] = ..., code: _Optional[str] = ..., human_message: _Optional[str] = ..., source_file: _Optional[str] = ..., line: _Optional[int] = ..., column: _Optional[int] = ...) -> None: ...
