package dev.nucleusframework.lab.probes.input.spellcheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenu
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.MaterialSpecimen
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextArea
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SpellcheckProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Spellcheck",
            domain = Domain.Input,
            summary =
                "Do text fields get the OS spellchecker (squiggles, suggestions, add to dictionary) in every " +
                    "language, and degrade cleanly without one?",
            modules = listOf("spellcheck", "nucleus-application"),
            checks =
                listOf(
                    Check("squiggle", "Misspelled words in every field get a red wavy underline that follows edits"),
                    Check(
                        "menu",
                        "Right-clicking a misspelled word offers the same suggestions as Observed, and picking one replaces the word",
                    ),
                    Check("add", "'Add to dictionary' removes the squiggle and survives a Lab restart"),
                    Check(
                        "locale",
                        "Switching language re-checks the text (French words stop being flagged under Français)",
                    ),
                    Check(
                        "missing",
                        "A language without a dictionary shows 'unavailable' and the fields keep working, no squiggles, no crash",
                    ),
                    Check("rtl", "Hebrew text is checked and underlined under the right glyphs"),
                ),
            keywords = listOf("hunspell", "nsspellchecker", "ispellchecker", "dictionary", "suggestions"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SpellcheckViewModel>()
        val state by vm.state.collectAsState()
        var probeWord by rememberSaveable { mutableStateOf("") }
        val report = state.report

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Engine for ${state.locale.toLanguageTag()}",
                        when {
                            state.loading || report == null -> Availability.Unknown
                            report.available -> Availability.Available
                            else -> Availability.Unavailable("no engine or dictionary for this language")
                        },
                        detail = report?.dictionaryTag,
                    ),
                ),
            controls = {
                ChoiceRow("Language", SpellcheckLocales, state.locale, name = { it.toLanguageTag() }) {
                    vm.onIntent(SpellcheckIntent.SelectLocale(it))
                }
                SubHeading("Material text field")
                Hint("Right-click a misspelled word for its suggestions.")
                SpellcheckContextMenu(
                    text = state.sample,
                    onTextChange = { vm.onSampleEdited(it) },
                    locale = state.locale,
                ) {
                    MaterialSpecimen {
                        OutlinedTextField(
                            state.sample,
                            { vm.onSampleEdited(it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                }
                SubHeading("Jewel text area")
                val jewel = rememberTextFieldState("helo wrold, this is a Jewel feild")
                SpellcheckContextMenu(state = jewel, locale = state.locale) {
                    TextArea(jewel, Modifier.fillMaxWidth())
                }
                SubHeading("BasicTextField")
                var basic by rememberSaveable { mutableStateOf("helo wrold, this is a basic feild") }
                SpellcheckContextMenu(text = basic, onTextChange = { basic = it }, locale = state.locale) {
                    BasicTextField(
                        basic,
                        { basic = it },
                        textStyle = LabTheme.typography.body.copy(color = LabTheme.colors.text),
                        cursorBrush = SolidColor(LabTheme.colors.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(LabTheme.colors.target, LabShapes.block)
                                .border(1.dp, LabTheme.colors.border, LabShapes.block)
                                .padding(LabDimens.gap),
                    )
                }
                SubHeading("Check one word")
                TextFieldRow("Word", probeWord) { probeWord = it }
                Actions { SecondaryAction("Check") { vm.onIntent(SpellcheckIntent.CheckWord(probeWord)) } }
            },
            observed = {
                Readout("Dictionary", report?.dictionaryTag ?: if (state.loading) "loading…" else "none")
                report?.dictionaryFiles?.let {
                    Readout(
                        "Hunspell files",
                        it,
                        tone = if (it == "none found") Tone.Warning else Tone.Neutral,
                    )
                }
                if (report != null &&
                    report.searchDirectories.isNotEmpty()
                ) {
                    Readout("Search path", report.searchDirectories.joinToString("\n"))
                }
                state.probeResult?.let {
                    Readout(
                        "'${state.probeWord}'",
                        if (it) "correct" else "misspelled",
                        tone = if (it) Tone.Ok else Tone.Warning,
                    )
                }
                SubHeading("Misspellings in the Material field")
                if (state.misspellings.isEmpty()) {
                    EmptyState(if (report?.available == true) "No misspelling." else "Engine unavailable.")
                }
                state.misspellings.forEach {
                    Readout("${it.word} @${it.start}", it.suggestions.joinToString().ifEmpty { "no suggestion" })
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("input.spellcheck")
    }
}
