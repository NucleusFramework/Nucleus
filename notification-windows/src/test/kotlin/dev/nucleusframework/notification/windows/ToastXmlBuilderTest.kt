package dev.nucleusframework.notification.windows

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastXmlBuilderTest {
    private fun xmlOf(
        scenario: ToastScenario = ToastScenario.DEFAULT,
        duration: ToastDuration = ToastDuration.DEFAULT,
    ): String =
        ToastXmlBuilder.buildXml(
            toast {
                this.scenario = scenario
                this.duration = duration
                visual { text("Title") }
            },
        )

    @Test
    fun `omits scenario and duration attributes by default`() {
        val xml = xmlOf()
        assertFalse(xml.contains("scenario="), xml)
        assertFalse(xml.contains("duration="), xml)
        assertTrue(xml.startsWith("<toast>"))
        assertTrue(xml.endsWith("</toast>"))
        assertContains(xml, "<visual><binding template=\"ToastGeneric\"><text>Title</text></binding></visual>")
    }

    @Test
    fun `emits every toast scenario`() {
        assertFalse(xmlOf(scenario = ToastScenario.DEFAULT).contains("scenario="))
        assertContains(xmlOf(scenario = ToastScenario.REMINDER), "scenario=\"reminder\"")
        assertContains(xmlOf(scenario = ToastScenario.ALARM), "scenario=\"alarm\"")
        assertContains(xmlOf(scenario = ToastScenario.INCOMING_CALL), "scenario=\"incomingCall\"")
        assertContains(xmlOf(scenario = ToastScenario.URGENT), "scenario=\"urgent\"")
    }

    @Test
    fun `emits short and long duration`() {
        assertContains(xmlOf(duration = ToastDuration.SHORT), "duration=\"short\"")
        assertContains(xmlOf(duration = ToastDuration.LONG), "duration=\"long\"")
        assertEquals("", ToastDuration.DEFAULT.xmlValue)
    }

    @Test
    fun `emits launch activation type and timestamp`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    launch = "action=open&id=1"
                    activationType = ActivationType.PROTOCOL
                    displayTimestamp = "2024-01-02T03:04:05Z"
                    visual { text("T") }
                },
            )
        assertContains(xml, "launch=\"action=open&amp;id=1\"")
        assertContains(xml, "activationType=\"protocol\"")
        assertContains(xml, "displayTimestamp=\"2024-01-02T03:04:05Z\"")
        assertFalse(xml.contains("activationType=\"foreground\""))
    }

    @Test
    fun `foreground activation type is omitted on the root toast`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    activationType = ActivationType.FOREGROUND
                    visual { text("T") }
                },
            )
        assertFalse(xml.contains("activationType="))
    }

    @Test
    fun `text hints and xml special characters are escaped`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual {
                        text(
                            content = "A & B <C> \"D\" 'E'",
                            style = AdaptiveTextStyle.TITLE,
                            wrap = true,
                            maxLines = 2,
                            minLines = 1,
                            align = AdaptiveTextAlign.CENTER,
                            language = "en-US",
                        )
                    }
                },
            )
        assertContains(xml, "hint-style=\"title\"")
        assertContains(xml, "hint-wrap=\"true\"")
        assertContains(xml, "hint-maxLines=\"2\"")
        assertContains(xml, "hint-minLines=\"1\"")
        assertContains(xml, "hint-align=\"center\"")
        assertContains(xml, "lang=\"en-US\"")
        assertContains(xml, "A &amp; B &lt;C&gt; &quot;D&quot; &apos;E&apos;")
    }

    @Test
    fun `inline image app logo hero and attribution are emitted`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual {
                        image(
                            source = "https://example.com/a.png",
                            crop = AdaptiveImageCrop.CIRCLE,
                            alt = "alt & more",
                            addImageQuery = true,
                        )
                        appLogo(
                            source = "ms-appx:///logo.png",
                            crop = AdaptiveImageCrop.NONE,
                            alt = "logo",
                            addImageQuery = false,
                        )
                        heroImage(
                            source = "https://example.com/hero.png",
                            alt = "hero",
                            addImageQuery = true,
                        )
                        attribution("via Calendar", language = "fr")
                    }
                },
            )
        assertContains(
            xml,
            "<image src=\"https://example.com/a.png\" hint-crop=\"circle\" " +
                "alt=\"alt &amp; more\" addImageQuery=\"true\"/>",
        )
        assertFalse(
            xml
                .substringAfter("<image src=\"https://example.com/a.png\"")
                .substringBefore("/>")
                .contains("placement="),
        )
        assertContains(xml, "placement=\"appLogoOverride\"")
        assertContains(xml, "hint-crop=\"none\"")
        assertContains(xml, "placement=\"hero\"")
        assertContains(xml, "<text placement=\"attribution\" lang=\"fr\">via Calendar</text>")
    }

    @Test
    fun `default crop and missing optional image attributes are omitted`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual {
                        image("https://example.com/plain.png")
                        appLogo("ms-appx:///plain.png")
                        heroImage("https://example.com/h.png")
                        attribution("plain")
                    }
                },
            )
        assertFalse(xml.contains("hint-crop="))
        assertFalse(xml.contains("addImageQuery="))
        assertFalse(xml.contains("alt="))
        assertContains(xml, "<text placement=\"attribution\">plain</text>")
    }

    @Test
    fun `groups and subgroups emit weight stacking text and images`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual {
                        group {
                            subgroup(weight = 1, textStacking = AdaptiveSubgroupTextStacking.BOTTOM) {
                                text(
                                    "Col1",
                                    style = AdaptiveTextStyle.CAPTION,
                                    wrap = false,
                                    align = AdaptiveTextAlign.RIGHT,
                                )
                            }
                            subgroup {
                                image(
                                    source = "https://example.com/i.png",
                                    crop = AdaptiveImageCrop.CIRCLE,
                                    removeMargin = true,
                                    align = AdaptiveImageAlign.CENTER,
                                    alt = "pic",
                                    addImageQuery = false,
                                )
                            }
                        }
                    }
                },
            )
        assertContains(xml, "<group>")
        assertContains(xml, "<subgroup hint-weight=\"1\" hint-textStacking=\"bottom\">")
        assertContains(xml, "hint-style=\"caption\"")
        assertContains(xml, "<subgroup>")
        assertContains(xml, "hint-removeMargin=\"true\"")
        assertContains(xml, "hint-align=\"center\"")
        assertContains(xml, "</group>")
    }

    @Test
    fun `progress bar supports determinate bound and indeterminate values`() {
        val determinate =
            ToastXmlBuilder.buildXml(
                toast {
                    visual {
                        progressBar(
                            status = "50% complete",
                            title = "Downloading...",
                            value = 0.5,
                            valueStringOverride = "50%",
                        )
                    }
                },
            )
        assertContains(
            determinate,
            "<progress title=\"Downloading...\" value=\"0.5\" " +
                "valueStringOverride=\"50%\" status=\"50% complete\"/>",
        )

        val bound =
            ToastXmlBuilder.buildXml(
                ToastContent(
                    visual =
                        ToastVisual(
                            binding =
                                ToastBindingGeneric(
                                    children =
                                        listOf(
                                            AdaptiveProgressBar(
                                                title = "Copying",
                                                value = 0.1,
                                                valueBind = "progressValue",
                                                valueStringOverride = "{progressText}",
                                                status = "{progressStatus}",
                                            ),
                                        ),
                                ),
                        ),
                ),
            )
        assertContains(bound, "value=\"{progressValue}\"")
        assertFalse(bound.contains("value=\"0.1\""))

        val indeterminate =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { progressBar(status = "Working") }
                },
            )
        assertContains(indeterminate, "value=\"indeterminate\"")
        assertContains(indeterminate, "status=\"Working\"")
    }

    @Test
    fun `actions include text box selection box buttons and context menu`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("Meeting") }
                    actions {
                        textBox(
                            id = "replyBox",
                            title = "Reply",
                            placeholder = "Type a message...",
                            defaultInput = "Hi",
                        )
                        selectionBox(
                            id = "snoozeTime",
                            title = "Snooze for",
                            defaultSelectionId = "15",
                        ) {
                            item("5", "5 minutes")
                            item("15", "15 minutes")
                        }
                        button(
                            content = "Reply",
                            arguments = "action=reply",
                            activationType = ActivationType.BACKGROUND,
                            imageUri = "ms-appx:///reply.png",
                            inputId = "replyBox",
                            afterActivation = AfterActivationBehavior.PENDING_UPDATE,
                            tooltip = "Send reply",
                        )
                        button("Default", arguments = "ok")
                        contextMenuItem(
                            content = "Open settings",
                            arguments = "action=settings",
                            activationType = ActivationType.PROTOCOL,
                        )
                        contextMenuItem("About", arguments = "about")
                    }
                },
            )
        assertContains(
            xml,
            "<input id=\"replyBox\" type=\"text\" title=\"Reply\" " +
                "placeHolderContent=\"Type a message...\" defaultInput=\"Hi\"/>",
        )
        assertContains(xml, "<input id=\"snoozeTime\" type=\"selection\" title=\"Snooze for\" defaultInput=\"15\">")
        assertContains(xml, "<selection id=\"5\" content=\"5 minutes\"/>")
        assertContains(xml, "activationType=\"background\"")
        assertContains(xml, "imageUri=\"ms-appx:///reply.png\"")
        assertContains(xml, "hint-inputId=\"replyBox\"")
        assertContains(xml, "afterActivationBehavior=\"pendingUpdate\"")
        assertContains(xml, "hint-toolTip=\"Send reply\"")
        assertContains(xml, "placement=\"contextMenu\"")
        assertContains(xml, "activationType=\"protocol\"")
        val defaultButton = xml.substringAfter("content=\"Default\"").substringBefore("/>")
        assertFalse(defaultButton.contains("activationType="))
        assertFalse(defaultButton.contains("afterActivationBehavior="))
    }

    @Test
    fun `optional input and button attributes are omitted when unset`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    actions {
                        textBox("box")
                        selectionBox("sel") { }
                        button("Go", arguments = "go")
                    }
                },
            )
        assertContains(xml, "<input id=\"box\" type=\"text\"/>")
        assertContains(xml, "<input id=\"sel\" type=\"selection\"></input>")
        assertContains(xml, "<action content=\"Go\" arguments=\"go\"/>")
    }

    @Test
    fun `audio silent custom source and loop branches`() {
        val silent =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    silentAudio()
                },
            )
        assertContains(silent, "<audio silent=\"true\"/>")
        assertFalse(silent.contains("src="))
        assertFalse(silent.contains("loop="))

        val looping =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    audio(ToastAudioSource.REMINDER, loop = true)
                },
            )
        assertContains(looping, "src=\"ms-winsoundevent:Notification.Reminder\"")
        assertContains(looping, "loop=\"true\"")

        val custom =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    audio(source = ToastAudioSource.DEFAULT, customSource = "ms-appx:///ping.wav")
                },
            )
        assertContains(custom, "src=\"ms-appx:///ping.wav\"")
        assertFalse(custom.contains("Notification.Default"))

        val named =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    audio(ToastAudioSource.MAIL)
                },
            )
        assertContains(named, ToastAudioSource.MAIL.uri)
        assertFalse(named.contains("loop="))
        assertFalse(named.contains("silent="))
    }

    @Test
    fun `header attributes include non-default activation type`() {
        val xml =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    header(
                        id = "meetings",
                        title = "Meetings & more",
                        arguments = "action=open",
                        activationType = ActivationType.BACKGROUND,
                    )
                },
            )
        assertContains(
            xml,
            "<header id=\"meetings\" title=\"Meetings &amp; more\" " +
                "arguments=\"action=open\" activationType=\"background\"/>",
        )

        val defaultHeader =
            ToastXmlBuilder.buildXml(
                toast {
                    visual { text("T") }
                    header("id", "title", "args")
                },
            )
        assertContains(defaultHeader, "<header id=\"id\" title=\"title\" arguments=\"args\"/>")
        assertFalse(defaultHeader.contains("activationType="))
    }

    @Test
    fun `toast without visual content is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> { toast { } }
        assertEquals("Toast must have visual content", error.message)
    }

    @Test
    fun `enum xml values stay stable`() {
        assertEquals("foreground", ActivationType.FOREGROUND.xmlValue)
        assertEquals("background", ActivationType.BACKGROUND.xmlValue)
        assertEquals("protocol", ActivationType.PROTOCOL.xmlValue)
        assertEquals("default", AfterActivationBehavior.DEFAULT.xmlValue)
        assertEquals("pendingUpdate", AfterActivationBehavior.PENDING_UPDATE.xmlValue)
        assertEquals("appLogoOverride", ImagePlacement.APP_LOGO_OVERRIDE.xmlValue)
        assertEquals("hero", ImagePlacement.HERO.xmlValue)
        assertEquals("", ImagePlacement.INLINE.xmlValue)
        assertEquals("stretch", AdaptiveImageAlign.STRETCH.xmlValue)
        assertEquals("top", AdaptiveSubgroupTextStacking.TOP.xmlValue)
        assertEquals("center", AdaptiveSubgroupTextStacking.CENTER.xmlValue)
        assertEquals("captionSubtle", AdaptiveTextStyle.CAPTION_SUBTLE.xmlValue)
        assertEquals("headerNumeral", AdaptiveTextStyle.HEADER_NUMERAL.xmlValue)
        assertEquals("auto", AdaptiveTextAlign.AUTO.xmlValue)
        assertEquals("left", AdaptiveTextAlign.LEFT.xmlValue)
    }

    @Test
    fun `dismissal reason maps raw values`() {
        assertEquals(DismissalReason.USER_CANCELED, DismissalReason.fromRawValue(0))
        assertEquals(DismissalReason.APPLICATION_HIDDEN, DismissalReason.fromRawValue(1))
        assertEquals(DismissalReason.TIMED_OUT, DismissalReason.fromRawValue(2))
        assertEquals(DismissalReason.TIMED_OUT, DismissalReason.fromRawValue(99))
    }
}
