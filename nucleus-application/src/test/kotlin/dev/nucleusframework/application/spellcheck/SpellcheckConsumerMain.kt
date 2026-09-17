package dev.nucleusframework.application.spellcheck

import dev.nucleusframework.spellcheck.SpellcheckSession
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

/**
 * Library-consumer entry point on the shipped check/suggest + menu items.
 */
public fun main() {
    SpellcheckSession(
        locale = Locale.US,
        userDictionaryFile =
            Files.createTempFile("nucleus-spellcheck-consumer-", "-${UUID.randomUUID()}.dic"),
    ).use { session ->
        val helloOk = session.check("hello")
        val heloMiss = !session.check("helo")
        val suggestions = session.suggest("helo")
        val helloInSuggestions = suggestions.any { it.equals("hello", ignoreCase = true) }
        val items =
            NucleusSpellcheckInstaller.menuItems(
                word = "helo",
                session = session,
                onSuggestion = {},
                onAddToDictionary = {},
            )

        println("available=${session.isAvailable}")
        println("hello=${if (helloOk) "ok" else "miss"}")
        println("helo=${if (heloMiss) "miss" else "ok"}")
        println("suggestion_contains_hello=$helloInSuggestions")
        println("menu_items=${items.joinToString("|") { it.label }}")
        check(session.isAvailable) { "spellcheck session must be available on this host" }
        check(helloOk) { "hello must check as correct" }
        check(heloMiss) { "helo must check as misspelled" }
        check(helloInSuggestions) { "suggestions for helo must include hello: $suggestions" }
        check(items.any { it.label != "Add to dictionary" }) { "menu must include a suggestion" }
    }
}
