/*
 * Port of Yomitan's ext/js/language/language-transformer.js
 * Copyright (C) 2024-2026 Yomitan Authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package io.github.kvth.hiroi.japanese

import org.json.JSONArray
import org.json.JSONObject

/**
 * Undoes inflections breadth-first with Yomitan's rules (see scripts/update-yomitan-transforms.mjs).
 * Every result carries condition flags: which word types (v1, v5, adj-i, ...) or intermediate
 * forms (-て, -ます, ...) it can be. A dictionary entry fits a result if its part-of-speech rules
 * share a flag with it ([conditionsMatch]).
 */
class LanguageTransformer(descriptorJson: String) {

    class Transform(val id: String, val name: String, val description: String, val rules: List<Rule>)

    class Rule(
        private val wholeWord: Boolean,
        private val inflected: String,
        private val deinflected: String,
        val conditionsIn: Int,
        val conditionsOut: Int,
    ) {
        fun matches(text: String) = if (wholeWord) text == inflected else text.endsWith(inflected)

        fun deinflect(text: String) = if (wholeWord) deinflected else text.dropLast(inflected.length) + deinflected
    }

    /** One rule application; [text] is the form the rule was applied to. */
    data class TraceFrame(val transform: String, val ruleIndex: Int, val text: String)

    /** [trace] lists the applied rules, the one nearest the dictionary form first. */
    data class TransformedText(val text: String, val conditions: Int, val trace: List<TraceFrame>)

    val transforms: List<Transform>
    private val conditionFlags: Map<String, Int>
    private val partOfSpeechFlags: Map<String, Int>

    init {
        val descriptor = JSONObject(descriptorJson)
        val conditions = descriptor.getJSONArray("conditions").objects()
        conditionFlags = conditionFlagsMap(conditions)
        partOfSpeechFlags = conditions
            .filter { it.getBoolean("isDictionaryForm") }
            .associate { it.getString("name") to conditionFlags.getValue(it.getString("name")) }
        transforms = descriptor.getJSONArray("transforms").objects().map { transform ->
            val id = transform.getString("id")
            Transform(
                id = id,
                name = transform.getString("name"),
                description = transform.optString("description"),
                rules = transform.getJSONArray("rules").objects().mapIndexed { j, rule ->
                    Rule(
                        wholeWord = rule.getString("type") == "wholeWord",
                        inflected = rule.getString("inflected"),
                        deinflected = rule.getString("deinflected"),
                        conditionsIn = strictFlags(rule.getJSONArray("conditionsIn").strings())
                            ?: throw IllegalArgumentException("Invalid conditionsIn for transform $id.rules[$j]"),
                        conditionsOut = strictFlags(rule.getJSONArray("conditionsOut").strings())
                            ?: throw IllegalArgumentException("Invalid conditionsOut for transform $id.rules[$j]"),
                    )
                },
            )
        }
    }

    fun transform(sourceText: String): List<TransformedText> {
        val results = mutableListOf(TransformedText(sourceText, 0, emptyList()))
        var i = 0
        while (i < results.size) {
            val (text, conditions, trace) = results[i++]
            for (transform in transforms) {
                for ((j, rule) in transform.rules.withIndex()) {
                    if (!conditionsMatch(conditions, rule.conditionsIn) || !rule.matches(text)) continue
                    if (trace.any { it.transform == transform.id && it.ruleIndex == j && it.text == text }) continue // cycle
                    results += TransformedText(
                        rule.deinflect(text),
                        rule.conditionsOut,
                        listOf(TraceFrame(transform.id, j, text)) + trace,
                    )
                }
            }
        }
        return results
    }

    /** Flags of a dictionary entry's part-of-speech rules; unknown ones count for nothing. */
    fun conditionFlagsFromPartsOfSpeech(partsOfSpeech: List<String>): Int =
        partsOfSpeech.fold(0) { flags, pos -> flags or (partOfSpeechFlags[pos] ?: 0) }

    fun conditionFlagsFromConditionType(conditionType: String): Int = conditionFlags[conditionType] ?: 0

    /** Conditions without sub-conditions get one bit each; the others combine their sub-conditions' bits. */
    private fun conditionFlagsMap(conditions: List<JSONObject>): Map<String, Int> {
        val flags = HashMap<String, Int>()
        var nextFlagIndex = 0
        var targets = conditions
        while (targets.isNotEmpty()) {
            val nextTargets = mutableListOf<JSONObject>()
            for (condition in targets) {
                val subConditions = condition.optJSONArray("subConditions")
                if (subConditions == null) {
                    require(nextFlagIndex < 32) { "Maximum number of conditions was exceeded" }
                    flags[condition.getString("name")] = 1 shl nextFlagIndex++
                } else {
                    val multiFlags = strictFlags(subConditions.strings(), flags)
                    if (multiFlags == null) nextTargets += condition else flags[condition.getString("name")] = multiFlags
                }
            }
            require(nextTargets.size < targets.size) { "Cycle in subConditions" }
            targets = nextTargets
        }
        return flags
    }

    private fun strictFlags(conditionTypes: List<String>, flags: Map<String, Int> = conditionFlags): Int? {
        var result = 0
        for (type in conditionTypes) result = result or (flags[type] ?: return null)
        return result
    }

    companion object {
        /** A result with no conditions (the text as written) fits anything; otherwise they must share a flag. */
        fun conditionsMatch(currentConditions: Int, nextConditions: Int): Boolean =
            currentConditions == 0 || (currentConditions and nextConditions) != 0

        private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

        private fun JSONArray.strings() = List(length()) { getString(it) }
    }
}
