package de.noizycologne.backend.calendar;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import static java.util.Objects.requireNonNull;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class RecurrencePlanningTests {
    private static @NonNull LocalDate date(int year,int month,int day) { return requireNonNull(LocalDate.of(year,month,day)); }
    @Test void weeklyCrossesMonthsAndYearWithoutInventingDatesBeforeStart() {
        var dates=RecurrencePlanningService.dates("weekly",6,null,date(2026,12,12),date(2026,11,7));
        assertThat(dates).hasSize(9).startsWith(date(2026,12,12)).endsWith(date(2027,2,6));
        assertThat(dates).allMatch(day -> day.getDayOfWeek()==DayOfWeek.SATURDAY);
    }
    @Test void secondSaturdayAndFirstThroughFourthFridayUseActualCalendarMonths() {
        assertThat(RecurrencePlanningService.dates("monthly_weekday",6,2,date(2026,11,7),date(2026,11,7)))
            .containsExactly(date(2026,11,14),date(2026,12,12),date(2027,1,9));
        for(int occurrence=1;occurrence<=4;occurrence++) {
            var dates=RecurrencePlanningService.dates("monthly_weekday",5,occurrence,date(2026,12,1),date(2026,12,1));
            int index=occurrence;
            assertThat(dates).hasSize(3).allMatch(day -> day.getDayOfWeek()==DayOfWeek.FRIDAY && (day.getDayOfMonth()-1)/7+1==index);
        }
    }
    @Test void lastWeekdayHandlesFebruaryLeapYearAndSundayZero() {
        assertThat(RecurrencePlanningService.dates("monthly_weekday",2,-1,date(2028,1,1),date(2028,1,1)))
            .containsExactly(date(2028,1,25),date(2028,2,29),date(2028,3,28));
        assertThat(RecurrencePlanningService.dates("monthly_weekday",0,-1,date(2026,11,1),date(2026,11,1)))
            .containsExactly(date(2026,11,29),date(2026,12,27),date(2027,1,31));
    }
    @Test void unknownRulesAndInvalidOrdinalsNeverGenerateDates() {
        var day=date(2026,11,7);
        assertThat(RecurrencePlanningService.dates("other",6,null,day,day)).isEmpty();
        assertThat(RecurrencePlanningService.dates("monthly_weekday",6,5,day,day)).isEmpty();
        assertThat(RecurrencePlanningService.dates("weekly",7,null,day,day)).isEmpty();
    }
}
