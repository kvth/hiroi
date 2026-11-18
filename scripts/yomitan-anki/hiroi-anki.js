/*
 * Hiroi's side of Yomitan's Anki note building, bundled with Yomitan's own code by
 * scripts/update-yomitan-anki.mjs into app/src/main/assets/yomitan/anki.js.
 *
 * Fields are filled exactly as Yomitan fills them: Yomitan's AnkiNoteBuilder renders each
 * {marker} with Yomitan's Handlebars templates (the default ones, or the user's own) and
 * template helpers. Hiroi hands over the dictionary entry in Yomitan's format and the media it
 * stored in Anki; this file stands in for the parts of Yomitan's background page the builder
 * would ask for them.
 *
 * Kotlin (AnkiTemplates.kt) calls hiroiAnki.call(id, method, args) and gets the answer through
 * HiroiAnkiBridge.done(id, json).
 *
 * Copyright (C) 2026 Kevin Thomas, GPL-3.0-or-later (as Yomitan, whose code this bundles).
 */

import {AnkiNoteBuilder} from './ext/js/data/anki-note-builder.js';
import {getDynamicFieldMarkers, getDynamicTemplates, getStandardFieldMarkers} from './ext/js/data/anki-template-util.js';
import {AnkiTemplateRenderer} from './ext/js/templates/anki-template-renderer.js';
import pronunciationStyle from './ext/data/pronunciation-style.json';
import structuredContentStyle from './ext/data/structured-content-style.json';
import defaultTemplates from './ext/data/templates/default-anki-field-templates.handlebars';

// The renderer loads its style data with fetch; nothing else may be fetched.
const ASSETS = {
    '/data/structured-content-style.json': structuredContentStyle,
    '/data/pronunciation-style.json': pronunciationStyle,
};
globalThis.fetch = async (url) => {
    if (Object.prototype.hasOwnProperty.call(ASSETS, url)) {
        return new Response(JSON.stringify(ASSETS[url]), {status: 200, headers: {'Content-Type': 'application/json'}});
    }
    throw new Error(`No network access in Hiroi's template renderer: ${url}`);
};

/** What Hiroi stored in Anki (or read) for the note being made: file names, texts, parsed text. */
let injected = null;

/** The parts of Yomitan's background API that AnkiNoteBuilder uses, answered from `injected`. */
const api = {
    async injectAnkiNoteMedia(_timestamp, _entry, _audio, _screenshot, _clipboard, dictionaryMedia) {
        return {
            audioFileName: injected?.audio ?? null,
            screenshotFileName: injected?.screenshot ?? null,
            clipboardImageFileName: injected?.clipboardImage ?? null,
            clipboardText: injected?.clipboardText ?? null,
            // Dictionary images Hiroi stored in Anki, by dictionary and path
            dictionaryMedia: dictionaryMedia.map(({dictionary, path}) => ({
                dictionary,
                path,
                mediaType: '',
                fileName: injected?.dictionaryMedia?.find((m) => m.dictionary === dictionary && m.path === path)?.fileName ?? null,
            })),
            errors: [],
        };
    },
    async parseText(text) {
        return [{id: 'scan', source: 'scanning-parser', dictionary: null, index: 0, content: injected?.parsedText?.[text] ?? []}];
    },
};

const renderer = new AnkiTemplateRenderer(document, window);
const ready = renderer.prepare();
const builder = new AnkiNoteBuilder(api, renderer.templateRenderer);
// Yomitan reads the selection in its popup; Hiroi's is in its panel.
builder._getPopupSelectionText = () => injected?.selectionText ?? '';

/** {name, terms, frequencies} of each dictionary, as getDynamicTemplates wants them. */
function dictionaryOptions(dictionaries) {
    return {
        options: {dictionaries: dictionaries.map(({name}) => ({name, enabled: true}))},
        info: dictionaries.map(({name, terms, frequencies}) => ({title: name, counts: {terms: {total: terms}, termMeta: {freq: frequencies}}})),
    };
}

function errorText(e) {
    const inner = e?.data?.error;
    const message = e?.message ?? String(e);
    return inner ? `${message}: ${inner.message ?? inner}` : message;
}

const methods = {
    /** The default templates and the markers they offer, standard and per dictionary. */
    async defaults({dictionaries}) {
        const {options, info} = dictionaryOptions(dictionaries);
        return {
            templates: defaultTemplates,
            markers: getStandardFieldMarkers('term', 'ja'),
            dynamicMarkers: getDynamicFieldMarkers(options.dictionaries, info),
        };
    },

    /**
     * Fills a card format's fields for one dictionary entry. Without `media` (the first pass) it
     * also says what media the fields need; Hiroi then stores those and asks again with them.
     */
    async createNote(request) {
        await ready;
        const {options, info} = dictionaryOptions(request.dictionaries);
        const template = (request.templates ?? defaultTemplates) + getDynamicTemplates(options, info);
        injected = request.media;
        try {
            const {note, errors, requirements} = await builder.createNote({
                dictionaryEntry: request.dictionaryEntry,
                cardFormat: request.cardFormat,
                context: request.context,
                template,
                tags: request.tags,
                requirements: request.requirements ?? [],
                duplicateScope: request.duplicateScope,
                duplicateScopeCheckAllModels: request.checkAllModels,
                resultOutputMode: 'group',
                glossaryLayoutMode: request.glossaryLayoutMode,
                compactTags: request.compactTags,
                mediaOptions: request.media === null ? null : {
                    audio: {sources: [], preferredAudioIndex: null, idleTimeout: null, languageSummary: null, enableDefaultAudioSources: true},
                    screenshot: {format: 'png', quality: 92, contentOrigin: {tabId: 0, frameId: 0}},
                    textParsing: {optionsContext: {index: 0}, scanLength: 16},
                },
                dictionaryStylesMap: builder.getDictionaryStylesMap(request.dictionaries),
            });
            return {fields: note.fields, errors: errors.map(errorText), requirements};
        } finally {
            injected = null;
        }
    },
};

globalThis.hiroiAnki = {
    call(id, method, args) {
        Promise.resolve()
            .then(() => methods[method](args))
            .then(
                (result) => HiroiAnkiBridge.done(id, JSON.stringify({result})),
                (e) => HiroiAnkiBridge.done(id, JSON.stringify({error: errorText(e)})),
            );
    },
};
HiroiAnkiBridge.ready();
