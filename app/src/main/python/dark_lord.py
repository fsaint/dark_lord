"""Small Python facade used by Dark Lord's embedded runtime."""
import contextlib
import io
import json


class _Facade:
    def __init__(self, bridge):
        self._bridge = bridge

    def call_tool(self, name, arguments=None):
        return dict(self._bridge.callTool(name, arguments or {}))

    def artifact_create(self, data, mime_type):
        return self._bridge.artifactCreate(data, mime_type)

    def artifact_read(self, artifact_id):
        return bytes(self._bridge.artifactRead(artifact_id))

    def artifact_metadata(self, artifact_id):
        return dict(self._bridge.artifactMetadata(artifact_id))


def execute(source, arguments, bridge):
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments) if arguments else {}
        except json.JSONDecodeError:
            arguments = {"value": arguments}
    output = io.StringIO()
    namespace = {"dark_lord": _Facade(bridge), "arguments": arguments or {}}
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        exec(source, namespace, namespace)
    return json.dumps({"stdout": output.getvalue()[:65536]})
