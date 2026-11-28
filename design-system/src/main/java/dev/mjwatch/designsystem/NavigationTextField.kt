@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.mjwatch.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Keep the form/model as the source of truth while the TV field owns its cursor and IME. */
@Composable private fun boundField(value:String,onValueChange:(String)->Unit):TextFieldState {
    val state=remember {TextFieldState(value)}
    val latestValue by rememberUpdatedState(value)
    val update by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if(state.text.toString()!=value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow {state.text.toString()}.collect {if(it!=latestValue) update(it)}
    }
    return state
}

/** D-pad focus is for navigation. Open the IME only on a deliberate press or tap. */
@Composable internal fun NavigationTextField(
    value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,label:@Composable ()->Unit,
    enabled:Boolean=true,singleLine:Boolean=false,maxLines:Int=Int.MAX_VALUE,isError:Boolean=false,
    leadingIcon:(@Composable ()->Unit)?=null,trailingIcon:(@Composable ()->Unit)?=null,
    suffix:(@Composable ()->Unit)?=null,supportingText:(@Composable ()->Unit)?=null,
    keyboardOptions:KeyboardOptions=KeyboardOptions.Default,onSearch:(()->Unit)?=null,
    shape:Shape=OutlinedTextFieldDefaults.shape,hideText:Boolean?=null,
) {
    if(!LocalTvNavigation.current) {
        OutlinedTextField(value,onValueChange,modifier,enabled=enabled,label=label,
            leadingIcon=leadingIcon,trailingIcon=trailingIcon,suffix=suffix,supportingText=supportingText,
            isError=isError,singleLine=singleLine,maxLines=maxLines,shape=shape,keyboardOptions=keyboardOptions,
            keyboardActions=if(onSearch==null) KeyboardActions.Default else KeyboardActions(onSearch={onSearch()}),
            visualTransformation=if(hideText==true) PasswordVisualTransformation() else VisualTransformation.None)
        return
    }
    val state=boundField(value,onValueChange)
    val keyboard=LocalSoftwareKeyboardController.current
    val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    var focused by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf(false)}
    val field=modifier.onFocusChanged {
        focused=it.isFocused
        if(!focused) editing=false
    }.onPreviewKeyEvent {event->
        if(!focused||imeVisible||event.key !in listOf(Key.DirectionCenter,Key.Enter,Key.NumPadEnter)) false
        else {
            if(event.type==KeyEventType.KeyUp) {
                if(editing) keyboard?.show() else editing=true
            }
            true
        }
    }
    val options=keyboardOptions.copy(showKeyboardOnFocus=editing)
    if(hideText!=null) {
        OutlinedSecureTextField(state,field,enabled=enabled,label={label()},trailingIcon=trailingIcon,
            shape=shape,keyboardOptions=options,textObfuscationMode=if(hideText) TextObfuscationMode.Hidden else TextObfuscationMode.Visible)
    } else {
        OutlinedTextField(state,field,enabled=enabled,label={label()},leadingIcon=leadingIcon,
            trailingIcon=trailingIcon,suffix=suffix,supportingText=supportingText,isError=isError,shape=shape,
            keyboardOptions=options,onKeyboardAction=onSearch?.let { submit->{if(state.text.toString()!=value)onValueChange(state.text.toString());submit()} },
            lineLimits=if(singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines=maxLines))
    }
}
