package de.noizycologne.backend.maintenance;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Private durable intent; only its exact SHA-256 committed in PostgreSQL authorizes cleanup. */
@Component
public final class ResetJournal {
    private static final String NAME = ".noizy-reset.json";
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();

    public ResetJournal(@Value("${noizy.media.root:./uploads}") String mediaRoot) {
        root = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    public record Entry(String path, boolean directory, long size, String modified, String changed, String fileKey) {
        public Entry {
            Objects.requireNonNull(path); Objects.requireNonNull(modified);
            Objects.requireNonNull(changed); Objects.requireNonNull(fileKey);
            Path relative = Path.of(path);
            if (path.isEmpty() || path.length() > 512 || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)
                    || relative.isAbsolute() || !relative.normalize().toString().equals(path)
                    || relative.startsWith("..") || relative.startsWith(".") || relative.getName(0).toString().startsWith(".noizy-reset")
                    || size < 0 || modified.length() > 64 || changed.length() > 64 || fileKey.isEmpty() || fileKey.length() > 256)
                throw new IllegalArgumentException("Unsafe reset journal entry");
        }
    }
    public record Inventory(List<@NonNull Entry> entries, long files, String fingerprint) { }
    public record Intent(int version, String generation, long deletedRecords, List<@NonNull Entry> entries) {
        public Intent {
            Objects.requireNonNull(generation); Objects.requireNonNull(entries);
            entries = List.copyOf(entries);
            Set<String> paths = new HashSet<>();
            if (version != 1 || !generation.matches("[a-f0-9]{64}") || deletedRecords < 0 || entries.size() > MAX_ENTRIES
                    || entries.stream().anyMatch(entry -> !paths.add(entry.path())))
                throw new IllegalArgumentException("Invalid reset intent");
        }
    }
    public record Prepared(Intent intent, String sha256) { }

    public boolean exists() { return Files.exists(root.resolve(NAME), LinkOption.NOFOLLOW_LINKS); }

    public Inventory inventory() throws IOException {
        validateRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.filter(path -> !path.equals(root) && !path.equals(root.resolve(NAME)))
                .limit(MAX_ENTRIES + 1L).map(Objects::requireNonNull).toList();
            if (paths.size() > MAX_ENTRIES) throw new IOException("Media inventory limit exceeded");
            var entries = new java.util.ArrayList<@NonNull Entry>();
            for (Path path : paths) entries.add(Objects.requireNonNull(inspect(path)));
            entries.sort(Comparator.comparing(Entry::path));
            var immutable = List.copyOf(entries);
            return new Inventory(immutable, immutable.stream().filter(entry -> !entry.directory()).count(),
                digest(json.writeValueAsBytes(immutable)));
        } catch (UncheckedIOException exception) { throw exception.getCause(); }
    }

    public Prepared prepare(Intent intent) throws IOException {
        validateRoot();
        if (exists()) throw new IOException("A reset journal already exists");
        byte[] bytes = json.writeValueAsBytes(intent);
        if (bytes.length > MAX_BYTES) throw new IOException("Reset journal size limit exceeded");
        Path temporary = Files.createTempFile(root, ".noizy-reset-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, root.resolve(NAME), StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
            return new Prepared(intent, digest(bytes));
        } finally { Files.deleteIfExists(temporary); }
    }

    public @Nullable Prepared read() throws IOException {
        if (!exists()) return null;
        validateRoot();
        Path journal = root.resolve(NAME);
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(journal)
                || Files.size(journal) > MAX_BYTES)
            throw new IOException("Unsafe reset journal");
        if (!Files.getPosixFilePermissions(journal).equals(PosixFilePermissions.fromString("rw-------")))
            throw new IOException("Reset journal is not private");
        byte[] bytes;
        try (var input = Files.newInputStream(journal, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int) MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Reset journal size limit exceeded");
        try {
            Intent intent = Objects.requireNonNull(json.readValue(bytes, Intent.class));
            return new Prepared(intent, digest(bytes));
        } catch (RuntimeException exception) { throw new IOException("Invalid reset journal", exception); }
    }

    public boolean unchanged(Prepared expected) throws IOException {
        Prepared current = read();
        return current != null && expected.sha256().equals(current.sha256());
    }

    public void remove(Prepared expected) throws IOException {
        if (!unchanged(expected)) throw new IOException("Reset journal changed");
        Files.delete(root.resolve(NAME));
        syncDirectory();
    }

    /** Missing old files are already reconciled; replacements and new files are never removed. */
    public void delete(Entry entry) throws IOException {
        validateRoot();
        Path path = root.resolve(entry.path()).normalize();
        if (!path.startsWith(root)) throw new IOException("Unsafe media path");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Path parent = path.getParent();
        if (parent == null || !parent.toRealPath().equals(parent)) throw new IOException("Media parent changed");
        Entry current = inspect(path);
        boolean matches = entry.directory()
            ? current.directory() && entry.fileKey().equals(current.fileKey())
            : entry.equals(current);
        if (!matches) throw new IOException("Media entry changed after reset confirmation");
        Files.delete(path); // An unlisted new child keeps a directory non-empty and therefore protected.
    }

    String referenceKey(String storagePath) throws IOException {
        Path target = root.resolve(storagePath).normalize();
        if (target.equals(root) || !target.startsWith(root)) throw new IOException("Unsafe media reference");
        return root.relativize(target).toString();
    }

    public boolean missing(Entry entry) {
        return Files.notExists(root.resolve(entry.path()), LinkOption.NOFOLLOW_LINKS);
    }

    private Entry inspect(Path path) throws IOException {
        BasicFileAttributes attributes = Objects.requireNonNull(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        if (attributes.isSymbolicLink() || (!attributes.isRegularFile() && !attributes.isDirectory()))
            throw new IOException("Unsupported media entry");
        Object key = attributes.fileKey();
        Object changed = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        if (key == null || !(changed instanceof FileTime changedAt)) throw new IOException("Stable media identity unavailable");
        try {
            return new Entry(root.relativize(path).toString(), attributes.isDirectory(), attributes.size(),
                attributes.lastModifiedTime().toString(), changedAt.toString(), key.toString());
        } catch (IllegalArgumentException exception) { throw new IOException("Unsupported media path", exception); }
    }

    private void validateRoot() throws IOException {
        if (root.getNameCount() < 2 || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root) || !Files.isWritable(root) || !root.toRealPath().equals(root))
            throw new IOException("Media root is not a canonical writable directory");
    }

    private void syncDirectory() throws IOException {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
