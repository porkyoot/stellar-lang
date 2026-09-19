<div align="center">

<img src="src/main/resources/assets/stellar_lang/icon.png" alt="Stellar Lang Icon" width="128" height="128" />

# 🌐 Stellar Lang

**Real-time client-side translation mod for chat, signs, books, entities, and items with area context and LibreTranslate integration.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue.svg)](https://www.minecraft.net/)
[![Loader](https://img.shields.io/badge/Loader-Quilt-purple.svg)](https://quiltmc.org/)
[![Language](https://img.shields.io/badge/Kotlin-2.4-orange.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/Java-26-red.svg)](https://adoptium.net/)
[![Environment](https://img.shields.io/badge/Environment-100%25%20Client--Side-blueviolet.svg)](#-technical-details)
[![Linter](https://img.shields.io/badge/Detekt-Passing-green.svg)](https://detekt.dev/)

</div>

---

## 📖 About

**Stellar Lang** is the dedicated real-time client-side translation module of the Stellar mod suite. It dynamically translates internationalized gameplay elements—including chat messages, signs, written books, entity nametags, and item tooltips—into the player's configured language.

Powered by **LibreTranslate**, Stellar Lang leverages contextual translation: nearby signs are translated together as a continuous message area, and written books are translated across page breaks to preserve full narrative context.

---

## ⚙️ Core Features

### 💬 Chat Translation
* **Interactive `[T]` Badge:** Cyan prefix attached to translated chat messages.
* **Hover Inspection:** Hover over `[T]` to view the original text and detected source language.
* **In-Place Toggle:** Click `[T]` to switch between translated and original text in-place.
* **Smart Detection:** Automatically avoids translating text that is already in your target language.

### 📖 Multi-Page Book Translation
* **Context Preservation:** Unifies book pages into a coherent document before translation to maintain sentence structure and grammar across page boundaries.
* **Floating `[T]` Button:** Interactive toggle button in the book reading screen (`BookViewScreen`) to quickly alternate between original and translated text.

### 🪧 Area Sign Translation
* **Spatial Sign Clustering:** Scans neighboring signs within a 5-block radius to translate multi-sign boards together as a unified message.
* **Original Text Peek:** Hold the configured hotkey (default: `,` comma) to instantly reveal the original sign text and nametags in-world.

### 🏷️ Entity & Item Name Translation
* Custom nametags on entities and item hover tooltips are translated automatically with a subtle `[T]` indicator.

### ⚡ High Performance & Resilience
* **Non-Blocking Async Pipeline:** Translations execute on dedicated background worker threads (`StellarLang-Worker`), preventing main-thread or render-thread hitches.
* **Request Coalescing:** Identical concurrent requests are automatically coalesced into a single HTTP call.
* **Circuit Breaker:** Automatically trips during rate-limiting (HTTP 429) to prevent server spam and recover gracefully.
* **Persistent Disk Caching:** Translations are persisted in `config/stellar_lang/cache.json` with an LRU in-memory cache to minimize external network requests across game sessions.

### ⚙️ In-Game Configuration GUI (Cloth Config & Mod Menu)
* Customize target language (e.g. `en`, `es`, `fr`, `de`, `ja`, `zh`), toggle individual translation categories (chat, signs, books, entities, items), customize keybinds, and configure API hosts and keys.
* **Live Connection Test:** Includes a one-click **Test Translation Connection** button in the config screen to verify API connectivity and translation functionality in-game.

---

## 🛠️ Technical Details

* **Target Minecraft Version:** 26.2
* **Mod Loader:** Quilt Loader (`0.31.0+`)
* **Environment:** Client-side only (`client` entrypoint: `com.stellar.lang.StellarLangMod`)
* **Language:** Kotlin on Java 26
* **Packaging (Fat Jar):** Bundles `stellar-core` internally. The produced JAR is standalone and does not require a separate `stellar-core.jar` in your mods folder.
* **Dependencies:**
  * **Mod Menu:** Optional soft dependency for accessing the configuration GUI in-game.
  * **Cloth Config:** Optional soft dependency for configuration screen rendering.

---

## 🏛️ Architectural Isolation & Boundaries

Stellar Lang is engineered strictly for client instances (`ClientModInitializer`). As enforced by [Detekt](../config/detekt/detekt-stellar-lang.yml):

- **100% Client-Side:** Never imports dedicated server Minecraft classes (`net.minecraft.server.dedicated.*`).
- **No Server Lifecycle Hooks:** Prohibited from using server-side entrypoints.
- **Decoupled from Server Moderation:** Completely decoupled from server-side modules (`com.stellar.ops.*`).

---

## 🐳 Local LibreTranslate Server (Docker)

To run a fast, private, self-hosted LibreTranslate server locally without rate limits or API key requirements:

```bash
# From the repository root, start the local LibreTranslate container
docker compose up -d

# Verify available languages and service health
curl http://localhost:5000/languages
```

### Configure in Minecraft

1. In Minecraft, open **Mod Menu** -> **Stellar Lang** (or press the mod config key) -> **API & Keys**.
2. Set **LibreTranslate API Host** to:
   ```
   http://localhost:5000
   ```
   > [!TIP]
   > Make sure to specify the `http://` scheme and port `:5000` when running locally (not `https://localhost`). If the scheme is omitted (e.g., `localhost:5000`), Stellar Lang will automatically prepend `http://`.
3. Leave **API Key** blank.
4. Click **Test Translation Connection** to verify that the green `✅ OK ('Hello' -> ...)` confirmation appears.
5. Click **Save and Exit**.

---

## 🧪 Testing & Verification

Always use the Gradle wrapper (`./gradlew`) rather than system `gradle`:

* **Unit Tests (Kotest):**
  ```bash
  ./gradlew :stellar-lang:test
  ```
  Runs unit test specifications in:
  - [`StellarLangSpec.kt`](src/test/kotlin/com/stellar/lang/StellarLangSpec.kt)
  - [`StellarLangConfigSpec.kt`](src/test/kotlin/com/stellar/lang/config/StellarLangConfigSpec.kt)
  - [`LangClothConfigScreenSpec.kt`](src/test/kotlin/com/stellar/lang/config/LangClothConfigScreenSpec.kt)
  - [`TranslationServiceSpec.kt`](src/test/kotlin/com/stellar/lang/service/TranslationServiceSpec.kt)
  - [`TranslationCacheSpec.kt`](src/test/kotlin/com/stellar/lang/service/TranslationCacheSpec.kt)
  - [`ChatTranslationManagerSpec.kt`](src/test/kotlin/com/stellar/lang/chat/ChatTranslationManagerSpec.kt)
  - [`BookTranslationManagerSpec.kt`](src/test/kotlin/com/stellar/lang/book/BookTranslationManagerSpec.kt)
  - [`SignTranslationManagerSpec.kt`](src/test/kotlin/com/stellar/lang/sign/SignTranslationManagerSpec.kt)
  - [`SignFormattingAndTooltipSpec.kt`](src/test/kotlin/com/stellar/lang/sign/SignFormattingAndTooltipSpec.kt)
  - [`EntityAndItemTranslationManagerSpec.kt`](src/test/kotlin/com/stellar/lang/feature/EntityAndItemTranslationManagerSpec.kt)

* **Code Quality & 95%+ Coverage Check:**
  ```bash
  ./gradlew :stellar-lang:check
  ```
  Executes Detekt static analysis and JaCoCo code coverage verification (minimum 95% instruction coverage enforced).

---

## 📦 Building from Source

```bash
./gradlew :stellar-lang:build
```

The compiled standalone fat JAR will be located at:
```
stellar-lang/build/libs/stellar-lang-1.0.0.jar
```
Copy this JAR directly into your instance's `.minecraft/mods/` folder.
