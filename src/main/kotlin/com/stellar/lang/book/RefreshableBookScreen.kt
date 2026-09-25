package com.stellar.lang.book

import net.minecraft.client.gui.screens.inventory.BookViewScreen

/**
 * Interface implemented by BookViewScreen mixin to allow external refresh of book translation.
 */
interface RefreshableBookScreen {
    fun stellarGetBookAccess(): BookViewScreen.BookAccess?

    fun stellarRefreshBook(): Boolean
}
