package dev.eknath.i18n

import dev.eknath.analyzers.models.Insight
import java.text.MessageFormat
import java.util.Properties

object MessageBundle {
    private val supportedLangs = setOf("en", "hi", "gu", "kn", "mr", "ta", "te", "pa", "bn", "ml")
    private val bundles = mutableMapOf<String, Properties>()

    init {
        for (lang in supportedLangs) {
            val props = Properties()
            val stream = MessageBundle::class.java.classLoader
                .getResourceAsStream("i18n/messages_$lang.properties")
            if (stream != null) {
                props.load(stream.reader(Charsets.UTF_8))
            }
            bundles[lang] = props
        }
    }

    fun resolve(insight: Insight, lang: String): Pair<String, String> {
        val effectiveLang = if (lang in supportedLangs) lang else "en"
        val props = bundles[effectiveLang] ?: bundles["en"]!!
        val fallback = bundles["en"]!!

        val titleKey = "${insight.category}_${insight.severity.name}_title"
        val descKey = "${insight.category}_${insight.severity.name}_description"

        val title = props.getProperty(titleKey) ?: fallback.getProperty(titleKey) ?: insight.category
        val descTemplate = props.getProperty(descKey) ?: fallback.getProperty(descKey) ?: ""

        val description = if (insight.formatArgs.isEmpty()) {
            descTemplate
        } else {
            try {
                MessageFormat.format(descTemplate, *insight.formatArgs.toTypedArray())
            } catch (e: Exception) {
                descTemplate
            }
        }

        return Pair(title, description)
    }
}
