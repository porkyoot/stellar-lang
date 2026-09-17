package com.stellar.lang

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Basic mod metadata tests for Stellar Lang.
 */
class StellarLangSpec : FunSpec({
    test("stellar lang mod id should be valid") {
        StellarLangMod.MOD_ID.shouldNotBeBlank()
        StellarLangMod.MOD_ID shouldBe "stellar_lang"
    }
})
