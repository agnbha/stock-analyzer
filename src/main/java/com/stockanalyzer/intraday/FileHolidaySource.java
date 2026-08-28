package com.stockanalyzer.intraday;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads holidays from a classpath file of {@code YYYY-MM-DD  # description}
 * lines. The NSE publishes its list annually; keeping it as an editable file
 * means updating it is a one-line change, not a code change.
 */
public final class FileHolidaySource implements HolidaySource {

    private static final Logger log = LoggerFactory.getLogger(FileHolidaySource.class);

    private final Set<LocalDate> holidays;

    private FileHolidaySource(Set<LocalDate> holidays) {
        this.holidays = holidays;
    }

    public static FileHolidaySource fromClasspath(String resource) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        try (InputStream in = FileHolidaySource.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("Holiday file '{}' not found on the classpath; only weekends will be skipped", resource);
                return new FileHolidaySource(Set.of());
            }
            new String(in.readAllBytes()).lines()
                    .map(FileHolidaySource::stripComment)
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> dates.add(LocalDate.parse(line)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read holiday file " + resource, e);
        }
        return new FileHolidaySource(dates);
    }

    /** Everything from the first {@code #} onward is a comment, including a whole line of it. */
    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }

    @Override
    public Set<LocalDate> holidays() {
        return holidays;
    }

    @Override
    public Optional<LocalDate> coveredUntil() {
        return holidays.stream().max(LocalDate::compareTo);
    }
}
