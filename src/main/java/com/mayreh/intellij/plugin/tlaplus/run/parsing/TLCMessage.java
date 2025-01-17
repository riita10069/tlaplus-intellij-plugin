package com.mayreh.intellij.plugin.tlaplus.run.parsing;

import javax.annotation.Nullable;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Represents messages from TLC which start from "@!@!@"
 */
@Value
@Accessors(fluent = true)
class TLCMessage {
    int code;
    @Nullable
    Integer severity;
}
