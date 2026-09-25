#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def run(command):
    print('+ '+' '.join(map(str,command)),flush=True)
    subprocess.run([str(x) for x in command],cwd=ROOT,check=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--gradle',action='store_true',help='Use CI Gradle JVM toolchain instead of installed kotlinc')
    args=parser.parse_args()
    run([sys.executable,ROOT/'tools/validate_project.py'])
    run([sys.executable,'-m','unittest','discover','-s','tests','-p','test_*.py','-v'])
    if args.gradle:
        run(['gradle',':gkd-core:verifyGkdRuntime',':gkd-core:verifyAndroidHost','--no-daemon','--console=plain'])
        return
    for tool in ('kotlinc','javac','java'):
        if not shutil.which(tool):
            raise SystemExit('Required host tool missing: '+tool+'; CI can use --gradle')
    with tempfile.TemporaryDirectory(prefix='gkd-host-') as directory:
        work=Path(directory);jar=work/'tests.jar';classes=work/'classes';classes.mkdir()
        sources=sorted((ROOT/'gkd-core/src/main/kotlin').rglob('*.kt'))+sorted((ROOT/'gkd-core/src/test/kotlin').rglob('*.kt'))
        run(['kotlinc',*sources,'-jvm-target','17','-include-runtime','-d',jar])
        run(['java','-Dfile.encoding=UTF-8','-jar',jar,'tests/selector-corpus.b64','tests/gkd-corpus.bin'])
        java=sorted((ROOT/'app/src/main/java').rglob('*.java'))+sorted((ROOT/'tests/android-host').rglob('*.java'))
        run(['javac','-encoding','UTF-8','--release','17','-cp',jar,'-d',classes,*java])
        import os
        run(['java','-Dfile.encoding=UTF-8','-Dtest.assets='+str(ROOT/'app/src/main/assets'),'-cp',str(classes)+os.pathsep+str(jar),'BackendTests'])
    print('Host tests passed; Android SDK build, real GPU/native OCR and device performance are NOT validated here')

if __name__=='__main__':
    main()
