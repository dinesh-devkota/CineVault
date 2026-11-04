package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton

/** TV Material 1.0.1 handles D-pad keys, but not pointer taps. Keep its focus/key semantics. */
@Composable
fun Modifier.pointerClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val action = rememberUpdatedState(onClick)
    val focus = remember { FocusRequester() }
    return focusRequester(focus).pointerInput(enabled) {
        if (enabled) detectTapGestures {
            focus.requestFocus()
            action.value()
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
           content: @Composable RowScope.() -> Unit) {
    TvButton(onClick, modifier.pointerClick(enabled, onClick), enabled = enabled, content = content)
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                   content: @Composable RowScope.() -> Unit) {
    TvOutlinedButton(onClick, modifier.pointerClick(enabled, onClick), enabled = enabled, content = content)
}
