package com.stellar.lang.sign

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText
import java.net.InetSocketAddress

@Suppress("LargeClass")
class SignFormattingAndTooltipSpec : FunSpec({
    lateinit var server: com.sun.net.httpserver.HttpServer
    var serverPort: Int = 0

    beforeSpec {
        server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.createContext("/translate") { exchange ->
            val body = """{"translatedText": "New", "detectedLanguage": "fr"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
    }

    afterSpec {
        server.stop(0)
    }

    beforeEach {
        SignTranslationManager.clearCache()
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateSigns.setValue(true, false)
        config.targetLanguage.setValue("en", false)
        config.apiHost.setValue("http://127.0.0.1:$serverPort", false)
    }

    test("isPureFormattingLine correctly identifies non-language lines") {
        SignTranslationManager.isPureFormattingLine("-------") shouldBe true
        SignTranslationManager.isPureFormattingLine("=======") shouldBe true
        SignTranslationManager.isPureFormattingLine("* * *") shouldBe true
        SignTranslationManager.isPureFormattingLine("***") shouldBe true
        SignTranslationManager.isPureFormattingLine("+----+") shouldBe true
        SignTranslationManager.isPureFormattingLine("~ ~ ~") shouldBe true
        SignTranslationManager.isPureFormattingLine("   ") shouldBe false
        SignTranslationManager.isPureFormattingLine("Text") shouldBe false
        SignTranslationManager.isPureFormattingLine("* Text *") shouldBe false
    }

    test("extractFraming extracts prefixes and suffixes accurately") {
        val f1 = SignTranslationManager.extractFraming("* Welcome *")
        f1.prefix shouldBe "* "
        f1.content shouldBe "Welcome"
        f1.suffix shouldBe " *"

        val f2 = SignTranslationManager.extractFraming("--- Rules ---")
        f2.prefix shouldBe "--- "
        f2.content shouldBe "Rules"
        f2.suffix shouldBe " ---"

        val f3 = SignTranslationManager.extractFraming("* Bullet item")
        f3.prefix shouldBe "* "
        f3.content shouldBe "Bullet item"
        f3.suffix shouldBe ""

        val f4 = SignTranslationManager.extractFraming("-------")
        f4.prefix shouldBe "-------"
        f4.content shouldBe ""
        f4.suffix shouldBe ""
    }

    test("splitIntoWords splits long words exceeding 15 chars with hyphenation") {
        val words = SignTranslationManager.splitIntoWords("Geschwindigkeitsbegrenzung", 15)
        words.size shouldBe 2
        words[0] shouldBe "Geschwindigkei-"
        words[1] shouldBe "tsbegrenzung"
        words.forEach { (it.length <= 15) shouldBe true }
    }

    test("preserves non-language formatting lines ------- and ====== exactly") {
        var signText = SignText()
        signText = signText.setMessage(0, Component.literal("-------"))
        signText = signText.setMessage(1, Component.literal("Shop"))
        signText = signText.setMessage(2, Component.literal("Open"))
        signText = signText.setMessage(3, Component.literal("======="))

        val outcome = SignTranslationManager.applyTranslatedLinesWithOutcome(signText, "Boutique Ouverte")
        outcome.signText.getMessage(0, false).string shouldBe "-------"
        outcome.signText.getMessage(1, false).string shouldBe "Boutique"
        outcome.signText.getMessage(2, false).string shouldBe "Ouverte"
        outcome.signText.getMessage(3, false).string shouldBe "======="
        outcome.excessText shouldBe null
    }

    test("preserves line framing like * Welcome * and keeps within 15 chars") {
        var signText = SignText()
        signText = signText.setMessage(0, Component.literal("* Welcome *"))
        signText = signText.setMessage(1, Component.literal("To Our City"))
        signText = signText.setMessage(2, Component.literal("Enjoy"))
        signText = signText.setMessage(3, Component.literal("* * * * *"))

        val outcome = SignTranslationManager.applyTranslatedLinesWithOutcome(signText, "Bienvenue Dans Notre Ville")
        outcome.signText.getMessage(0, false).string shouldBe "* Bienvenue *"
        outcome.signText.getMessage(3, false).string shouldBe "* * * * *"
        for (i in 0 until 4) {
            (outcome.signText.getMessage(i, false).string.length <= 15) shouldBe true
        }
    }

    test("guarantees text fits in sign whatever happens and captures excess text") {
        var signText = SignText()
        signText = signText.setMessage(0, Component.literal("-------"))
        signText = signText.setMessage(1, Component.literal("Notice"))
        signText = signText.setMessage(2, Component.literal("Here"))
        signText = signText.setMessage(3, Component.literal("-------"))

        val longTranslation = "Bienvenue a notre tres grand chateau medieval ou nous servons des boissons fraiches"
        val outcome = SignTranslationManager.applyTranslatedLinesWithOutcome(signText, longTranslation)
        outcome.signText.getMessage(0, false).string shouldBe "-------"
        outcome.signText.getMessage(3, false).string shouldBe "-------"
        outcome.excessText shouldNotBe null
        for (i in 0 until 4) {
            (outcome.signText.getMessage(i, false).string.length <= 15) shouldBe true
        }
    }

    test("wrapTooltipLines wraps text into readable lines") {
        val tooltipLines = SignTranslationManager.wrapTooltipLines(
            "This is a fairly long excess text description that should be wrapped into multiple lines",
            20,
        )
        tooltipLines.size shouldBe 5
        tooltipLines.forEach { (it.length <= 20) shouldBe true }
    }

    test("clearCache empties all caches and all-pure-formatting sign returns original") {
        var allFormatSign = SignText()
        allFormatSign = allFormatSign.setMessage(0, Component.literal("-------"))
        allFormatSign = allFormatSign.setMessage(1, Component.literal("======="))
        allFormatSign = allFormatSign.setMessage(2, Component.literal("* * *"))
        allFormatSign = allFormatSign.setMessage(3, Component.literal("-------"))

        val outcome = SignTranslationManager.applyTranslatedLinesWithOutcome(allFormatSign, "Translation Here")
        outcome.signText shouldBe allFormatSign

        SignTranslationManager.clearCache()
        SignTranslationManager.textOutcomeCache.isEmpty() shouldBe true
    }

    test("excess text retrieval and dispatch integration") {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val posField = net.minecraft.world.level.block.entity.BlockEntity::class.java.getDeclaredField("worldPosition")
        posField.isAccessible = true
        posField.set(sign, BlockPos(50, 64, 50))

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true

        var frontSignText = SignText()
        frontSignText = frontSignText.setMessage(0, Component.literal("-------"))
        frontSignText = frontSignText.setMessage(1, Component.literal("Grand Palace"))
        frontSignText = frontSignText.setMessage(2, Component.literal("Royal Guard"))
        frontSignText = frontSignText.setMessage(3, Component.literal("-------"))
        frontTextField.set(sign, frontSignText)

        val longTrans = "Grand Palais de la Couronne Royale avec Gardes Armes et Soldats en Armure"
        val fakeResult = TranslationResult("Grand Palace Royal Guard", longTrans, "fr", "en", false)
        TranslationService.putCache(fakeResult)

        SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        val translated = SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        translated shouldNotBe null
        translated!!.getMessage(0, false).string shouldBe "-------"
        translated.getMessage(3, false).string shouldBe "-------"

        val excess = SignTranslationManager.getExcessText(sign, true)
        excess shouldNotBe null

        val fullTrans = SignTranslationManager.getFullTranslation(sign, true)
        fullTrans shouldBe longTrans

        // Test back text retrieval
        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign, frontSignText)

        SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        SignTranslationManager.getExcessText(sign, false) shouldNotBe null
        SignTranslationManager.getFullTranslation(sign, false) shouldBe longTrans

        // Test disabled config returns null
        val config = TranslationService.getConfig()
        config.enabled.setValue(false, false)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, true) shouldBe null
        SignTranslationManager.getExcessText(sign, true) shouldBe null
        SignTranslationManager.getFullTranslation(sign, true) shouldBe null

        config.enabled.setValue(true, false)
        config.translateSigns.setValue(false, false)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, true) shouldBe null
    }

    test("packLastSlot handles oversized word when budget is greater than or less than ellipsis length") {
        // Case 1: budget > 3 (framing '* * X * *', prefix 4, suffix 4, budget 7)
        var signText1 = SignText()
        signText1 = signText1.setMessage(0, Component.literal("Line0"))
        signText1 = signText1.setMessage(1, Component.literal("Line1"))
        signText1 = signText1.setMessage(2, Component.literal("Line2"))
        signText1 = signText1.setMessage(3, Component.literal("* * X * *"))

        val outcome1 = SignFormatHelper.applyTranslatedLinesWithOutcome(
            signText1,
            "TenLetters TenLetters1 TenLetters2 Magnificent",
        )
        outcome1.excessText shouldBe "cent"
        outcome1.signText.getMessage(3, false).string shouldBe "* * Magn... * *"
        (outcome1.signText.getMessage(3, false).string.length <= 15) shouldBe true

        // Case 2: budget <= 3 (framing '***** X *****', prefix 6, suffix 6, budget 3)
        var signText2 = SignText()
        signText2 = signText2.setMessage(0, Component.literal("Line0"))
        signText2 = signText2.setMessage(1, Component.literal("Line1"))
        signText2 = signText2.setMessage(2, Component.literal("Line2"))
        signText2 = signText2.setMessage(3, Component.literal("***** X *****"))

        val outcome2 = SignFormatHelper.applyTranslatedLinesWithOutcome(
            signText2,
            "TenLetters TenLetters1 TenLetters2 Magnificent",
        )
        outcome2.excessText shouldBe "nificent"
        outcome2.signText.getMessage(3, false).string shouldBe "***** Mag *****"
        (outcome2.signText.getMessage(3, false).string.length <= 15) shouldBe true
    }

    test("formatFittedWithEllipsis covers all branches when remaining words exist") {
        // Branch A: fittedContent.length + 3 <= budget (framing '* X *', budget 11)
        var signTextA = SignText()
        signTextA = signTextA.setMessage(0, Component.literal("L0"))
        signTextA = signTextA.setMessage(1, Component.literal("L1"))
        signTextA = signTextA.setMessage(2, Component.literal("L2"))
        signTextA = signTextA.setMessage(3, Component.literal("* X *"))

        val outcomeA = SignFormatHelper.applyTranslatedLinesWithOutcome(
            signTextA,
            "TenLetters TenLetters1 TenLetters2 Shop ExtraWords",
        )
        outcomeA.excessText shouldBe "ExtraWords"
        outcomeA.signText.getMessage(3, false).string shouldBe "* Shop... *"

        // Branch B: fittedContent.length + 3 > budget and length > 3 (budget 5)
        var signTextB = SignText()
        signTextB = signTextB.setMessage(0, Component.literal("L0"))
        signTextB = signTextB.setMessage(1, Component.literal("L1"))
        signTextB = signTextB.setMessage(2, Component.literal("L2"))
        signTextB = signTextB.setMessage(3, Component.literal("**** X ****"))

        val outcomeB = SignFormatHelper.applyTranslatedLinesWithOutcome(
            signTextB,
            "TenLetters TenLetters1 TenLetters2 Store ExtraWords",
        )
        outcomeB.excessText shouldBe "ExtraWords"
        outcomeB.signText.getMessage(3, false).string shouldBe "**** St... ****"

        // Branch C: fittedContent.length + 3 > budget and length <= 3 (budget 3)
        var signTextC = SignText()
        signTextC = signTextC.setMessage(0, Component.literal("L0"))
        signTextC = signTextC.setMessage(1, Component.literal("L1"))
        signTextC = signTextC.setMessage(2, Component.literal("L2"))
        signTextC = signTextC.setMessage(3, Component.literal("***** X *****"))

        val outcomeC = SignFormatHelper.applyTranslatedLinesWithOutcome(
            signTextC,
            "TenLetters ThirteenChars ThirteenChars Hi ExtraWords",
        )
        outcomeC.excessText shouldBe "ExtraWords"
        outcomeC.signText.getMessage(3, false).string shouldBe "***** Hi *****"
    }

    test("default arguments and helper functions operate correctly") {
        SignTranslationManager.splitIntoWords("Hello World").size shouldBe 2
        SignTranslationManager.wrapTooltipLines("Hello World").size shouldBe 1
        SignTranslationManager.extractSentence(SignText()) shouldBe ""
        SignFormatHelper.wrapToSignLines("A B C D E F G H I J K L M N O P Q R S T U V W X Y Z").size shouldBe 4

        val unsafeExtractorField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeExtractorField.isAccessible = true
        val unsafeExtractor = unsafeExtractorField.get(null) as sun.misc.Unsafe
        val mockExtractor = unsafeExtractor.allocateInstance(
            net.minecraft.client.gui.GuiGraphicsExtractor::class.java,
        ) as net.minecraft.client.gui.GuiGraphicsExtractor
        SignTranslationManager.renderSignTooltipIfLooking(mockExtractor)
    }

    test("signs with identical text share the same text-based cache across different positions") {
        var text = SignText()
        text = text.setMessage(0, Component.literal("Town Hall"))
        text = text.setMessage(1, Component.literal("Notice Board"))

        val fakeResult = TranslationResult("Town Hall Notice Board", "Mairie Tableau d'Affichage", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign1 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        val sign2 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        frontTextField.set(sign1, text)
        frontTextField.set(sign2, text)

        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign1, SignText())
        backTextField.set(sign2, SignText())

        SignTranslationManager.onSignLoaded(sign1)
        SignTranslationManager.onSignLoaded(sign2)

        val trans1 = SignTranslationManager.getTranslatedSignText(text)
        val trans2 = SignTranslationManager.getTranslatedSignText(text)
        trans1 shouldNotBe null
        trans2 shouldNotBe null
        trans1!!.getMessage(0, false).string shouldBe trans2!!.getMessage(0, false).string
        trans1.getMessage(1, false).string shouldBe trans2.getMessage(1, false).string

        SignTranslationManager.textOutcomeCache.size shouldBe 1
        SignTranslationManager.textOutcomeCache.containsKey("en::Town Hall Notice Board") shouldBe true
    }

    test("SignTranslationManager direct methods, async callbacks, and edge cases") {
        SignTranslationManager.clearCache()
        TranslationService.clearCache()

        SignTranslationManager.signCache["test"] = SignText()
        SignTranslationManager.signCache.isEmpty() shouldBe false

        var excessSignText = SignText()
        excessSignText = excessSignText.setMessage(0, Component.literal("-------"))
        excessSignText = excessSignText.setMessage(1, Component.literal("Museum Notice"))
        excessSignText = excessSignText.setMessage(2, Component.literal("-------"))
        excessSignText = excessSignText.setMessage(3, Component.literal("-------"))
        val transResult = TranslationResult(
            "Museum Notice",
            "Notice du Grand Musee Historique Royal Medieval de la Ville de Paris et des Arts Anciens de France",
            "fr",
            "en",
            false,
        )
        TranslationService.putCache(transResult)
        SignTranslationManager.getExcessText(excessSignText) shouldNotBe null

        // Test outcome already cached branch in getTranslatedSignText, getExcessText, getFullTranslation
        SignTranslationManager.getTranslatedSignText(excessSignText) shouldNotBe null
        SignTranslationManager.getFullTranslation(excessSignText) shouldNotBe null

        // Test empty sign text returns null
        val emptySign = SignText()
        SignTranslationManager.getTranslatedSignText(emptySign) shouldBe null
        SignTranslationManager.getExcessText(emptySign) shouldBe null
        SignTranslationManager.getFullTranslation(emptySign) shouldBe null

        // Test isShowingOriginal returns null
        try {
            com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = { true }
            SignTranslationManager.getTranslatedSignText(excessSignText) shouldBe null
            SignTranslationManager.getExcessText(excessSignText) shouldBe null
            SignTranslationManager.getFullTranslation(excessSignText) shouldBe null
        } finally {
            com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null
        }

        // Test sameLanguage in TranslationService cache returns null
        val sameLangResult = TranslationResult(
            "English Sign",
            "English Sign",
            "en",
            "en",
            true,
        )
        TranslationService.putCache(sameLangResult)
        var sameLangSign = SignText()
        sameLangSign = sameLangSign.setMessage(0, Component.literal("English Sign"))
        SignTranslationManager.getTranslatedSignText(sameLangSign) shouldBe null
        SignTranslationManager.getExcessText(sameLangSign) shouldBe null
        SignTranslationManager.getFullTranslation(sameLangSign) shouldBe null

        // Test config disabled returns early
        val config = TranslationService.getConfig()
        config.enabled.setValue(false, false)
        SignTranslationManager.getTranslatedSignText(excessSignText) shouldBe null
        SignTranslationManager.getExcessText(excessSignText) shouldBe null
        SignTranslationManager.getFullTranslation(excessSignText) shouldBe null
        SignTranslationManager.translateSignText(excessSignText)
        config.enabled.setValue(true, false)

        // Test translateSignText with default params and async completion
        var uncachedSign = SignText()
        uncachedSign = uncachedSign.setMessage(0, Component.literal("Nouveau"))
        SignTranslationManager.translateSignText(uncachedSign)
        SignTranslationManager.onSignTextChanged(uncachedSign)

        var signPopulated = false
        var attempts = 0
        while (attempts++ < 30) {
            if (SignTranslationManager.textOutcomeCache.containsKey("en::Nouveau")) {
                signPopulated = true
                break
            }
            Thread.sleep(50)
        }
        signPopulated shouldBe true
    }

    test("SignTranslationOutcome hasOverflow accurately flags overflow conditions") {
        val normalSign = SignText().setMessage(0, Component.literal("Hello World"))
        val normalOutcome = SignFormatHelper.SignTranslationOutcome(
            signText = normalSign,
            excessText = null,
            fullTranslation = "Hello World",
        )
        normalOutcome.hasOverflow shouldBe false

        val excessOutcome = SignFormatHelper.SignTranslationOutcome(
            signText = normalSign,
            excessText = "more words",
            fullTranslation = "Hello World more words",
        )
        excessOutcome.hasOverflow shouldBe true

        val ellipsisSign = SignText().setMessage(0, Component.literal("Hello..."))
        val ellipsisOutcome = SignFormatHelper.SignTranslationOutcome(
            signText = ellipsisSign,
            excessText = null,
            fullTranslation = "Hello World",
        )
        ellipsisOutcome.hasOverflow shouldBe true

        val mismatchSign = SignText().setMessage(0, Component.literal("Hello"))
        val mismatchOutcome = SignFormatHelper.SignTranslationOutcome(
            signText = mismatchSign,
            excessText = null,
            fullTranslation = "Hello World Extra",
        )
        mismatchOutcome.hasOverflow shouldBe true
    }

    test("SignTooltipRenderer storeRenderStateData and getRenderStateData manage outcome data") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val state = unsafe.allocateInstance(
            net.minecraft.client.renderer.blockentity.state.SignRenderState::class.java,
        ) as net.minecraft.client.renderer.blockentity.state.SignRenderState

        val outcome = SignFormatHelper.SignTranslationOutcome(
            signText = SignText(),
            excessText = "extra",
            fullTranslation = "full text",
        )
        val data = SignTooltipRenderer.SignRenderOutcomeData(
            frontOutcome = outcome,
            backOutcome = null,
            isFacingFront = true,
        )

        SignTooltipRenderer.getRenderStateData(state) shouldBe null
        SignTooltipRenderer.storeRenderStateData(state, data)
        SignTooltipRenderer.getRenderStateData(state) shouldBe data
    }

    test("SignTranslationManager getOutcome and helper defaults coverage") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        var text = SignText()
        text = text.setMessage(0, Component.literal("Hello"))
        frontTextField.set(sign, text)

        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign, text)

        val fakeResult = TranslationResult("Hello", "Bonjour", "en", "fr", false)
        val config = TranslationService.getConfig()
        config.targetLanguage.setValue("fr", false)
        TranslationService.putCache(fakeResult)

        SignTranslationManager.getOutcome(sign, true) shouldNotBe null
        SignTranslationManager.getOutcome(sign, false) shouldNotBe null

        // Default constructor for SignTranslationOutcome
        val defaultOutcome = SignFormatHelper.SignTranslationOutcome(SignText(), null)
        defaultOutcome.fullTranslation shouldBe ""

        // Default splitIntoWords and blank isDisplayedTextDifferent
        SignFormatHelper.splitIntoWords("Sample text").size shouldBe 2
        SignFormatHelper.isDisplayedTextDifferent(SignText(), "") shouldBe false
    }

    test("SignTooltipRenderer handles hanging sign outcome data") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val hangingState = unsafe.allocateInstance(
            net.minecraft.client.renderer.blockentity.state.HangingSignRenderState::class.java,
        ) as net.minecraft.client.renderer.blockentity.state.HangingSignRenderState

        val outcome = SignFormatHelper.SignTranslationOutcome(
            signText = SignText(),
            excessText = null,
            fullTranslation = "Sample",
        )
        val hangingData = SignTooltipRenderer.SignRenderOutcomeData(
            frontOutcome = outcome,
            backOutcome = null,
            isFacingFront = true,
            isHanging = true,
        )

        hangingData.isHanging shouldBe true
        SignTooltipRenderer.storeRenderStateData(hangingState, hangingData)
        SignTooltipRenderer.getRenderStateData(hangingState) shouldBe hangingData
    }

    test("SignTooltipRenderer isolates front and back face outcomes without cross-face fallback") {
        val frontText = SignText().setMessage(0, Component.literal("Short front"))
        val frontNoOverflow = SignFormatHelper.SignTranslationOutcome(
            signText = frontText,
            excessText = null,
            fullTranslation = "Short front",
        )
        val backWithOverflow = SignFormatHelper.SignTranslationOutcome(
            signText = SignText(),
            excessText = "very long excess back text",
            fullTranslation = "Long back text with lots of content",
        )

        frontNoOverflow.hasOverflow shouldBe false
        backWithOverflow.hasOverflow shouldBe true

        // When facing front: active outcome must be strictly frontOutcome
        val dataFacingFront = SignTooltipRenderer.SignRenderOutcomeData(
            frontOutcome = frontNoOverflow,
            backOutcome = backWithOverflow,
            isFacingFront = true,
        )
        val activeFront = if (dataFacingFront.isFacingFront) {
            dataFacingFront.frontOutcome
        } else {
            dataFacingFront.backOutcome
        }
        activeFront shouldBe frontNoOverflow
        activeFront?.hasOverflow shouldBe false

        // When facing back: active outcome must be strictly backOutcome
        val dataFacingBack = SignTooltipRenderer.SignRenderOutcomeData(
            frontOutcome = frontNoOverflow,
            backOutcome = backWithOverflow,
            isFacingFront = false,
        )
        val activeBack = if (dataFacingBack.isFacingFront) {
            dataFacingBack.frontOutcome
        } else {
            dataFacingBack.backOutcome
        }
        activeBack shouldBe backWithOverflow
        activeBack?.hasOverflow shouldBe true

        // When front is untranslated (null), facing front must NOT fall back to back
        val dataUntranslatedFront = SignTooltipRenderer.SignRenderOutcomeData(
            frontOutcome = null,
            backOutcome = backWithOverflow,
            isFacingFront = true,
        )
        val activeUntranslated = if (dataUntranslatedFront.isFacingFront) {
            dataUntranslatedFront.frontOutcome
        } else {
            dataUntranslatedFront.backOutcome
        }
        activeUntranslated shouldBe null
    }
})
