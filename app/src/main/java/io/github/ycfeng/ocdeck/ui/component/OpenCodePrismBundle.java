package io.github.ycfeng.ocdeck.ui.component;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(
        include = {
                "json",
                "javascript",
                "java",
                "kotlin",
                "markup",
                "css",
                "yaml",
                "markdown",
                "python",
                "go",
                "sql"
        },
        grammarLocatorClassName = ".OpenCodeGrammarLocator"
)
public final class OpenCodePrismBundle {
    private OpenCodePrismBundle() {
    }
}
