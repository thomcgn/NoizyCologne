/** Calendar arithmetic uses local date objects representing the civil day in Berlin. */
export function berlinDate(now = new Date()): Date {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Berlin", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(now);
  const value = (type: string) => Number(parts.find(part => part.type === type)?.value);
  return new Date(value("year"), value("month") - 1, value("day"));
}

export function calendarEntryHref(id: string): string {
  return `/dashboard/kalender?entry=${encodeURIComponent(id)}`;
}
