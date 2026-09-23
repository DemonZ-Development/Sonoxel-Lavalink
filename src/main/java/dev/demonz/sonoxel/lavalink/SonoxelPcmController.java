package dev.demonz.sonoxel.lavalink;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public final class SonoxelPcmController {
    private final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final Semaphore streams = new Semaphore(8);
    private final Path root;
    private final String key;

    public SonoxelPcmController() throws IOException {
        String directory = System.getenv("SONOXEL_LAVALINK_MEDIA");
        key = System.getenv("SONOXEL_LAVALINK_KEY");
        if (directory == null || key == null || key.length() < 32)
            throw new IllegalStateException("SONOXEL_LAVALINK_MEDIA and SONOXEL_LAVALINK_KEY are required");
        root = Path.of(directory).toRealPath();
        manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
        manager.registerSourceManager(new LocalAudioSourceManager());
    }

    @GetMapping("/sonoxel/v1/health")
    public Map<String, Object> health() { return Map.of("status", "ok", "format", "pcm-s16le-44100-stereo"); }

    @GetMapping("/sonoxel/v1/pcm")
    public ResponseEntity<StreamingResponseBody> pcm(
            @RequestParam("file") String file,
            @RequestParam(value = "offsetMs", defaultValue = "0") long offsetMs,
            @RequestHeader(value = "X-Sonoxel-Lavalink-Key", required = false) String suppliedKey) throws Exception {
        if (!constantEquals(suppliedKey, key)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!file.matches("[a-zA-Z0-9_.-]{1,120}\\.mp3") || offsetMs < 0)
            return ResponseEntity.badRequest().build();
        Path path = root.resolve(file).normalize();
        if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(root))
            return ResponseEntity.notFound().build();
        if (!streams.tryAcquire()) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        AudioTrack track;
        try { track = load(path); }
        catch (Exception ex) { streams.release(); throw ex; }
        StreamingResponseBody body = output -> {
            AudioPlayer player = manager.createPlayer();
            try {
                track.setPosition(offsetMs);
                player.playTrack(track);
                while (!Thread.currentThread().isInterrupted()) {
                    AudioFrame frame;
                    try { frame = player.provide(250, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException ex) { continue; }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                    if (frame == null) {
                        if (player.getPlayingTrack() == null) break;
                        continue;
                    }
                    if (frame.getDataLength() > 8192) throw new IOException("unexpected frame size");
                    output.write(frame.getData(), 0, frame.getDataLength());
                }
            } finally {
                player.destroy();
                streams.release();
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.sonoxel.pcm;rate=44100;channels=2"));
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private AudioTrack load(Path path) throws Exception {
        CompletableFuture<AudioTrack> result = new CompletableFuture<>();
        manager.loadItem(path.toString(), new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { result.complete(track); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { result.completeExceptionally(new IOException("playlist denied")); }
            @Override public void noMatches() { result.completeExceptionally(new IOException("track not found")); }
            @Override public void loadFailed(FriendlyException ex) { result.completeExceptionally(ex); }
        });
        return result.get(8, TimeUnit.SECONDS);
    }

    private static boolean constantEquals(String given, String expected) {
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
