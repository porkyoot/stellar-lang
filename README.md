<div align="center">

<img src="src/main/resources/assets/stellar_lang/icon.png" alt="Stellarlang Icon" width="128" height="128" />

# 🌐 Stellarlang

**Real-time client-side translation mod for chat, signs, books, entities, and items with area context and LibreTranslate integration.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue.svg)](https://www.minecraft.net/)
[![Loader](https://img.shields.io/badge/Loader-Quilt-purple.svg)](https://quiltmc.org/)
[![Language](https://img.shields.io/badge/Kotlin-2.4-orange.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/Java-21-red.svg)](https://adoptium.net/)
[![Environment](https://img.shields.io/badge/Environment-100%25%20Client--Side-blueviolet.svg)](#-technical-details)
[![Linter](https://img.shields.io/badge/Detekt-Passing-green.svg)](https://detekt.dev/)

</div>

---

## 📖 About

**Stellarlang** is the dedicated real-time client-side translation module of the Stellar mod suite. It dynamically translates internationalized gameplay elements—including chat messages, signs, written books, entity nametags, and item tooltips—into the player's configured language.

Powered by **LibreTranslate**, Stellarlang leverages contextual translation: nearby signs are translated together as a continuous message area, and written books are translated across page breaks to preserve full narrative context.

---

## ⚙️ Core Features

### 💬 Chat Translation
* **Interactive `[T]` Badge:** Cyan prefix attached to translated messages.
* **Hover Inspection:** Displays original text and detected source language.
* **In-Place Toggle:** Click `[T]` to toggle between translated and original text in-place.
* **Smart Detection:** Automatically avoids translating text that is already in your target language.

### 📖 Multi-Page Book Translation
* **Context Preservation:** Joins book pages into a unified document before translating to maintain sentence and paragraph context across page splits.
* **Floating `[T]` Button:** Interactive button in `BookViewScreen` toggling between translated and original pages.

### 🪧 Area Sign Translation
* **Spatial Sign Clustering:** Scans neighboring signs within a 5-block radius to translate multi-sign boards together.
* **Instant Original Text Preview:** Hold the configured key (default: `,` comma) to instantly view original text on signs and nametags.

### 🏷️ Entity & Item Name Translation
* Custom nametags on entities and item hover tooltips are translated with a `[T]` indicator.

### ⚙️ Cloth Config & ModMenu
* Built-in configuration screen to customize API endpoints, API keys, target languages, category switches, and keybinds.

---

## 🏛️ Architectural Isolation & Boundaries

Stellarlang is engineered strictly for client instances (`ClientModInitializer`). As enforced by [Detekt](../config/detekt/detekt-stellar-lang.yml):

- **100% Client-Side:** Never imports dedicated server Minecraft classes (`net.minecraft.server.dedicated.*`).
- **No Server Lifecycle Hooks:** Prohibited from using server-side entrypoints.
- **Decoupled from Server Moderation:** Never imports or directly couples with `com.stellar.ops.*`.

---

## 🛠️ Technical Details

* **Target Minecraft Version:** 26.2
* **Mod Loader:** Quilt Loader (`0.30.1+`)
* **Environment:** Client-side only (`client` entrypoint: `com.stellar.lang.StellarLangMod`)
* **Language:** Kotlin on Java 21
* **Dependencies:**
  * **Stellarcore:** Bundled internally via Gradle Fat Jar.
  * **Mod Menu:** Soft dependency for accessing the configuration GUI in-game.
  * **Cloth Config:** Configuration screen provider.

---

## 🧪 Testing & Verification

* **Unit Tests (Kotest):**
  ```bash
  ./gradlew :stellar-lang:test
  ```
  Runs unit specifications in [`StellarLangSpec.kt`](src/test/kotlin/com/stellar/lang/StellarLangSpec.kt), [`StellarLangConfigSpec.kt`](src/test/kotlin/com/stellar/lang/StellarLangConfigSpec.kt), and [`TranslationServiceSpec.kt`](src/test/kotlin/com/stellar/lang/TranslationServiceSpec.kt).

* **Detekt Static Analysis:**
  ```bash
  ./gradlew :stellar-lang:detekt
  ```

---

## 📦 Building from Source

```bash
./gradlew :stellar-lang:build
```

The compiled mod JAR will be located at:
```
stellar-lang/build/libs/stellar-lang-1.0.0.jar
```

---

## 🐳 Local LibreTranslate Server (Docker)

To run a fast, self-hosted LibreTranslate server locally without rate limits or API key requirements:

```bash
# Start the local LibreTranslate service with most major languages pre-loaded
docker compose up -d

# Verify available languages and service health
curl http://localhost:5000/languages
```

### Configure in Minecraft
1. Open **Mod Menu** -> **Stellar Lang** -> **API & Keys**.
2. Set **LibreTranslate API Host** to `http://localhost:5000` (or `http://127.0.0.1:5000`).
3. Leave **API Key** blank.

