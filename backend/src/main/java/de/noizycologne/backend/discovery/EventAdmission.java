package de.noizycologne.backend.discovery;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Only scoped event offers or explicitly labelled admission prices; missing is never free. */
final class EventAdmission {
    private EventAdmission() { }
    private static final Pattern LABEL = Pattern.compile("(?iu)\\b(?:eintritt|admission|ticketpreis)\\s*[:=]\\s*(frei|kostenlos|[0-9]{1,4}(?:[.,][0-9]{1,2})?\\s*(?:€|EUR))");
    static String price(EventExtractor.Event event) {
        String evidence = event.evidence();
        if (evidence.stripLeading().startsWith("{")) {
            try {
                var node = JsonMapper.builder().build().readTree(evidence);
                if (node.path("isAccessibleForFree").asBoolean(false)) return "Eintritt frei";
                var offers = node.path("offers");
                var values = offers.isArray() ? offers : JsonMapper.builder().build().createArrayNode().add(offers);
                var prices = new LinkedHashSet<String>();
                for (var offer : values) {
                    String value = offer.path("price").asString("");
                    if (offer.path("priceCurrency").asString("").equalsIgnoreCase("EUR") && value.matches("[0-9]{1,4}(?:[.,][0-9]{1,2})?")) {
                        var number = new BigDecimal(value.replace(',', '.')).stripTrailingZeros();
                        prices.add(number.signum() == 0 ? "Eintritt frei" : number.toPlainString() + " €");
                    }
                }
                if (!prices.isEmpty()) {
                    String price = String.join(" / ", prices);
                    return price.length() <= 100 ? price : "";
                }
            } catch (RuntimeException ignored) { /* Incomplete historical JSON is not a confirmed price. */ }
            return "";
        }
        var match = LABEL.matcher(evidence);
        if (!match.find()) return "";
        String value = match.group(1);
        if (value.equalsIgnoreCase("frei") || value.equalsIgnoreCase("kostenlos")) return "Eintritt frei";
        return value.replaceAll("(?i)EUR", "€");
    }
}
