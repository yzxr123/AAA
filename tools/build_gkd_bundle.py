#!/usr/bin/env python3
import argparse
import base64
import struct
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AD_CATEGORIES = ('开屏广告', '局部广告', '全屏广告', '分段广告')
SELECTOR_FIELDS = ('matches', 'anyMatches', 'excludeMatches', 'excludeAllMatches')

def is_ad_group(group):
    return group.get('name', '').split('-')[0] in AD_CATEGORIES

def rule_list(group):
    rules = group.get('rules', [])
    return rules if isinstance(rules, list) else [rules]

def rule_count(group):
    return len(rule_list(group))

def select_ads(source):
    result = {k: copy.deepcopy(v) for k, v in source.items() if k not in ('apps', 'globalGroups')}
    result['apps'] = []
    result['globalGroups'] = [copy.deepcopy(g) for g in source.get('globalGroups', []) if is_ad_group(g)]
    for app in source.get('apps', []):
        groups = [copy.deepcopy(g) for g in app.get('groups', []) if is_ad_group(g)]
        if groups:
            entry = copy.deepcopy(app)
            entry['groups'] = groups
            result['apps'].append(entry)
    for app in result['apps']:
        keys = {g['key'] for g in app['groups']}
        for group in app['groups']:
            if not set(group.get('scopeKeys', [])).issubset(keys):
                raise ValueError('scopeKeys dependency outside ad groups: ' + app['id'])
    return result

def merge_supplement(primary, supplement):
    """Merge a reviewed app-scoped supplement without widening global matching."""
    if supplement.get('globalGroups'):
        raise ValueError('supplement global rules are not allowed')
    result = copy.deepcopy(primary)
    apps = {app['id']: app for app in result.get('apps', [])}
    for addition in supplement.get('apps', []):
        package_name = addition.get('id', '')
        groups = addition.get('groups', [])
        if not package_name or not groups:
            raise ValueError('supplement app entry is incomplete')
        if any(not is_ad_group(group) for group in groups):
            raise ValueError('supplement contains a non-ad group: ' + package_name)
        target = apps.get(package_name)
        if target is None:
            target = {'id': package_name, 'name': addition.get('name', package_name), 'groups': []}
            result.setdefault('apps', []).append(target)
            apps[package_name] = target
        existing_keys = {group['key'] for group in target.get('groups', [])}
        for group in groups:
            if group.get('key') in existing_keys:
                raise ValueError('supplement group key conflict: ' + package_name)
            if group.get('scopeKeys'):
                raise ValueError('supplement scope dependency is not allowed: ' + package_name)
            target.setdefault('groups', []).append(copy.deepcopy(group))
            existing_keys.add(group['key'])
    return result

def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True) + '\n').encode('utf-8')

def build_files():
    source_path = ROOT / 'third_party/gkd_subscription/source.json'
    raw_path = ROOT / 'third_party/gkd_subscription/gkd.json5'
    provenance = json.loads((ROOT / 'third_party/gkd_subscription/source-provenance.json').read_text(encoding='utf-8'))
    if hashlib.sha256(raw_path.read_bytes()).hexdigest() != provenance['raw_sha256']:
        raise ValueError('Original GKD subscription bytes changed')
    if hashlib.sha256(source_path.read_bytes()).hexdigest() != provenance['normalized_sha256']:
        raise ValueError('Normalized GKD subscription changed without reviewed source provenance')
    supplement_path = ROOT / 'third_party/gkd_supplement/source.json'
    supplement_provenance_path = ROOT / 'third_party/gkd_supplement/source-provenance.json'
    supplement_provenance = json.loads(supplement_provenance_path.read_text(encoding='utf-8'))
    if hashlib.sha256(supplement_path.read_bytes()).hexdigest() != supplement_provenance['sha256']:
        raise ValueError('Reviewed GKD supplement changed without updated provenance')
    primary = json.loads(source_path.read_text(encoding='utf-8'))
    supplement = json.loads(supplement_path.read_text(encoding='utf-8'))
    source = merge_supplement(primary, supplement)
    selected = select_ads(source)
    files = {}
    apps = {}
    total_groups = len(selected['globalGroups'])
    total_rules = sum(rule_count(g) for g in selected['globalGroups'])
    selectors = []
    for app in selected['apps']:
        name = 'apps/' + app['id'] + '.json'
        files[name] = encoded(app)
        apps[app['id']] = {'file': name, 'groups': len(app['groups']),
                           'rules': sum(rule_count(g) for g in app['groups'])}
        total_groups += apps[app['id']]['groups']
        total_rules += apps[app['id']]['rules']
    files['global.json'] = encoded({'groups': selected['globalGroups']})
    for app in selected['apps'] + [{'id': '*', 'groups': selected['globalGroups']}]:
        for group in app['groups']:
            for index, raw_rule in enumerate(rule_list(group)):
                rule = {'matches': raw_rule} if isinstance(raw_rule, str) else raw_rule
                for field in SELECTOR_FIELDS:
                    values = rule.get(field, [])
                    if isinstance(values, str):
                        values = [values]
                    for value in values:
                        if not isinstance(value, str) or not value:
                            raise ValueError('invalid selector ' + app['id'])
                        selectors.append([app['id'], group['key'], index, field, value])
    files['manifest.json'] = encoded({
        'schema': 1, 'id': source['id'], 'version': source['version'],
        'categories': list(AD_CATEGORIES), 'apps': apps,
        'app_count': len(apps), 'group_count': total_groups, 'rule_count': total_rules,
        'selector_count': len(selectors), 'global_group_count': len(selected['globalGroups']),
        'source_sha256': hashlib.sha256(raw_path.read_bytes()).hexdigest(),
        'normalized_source_sha256': hashlib.sha256(source_path.read_bytes()).hexdigest(),
        'supplement_source_sha256': hashlib.sha256(supplement_path.read_bytes()).hexdigest(),
        'supplement_app_count': len(supplement.get('apps', [])),
        'sources': [
            {'name': provenance.get('repository', 'Lin-arm/GKD_subscription'),
             'commit': provenance.get('commit', ''), 'license': provenance.get('license', '')},
            {'name': supplement_provenance['repository'],
             'commit': supplement_provenance['commit'], 'license': supplement_provenance['license']},
        ],
        'file_sha256': {k: hashlib.sha256(v).hexdigest() for k, v in sorted(files.items())},
        'policy': 'Ad categories only; reviewed app-scoped supplement; no supplemental global rules; all excludes preserved',
    })
    corpus = '\n'.join(json.dumps(row, ensure_ascii=False) for row in selectors) + '\n'
    return files, corpus.encode('utf-8')

def fixture_bytes(value):
    if value is None: return b'n'
    if isinstance(value, bool): return b't' if value else b'f'
    if isinstance(value, (int, float)):
        return b'd' + struct.pack('>d', value)
    if isinstance(value, str):
        raw = value.encode('utf-8')
        return b's' + struct.pack('>i', len(raw)) + raw
    if isinstance(value, list):
        return b'l' + struct.pack('>i', len(value)) + b''.join(fixture_bytes(x) for x in value)
    if isinstance(value, dict):
        return b'm' + struct.pack('>i', len(value)) + b''.join(fixture_bytes(k) + fixture_bytes(v) for k,v in value.items())
    raise TypeError(type(value))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files, corpus = build_files()
    primary = json.loads((ROOT/'third_party/gkd_subscription/source.json').read_text(encoding='utf-8'))
    supplement = json.loads((ROOT/'third_party/gkd_supplement/source.json').read_text(encoding='utf-8'))
    source = merge_supplement(primary, supplement)
    fixtures = {
        'selector-corpus.jsonl': corpus,
        'selector-corpus.b64': b''.join(base64.b64encode(json.loads(row)[4].encode('utf-8'))+b'\n' for row in corpus.decode('utf-8').splitlines()),
        'gkd-corpus.bin': fixture_bytes(select_ads(source)),
    }
    target = ROOT / 'app/src/main/assets/gkd' 
    if args.check:
        actual = {p.relative_to(target).as_posix(): p.read_bytes() for p in target.rglob('*.json')}
        if actual != files:
            raise SystemExit('GKD bundle differs from original ad-group data; run tools/build_gkd_bundle.py')
        for name,data in fixtures.items():
            if (ROOT/'tests'/name).read_bytes() != data:
                raise SystemExit('test corpus differs from rule bundle: '+name)
    else:
        target.mkdir(parents=True, exist_ok=True)
        for p in target.rglob('*.json'):
            if p.relative_to(target).as_posix() not in files:
                p.unlink()
        for name, data in files.items():
            p = target / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        for name,data in fixtures.items(): (ROOT/'tests'/name).write_bytes(data)
    m = json.loads(files['manifest.json'])
    print('GKD snapshot id=%s version=%s apps=%s groups=%s rules=%s selectors=%s' % tuple(
          m[k] for k in ('id','version','app_count','group_count','rule_count','selector_count')))

if __name__ == '__main__':
    main()
