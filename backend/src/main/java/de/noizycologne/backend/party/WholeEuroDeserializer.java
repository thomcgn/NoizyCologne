package de.noizycologne.backend.party;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Do not let Jackson silently truncate fractional admission prices to whole euros. */
public final class WholeEuroDeserializer extends ValueDeserializer<Integer> {
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Integer.class, "defaultEntryPrice muss eine ganze Eurozahl sein");
        return parser.getIntValue();
    }
}
