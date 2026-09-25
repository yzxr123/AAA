#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'

def require(condition, message):
    if not condition:
        raise SystemExit('ERROR: ' + message)

def text(name):
    return (ROOT / name).read_text(encoding='utf-8')

def main():
    for path in (ROOT / 'app/src/main').rglob('*.xml'):
        ET.parse(path)

    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
    permissions = {node.get(ANDROID + 'name') for node in manifest.findall('uses-permission')}
    forbidden_permissions = {
        'android.permission.INTERNET',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.QUERY_ALL_PACKAGES',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.WRITE_SECURE_SETTINGS',
    }
    require(not (permissions & forbidden_permissions), 'Unused or privileged permission remains')

    services = {node.get(ANDROID + 'name'): node for node in manifest.findall('application/service')}
    accessibility = services.get('org.adguardian.app.service.AdAccessibilityService')
    require(accessibility is not None, 'Accessibility service missing')
    require(accessibility.get(ANDROID + 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE',
            'Accessibility service is not system-bound')
    require(accessibility.get(ANDROID + 'stopWithTask') == 'false',
            'Accessibility service task behavior changed')

    base_config = ET.parse(ROOT / 'app/src/main/res/xml/accessibility_service_config.xml').getroot()
    v31_config = ET.parse(ROOT / 'app/src/main/res/xml-v31/accessibility_service_config.xml').getroot()
    require(base_config.get(ANDROID + 'isAccessibilityTool') is None,
            'API 31-only attribute leaked into the base resource')
    require(v31_config.get(ANDROID + 'isAccessibilityTool') == 'true',
            'API 31 accessibility declaration missing')
    for config in (base_config, v31_config):
        require(config.get(ANDROID + 'canPerformGestures') == 'true'
                and config.get(ANDROID + 'canTakeScreenshot') == 'true',
                'Accessibility action or screenshot capability missing')

    application = manifest.find('application')
    require(application.get(ANDROID + 'allowBackup') == 'false', 'Backup must remain disabled')
    require(application.get(ANDROID + 'dataExtractionRules') == '@xml/data_extraction_rules',
            'Android 12 data extraction rules missing')
    require(application.get(ANDROID + 'fullBackupContent') == '@xml/backup_rules',
            'Legacy backup rules missing')
    require(application.get(ANDROID + 'roundIcon') is None,
            'Non-round legacy icon is still declared as a round icon')
    icon_hashes = {
        'mdpi': '115f8ef22bb9fb7132844423a8614316dc0f1b108bb3f1e61e2521fcc30254ab',
        'hdpi': 'c694e183fb7ff3d2f6941724e30e4a1adfd4cbf2114e3dfe890abdad1c75664b',
        'xhdpi': 'acd8421fc9e66f81f1d93c1e94649b6005ab3b661b7e7886b0ea7c50578d1531',
        'xxhdpi': 'f5de27d139f5327eec3789c89c54400cce7e5f9097a2f45187afe7a3d9f12a8d',
        'xxxhdpi': '00d255df7345a57f31fd542abd4bc4fd0a1762603af0848ec7c92e4c8439c593',
    }
    require(application.get(ANDROID + 'icon') == '@mipmap/ic_launcher', 'Original launcher icon not selected')
    for density, digest in icon_hashes.items():
        icon = ROOT / ('app/src/main/res/mipmap-' + density) / 'ic_launcher.png'
        require(icon.is_file() and hashlib.sha256(icon.read_bytes()).hexdigest() == digest,
                'Original launcher art changed: ' + density)
    require(not list((ROOT / 'app/src/main/res').glob('mipmap-anydpi*/ic_launcher.xml')),
            'An adaptive replacement hides the original launcher art')

    app_build = text('app/build.gradle')
    require("versionName = '0.7.17-b7.17'" in app_build and 'versionCode = 37' in app_build,
            'B7.17 version mismatch')
    require('compileSdk = 37' in app_build and 'targetSdk = 37' in app_build,
            'Android API 37 target missing')
    require("abiFilters.addAll(['arm64-v8a', 'x86_64'])" in app_build, 'Required phone and ChromeOS ABIs missing')
    require("implementation project(':gkd-core')" in app_build, 'GKD selector module not linked')
    require('minifyEnabled = true' in app_build and 'shrinkResources = true' in app_build,
            'Release shrinking missing')
    require('Tesseract4Android:tesseract4android:4.9.0' in app_build, 'OCR dependency differs')
    require("id 'com.android.application' version '9.2.0'" in text('build.gradle'),
            'Android 17 build plugin mismatch')

    hashes = {
        'AllRules.json': '0623ae015e6868828f391e4f2f4fae3eec1f2d83e407e19dfc197b8d96d201f9',
        'BasicRules.json': 'f905a2b066459d25ca89502536b27428940856b4e8df980d0f35a68ac88893ca',
        'ExtendedRules.json': '2cde4b84cbb7e3b38f82240d5cb1850337960480f2b857e6a1434e754cd89ce9',
    }
    for name, digest in hashes.items():
        actual = hashlib.sha256((ROOT / 'app/src/main/assets/ltt' / name).read_bytes()).hexdigest()
        require(actual == digest, 'LTT source changed unexpectedly: ' + name)

    for script in ('build_gkd_bundle.py', 'prepare_gkd_selector.py'):
        subprocess.run([sys.executable, str(ROOT / 'tools' / script), '--check'], cwd=ROOT, check=True)
    gkd = json.loads(text('app/src/main/assets/gkd/manifest.json'))
    require(gkd.get('version') == 593, 'GKD primary subscription is not v593')
    require(tuple(gkd[k] for k in ('app_count', 'group_count', 'rule_count', 'selector_count'))
            == (753, 1346, 2462, 2622), 'Merged GKD bundle count mismatch')
    require(gkd.get('supplement_app_count') == 11, 'Reviewed second rule source missing')

    production_files = list((ROOT / 'app/src/main/java').rglob('*.java'))
    production = '\n'.join(path.read_text(encoding='utf-8') for path in production_files)
    for removed in ('learning', 'flow', 'recording', 'classapp'):
        require(not (ROOT / 'app/src/main/java/org/adguardian/app' / removed).exists(),
                'Removed feature directory remains: ' + removed)
    require(not (ROOT / 'app/src/main/assets/class').exists(), 'Removed CLASS asset remains')
    for removed_type in ('LearningController', 'FlowTeachingController', 'RecordingDraft', 'ClassAdEngine'):
        require(removed_type not in production, 'Removed runtime remains: ' + removed_type)
    for removed_text in ('手动学习', '课表增强', '本演示版', '复制运行状态'):
        require(removed_text not in production, 'Removed product wording remains: ' + removed_text)

    for name in (
        'engine/RuleEngine.java', 'engine/JumpGuard.java', 'engine/SafeActionTarget.java',
        'gkd/GkdNodeAdapter.java', 'gkd/GkdRuleRepository.java', 'gkd/GkdSubscriptionEngine.java',
        'ocr/OcrFallbackController.java', 'ocr/OcrActionPolicy.java', 'ocr/OcrTextEvidence.java',
    ):
        require((ROOT / 'app/src/main/java/org/adguardian/app' / name).is_file(),
                'Required runtime missing: ' + name)

    main_activity = text('app/src/main/java/org/adguardian/app/MainActivity.java')
    require('"LTT 核心"' in main_activity and '"GKD 核心"' in main_activity,
            'Professional core labels missing')
    require('约 70%' in main_activity and '保留最近任务' in main_activity
            and '关闭后重新开启辅助功能' in main_activity,
            'Usage boundary is incomplete')
    ocr = text('app/src/main/java/org/adguardian/app/ocr/OcrFallbackController.java')
    require('ENTRY_OCR_DELAYS_MS={250L,900L,2200L}' in ocr,
            'Fast entry OCR schedule changed')

    protection = services.get('org.adguardian.app.service.ProtectionService')
    require(protection is not None and protection.get(ANDROID + 'exported') == 'false',
            'Foreground status service missing')
    require(protection.get(ANDROID + 'foregroundServiceType') == 'specialUse',
            'Foreground service type missing')
    require({'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
             'android.permission.RECEIVE_BOOT_COMPLETED'} <= permissions,
            'Foreground service declarations missing')
    require('Settings.Secure.put' not in production and 'Runtime.getRuntime().exec' not in production,
            'Automatic system authorization mutation remains')

    backend = text('tests/android-host/BackendTests.java')
    for case in ('RegressionB75Tests', 'SdkRuntimeB712Tests', 'BehaviorB715Tests', 'ProductB716Tests',
                 'RecordLogB716Tests', 'PopupSafetyB716Tests',
                 'SubscriptionCancellationB716Tests', 'SubscriptionOwnershipB716Tests'):
        require(case + '.main' in backend, 'Host behavior suite missing: ' + case)
    require('Xiachufang splash skip acts on the first rule scan without a timer' in backend,
            'Xiachufang immediate-rule regression missing')

    workflow = text('.github/workflows/build-apk.yml')
    for required in ('Build 去你的广告 B7.17', 'Qunideguanggao-B7.17.apk',
                     ':gkd-core:verifyGkdRuntime', ':gkd-core:verifyAndroidHost',
                     'platforms;android-37.0', 'assembleRelease', '52428800', '50 MiB',
                     'Collect complete lint diagnostics', 'Upload complete lint reports',
                     'Enforce Release lint result'):
        require(required in workflow, 'Active workflow missing: ' + required)
    require(workflow.index('Upload complete lint reports')
            < workflow.index('Enforce Release lint result') < workflow.index('Prepare APK'),
            'Lint evidence must precede APK publishing')
    require('abortOnError = true' in app_build
            and all(value in app_build for value in ('textReport = true', 'xmlReport = true', 'htmlReport = true')),
            'Complete lint reports are not enforced')
    require((ROOT / 'dev-signing.jks').is_file(), 'Stable development signing missing')

    model = ROOT / 'app/src/main/assets/tessdata/chi_sim.traineddata'
    if model.exists():
        require(hashlib.sha256(model.read_bytes()).hexdigest()
                == 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730',
                'OCR model changed')

    print('去你的广告 B7.17 project structure and source checks passed')
    print('GKD: v593 / 753 apps / 1346 ad groups / 2462 rules / 2622 selectors')
    print('Supplement: 11 apps / 12 app-scoped groups / no supplemental global rules')
    print('Runtime INTERNET and privileged settings permissions: none')

if __name__ == '__main__':
    main()
