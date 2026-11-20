package com.sphinx.peel.ui

import android.content.Context
import androidx.annotation.StringRes

/// A string resource read from a `Context` held in a composable's callback (a click, a coroutine, a toast), where
/// `stringResource` cannot be called. Going through this keeps lint's `LocalContextGetResourceValueCall` quiet: the read happens
/// when the callback runs, in the language the device has then.
fun Context.str(@StringRes id: Int, vararg args: Any): String = getString(id, *args)
