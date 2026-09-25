#!/usr/bin/env python3
"""Export complete release diagnostics, then gate publishing on the real lint outcome.

Collection deliberately returns successfully for a failing lint run so Actions can
upload the evidence.  --check is the separate, fail-closed publishing gate.
"""
from __future__ import annotations

import argparse
import html
import json
import shutil
from pathlib import Path
import xml.etree.ElementTree as ET

REPORT_NAMES = tuple('lint-results-release.' + ext for ext in ('xml', 'txt', 'html'))
INTERMEDIATE_TEXT = Path('app/build/intermediates/lint_intermediate_text_report/release/'
                         'lintReportRelease/lint-results-release.txt')
OUTCOMES = ('success', 'failure', 'cancelled', 'skipped')
SUMMARY_LIMIT = 500_000


def clean(project: Path) -> None:
    """Remove only our previous lint outputs, not any project or signing inputs."""
    project = project.resolve()
    for name in REPORT_NAMES:
        (project / 'app/build/reports' / name).unlink(missing_ok=True)
    (project / INTERMEDIATE_TEXT).unlink(missing_ok=True)
    for name in ('lint-release.log', 'lint-summary.md', 'lint-summary.json'):
        (project / 'dist' / name).unlink(missing_ok=True)
    folder = project / 'dist/lint-reports'
    if folder.is_symlink():
        folder.unlink()
    elif folder.exists():
        shutil.rmtree(folder)


def collect(project: Path, outcome: str) -> dict:
    if outcome not in OUTCOMES:
        raise ValueError('Unknown lint step outcome')
    project = project.resolve()
    dist = project / 'dist'
    reports = dist / 'lint-reports'
    reports.mkdir(parents=True, exist_ok=True)
    copied = []
    copy_errors = []
    for name in REPORT_NAMES:
        source = project / 'app/build/reports' / name
        if name.endswith('.txt') and not source.is_file():
            source = project / INTERMEDIATE_TEXT
        destination = reports / name
        # A second collection must not keep artifacts from an earlier observation.
        destination.unlink(missing_ok=True)
        if source.is_file():
            try:
                if not source.resolve().is_relative_to(project):
                    raise OSError('Report source is outside the project')
                shutil.copyfile(source, destination)
                copied.append(name)
            except OSError as exc:
                copy_errors.append(f'{name}: {type(exc).__name__}')
    raw = dist / 'lint-release.log'
    destination = reports / raw.name
    destination.unlink(missing_ok=True)
    if raw.is_file():
        shutil.copyfile(raw, destination)
        copied.append(raw.name)

    issues = []
    problem = ''
    errors = warnings = None
    try:
        xml = reports / 'lint-results-release.xml'
        if not xml.is_file():
            raise ValueError('No complete release XML report was produced')
        if xml.stat().st_size > 16 * 1024 * 1024:
            raise ValueError('XML report exceeds parser size budget; full file retained')
        data = xml.read_bytes()
        if b'<!DOCTYPE' in data.upper():
            raise ValueError('Unexpected XML document type')
        root = ET.fromstring(data)
        if root.tag != 'issues' or any(c.tag != 'issue' for c in root):
            raise ValueError('Unrecognized lint XML structure')
        for issue in root.findall('issue'):
            severity = issue.get('severity', '').lower()
            if severity not in ('fatal', 'error', 'warning', 'informational', 'information', 'ignore'):
                raise ValueError('Unknown lint issue severity')
            locations = [dict(location.attrib) for location in issue.findall('location')]
            issues.append({'id': issue.get('id', 'Unknown'), 'severity': severity,
                           'message': issue.get('message', ''), 'locations': locations})
        errors = sum(i['severity'] in ('fatal', 'error') for i in issues)
        warnings = sum(i['severity'] == 'warning' for i in issues)
    except (OSError, ValueError, ET.ParseError) as exc:
        # Missing/broken output cannot become a misleading zero-error green run.
        problem = str(exc)
    if copy_errors:
        problem = '; '.join(([problem] if problem else []) + copy_errors)
    passed = outcome == 'success' and errors == 0 and not problem
    result = {'outcome': outcome, 'errors': errors, 'warnings': warnings,
              'passed': passed, 'report_problem': problem, 'artifacts': copied, 'issues': issues}
    (dist / 'lint-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    lines = ['### Release Lint 诊断', '',
             '检查结果 ' + ('通过' if passed else '未通过 不发布安装包'),
             '任务状态 ' + outcome,
             f'错误 {errors if errors is not None else "未知"}  警告 {warnings if warnings is not None else "未知"}', '']
    if problem:
        lines.extend(['报告读取问题 ' + html.escape(problem), ''])
    if outcome != 'success':
        lines.extend(['Gradle 检查没有成功 即使已有 XML 也不能视为通过 请查看原始日志', ''])
    for item in issues:
        lines.append('**' + html.escape(item['severity'] + ' ' + item['id']) + '**')
        lines.append(html.escape(item['message']).replace('\n', '  \n'))
        for loc in item['locations']:
            lines.append(html.escape(loc.get('file', '') + ':' + loc.get('line', '?') + ':' + loc.get('column', '?')))
        lines.append('')
    lines.append('完整 XML TXT HTML 原始日志见 lint-reports 构建附件 本摘要不代替完整报告')
    summary = '\n'.join(lines) + '\n'
    if len(summary.encode('utf-8')) > SUMMARY_LIMIT:
        summary = summary.encode('utf-8')[:SUMMARY_LIMIT].decode('utf-8', errors='ignore')
        summary += '\n\n摘要达到显示上限 全部诊断仍在完整附件和 lint-summary.json 中\n'
    (dist / 'lint-summary.md').write_text(summary, encoding='utf-8')
    shutil.copyfile(dist / 'lint-summary.json', reports / 'lint-summary.json')
    shutil.copyfile(dist / 'lint-summary.md', reports / 'lint-summary.md')
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=Path.cwd())
    parser.add_argument('--outcome', choices=OUTCOMES)
    parser.add_argument('--clean', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.clean:
        if args.check or args.outcome:
            parser.error('--clean must be used separately')
        clean(args.project)
        return 0
    if args.outcome is None:
        parser.error('--outcome is required')
    result = collect(args.project, args.outcome)
    print('Release Lint: outcome={outcome} errors={errors} warnings={warnings} passed={passed}'.format(**result))
    if result['report_problem']:
        print(result['report_problem'])
    return 1 if args.check and not result['passed'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
