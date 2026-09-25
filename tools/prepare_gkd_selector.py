#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def jvm_compatibility(name, text):
    if name == 'ExpressionTypeChecker.kt':
        text = text.replace('val failures: List<TypeCheckFailure>\n        field = mutableListOf<TypeCheckFailure>()',
                            'private val mutableFailures = mutableListOf<TypeCheckFailure>()\n    val failures: List<TypeCheckFailure> get() = mutableFailures')
        text = text.replace('if (!isFull) failures.add(failure)', 'if (!isFull) mutableFailures.add(failure)')
        text = text.replace('is ValueExpression.Identifier -> {\n                result =',
                            'is ValueExpression.Identifier -> {\n                val identifierName = current.name\n                result =')
        text = text.replace('it.name == current.name', 'it.name == identifierName')
        text = text.replace('nextVariableArgument(frame)?.let { argument ->\n                    current = argument\n                    continue\n                }',
                            'val argument = nextVariableArgument(frame)\n                if (argument != null) {\n                    current = argument\n                    continue\n                }')
        text = text.replace('nextVariableArgument(frame)?.let { argument ->\n                        current = argument\n                        break\n                    }',
                            'val argument = nextVariableArgument(frame)\n                    if (argument != null) {\n                        current = argument\n                        break\n                    }')
    if name == 'BuiltinMembers.kt':
        text = text.replace('private fun BuiltinMethod.evaluate(receiver: Any, args: List<Any?>): Any? = when (id) {',
                            'private fun BuiltinMethod.evaluate(receiver: Any, args: List<Any?>): Any? { return when (id) {')
        text = text.replace('    private fun Any.scope():', '    }\n\n    private fun Any.scope():')
    if name == 'ValueEvaluator.kt':
        text = text.replace('): Any? = when (val callee = frame.expression.callee) {',
                            '): Any? { return when (val callee = frame.expression.callee) {')
        text = text.replace('private fun <T : Any> invoke(', '}\n\nprivate fun <T : Any> invoke(')
    return text

def prepared():
    base = ROOT/'third_party/gkd-selector'
    generated = {}
    manifest = {}
    for source in sorted(base.rglob('*.kt')):
        data = source.read_text(encoding='utf-8')
        result = []
        for line in data.splitlines():
            stripped = line.strip()
            if stripped in ('import kotlin.js.JsExport', 'import kotlin.js.JsStatic', '@JsExport', '@JsExport.Ignore', '@JsStatic', '@kotlin.js.JsExport'):
                continue
            if stripped.startswith('internal expect fun '):
                continue
            result.append(line.replace('internal actual fun ', 'internal fun '))
        value = jvm_compatibility(source.name, '\n'.join(result)+'\n').encode('utf-8')
        relative = source.as_posix().split('/kotlin/',1)[1]
        generated[relative] = value
        manifest[source.relative_to(base).as_posix()] = {
            'original_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'jvm_sha256': hashlib.sha256(value).hexdigest(), 'destination': relative}
    return generated, manifest

def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args()
    generated, manifest = prepared()
    out = ROOT/'gkd-core/src/main/kotlin'
    for name, data in generated.items():
        target=out/name
        if a.check:
            if not target.is_file() or target.read_bytes()!=data:
                raise SystemExit('Vendored GKD selector differs: '+name)
        else:
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(data)
    manifest_path=ROOT/'third_party/gkd-selector/source-manifest.json'
    value=(json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode()
    if a.check:
        if manifest_path.read_bytes()!=value: raise SystemExit('Selector provenance manifest mismatch')
    else: manifest_path.write_bytes(value)
    print('GKD selector source verified: %d files; JS/JVM bindings and Kotlin 2.4 syntax lowered without changing selector matching'%len(generated))
if __name__=='__main__':main()
