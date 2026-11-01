package com.unipars.engine

data class ValidationResult(val ok: Boolean, val problems: List<String>) {
    val message: String get() = problems.joinToString("; ")
}

/** Detects a broken recipe: too few items or required fields mostly empty. */
object Validator {

    fun validate(rules: Validation, result: ExtractionResult): ValidationResult {
        val problems = mutableListOf<String>()
        problems += result.errors
        if (result.items.size < rules.minItems) {
            problems += "found ${result.items.size} items, expected at least ${rules.minItems}"
        }
        if (result.items.isNotEmpty() && rules.requiredFields.isNotEmpty()) {
            val filled = result.items.count { item ->
                rules.requiredFields.all { !item.fields[it].isNullOrBlank() }
            }
            val ratio = filled.toDouble() / result.items.size
            if (ratio < rules.minFilledRatio) {
                val empty = rules.requiredFields.filter { f -> result.items.count { it.fields[f].isNullOrBlank() } > 0 }
                problems += "required fields filled in ${"%.0f".format(ratio * 100)}% of items " +
                    "(need ${"%.0f".format(rules.minFilledRatio * 100)}%), empty: ${empty.joinToString()}"
            }
        }
        return ValidationResult(problems.isEmpty(), problems)
    }
}
