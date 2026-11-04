#!/usr/bin/env python3
"""真实宿主类型的隔离测试；仅写任务临时目录，API 替身没有任何网络实现。"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('label')
args = parser.parse_args()
assert re.fullmatch('[a-z0-9-]+', args.label)
out = Path(os.environ['TEMP']) / 'reamicro-analysis/runtime/manual234-20261105' / args.label
out.mkdir(parents=True, exist_ok=False)
java = Path('C:/Program Files/Android/Android Studio1/jbr/bin')
sdk = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
android = sdk / 'platforms/android-36/android.jar'
probe, fixture = out / 'probe', out / 'fixture'
probe.mkdir(); fixture.mkdir()


def run(command, log):
    result = subprocess.run(list(map(str, command)), capture_output=True, timeout=180)
    (out / log).write_bytes(result.stdout + result.stderr)
    if result.returncode:
        print((result.stdout + result.stderr).decode('utf-8', errors='replace')[-5000:])
        raise SystemExit(result.returncode)
    return result.stdout


run([java/'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-cp', android, '-d', probe,
     ROOT/'tools/Rc3ProtocolProbe.java', ROOT/'tools/Manual233SourceContract.java',
     ROOT/'tools/Manual234SourceContract.java'], 'javac-probe.log')
# 宿主类型桩仅供 javac 识别描述符，不放进设备测试 dex；运行期使用真实宿主。
for name in ('y48', 'o12'):
    (fixture/(name+'.java')).write_text('public class '+name+' {}\n', encoding='utf-8', newline='')
for name in ('q5c', 'j5c'):
    (fixture/(name+'.java')).write_text('public interface '+name+' {}\n', encoding='utf-8', newline='')
(fixture/'ke6.java').write_text('''public abstract class ke6 {
    public ke6() {}
    public abstract q5c g();
    public abstract void i(j5c intent);
}
''', encoding='utf-8', newline='')
(fixture/'Manual234FakeViewModel.java').write_text('''public final class Manual234FakeViewModel extends ke6 {
    public static q5c initialState;
    public long bookId;
    @Override public q5c g() { return initialState; }
    @Override public void i(j5c intent) {}
}
''', encoding='utf-8', newline='')
(fixture/'Manual234FakeApi.java').write_text('''public final class Manual234FakeApi {
    public Object response;
    public Object request;
    public int calls;
    public Object a1(y48 request, o12 continuation) {
        this.request = request;
        calls++;
        return response;
    }
}
''', encoding='utf-8', newline='')
(fixture/'Manual234FakeRepository.java').write_text('''public final class Manual234FakeRepository {
    public Object api;
}
''', encoding='utf-8', newline='')
run([java/'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-d', fixture, *fixture.glob('*.java')], 'javac-fixture.log')
for directory, inputs in ((probe, list(probe.glob('*.class'))),
                          (fixture, list(fixture.glob('Manual234Fake*.class')))):
    run([java/'java.exe', '-cp', sdk/'build-tools/36.0.0/lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '26', '--lib', android, '--output', directory, *inputs], directory.name+'-d8.log')
    with zipfile.ZipFile(directory/'test.jar', 'x') as archive:
        archive.write(directory/'classes.dex', 'classes.dex')
remote = '/data/local/tmp/reamicro-manual234-' + args.label
subprocess.run(['adb', 'shell', 'mkdir', '-p', remote], check=True)
for path, name in ((probe/'test.jar', 'probe.jar'), (fixture/'test.jar', 'fixture.jar'),
                   (ROOT/'app/build/outputs/apk/release/app-release.apk', 'module.apk'),
                   (ROOT/'source-files/manual-edit.rmsource', 'source.rmsource')):
    subprocess.run(['adb', 'push', str(path), remote+'/'+name], check=True, stdout=subprocess.DEVNULL)
host = subprocess.check_output(['adb', 'shell', 'pm', 'path', 'app.zhendong.reamicro']).decode().strip().removeprefix('package:')
command = ('CLASSPATH='+remote+'/probe.jar app_process -Dreamicro.hostProfile=2.3.4-b1 /system/bin Manual234SourceContract '
           +host+' '+remote+'/module.apk '+remote+' '+remote+'/source.rmsource '+remote+'/fixture.jar')
output = run(['adb', 'exec-out', 'su', '-c', command], 'result.log')
text = output.decode('utf-8', errors='replace')
print(text[-6500:])
assert 'MANUAL_234_SOURCE_CONTRACT_PASS' in text, 'Native contract failed; see '+str(out/'result.log')
