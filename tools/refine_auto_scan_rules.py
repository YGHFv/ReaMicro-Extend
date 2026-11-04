#!/usr/bin/env python3
"""按逐项核验的目标补充类型、调用链约束，保留规则全集和原有 opcode/字符串校验。"""

import argparse
import json
import re
from pathlib import Path

import hostsym
import retarget_profile as rp
from rc3_retarget import load_code


def normalized(signature):
    return re.sub(r'L[^;]+;', lambda m: m[0] if m[0].startswith(('Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/')) else 'L*;', signature)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--rules', type=Path, required=True)
    parser.add_argument('--failures', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path, required=True)
    args = parser.parse_args()
    profile = rp.parse_profile(args.profile)
    classes = profile['classes'] | profile['typeAliases']
    by_actual = {}
    for logical, actual in classes.items():
        by_actual.setdefault(actual, logical)
    code = load_code('target', args.apk, args.cache_dir)
    rules = json.loads(args.rules.read_text(encoding='utf-8'))
    failures = json.loads(args.failures.read_text(encoding='utf-8'))
    if isinstance(failures, dict):
        failures = [item['baselineFailure'] for item in failures['items']]
    failed = {entry.split(' candidates')[0] for entry in failures}
    changes = []

    def shapes(actual):
        return sorted(normalized(signature) for _, signature in code[actual]['methods'])

    def calls(owner, key, depth=2):
        result = set()
        method = code.get(owner, {}).get('methods', {}).get(key)
        for op, text in method['ins'] if method else []:
            if not op.startswith('invoke-'):
                continue
            match = re.search(r'(L[^;]+;)->([^ (]+)(\(.*)$', text)
            if not match:
                continue
            target = match[1][1:-1].replace('/', '.')
            signature = match[3].replace(' ', '')
            if target.startswith(('java.', 'javax.', 'android.')):
                result.add(match[1]+'->'+match[2]+signature)
            elif depth > 0:
                result.update(calls(target, (match[2], signature), depth-1))
        return result

    for rule in rules['classes']:
        if 'class:'+rule['id'] in failed:
            rule['methodShapes'] = shapes(classes[rule['id']])
            changes.append((rule['id'], 'class-method-shapes'))
    for rule in rules['methods']:
        if 'method:'+rule['id'] not in failed:
            continue
        owner = profile['methodOwners'].get(rule['id'], classes[rule['owner']])
        name = profile['methods'][rule['id']]
        candidates = [(key, method) for key, method in code[owner]['methods'].items() if key[0] == name]
        if len(candidates) != 1:
            candidates = [(key, method) for key, method in candidates if key[1] == rule['signature']]
        assert len(candidates) == 1, (rule['id'], candidates)
        key, method = candidates[0]
        # 已核实的错误 setter 归属会改变参数类型；保持完整参数槽位而不是模糊放行。
        rule['signature'] = key[1]
        params = hostsym.parse_params(key[1])
        returned = hostsym.return_type(key[1])
        rule['types'] = [by_actual.get(token[1:-1].replace('/', '.'), '') if token.startswith('L') else '' for token in params+[returned]]
        parameter_shapes = {}
        for index, token in enumerate(params+[returned]):
            if not token.startswith('L'):
                continue
            actual = token[1:-1].replace('/', '.')
            if actual not in by_actual and actual in code:
                if index == len(params):
                    rule['returnShape'] = shapes(actual)
                else:
                    parameter_shapes[str(index)] = shapes(actual)
        if parameter_shapes:
            rule['parameterShapes'] = parameter_shapes
        stable_calls = sorted(calls(owner, key))
        if stable_calls:
            rule['stableCalls'] = stable_calls
        if rule['id'] == 'androidx.compose.foundation.layout.SizeKt#height-3ABfNKs':
            rule['numbers'] = [1, 5]
        if rule['id'] in (
            'app.zhendong.reamicro.ui.backup.components.DriveCardKt#Yun115NetDiskCard',
            'app.zhendong.reamicro.ui.setting.AboutScreenKt#AboutScreen',
        ):
            # 同形 Compose 函数以实际首个分组常量区分，不依赖混淆后的类名或方法名。
            first_constant = next(text for op, text in method['ins'] if op == 'const')
            rule['numbers'] = [int(first_constant.rsplit(',', 1)[1].strip())]
        if rule['id'] == 'org.epub.FontProvider#builtInSong':
            vm = classes['app.zhendong.reamicro.ui.reader.ReaderViewModel']
            ctor = [key for key in code[vm]['methods'] if key[0]=='<init>']
            assert len(ctor)==1
            rule['calledBy'] = {'owner':'app.zhendong.reamicro.ui.reader.ReaderViewModel',
                'signature':ctor[0][1], 'static':False, 'stable':'<init>'}
        changes.append((rule['id'], 'typed-signature-and-call-constraints'))
    assert len(changes) == len(failed), (len(changes), len(failed))
    args.rules.write_text(json.dumps(rules,ensure_ascii=False,separators=(',', ':'))+'\n',encoding='utf-8',newline='')
    print('refined',len(changes),'total',sum(len(rules[k]) for k in ('classes','methods','fields')))


if __name__ == '__main__':
    main()
