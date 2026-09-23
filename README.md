# Sonoxel Lavalink plugin

This GPL-3.0-only repository contains the required Sonoxel PCM plugin for Lavalink 4.1.2. It exports approved local MP3 files as 44.1 kHz stereo signed 16-bit PCM through a private HTTP endpoint. The plugin does not expose Lavalink to Minecraft clients.

Build with a Java 25 toolchain; the plugin classes target Java 21 for compatibility with Lavalink's Spring scanner:

```powershell
.\gradlew.bat build --no-daemon --no-parallel
```

Install `build/libs/lavalink-plugin-0.1.0.jar` in Lavalink's plugin directory. Set `SONOXEL_LAVALINK_MEDIA` to an approved media directory and `SONOXEL_LAVALINK_KEY` to a private random key of at least 32 characters. Keep Lavalink bound to loopback and set its password in the application configuration. The Sonoxel API is the only intended caller of `/sonoxel/v1/pcm`.

The main Sonoxel repository contains the API, Minecraft components, Lightsail setup, and smoke script. The supplied MP3 and all credentials remain outside this repository.
