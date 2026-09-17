[upstream]: https://github.com/discord-jda/JDA
[license]: https://github.com/lajczik/JDA-Enhanced/tree/master/LICENSE
[docs]: https://docs.jda.wiki/index.html
[wiki]: https://jda.wiki/introduction/jda/
[license-shield]: https://img.shields.io/badge/License-Apache%202.0-white.svg
[GatewayIntent]: https://docs.jda.wiki/net/dv8tion/jda/api/requests/GatewayIntent.html
[JDABuilder]: https://docs.jda.wiki/net/dv8tion/jda/api/JDABuilder.html
[DefaultShardManagerBuilder]: https://docs.jda.wiki/net/dv8tion/jda/api/sharding/DefaultShardManagerBuilder.html

<img align="right" src="https://github.com/discord-jda/JDA/blob/assets/assets/readme/logo.png?raw=true" height="150" width="150" alt="JDA Logo" />

[![license-shield][]][license]

# JDA Enhanced

An optimized fork of [JDA (Java Discord API)][upstream] targeting **Java 21+**.
This fork replaces OkHttp and nv-websocket-client with **Netty** and **Reactor Netty** for all HTTP, WebSocket, and audio UDP transport, and applies a number of performance and modernization improvements while tracking upstream feature releases.

## What changed from upstream JDA

| Area | Upstream JDA | JDA Enhanced |
|---|---|---|
| **Java baseline** | Java 8 | **Java 21** |
| **HTTP client** | OkHttp | Reactor Netty HTTP |
| **WebSocket** | nv-websocket-client | Netty WebSocket |
| **Audio UDP** | Raw `DatagramSocket` | Netty `DatagramChannel` |
| **Gateway compression** | java.util.zip | Netty Zlib + Zstd stream decoders |
| **JSON engine** | Fixed (nanojson) | Pluggable `JsonEngine` (nanojson, Jackson 2, Jackson 3) |
| **Collections** | `commons-collections4` `MultiSet` | Zero-dependency `Bag` / `HashBag` |
| **Threading** | ForkJoinPool everywhere | Virtual threads + dedicated Netty thread factories |
| **Message caching** | Eager `ReceivedMessage` | Lazy `LazyReceivedMessage` (fields parsed on access) |

Upstream JDA feature releases (member banners, file type filtering, etc.) are merged regularly.

## Requirements

- **Java 21** or newer
- Gradle 8.10+ (wrapper included)

## 📖 Overview

The core concepts of JDA have been developed to make building scalable apps easy:

1. Event System
    Providing simplified events from the gateway API, to respond to any platform events in real-time without much hassle.
2. Rest Actions
    Easy to use and scalable implementation of REST API functionality, letting you choose between callbacks with combinators, futures, and blocking.
    The library also handles rate-limits imposed by Discord automatically, while still offering ways to replace the default implementation.
3. Customizable Cache
    Trading memory usage for better performance where necessary, with sane default presets to choose from and customize.

You can learn more by visiting the [wiki][wiki] or referencing the [Javadocs][docs].

## 🔬 Installation

Add the repository and dependency to your build file. Replace `$version` with the version you want to use.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    // or your own repository / JitPack for snapshot builds
}

dependencies {
    implementation("io.github.lajczik:JDA-Enhanced:$version") {
        // === Audio excludes (safe if you don't use voice) ===
        // exclude(module = "opus-java")  // Opus encoding via JNA native bindings
        // exclude(module = "tink")       // DAVE protocol audio encryption

        // === Compression excludes (if you only use zlib or don't need zstd) ===
        // exclude(module = "zstd-jni")   // Zstandard native compression for gateway

        // === Platform-specific Netty transport excludes ===
        // exclude(module = "netty-transport-classes-epoll")  // Linux epoll (not needed on Windows/macOS)
        // exclude(module = "netty-transport-native-epoll")   // Linux epoll native binaries
        // exclude(module = "netty-transport-classes-kqueue")  // macOS kqueue (not needed on Linux/Windows)

        // === TLS excludes (if using JDK SSL instead of Netty's native TLS) ===
        // exclude(module = "netty-tcnative-classes") // BoringSSL native TLS provider

        // === Reactor Netty transitive excludes ===
        // exclude(module = "netty-resolver-dns")          // Netty DNS resolver (JDK resolver works fine)
        // exclude(module = "netty-codec-dns")             // DNS wire protocol codec
        // exclude(module = "netty-handler-proxy")         // SOCKS/HTTP proxy handler (if not behind a proxy)
        // exclude(module = "netty-codec-socks")           // SOCKS protocol codec
        // exclude(module = "netty-codec-http2")           // HTTP/2 codec (Discord API is HTTP/1.1)
    }
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.lajczik:JDA-Enhanced:$version") {
        // exclude module: 'opus-java'   // Opus encoding
        // exclude module: 'tink'        // DAVE protocol audio encryption
        // exclude module: 'zstd-jni'    // Zstandard compression
        // exclude module: 'netty-transport-native-epoll'  // Linux epoll binaries
    }
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.lajczik</groupId>
    <artifactId>JDA-Enhanced</artifactId>
    <version>$version</version> <!-- replace $version with the latest version -->
    <exclusions>
        <!-- Audio: Opus encoding via JNA native bindings -->
        <!--
        <exclusion>
            <groupId>club.minnced</groupId>
            <artifactId>opus-java</artifactId>
        </exclusion>
        -->
        <!-- Audio: DAVE protocol encryption -->
        <!--
        <exclusion>
            <groupId>com.google.crypto.tink</groupId>
            <artifactId>tink</artifactId>
        </exclusion>
        -->
        <!-- Compression: Zstandard native -->
        <!--
        <exclusion>
            <groupId>com.github.luben</groupId>
            <artifactId>zstd-jni</artifactId>
        </exclusion>
        -->
        <!-- Transport: Linux epoll (not needed on Windows/macOS) -->
        <!--
        <exclusion>
            <groupId>io.netty</groupId>
            <artifactId>netty-transport-native-epoll</artifactId>
        </exclusion>
        -->
    </exclusions>
</dependency>
```

### Build artifacts

The build produces several JAR variants:

| Artifact | Contents |
|---|---|
| `JDA-Enhanced-$version.jar` | Library only, no bundled dependencies |
| `JDA-Enhanced-$version-withDependencies.jar` | Fat jar with all runtime dependencies |
| `JDA-Enhanced-$version-withDependencies-no-opus.jar` | Fat jar without opus/JNA |
| `JDA-Enhanced-$version-withDependencies-min.jar` | Minimized fat jar — no audio, no platform-specific Netty transport, `minimize()` applied |

## 🤖 Creating a Bot

To use this library, you have to create an Application in the [Discord Application Dashboard](https://discord.com/developers/applications) and grab your bot token. You can find a step-by-step guide for this in the wiki page [Creating a Discord Bot](https://jda.wiki/using-jda/getting-started/#creating-a-discord-bot).

## 🏃‍♂️ Getting Started

We provide a number of [examples](https://github.com/discord-jda/JDA/tree/master/src/examples/java) to introduce you to JDA. You can also take a look at the [Wiki][wiki], [Documentation][docs], and [FAQ](https://jda.wiki/introduction/faq/).

Every bot implemented by JDA starts out using the [JDABuilder][JDABuilder] or [DefaultShardManagerBuilder][DefaultShardManagerBuilder]. Both builders provide a set of default presets for cache usage and events it wants to receive:

- `createDefault` - Enables cache for users who are active in voice channels and all cache flags
- `createLight` - Disables all user cache and cache flags
- `create` - Enables member chunking, caches all users, and enables all cache flags

We recommend reading the guide on [caching and intents](https://jda.wiki/using-jda/gateway-intents-and-member-cache-policy/) to get a feel for configuring your bot properly. Here are some possible use-cases:

### Example: Message Logging

> [!NOTE]
> The following example makes use of the **privileged intent** `GatewayIntent.MESSAGE_CONTENT`, which must be explicitly enabled in your application dashboard. You can find out more about intents in the [wiki guide](https://jda.wiki/using-jda/gateway-intents-and-member-cache-policy/).

Simply logging messages to the console. Making use of [JDABuilder][JDABuilder], the intended entry point for smaller bots that don't intend to grow to thousands of guilds.

Starting your bot and attaching an event listener, using the right [intents][GatewayIntent]:

```java
public static void main(String[] args) {
  JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
      .addEventListeners(new MessageReceiveListener())
      .build();
}
```

Your event listener could look like this:

```java
public class MessageReceiveListener extends ListenerAdapter {
  @Override
  public void onMessageReceived(MessageReceivedEvent event) {
    System.out.printf("[%s] %#s: %s\n",
      event.getChannel(),
      event.getAuthor(),
      event.getMessage().getContentDisplay());
  }
}
```

You can find a more thorough example with the [MessageLoggerExample](https://github.com/discord-jda/JDA/blob/master/src/examples/java/MessageLoggerExample.java) class.

### Example: Slash Command Bot

This is a bot that makes use of [interactions](https://jda.wiki/using-jda/interactions/) to respond to user commands. Unlike the message logging bot, this bot can work without any enabled intents, since interactions are always available.

```java
public static void main(String[] args) {
  JDA jda = JDABuilder.createLight(token, Collections.emptyList())
      .addEventListeners(new SlashCommandListener())
      .build();

  // Register your commands to make them visible globally on Discord:

  CommandListUpdateAction commands = jda.updateCommands();

  // Add all your commands on this action instance
  commands.addCommands(
    Commands.slash("say", "Makes the bot say what you tell it to")
      .addOption(STRING, "content", "What the bot should say", true), // Accepting a user input
    Commands.slash("leave", "Makes the bot leave the server")
      .setContexts(InteractionContextType.GUILD) // this doesn't make sense in DMs
      .setDefaultPermissions(DefaultMemberPermissions.DISABLED) // only admins should be able to use this command.
  );

  // Then finally send your commands to discord using the API
  commands.queue();
}
```

An event listener that responds to commands could look like this:

```java
public class SlashCommandListener extends ListenerAdapter {
  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    switch (event.getName()) {
      case "say" -> {
        String content = event.getOption("content", OptionMapping::getAsString);
        event.reply(content).queue();
      }
      case "leave" -> {
        event.reply("I'm leaving the server now!")
          .setEphemeral(true) // this message is only visible to the command user
          .flatMap(m -> event.getGuild().leave()) // append a follow-up action using flatMap
          .queue(); // enqueue both actions to run in sequence (send message -> leave guild)
      }
    }
  }
}
```

You can find a more thorough example with the [SlashBotExample](https://github.com/discord-jda/JDA/blob/master/src/examples/java/SlashBotExample.java) class.

## 🚀 RestAction

In this library, the [RestAction](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html) interface is used as a request builder for all API endpoints.
This interface represents a lazy request builder, as shown in this simple example:

```java
channel.sendMessage("Hello Friend!")
  .addFiles(FileUpload.fromData(greetImage)) // Chain builder methods to configure the request
  .queue() // Send the request asynchronously
```

> [!IMPORTANT]
> The final call to [`queue()`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#queue%28%29) sends the request.
> You can also send the request synchronously or using futures, check out the extended guide in the [RestAction Wiki](https://jda.wiki/using-jda/using-restaction/).

The RestAction interface also supports a number of operators to avoid callback hell:

- [`map`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#map%28java.util.function.Function%29)
    Convert the result of the `RestAction` to a different value
- [`flatMap`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#flatMap%28java.util.function.Function%29)
    Chain another `RestAction` on the result
- [`delay`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#delay%28java.time.Duration%29)
    Delay the element of the previous step

As well as combinators like:

- [`and`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#and(net.dv8tion.jda.api.requests.RestAction,java.util.function.BiFunction))
   Require another RestAction to complete successfully, running in parallel
- [`allOf`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#allOf(java.util.Collection))
   Accumulate a list of many actions into one (see also [`mapToResult`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#mapToResult()))
- [`zip`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#zip(net.dv8tion.jda.api.requests.RestAction,net.dv8tion.jda.api.requests.RestAction...))
   Similar to `and`, but combines the results into a list


And configurators like:

- [`timeout`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#timeout(long,java.util.concurrent.TimeUnit)) and [`deadline`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#deadline(long))
   Configure how long the action is allowed to be in queue, cancelling if it takes too long
- [`setCheck`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/RestAction.html#setCheck(java.util.function.BooleanSupplier))
   Running some checks right before the request is sent, this can be helpful when it is in queue for a while
- [`reason`](https://docs.jda.wiki/net/dv8tion/jda/api/requests/restaction/AuditableRestAction.html#reason(java.lang.String))
   The [audit log reason](https://discord.com/developers/docs/resources/audit-log) for an action

**Example**:

```java
public RestAction<Void> selfDestruct(MessageChannel channel, String content) {
    return channel.sendMessage("The following message will destroy itself in 1 minute!")
        .addComponents(ActionRow.of(Button.danger("delete", "Delete now"))) // further amend message before sending
        .delay(10, SECONDS, scheduler) // after sending, wait 10 seconds
        .flatMap((it) -> it.editMessage(content)) // then edit the message
        .delay(1, MINUTES, scheduler) // wait another minute
        .flatMap(Message::delete); // then delete
}
```

This could then be used in code:

```java
selfDestruct(channel, "Hello friend, this is my secret message").queue();
```

## 🧩 Extensions

### [jda-ktx](https://github.com/MinnDevelopment/jda-ktx)

Created and maintained by [MinnDevelopment](https://github.com/MinnDevelopment).
Provides [Kotlin](https://kotlinlang.org/) extensions for **RestAction** and events that provide a more idiomatic Kotlin experience.

```kotlin
fun main() {
    val jda = light(BOT_TOKEN)

    jda.onCommand("ping") { event ->
        val time = measureTime {
            event.reply("Pong!").await() // suspending
        }.inWholeMilliseconds

        event.hook.editOriginal("Pong: $time ms").queue()
    }
}
```

There are a number of examples available in the [README](https://github.com/MinnDevelopment/jda-ktx/#jda-ktx).

### [Lavaplayer](https://github.com/lavalink-devs/lavaplayer)

Created by [sedmelluq](https://github.com/sedmelluq) and now maintained by the [lavalink community](https://github.com/lavalink-devs)
Lavaplayer is the most popular library used by Music Bots created in Java.
It is highly compatible with JDA and Discord4J and allows playing audio from
YouTube, Soundcloud, Twitch, Bandcamp and [more providers](https://github.com/lavalink-devs/lavaplayer#supported-formats).
The library can easily be expanded to more services by implementing your own AudioSourceManager and registering it.

It is recommended to read the [Usage](https://github.com/lavalink-devs/lavaplayer#usage) section of Lavaplayer
to understand a proper implementation.
Sedmelluq provided a demo in his repository which presents an example implementation for JDA:
https://github.com/lavalink-devs/lavaplayer/tree/master/demo-jda

### [Lavalink](https://github.com/lavalink-devs/Lavalink)

Created by [Freya Arbjerg](https://github.com/freyacodes) and now maintained by the [lavalink community](https://github.com/lavalink-devs).

Lavalink is a popular standalone audio sending node based on Lavaplayer. Lavalink was built with scalability in mind,
and allows streaming music via many servers. It supports most of Lavaplayer's features.

Lavalink is used by many large bots, as well as bot developers who can not use a Java library like Lavaplayer.
If you plan on serving music on a smaller scale with JDA, it is often preferable to just use Lavaplayer directly
as it is easier.

[Lavalink-Client](https://github.com/FredBoat/Lavalink-Client) is the official Lavalink client for JDA.

## 🚨 Breaking Changes

Due to the nature of the Discord API, the library will regularly introduce breaking changes to allow for a quick adoption of newer features. We try to keep these breaking changes minimal, but cannot avoid them entirely.

Most breaking changes will result in a **minor** version bump (`5.1.2` → `5.2.0`).
