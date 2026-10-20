package uz.biolife.conversation.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Audio files on local disk: <dir>/yyyy/MM/dd/<id>.<ext>. Written to a temp name first, then moved into place. */
@Component
public class RecordingStorage {
    private static final Map<String, String> EXT = Map.of(
        "audio/webm", "webm", "audio/ogg", "ogg", "audio/mp4", "m4a", "audio/m4a", "m4a", "audio/x-m4a", "m4a",
        "audio/mpeg", "mp3", "audio/mp3", "mp3", "audio/wav", "wav", "audio/x-wav", "wav", "audio/aac", "aac");
    private final Path root;

    public RecordingStorage(@Value("${biolife.recordings.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public static String baseMime(String contentType) {
        if (contentType == null) return "application/octet-stream";
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase();
    }

    public Path save(UUID id, LocalDate date, String contentType, InputStream in) {
        String ext = EXT.getOrDefault(baseMime(contentType), "bin");
        Path dir = root.resolve(String.format("%04d/%02d/%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(id + "." + ext + ".part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Path target = dir.resolve(id + "." + ext);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Yozuvni saqlab bo'lmadi", e);
        }
    }

    /** Only files under the storage root are ever served. */
    public Path resolve(String stored) {
        Path p = Path.of(stored).toAbsolutePath().normalize();
        if (!p.startsWith(root)) throw new IllegalStateException("Yozuv saqlash papkasidan tashqarida");
        return p;
    }
}
