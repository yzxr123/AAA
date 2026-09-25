import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class SubscriptionBundleTests(unittest.TestCase):
    def module(self):
        p = ROOT / 'tools/build_gkd_bundle.py'
        self.assertTrue(p.is_file(), 'GKD subscription is not yet imported into a real rule bundle')
        spec = importlib.util.spec_from_file_location('bundle', p)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_ad_only_groups_keep_rule_semantics(self):
        mod = self.module()
        source = {'id': 667, 'version': 592, 'globalGroups': [], 'apps': [
            {'id': 'app.example', 'name': 'Example', 'groups': [
                {'key': 1, 'name': '分段广告-信息流', 'actionCd': 2500, 'rules': [
                    {'key': 0, 'matches': '[vid="ad_more"]'},
                    {'preKeys': [0], 'matches': '[text="不感兴趣"]', 'actionDelay': 200}]},
                {'key': 2, 'name': '权限提示-通知', 'rules': '[text="允许"]'},
                {'key': 3, 'name': '功能类-看广告领奖励', 'rules': '[text="领取"]'}]}]}
        result = mod.select_ads(source)
        self.assertEqual(len(result['apps']), 1)
        self.assertEqual(result['apps'][0]['groups'], source['apps'][0]['groups'][:1])
        self.assertEqual(source['apps'][0]['groups'][0]['rules'][1]['preKeys'], [0])

    def test_shorthand_and_exclusion_preserved(self):
        mod = self.module()
        group = {'key': 0, 'name': '开屏广告-全局', 'apps': [{'id': 'bank.app', 'enable': False}],
                 'rules': '[text="跳过"][visibleToUser=true]'}
        selected = mod.select_ads({'id': 1, 'version': 1, 'apps': [], 'globalGroups': [group]})
        self.assertEqual(selected['globalGroups'], [group])
        self.assertEqual(mod.rule_count(group), 1)

    def test_actual_snapshot_counts_and_all_original_ad_fields(self):
        mod = self.module()
        source = json.loads((ROOT / 'third_party/gkd_subscription/source.json').read_text())
        selected = mod.select_ads(source)
        self.assertEqual(len(selected['apps']), 753)
        groups = [g for a in selected['apps'] for g in a['groups']] + selected['globalGroups']
        self.assertEqual(len(groups), 1334)
        self.assertEqual(sum(mod.rule_count(g) for g in groups), 2445)
        for app in selected['apps']:
            original = next(a for a in source['apps'] if a['id'] == app['id'])
            for group in app['groups']:
                self.assertEqual(group, next(g for g in original['groups'] if g['key'] == group['key']))

    def test_dependency_to_unselected_group_is_not_silently_accepted(self):
        mod = self.module()
        data = {'id': 1, 'version': 1, 'globalGroups': [], 'apps': [{'id': 'a', 'groups': [
            {'key': 1, 'name': '分段广告', 'scopeKeys': [2], 'rules': {'key': 3, 'matches': '[text="ad"]'}},
            {'key': 2, 'name': '功能类', 'rules': '[text="non-ad"]'}]}]}
        with self.assertRaisesRegex(ValueError, 'scopeKeys'):
            mod.select_ads(data)

    def test_reviewed_supplement_adds_only_app_scoped_ad_rules(self):
        mod = self.module()
        primary = {'id': 667, 'version': 593, 'globalGroups': [
            {'key': 0, 'name': '开屏广告', 'rules': '[text="跳过"]'}], 'apps': [
            {'id': 'app.example', 'name': 'Example', 'groups': [
                {'key': 1, 'name': '开屏广告', 'rules': '[vid="skip"]'}]}]}
        supplement = {'source': 'reviewed', 'globalGroups': [], 'apps': [
            {'id': 'app.example', 'name': 'Example', 'groups': [
                {'key': 9, 'name': '开屏广告-补充', 'rules': '[text^="跳过"]'}]}]}
        merged = mod.merge_supplement(primary, supplement)
        self.assertEqual(merged['globalGroups'], primary['globalGroups'])
        self.assertEqual([g['key'] for g in merged['apps'][0]['groups']], [1, 9])
        self.assertEqual(merged['apps'][0]['groups'][1]['name'], '开屏广告-补充')

        unsafe = {'globalGroups': [{'key': 2, 'name': '开屏广告', 'rules': '[text="跳过"]'}], 'apps': []}
        with self.assertRaisesRegex(ValueError, 'global'):
            mod.merge_supplement(primary, unsafe)

    def test_actual_merged_bundle_has_reviewed_second_source(self):
        mod = self.module()
        files, _ = mod.build_files()
        manifest = json.loads(files['manifest.json'])
        self.assertEqual(manifest['version'], 593)
        self.assertEqual(
            (manifest['app_count'], manifest['group_count'], manifest['rule_count'], manifest['selector_count']),
            (753, 1346, 2462, 2622),
        )
        self.assertEqual(manifest['supplement_app_count'], 11)
        self.assertEqual(manifest['sources'][1]['license'], 'Apache-2.0')

        supplement = json.loads((ROOT / 'third_party/gkd_supplement/source.json').read_text())
        self.assertEqual(supplement['globalGroups'], [])
        self.assertNotIn('com.android.bankabc', {app['id'] for app in supplement['apps']})

if __name__ == '__main__':
    unittest.main()
