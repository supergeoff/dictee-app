package top.supergeoff.dictee

/** Texte local transmis comme indice de vocabulaire standard, sans interprétation serveur. */
object ComplementaryGlossary {
    fun entries(text: String): List<String> = text.lineSequence().filter { it.isNotBlank() }.toList()

    fun append(text: String, input: String): String = (entries(text) + entries(input)).joinToString("\n")

    fun remove(text: String, index: Int): String {
        val lines = entries(text)
        return if (index in lines.indices) lines.filterIndexed { i, _ -> i != index }.joinToString("\n") else text
    }

    fun validationError(text: String): String? = when {
        entries(text).size > 500 -> "Le glossaire ne peut pas dépasser 500 lignes."
        text.codePointCount(0, text.length) > 20_000 -> "Le glossaire ne peut pas dépasser 20 000 caractères."
        else -> null
    }

    fun hasAdvisory(text: String): Boolean = entries(text).any { line ->
        val value = line.trim()
        value.split(Regex("\\s+")).size > 5 || value.lastOrNull() in listOf('.', '!', '?')
    }
}
