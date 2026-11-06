package de.noizycologne.backend.discovery;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class EventNormalizer {
    private EventNormalizer() { }
    public static String text(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
    public static LocalDate date(String input) {
        if (input == null) return null;
        try {
            String value = input.trim();
            if (value.contains("T") && (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$")))
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalDate();
            if (value.matches("\\d{8}")) return LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if (value.contains("T")) return LocalDateTime.parse(value).toLocalDate();
            return LocalDate.parse(value);
        } catch (DateTimeException ignored) { }
        return null;
    }
    public static LocalTime time(String input) {
        if (input == null || !input.contains("T")) return null;
        try {
            if (input.endsWith("Z") || input.matches(".*[+-]\\d{2}:\\d{2}$"))
                return OffsetDateTime.parse(input).atZoneSameInstant(ZoneId.of("Europe/Berlin")).toLocalTime();
            String value = input.substring(input.indexOf('T') + 1);
            if (value.matches("\\d{6}Z?")) return LocalTime.parse(value.substring(0, 6), java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            return LocalTime.parse(value);
        } catch (DateTimeException ignored) { return null; }
    }
    static boolean distinctTimedOccurrences(EventExtractor.Event first,EventExtractor.Event second) {
        if (first.identity().isBlank() || second.identity().isBlank() || first.identity().equals(second.identity())) return false;
        var firstDays=first.starts().stream().map(EventNormalizer::date).filter(Objects::nonNull).distinct().toList();
        var secondDays=second.starts().stream().map(EventNormalizer::date).filter(Objects::nonNull).distinct().toList();
        var firstTimes=first.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).distinct().toList();
        var secondTimes=second.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).distinct().toList();
        return firstDays.size()==1 && firstDays.equals(secondDays) && firstTimes.size()==1 && secondTimes.size()==1 && !firstTimes.equals(secondTimes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
