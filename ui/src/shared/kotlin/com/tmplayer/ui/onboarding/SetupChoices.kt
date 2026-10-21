package com.tmplayer.ui.onboarding

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.Recommendation
import com.tmplayer.data.SettingsStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.launch

/**
 * Where a setup step's settings live in Settings otherwise: the place's name as Settings titles it,
 * and how to get there. [open] jumps to that page and to the row.
 */
class SetupPlace(val name: String, val open: () -> Unit)

/**
 * Settings' Setup choices: every step the setup after sign in asks on this device, in the same
 * order, each with the picture of the answer that is set, the answer in words, the same two
 * answers to change it, and where the setting also lives. Changing an answer here writes exactly
 * what the setup writes ([applyTo]). The steps are [Setup.stepsFor]'s, so a step the setup leaves
 * out (Mobile data off a phone, the Device step when nothing is recommended) is left out here too.
 * Run the setup again sits at the end.
 *
 * The host draws its own heading and scrolling; this is the page's body. [place] says where a
 * step's settings also live, null where they live nowhere else. [landing] goes on the control a
 * remote starts on: the row of [focusStep], or the first row.
 */
@Composable
fun SetupChoicesList(
    settings: SettingsStore,
    place: (SetupStep) -> SetupPlace?,
    onRunSetupAgain: () -> Unit,
    modifier: Modifier = Modifier,
    form: DeviceForm = deviceForm(),
    landing: FocusRequester? = null,
    focusStep: SetupStep? = null,
    fit: DeviceFit = rememberDeviceFit(),
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val online = OnlineMetadata.current
    val light by settings.lightSettings.collectAsState(initial = null)
    val seekThumbnails = rememberSeekThumbnailsShown()
    // Read once, as the page opens, the way the setup reads them: applying them must not take the
    // Device step away under the viewer.
    val atOpen = remember(light != null) {
        light?.let { DeviceFitRules.recommendations(fit, it, seekThumbnails, pictures = hasPictureSettings) }
    }
    val stillOff = light?.let { now ->
        remember(now) { DeviceFitRules.recommendations(fit, now, seekThumbnails, pictures = hasPictureSettings) }
    }
    val lowStorage by settings.lowStorageMode.collectAsState(initial = false)
    val lighter by settings.lighterPictures.collectAsState(initial = false)
    val saveData by settings.saveMobileData.collectAsState(initial = false)
    val posters = online?.settings?.collectAsState()?.value?.enabled ?: true
    val values = SetupValues(
        posters = posters,
        lowStorage = lowStorage,
        lighter = lighter,
        saveData = saveData,
    )
    if (atOpen == null) return
    val steps = remember(form, online, atOpen) {
        Setup.stepsFor(form, posters = online != null, recommendations = atOpen)
    }

    fun pick(choice: SetupChoice) {
        scope.launch {
            runCatching {
                choice.applyTo(
                    settings,
                    posters = online?.let { o -> { on: Boolean -> o.setEnabled(on) } },
                    recommendations = atOpen,
                )
            }
        }
    }

    val starts = focusStep?.takeIf { it in steps } ?: steps.firstOrNull()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.setupChoicesBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        for (step in steps) {
            StepRow(
                step = step,
                values = values,
                form = form,
                fit = fit,
                recommendations = atOpen,
                applied = stillOff?.isEmpty() == true,
                place = place(step),
                landing = if (step == starts) landing else null,
                onPick = ::pick,
            )
        }
        // On the page's own ground, under the rows, so the button's fill stands out from it.
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s.settingsWalkthroughBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            SetupButton(
                s.settingsWalkthrough,
                modifier = if (steps.isEmpty() && landing != null) Modifier.focusRequester(landing) else Modifier,
                onClick = onRunSetupAgain,
            )
        }
    }
}

@Composable
private fun StepRow(
    step: SetupStep,
    values: SetupValues,
    form: DeviceForm,
    fit: DeviceFit,
    recommendations: List<Recommendation>,
    applied: Boolean,
    place: SetupPlace?,
    landing: FocusRequester?,
    onPick: (SetupChoice) -> Unit,
) {
    val s = LocalStrings.current
    val chosen = values.chosen(step)
    val thumb = if (form == DeviceForm.Phone) 88.dp else 112.dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Large))
            .background(Tone.surfaceHigh)
            .padding(14.dp)
            .testTag("setup-choice-${step.name}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chosen != null) {
                // The picture of the answer that is set, the one its card in the setup shows.
                Box(
                    Modifier.size(thumb).clip(RoundedCornerShape(Corner.Medium)).background(Tone.background),
                ) {
                    ChoicePicture(chosen, Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = if (step == SetupStep.Device) s.fitHeadline(fit) else s.setupCopy(step).title
                Text(title, style = MaterialTheme.typography.titleMedium, color = Tone.text)
                val now = when {
                    chosen != null -> s.setupChoicesNow(s.choiceCopy(chosen).title)
                    applied -> s.setupChoicesDeviceOn
                    else -> s.setupChoicesDeviceOff
                }
                Text(now, style = MaterialTheme.typography.bodyMedium, color = Tone.accent)
                if (chosen != null) {
                    Text(
                        s.choiceCopy(chosen).detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.muted,
                        maxLines = 3,
                    )
                }
                if (place == null && step != SetupStep.Device) {
                    Text(s.setupChoicesOnlyHere, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
            }
        }
        if (step == SetupStep.Device) {
            RecommendationList(recommendations)
            if (!applied) {
                SetupButton(
                    s.deviceApply,
                    primary = true,
                    modifier = if (landing != null) Modifier.focusRequester(landing) else Modifier,
                ) { onPick(SetupChoice.ApplyRecommended) }
            }
        } else {
            ChoiceSwitch(
                choices = step.choices,
                selected = chosen,
                onPick = onPick,
                modifier = if (landing != null) Modifier.focusRequester(landing) else Modifier,
            )
        }
        if (place != null) PlaceLink(s.setupChoicesAlsoIn(place.name), place.open)
    }
}

/**
 * The step's two answers as one segmented control. A touch or a click picks the answer under it;
 * on a remote or a keyboard the control is one stop, where Left and Right pick the answer on that
 * side and OK swaps to the other.
 */
@Composable
private fun ChoiceSwitch(
    choices: List<SetupChoice>,
    selected: SetupChoice?,
    onPick: (SetupChoice) -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    // A new side taken is one tick, as a choice in a list is.
    val stepTick = com.tmplayer.ui.components.rememberStepTick()
    val picked: (SetupChoice) -> Unit = { choice -> if (choice != selected) stepTick(); onPick(choice) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val outer = RoundedCornerShape(Corner.Medium)
    val inner = RoundedCornerShape(Corner.Small)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(outer)
            .background(if (focused) Tone.focusFill.copy(alpha = 0.35f) else Tone.background)
            .focusRing(focused, outer)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val other = choices.firstOrNull { it != selected } ?: choices.first()
                val pick = when (event.key) {
                    Key.DirectionLeft -> choices[if (rtl) 1 else 0]
                    Key.DirectionRight -> choices[if (rtl) 0 else 1]
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> other
                    else -> return@onKeyEvent false
                }
                if (pick != selected) picked(pick)
                true
            }
            .focusable(interactionSource = interactions)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (choice in choices) {
            val on = choice == selected
            val title = s.choiceCopy(choice).title
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(inner)
                    .background(if (on) Tone.accent else Color.Transparent)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = on
                        onClick(label = title) {
                            picked(choice)
                            true
                        }
                    }
                    .pointerInput(choice) { detectTapGestures { picked(choice) } }
                    .testTag("setup-answer-${choice.name}")
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Icon(TmIcons.Check, contentDescription = null, tint = Tone.onAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) Tone.onAccent else Tone.text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** "Also in Playback": a quiet link that fills under the focus like the setup's buttons. */
@Composable
private fun PlaceLink(label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val touch = deviceForm() == DeviceForm.Phone
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .focusRing(focused, CircleShape)
            .clickable(
                interactionSource = interactions,
                indication = if (touch) LocalIndication.current else null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (focused) Tone.onFocusFill else Tone.accent
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (LocalLayoutDirection.current == LayoutDirection.Rtl) TmIcons.ChevronLeft else TmIcons.ChevronRight,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
    }
}
