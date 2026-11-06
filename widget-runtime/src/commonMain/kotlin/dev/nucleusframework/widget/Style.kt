package dev.nucleusframework.widget

/**
 * Text size. Widget hosts only offer a fixed scale (Adaptive Cards on Windows), so sizes are
 * steps of that scale rather than `sp`.
 */
public enum class TextSize {
    /** Smaller than the body text. */
    Small,

    /** The body text size. */
    Default,

    /** Slightly larger than the body text. */
    Medium,

    /** A heading. */
    Large,

    /** A hero value. */
    ExtraLarge,
}

/** Text weight. */
public enum class FontWeight {
    /** Thinner than the body text. */
    Light,

    /** The body text weight. */
    Normal,

    /** Bold. */
    Bold,
}

/**
 * Semantic text color, resolved by the widget host for its current theme (light, dark, high
 * contrast): widget hosts do not take arbitrary colors for text.
 */
public enum class WidgetColor {
    /** The host's default text color. */
    Default,

    /** A dimmed, secondary text color. */
    Subtle,

    /** The host's accent color. */
    Accent,

    /** A positive (success) color. */
    Good,

    /** A cautionary color. */
    Warning,

    /** An alarming (error) color. */
    Attention,
}

/** Semantic container background, resolved by the widget host for its current theme. */
public enum class WidgetBackground {
    /** The host's default surface. */
    Default,

    /** A slightly contrasting surface, to group content. */
    Emphasis,

    /** The accent color. */
    Accent,

    /** A positive (success) tint. */
    Good,

    /** A cautionary tint. */
    Warning,

    /** An alarming (error) tint. */
    Attention,
}

/** Gap between the children of a [Column] or [Row], as a step of the host's spacing scale. */
public enum class Spacing {
    /** No gap. */
    None,

    /** A small gap. */
    Small,

    /** The host's default gap. */
    Default,

    /** A medium gap. */
    Medium,

    /** A large gap. */
    Large,

    /** An extra-large gap. */
    ExtraLarge,
}

/** Style of a [Text]. */
public data class TextStyle(
    val fontSize: TextSize = TextSize.Default,
    val fontWeight: FontWeight = FontWeight.Normal,
    val color: WidgetColor = WidgetColor.Default,
)

/** Horizontal and vertical alignments. */
public object Alignment {
    /** Horizontal alignment of a child. */
    public enum class Horizontal {
        /** Leading edge. */
        Start,

        /** Centered. */
        CenterHorizontally,

        /** Trailing edge. */
        End,
    }

    /** Vertical alignment of a child. */
    public enum class Vertical {
        /** Top edge. */
        Top,

        /** Centered. */
        CenterVertically,

        /** Bottom edge. */
        Bottom,
    }
}

/** Size of an [Image]. */
public enum class ImageSize {
    /** The image's natural size, shrunk to fit. */
    Auto,

    /** Stretched to the available width. */
    Stretch,

    /** A small icon. */
    Small,

    /** A medium icon. */
    Medium,

    /** A large picture. */
    Large,

    /** An inline icon the height of a line of text (20 px on Windows), e.g. a check mark beside a label. */
    Icon,
}
