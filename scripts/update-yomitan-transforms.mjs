#!/usr/bin/env node
// Regenerates Hiroi's Japanese deinflection rules from Yomitan's own
// (ext/js/language/ja/japanese-transforms.js), as JSON for the Kotlin port of
// Yomitan's LanguageTransformer (app/.../japanese/LanguageTransformer.kt).
//
// It also records what Yomitan's transformer returns for a set of sample words,
// and YomitanParityTest checks that the Kotlin port returns exactly the same.
//
// Usage: node scripts/update-yomitan-transforms.mjs [yomitan-release-tag]
//    or: podman run --rm -v "$PWD":/work:Z -w /work docker.io/library/node:24-slim \
//          node scripts/update-yomitan-transforms.mjs

import {mkdir, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const TAG = process.argv[2] ?? '26.11.2.0';
const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const RULES_OUT = join(REPO_ROOT, 'app/src/main/resources/io/github/kvth/hiroi/japanese/japanese-transforms.json');
const SAMPLES_OUT = join(REPO_ROOT, 'app/src/test/resources/io/github/kvth/hiroi/japanese/yomitan-samples.json');
const FILES = [
    'ext/js/language/language-transforms.js',
    'ext/js/language/language-transformer.js',
    'ext/js/language/ja/japanese-transforms.js',
];
const SAMPLE_WORDS = [
    '食べた', '食べなかった', '食べませんでした', '食べさせられなかった', '食べちゃった', '食べれば', '食べよう',
    '書いた', '書かれた', '書いとく', '泳いだ', '話した', '死んだ', '遊んだ', '行った', '行っちまった',
    '勉強した', '勉強できる', '来た', 'こなかった', '高くなかった', '高ければ', 'よかった', '行かなきゃ',
    '読んだり', '知らん', '食べず', '食べなさい', '問うた', 'いらっしゃいます', '高すぎる', 'すごく',
];

const dir = await mkdtemp(join(tmpdir(), 'yomitan-'));
try {
    for (const file of FILES) {
        const url = `https://raw.githubusercontent.com/yomidevs/yomitan/${TAG}/${file}`;
        const response = await fetch(url);
        if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
        await mkdir(dirname(join(dir, file)), {recursive: true});
        await writeFile(join(dir, file), await response.text());
    }
    // language-transformer.js only uses log.warn, and Yomitan's logger pulls in half of Yomitan.
    await mkdir(join(dir, 'ext/js/core'), {recursive: true});
    await writeFile(join(dir, 'ext/js/core/log.js'), 'export const log = {warn: (e) => console.warn(e)};\n');

    const {japaneseTransforms} = await import(pathToFileURL(join(dir, 'ext/js/language/ja/japanese-transforms.js')));
    const {LanguageTransformer} = await import(pathToFileURL(join(dir, 'ext/js/language/language-transformer.js')));

    const rules = {
        source: `https://github.com/yomidevs/yomitan/blob/${TAG}/ext/js/language/ja/japanese-transforms.js`,
        license: 'GPL-3.0-or-later, Copyright (C) 2024-2026 Yomitan Authors',
        conditions: Object.entries(japaneseTransforms.conditions).map(([name, condition]) => ({
            name,
            isDictionaryForm: condition.isDictionaryForm,
            ...(condition.subConditions ? {subConditions: condition.subConditions} : {}),
        })),
        transforms: Object.entries(japaneseTransforms.transforms).map(([id, transform]) => ({
            id,
            name: transform.name,
            description: transform.description ?? '',
            rules: transform.rules.map((rule) => serializeRule(id, rule)),
        })),
    };
    await writeFile(RULES_OUT, JSON.stringify(rules, null, 1) + '\n');

    const transformer = new LanguageTransformer();
    transformer.addDescriptor(japaneseTransforms);
    const samples = Object.fromEntries(SAMPLE_WORDS.map((word) => [
        word,
        transformer.transform(word).map(({text, trace}) => [text, trace.map((frame) => frame.transform)]),
    ]));
    await writeFile(SAMPLES_OUT, JSON.stringify(samples, null, 1) + '\n');

    const ruleCount = rules.transforms.reduce((n, t) => n + t.rules.length, 0);
    console.log(`Yomitan ${TAG}: ${rules.transforms.length} transforms, ${ruleCount} rules, ${rules.conditions.length} conditions`);
} finally {
    await rm(dir, {recursive: true, force: true});
}

// The Kotlin port matches plain suffixes and whole words instead of regular
// expressions, so refuse anything that isn't one of those.
function serializeRule(id, rule) {
    const {type, isInflected, conditionsIn, conditionsOut} = rule;
    const source = isInflected.source;
    let inflected;
    let deinflected;
    if (type === 'suffix' && source.endsWith('$')) {
        inflected = source.slice(0, -1);
        deinflected = rule.deinflected;
    } else if (type === 'wholeWord' && source.startsWith('^') && source.endsWith('$')) {
        inflected = source.slice(1, -1);
        deinflected = rule.deinflect('');
    } else {
        throw new Error(`${id}: unsupported rule ${type} /${source}/`);
    }
    if (!inflected || /[\\^$.*+?()[\]{}|]/.test(inflected)) throw new Error(`${id}: not a plain ${type}: /${source}/`);
    return {type, inflected, deinflected, conditionsIn, conditionsOut};
}
