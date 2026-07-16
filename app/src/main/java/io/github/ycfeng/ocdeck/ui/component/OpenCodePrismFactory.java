package io.github.ycfeng.ocdeck.ui.component;

import io.noties.prism4j.Prism4j;

public final class OpenCodePrismFactory {
    private OpenCodePrismFactory() {
    }

    public static Prism4j create() {
        return new Prism4j(new OpenCodeGrammarLocator());
    }
}
