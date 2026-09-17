package dev.nucleusframework.sfsymbols

/**
 * Type-safe representation of an Apple SF Symbol name.
 *
 * Symbol names are grouped by category. Use [Custom] for symbols not
 * listed here or for forward-compatibility with newer SF Symbols versions.
 *
 * @see <a href="https://developer.apple.com/sf-symbols/">Apple SF Symbols</a>
 */
public sealed interface SFSymbol {
    /** The symbol name string passed to `NSImage.imageWithSystemSymbolName`. */
    public val symbolName: String

    /**
     * A custom symbol name for symbols not covered by the predefined constants.
     *
     * ```kotlin
     * SFSymbol.Custom("my.custom.symbol")
     * ```
     */
    @JvmInline
    public value class Custom(
        override val symbolName: String,
    ) : SFSymbol
}
