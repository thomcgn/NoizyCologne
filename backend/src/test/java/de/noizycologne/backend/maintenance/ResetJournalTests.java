package de.noizycologne.backend.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResetJournalTests {
    @TempDir Path root;
    private ResetJournal journal() { return new ResetJournal(root.toString()); }
    @Test void privateDurableManifestDoesNotInvalidatePreviewAndRoundTrips() throws Exception {
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/old.png"),"old");
        var journal = journal(); var before = journal.inventory();
        var prepared = journal.prepare(new ResetJournal.Intent(1,"a".repeat(64),3,before.entries()));
        assertThat(Objects.requireNonNull(journal.read())).isEqualTo(prepared);
        assertThat(journal.inventory().fingerprint()).isEqualTo(before.fingerprint());
        assertThat(Files.getPosixFilePermissions(root.resolve(".noizy-reset.json"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        journal.remove(prepared); assertThat(journal.exists()).isFalse();
        assertThat(Files.readString(root.resolve("nested/old.png"))).isEqualTo("old");
    }
    @Test void replacementAndNewFilesArePreservedEvenWithOriginalMtimeAndSize() throws Exception {
        Path path = root.resolve("old.png"); Files.writeString(path,"old");
        var journal = journal(); var before = journal.inventory();
        var entry = Objects.requireNonNull(before.entries().getFirst());
        var modified = Files.getLastModifiedTime(path);
        Files.writeString(path,"new"); Files.setLastModifiedTime(path,modified);
        assertThatThrownBy(() -> journal.delete(entry)).hasMessageContaining("changed");
        Files.writeString(root.resolve("new.png"),"new");
        assertThat(Files.readString(path)).isEqualTo("new"); assertThat(journal.inventory().files()).isEqualTo(2);
    }
    @Test void nestedSymlinkAndSymlinkRootFailBeforeAnyFileRemoval() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        Path link = root.resolve("link"); Files.createSymbolicLink(link,actual);
        assertThatThrownBy(() -> journal().inventory()).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> new ResetJournal(link.toString()).inventory()).hasMessageContaining("canonical");
        assertThat(Files.exists(actual)).isTrue();
    }
    @Test void malformedNonPrivateAndOversizedJournalsFailClosed() throws Exception {
        Path path = root.resolve(".noizy-reset.json"); Files.writeString(path,"{}");
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> journal().read()).hasMessageContaining("Invalid");
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> journal().read()).hasMessageContaining("private");
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        try(var file = new java.io.RandomAccessFile(path.toFile(),"rw")) { file.setLength(8L*1024*1024+1); }
        assertThatThrownBy(() -> journal().read()).hasMessageContaining("Unsafe");
    }
    @Test void unsafeAndDuplicatePathsAreRejectedAndReferencesCanonicalized() throws Exception {
        for(String path:List.of("../outside","/absolute","nested/../outside",".noizy-reset.tmp","bad\\path"))
            assertThatThrownBy(() -> new ResetJournal.Entry(path,false,1,"time","time","key")).isInstanceOf(IllegalArgumentException.class);
        var entry = new ResetJournal.Entry("old.png",false,1,"time","time","key");
        assertThatThrownBy(() -> new ResetJournal.Intent(1,"a".repeat(64),0,List.of(entry,entry))).isInstanceOf(IllegalArgumentException.class);
        assertThat(journal().referenceKey("nested/../old.png")).isEqualTo("old.png");
        assertThatThrownBy(() -> journal().referenceKey("../outside")).hasMessageContaining("Unsafe");
    }
    @Test void missingOldFilesReconcileButNewChildrenProtectTheirParentDirectory() throws Exception {
        Path directory=Files.createDirectory(root.resolve("nested"));
        Path old=directory.resolve("old.png"); Files.writeString(old,"old");
        var journal=journal(); var entries=journal.inventory().entries();
        var file=java.util.Objects.requireNonNull(entries.stream().filter(entry -> !entry.directory()).findFirst().orElseThrow());
        var parent=java.util.Objects.requireNonNull(entries.stream().filter(ResetJournal.Entry::directory).findFirst().orElseThrow());
        Files.delete(old); journal.delete(file); assertThat(journal.missing(file)).isTrue();
        Files.writeString(directory.resolve("new.png"),"new");
        assertThatThrownBy(() -> journal.delete(parent)).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
        assertThat(Files.readString(directory.resolve("new.png"))).isEqualTo("new");
    }
}
