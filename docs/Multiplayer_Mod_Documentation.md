# Multiplayer Mod Documentation

## 1. High-Level Overview
- Provides combat-focused multiplayer/co-op by syncing ships, projectiles, player inputs, and chat between a host and remote clients.
- Touches combat systems (ship state, weapons, projectiles, player camera), in-combat UI (MP menu, ship selector, chatbox), and adds console commands and sample missions; campaign is not addressed.
- Architecture: BaseModPlugin registers network entity/record types, then either a server or client `BaseEveryFrameCombatPlugin` is attached. Netty TCP is used for reliable handshake, lobby, and variant data; Netty UDP is used for high-frequency simulation deltas. Data is serialized through `EntityData`/`DataRecord` lambdas and routed via `Inbound/OutboundEntityManager`s. A separate server thread (`ServerConnectionManager`) drives networking at a fixed tick rate while the combat plugin runs on the game thread.
- Current status (0.98a-RC8 target, uncertain stability): multiplayer loop is present but many AI stubs are empty (`MPDefaultShipAIPlugin`, autofire/missile AI), networking lacks reliability safeguards, and the mod depends on external libraries (CMUtils UI/debug, LazyLib JSON/console, Netty). Uses internal combat classes (`com.fs.starfarer.combat.entities.*`), so breakage is likely across Starsector updates.

## 2. Project Structure
- `mod_info.json`: id `multiplayer`, plugin `data.scripts.MPModPlugin`, packaged jar `jars/Multiplayer.jar`, bundled Netty libs, gameVersion `0.98a-RC8`, utility=true.
- `data/config/settings.json`: registers `MPUIPlugin` as a combat overlay; tunables for server/client tick rates, connection limits, packet size, default username.
- `data/config/mp/proj_spawns.csv`: maps projectile ids to spawn-weapon ids for special MIRV stages; used by `ProjectileSpecDatastore`.
- `data/weapons/`: temporary weapon specs (`mp_*_proj_spawn.wpn`) referenced when recreating MIRV submunitions.
- `data/console/commands.csv`: exposes console command `mpP` (mpPilotShip, deprecated).
- `data/missions/`: sample missions (`MP_Default`, `MP_FighterTest`) plus `MultiplayerMissionPlugin` to auto-respawn pilots for testing.
- `src/data/scripts/`: Java sources: `MPModPlugin`, plugins (client/server/UI/chat), console commands, networking (`net/*`), and data packables/records/tables.
- `jars/`: built mod jar plus Netty 4.1.69 dependencies.
- `target/`: partial compiled output (only `mpRunServer.class`); not a build system.
- IDE files (`.idea`, `.vscode`, `Multiplayer.iml`) assist local development.

## 3. Key Classes and Responsibilities
### Entry Point & Plugin Wiring
- `MPModPlugin` (`data.scripts.MPModPlugin`)
  - Registers network entity and record type ids in `onApplicationLoad` for ships, projectiles, player/lobby metadata, and record codecs (float/byte/string/vector arrays).
  - Overrides `pickWeaponAutofireAI`/`pickMissileAI` to inject stubbed client-side AI (`MPDefaultAutofireAIPlugin`/`MPDefaultMissileAIPlugin`) when a client plugin is active; stores per-weapon AI in the client ship table.
  - Static `setPlugin/destroyPlugin/getPlugin` add/remove the active `MPPlugin` (client or server) to the combat engine.

### Multiplayer Plugins
- `MPServerPlugin` (extends `MPPlugin`, PacketType mix of TCP/UDP)
  - Constructs chatbox UI, variant/projectile/fighter datastores, `ServerConnectionManager` (network thread), server-side managers: `ShipTable`, `PlayerShips`, `PlayerLobby`, `ProjectileTable`, `TextChatHost`.
  - `advance`: consumes inbound deltas from clients, removes asteroids, updates entity managers, gathers outbound socket/udp deltas per connection, and enqueues them on the connection manager; writes debug info via CMUtils.
  - `stop`: shuts down networking, removes itself; console notice.

- `MPClientPlugin` (extends `MPPlugin`)
  - On construction: attaches chatbox UI and opens `ClientConnectionWrapper` to host/port.
  - `init` (triggered once connection metadata is received): clears existing ships, generates datastores (projectile/fighter), initializes managers `ClientShipTable`, `ClientProjectileTable`, `VariantDataMap`, `PlayerShip`, `LobbyInput`, `Player` (client metadata), `TextChatClient`; starts DebugGraph for packet size.
  - `advance`: disconnects on closed connection, strips asteroids, applies inbound deltas via `DataGenManager`, updates entity managers (including interpolation), gathers outbound socket/udp data for its connection id.
  - `stop`: closes connection and removes plugin.

### UI & Console
- `MPUIPlugin` (registered in settings): combat overlay providing MP menu, host/join forms, ship selection grid, camera lock toggle, and disconnect buttons. Uses CMUKitUI widgets and LazyLib fonts. Starts server/client via `MPModPlugin.setPlugin(new MPServerPlugin(port))` or `MPClientPlugin(host, port)`. Stores cached host/port in `mp_cache` JSON (LazyLib helper).
- `MPChatboxPlugin`: in-combat chat window with toggle states (text-only/chat/hidden), input box, and scaling support. Singleton instance used by `MPLogger` and chat tables.
- Console commands (`data.scripts.console.commands.*`):
  - `mpRunServer`: start a server on port 20303 (combat-only).
  - `mpConnectToHost`: connect to `host:port` provided; `mpConnectToHostCached`: uses settings string `mpHost` (not defined in shipped settings).
  - `mpFlush`: sets a custom data flag; `mpPilotShip`: deprecated AI override test.

### Networking Core
- `BaseConnectionWrapper`: common state machine (`ConnectionState` INITIALISATION_READY>SIMULATING>CLOSED), buffer packing/unpacking (`writeBuffer/readBuffer`), max packet size from settings, and helpers for tick/id headers. Uses Netty `ByteBuf`.
- `ClientConnectionWrapper`: owns `ClientDuplex`, TCP `SocketClient`, and UDP `DatagramClient`. Drives connection-state transitions, builds outbound messages (injects `ClientConnectionData`), and applies inbound `ServerConnectionData` then initializes the client plugin. Exposes `queueVariantDownloadForID` for missing variants.
- `ServerConnectionWrapper`: per-client handler created by `SocketChannelInitializer`; tracks `ServerConnectionData` to send and `ClientConnectionData` to receive. Serves variant/ship/projectile bootstrap during LOADING/SPAWNING stages, then SIMULATING; updates outbound buffers in `ServerDuplex`.
- `ServerConnectionManager`: main server loop thread at `mpServerTickRate` (default 60 Hz). Hosts Netty TCP (`SocketServer`) and UDP (`DatagramServer`), maintains connection wrappers, tick counter, and shutdown logic.
- Duplex buffers: `ServerDuplex` (per-connection inbound/outbound maps) and `ClientDuplex` (single inbound plus outbound socket/datagram). Utility classes include `MessageContainer`, `Unpacked`, `Clock`, `CompressionUtils` (deflate unused in current UDP path), and Netty encoders/decoders for TCP/UDP.

### Data Model & Entity Sync
- `DataGenManager`: runtime registry mapping entity classes and record types to byte ids and dispatching inbound/outbound deltas through registered `InboundEntityManager`/`OutboundEntityManager`s; factory for record instances.
- `EntityData`: base for networked entities. Holds record lambdas (source/dest), optional interpolation lambdas, `sourceExecute` to produce deltas, `destExecute` to apply, `interp` for smoothing, `flush` to force full state.
- `RecordLambda`/`InterpRecordLambda`: pair lambdas for read/write of a `DataRecord<T>`; optional interval gating; interpolation uses linear lerp or custom functions. Record types include Byte/Short/Int/Float16/Float32, String, Vector2f/3f, and collection records (`ListenArrayRecord`, `SyncingListRecord`).
- Ship stack:
  - `ShipData`: syncs fleet id, hull id, location/velocity/facing/angVel, hull/flux/CR, vent/overload + engine command bitmask, mouse target, owner, armour deltas, engine disabled flags. On client init spawns ships/wings using variant data or fighter lookup; applies engine commands each update; deletes by removing entity.
  - `ShieldData`: syncs shield on/off, facing, arc; lazy attaches to client ship on first update.
  - `WeaponData`: syncs per-slot fire/disable state, aim angle (coarse or 16-bit for beams), autofire flags, selected group; reconstructs on client by forcing fire/repair and angle.
  - `VariantData`: packages variant layout (caps/vents, hullmods, slot ids, weapon fits, weapon groups) tied to fleet member id; used to reconstruct ships on clients and by server to resupply missing variants on request.
- Projectile stack (`ProjectileTable` server, `ClientProjectileTable` client): tracks moving rays, ballistic projectiles, and missiles; records weapon spec id, owning ship/weapon ids, position/velocity/facing/owner/hp plus missile engine flags. Uses `ProjectileSpecDatastore` to map ids when respawning projectiles client-side.
- Player control & lobby:
  - `ClientPlayerData`: captures local input bitmask (movement, system, vent, hold fire, fighters, group selection/autofire, mouse target) using LWJGL input; server `unmask` applies commands to controlled ship. Also tracks requested ship id switches.
  - `PlayerShip` (client): outbound sender of `ClientPlayerData`, inbound receiver of `ServerPlayerData`; keeps client player ship in sync with server host-active id and requested transfers.
  - `PlayerShips` (server): inbound consumer of client controls, outbound sender of `ServerPlayerData`; manages ship control handoff between host/clients and sets AIs on relinquished ships.
  - `LobbyData`/`LobbyInput` and `PlayerLobby`: distribute player list, piloted ship ids, and usernames (max 12 chars) to clients.
- Chat: `ChatListenData` moves chat lines (connection id + UTF-8 bytes). `TextChatHost` aggregates chat from clients and host UI, re-broadcasts, and writes to chatbox; `TextChatClient` submits input from chatbox and displays received entries with usernames from lobby data.
- Datastores: `ShipVariantDatastore` scans fleets to package variants; `ProjectileSpecDatastore` enumerates weapon/projectile specs and spawn overrides from CSV; `FighterVariantDatastore` maps fighter hull ids to variant ids for spawning wings.
- Utilities: `MapSet` bidirectional map for slot/weapon ids; `MPLogger` writes to both Starsector logger and chatbox as system/error messages.

## 4. Integration with Starsector and Game Code
- Core APIs used: `BaseModPlugin`, `BaseEveryFrameCombatPlugin`, `CombatEngineAPI` (entity access, spawning, remove asteroids, set player ship), `ShipAPI`/`WeaponAPI`/`MissileAPI`/`ShieldAPI`, `ShipCommand`, `Global` settings/logger, `ViewportAPI`, `CombatFleetManagerAPI`. LWJGL input (`Keyboard`, `Mouse`) used for capturing controls.
- Non-API/internal classes: `com.fs.starfarer.combat.entities.BallisticProjectile`, `MovingRay`, `Missile`, `DamagingExplosion` for projectile typing; reliance on these may break on future updates.
- Hooks:
  - `mod_info.json` points to `data.scripts.MPModPlugin`.
  - `data/config/settings.json` registers `MPUIPlugin` as a combat plugin.
  - `MPModPlugin` uses Global combat engine to inject/remove MP plugins and override weapon/missile AI picks.
  - Entity managers spawn/remove ships and projectiles through `CombatFleetManagerAPI` and `Global.getCombatEngine().spawnProjectile`.
  - UI uses CMUKitUI rendering stack and LazyLib fonts (external dependencies not shipped in repo).
- External dependencies (not bundled in `jars/Multiplayer.jar`):
  - Netty 4.1.69 (bundled under `jars/netty`).
  - CMUtils/CMUKitUI (UI widgets, debug graphs) – must be present as another mod/library.
  - LazyLib (fonts, JSON utils) and Console Commands (for console integration).
  - LWJGL provided by the game.
- Loading requirements: place the mod folder under `Starsector/mods`, ensure dependency mods are enabled, and the bundled jars remain under `jars/` as referenced by `mod_info.json`.

## 5. Build & Run Instructions (Java 17, VS Code assumption)
- Import project as a Java project; add Starsector core jars (from `starsector-core/`) plus dependency mods (LazyLib, Console Commands, CMUtils) and Netty jars under `jars/netty` to the classpath.
- No Gradle/Maven script is provided; build manually via IDE "Build Artifacts" or `javac/jar`. Target output jar should replace `jars/Multiplayer.jar` (keep the netty jars alongside).
- Suggested manual build outline:
  1. Compile sources under `src/` with `-source 8 -target 8` (game runtime) or match the shipped Netty version; include Starsector + dependency mod classes on the compile classpath.
  2. Package compiled classes into `jars/Multiplayer.jar` (preserve package paths under `data/`).
  3. Leave `mod_info.json` pointing to `data.scripts.MPModPlugin` and ensure `jars/netty/*.jar` remain present.
- Enabling & running:
  - Enable the mod (and required dependency mods) in the Starsector launcher.
  - In combat, open the MP UI ("MP" button) to host or join, or use console: `mpRunServer` (hosts on 20303) / `mpConnectToHost host:port`.
  - Server tick defaults to 60 Hz; client sends at 30 Hz. Both TCP and UDP use the same user-specified port; forward it for external clients.
  - For testing, run two game instances on localhost; the UI defaults to port 8080 for localhost in `initClient` (non-localhost uses provided port).
  - Sample missions (`MP_Default`, `MP_FighterTest`) provide quick combat sandboxes; `MultiplayerMissionPlugin` auto-respawns dead pilots on the host/server.

## 6. Version Compatibility & Known Issues
- Declared for `0.98a-RC8`, but code predates final 0.98 APIs and uses internal combat classes; breakage risk is high on newer builds.
- AI stubs (`MPDefaultShipAIPlugin`, `MPDefaultAutofireAIPlugin`, `MPDefaultMissileAIPlugin`) are empty > ships without a human pilot will not act, and autofire is effectively disabled on clients.
- Networking reliability: no resend/ordering logic beyond TCP vs UDP split; packet loss on UDP may desync projectiles/ships. No authentication or timeout handling beyond connection state flagging.
- Concurrency risk: Netty threads mutate duplex buffers accessed from the combat thread; minimal synchronization is present, but game-state reads on the server thread could race if extended.
- Uses internal `com.fs.starfarer.combat.entities.*` classes and direct LWJGL input; any engine refactors could break compilation or behavior.
- Host/client variance: `MPModPlugin.VERSION` (`v0.1.1`) differs from `mod_info.json` version (`0.1.2`); may confuse compatibility checks.
- Cached connect command `mpConnectToHostCached` expects settings key `mpHost`, which is absent in the shipped settings (connection will fail without adding it).
- `ProjectileSpecDatastore` relies on `data/config/mp/proj_spawns.csv`; missing entries for new MIRVs will spawn wrong weapons. Armour sync uses a custom bit-pack and may overflow if grid exceeds encoded limits.
- Mission plugin removes and respawns ships aggressively; using in campaign may soft-lock engagements (campaign support not implemented).

## 7. Extension & Maintenance Notes
- Adding new synced data: create an `EntityData` subclass, register its TYPE_ID in `MPModPlugin.onApplicationLoad`, implement `Inbound/OutboundEntityManager`, and hook into `DataGenManager.registerInbound/OutboundEntityManager`. Keep record counts compact to honor `MP_PacketSize`.
- New message types or lobby metadata: extend `ChatListenData`/`LobbyData` or add a new metadata `EntityData` with its own manager; route via the socket channel for reliability.
- Control schemes: update `ClientPlayerData.mask()`/`unmask()` for additional inputs; mirror changes on server-side handling to avoid desync.
- Danger zones: modifying `ServerConnectionManager` tick rate or packet split (socket vs datagram) affects compatibility; changing TYPE_ID registration order breaks decoding across clients/servers. Be careful with thread access to `Global.getCombatEngine()` from outside the main thread.
- Refactors to consider: implement actual ship/autofire/missile AI instead of stubs; separate simulation authority (server-authoritative positions) from display interpolation; replace internal class usage with API equivalents; add reliability/handshake for UDP or move critical data to TCP; extract dependency assumptions (CMUtils/LazyLib) into explicit checks.
- When updating for future Starsector versions, review any internal class imports, input handling, and Netty compatibility, then retest mission bootstrap and lobby/connect flows.
