// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.overlay

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowMetricsCalculator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.features.input.NativeInput
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.features.input.model.NativeAnalog
import dev.lemon.lemon_emu.features.input.model.NativeButton
import dev.lemon.lemon_emu.features.input.model.NpadStyleIndex
import dev.lemon.lemon_emu.features.settings.model.BooleanSetting
import dev.lemon.lemon_emu.features.settings.model.IntSetting
import dev.lemon.lemon_emu.overlay.model.OverlayControl
import dev.lemon.lemon_emu.overlay.model.OverlayControlData
import dev.lemon.lemon_emu.overlay.model.OverlayLayout
import dev.lemon.lemon_emu.overlay.model.OverlayPreset
import dev.lemon.lemon_emu.utils.NativeConfig

/**
 * Draws the interactive input overlay on top of the
 * emulation rendering surface.
 */
class InputOverlay(context: Context, attrs: AttributeSet?) :
    View(context, attrs),
    OnTouchListener {
    private val overlayButtons: MutableSet<InputOverlayDrawableButton> = HashSet()
    private val overlayDpads: MutableSet<InputOverlayDrawableDpad> = HashSet()
    private val overlayJoysticks: MutableSet<InputOverlayDrawableJoystick> = HashSet()
    private val imeEditable = Editable.Factory.getInstance().newEditable("")

    private var inEditMode = false
    private var gamelessMode = false
    private var buttonBeingConfigured: InputOverlayDrawableButton? = null
    private var dpadBeingConfigured: InputOverlayDrawableDpad? = null
    private var joystickBeingConfigured: InputOverlayDrawableJoystick? = null

    private var scaleDialog: OverlayScaleDialog? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasMoved = false
    private val moveThreshold = 20f

    // --- SWIPE CAMERA CONTROL (Плавающий свайп правого стика как в Android шутерах) ---
    private var cameraPointerId: Int = -1
    private var lastCameraX: Float = 0f
    private var lastCameraY: Float = 0f
    // Чувствительность свайпа камеры: оптимальный баланс точности и скорости поворота
    private val swipeSensitivity = 0.038f
    private val cameraResetHandler = Handler(Looper.getMainLooper())
    private var activePlayerIndex = 0

    private val resetStickRunnable = Runnable {
        sendJoystickEvent(activePlayerIndex, NativeAnalog.RStick, 0f, 0f)
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private lateinit var windowInsets: WindowInsets

    var layout = OverlayLayout.Landscape

    // External listener for EmulationFragment joypad overlay auto-hide
    var touchEventListener: ((MotionEvent) -> Unit)? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        imeEditable.clear()
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = imeEditable

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    forwardCommittedText(text)
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) {
                    NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_DEL)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return true
                }

                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_ENTER -> {
                        NativeLibrary.submitInlineKeyboardInput(event.keyCode)
                    }
                    else -> {
                        val textChar = event.unicodeChar
                        if (textChar != 0) {
                            NativeLibrary.submitInlineKeyboardText(textChar.toChar().toString())
                        }
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        windowInsets = rootWindowInsets

        val overlayControlData = NativeConfig.getOverlayControlData()
        if (overlayControlData.isEmpty()) {
            populateDefaultConfig()
        } else {
            checkForNewControls(overlayControlData)
        }

        // Load the controls.
        refreshControls()

        // Set the on touch listener.
        setOnTouchListener(this)

        // Force draw
        setWillNotDraw(false)

        // Request focus for the overlay so it has priority on presses.
        requestFocus()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw grid when in edit mode and snap-to-grid is enabled
        if (inEditMode && BooleanSetting.OVERLAY_SNAP_TO_GRID.getBoolean()) {
            drawGrid(canvas)
        }

        for (button in overlayButtons) {
            button.draw(canvas)
        }
        for (dpad in overlayDpads) {
            dpad.draw(canvas)
        }
        for (joystick in overlayJoysticks) {
            joystick.draw(canvas)
        }
    }

    private fun forwardCommittedText(text: CharSequence) {
        val builder = StringBuilder()
        text.forEach { character ->
            when (character) {
                '\n' -> {
                    if (builder.isNotEmpty()) {
                        NativeLibrary.submitInlineKeyboardText(builder.toString())
                        builder.clear()
                    }
                    NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_ENTER)
                }
                else -> builder.append(character)
            }
        }
        if (builder.isNotEmpty()) {
            NativeLibrary.submitInlineKeyboardText(builder.toString())
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val gridSize = IntSetting.OVERLAY_GRID_SIZE.getInt()
        val width = canvas.width
        val height = canvas.height

        // Draw vertical lines
        var x = 0
        while (x <= width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
            x += gridSize
        }

        // Draw horizontal lines
        var y = 0
        while (y <= height) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
            y += gridSize
        }
    }

    /**
     * Asked first for every touch: returns true when the touch was used to close the in-game menu
     * (a tap on the game while the menu is open), so it must not also reach the controls.
     */
    var menuOutsideTapHandler: ((MotionEvent) -> Boolean)? = null

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (menuOutsideTapHandler?.invoke(event) == true) {
            return true
        }

        try {
            touchEventListener?.invoke(event)
        } catch (e: Exception) {}

        if (inEditMode) {
            return onTouchWhileEditing(event)
        }

        var shouldUpdateView = false
        val playerIndex = when (NativeInput.getStyleIndex(0)) {
            NpadStyleIndex.Handheld -> 8
            else -> 0
        }
        activePlayerIndex = playerIndex

        for (button in overlayButtons) {
            if (!button.updateStatus(event)) {
                continue
            }
            sendButtonEvent(
                playerIndex,
                button.button,
                button.status
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        for (dpad in overlayDpads) {
            if (!dpad.updateStatus(event, BooleanSetting.DPAD_SLIDE.getBoolean())) {
                continue
            }
            sendButtonEvent(
                playerIndex,
                dpad.up,
                dpad.upStatus
            )
            sendButtonEvent(
                playerIndex,
                dpad.down,
                dpad.downStatus
            )
            sendButtonEvent(
                playerIndex,
                dpad.left,
                dpad.leftStatus
            )
            sendButtonEvent(
                playerIndex,
                dpad.right,
                dpad.rightStatus
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        for (joystick in overlayJoysticks) {
            if (!joystick.updateStatus(event)) {
                continue
            }
            sendJoystickEvent(
                playerIndex,
                joystick.joystick,
                joystick.xAxis,
                joystick.realYAxis
            )
            sendButtonEvent(
                playerIndex,
                joystick.button,
                joystick.buttonStatus
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        if (shouldUpdateView) {
            invalidate()
        }

        // Запоминаем текущий палец камеры перед обработкой, чтобы он не просочился в сенсор экрана
        val currentCameraFinger = cameraPointerId

        // Обработка жеста свайпа правого стика (Camera Control)
        handleCameraSwipe(event, playerIndex)

        if (!BooleanSetting.TOUCHSCREEN.getBoolean()) {
            return true
        }

        val pointerIndex = event.actionIndex
        val xPosition = event.getX(pointerIndex).toInt()
        val yPosition = event.getY(pointerIndex).toInt()
        val pointerId = event.getPointerId(pointerIndex)
        val motionEvent = event.action and MotionEvent.ACTION_MASK
        val isActionDown =
            motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_POINTER_DOWN
        val isActionMove = motionEvent == MotionEvent.ACTION_MOVE
        val isActionUp =
            motionEvent == MotionEvent.ACTION_UP || motionEvent == MotionEvent.ACTION_POINTER_UP

        val isCameraTouch = (pointerId == cameraPointerId || pointerId == currentCameraFinger)

        if (isActionDown && !isTouchInputConsumed(pointerId) && !isCameraTouch) {
            NativeInput.onTouchPressed(pointerId, xPosition.toFloat(), yPosition.toFloat())
        }

        if (isActionMove) {
            for (i in 0 until event.pointerCount) {
                val fingerId = event.getPointerId(i)
                if (isTouchInputConsumed(fingerId) || fingerId == cameraPointerId || fingerId == currentCameraFinger) {
                    continue
                }
                NativeInput.onTouchMoved(fingerId, event.getX(i), event.getY(i))
            }
        }

        if (isActionUp && !isTouchInputConsumed(pointerId) && !isCameraTouch) {
            NativeInput.onTouchReleased(pointerId)
        }

        return true
    }

    /**
     * Обрабатывает свободный свайп пальцем в правой половине экрана
     * и транслирует дельту движения в координаты правого аналогового стика.
     */
    private fun handleCameraSwipe(event: MotionEvent, playerIndex: Int) {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                // Зона свайпа: правая часть экрана (начиная с 45% ширины экрана) в свободной от кнопок зоне
                if (cameraPointerId == -1 && x > (width * 0.45f) && !isTouchInputConsumed(pointerId)) {
                    cameraPointerId = pointerId
                    lastCameraX = x
                    lastCameraY = y
                    cameraResetHandler.removeCallbacks(resetStickRunnable)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (cameraPointerId != -1) {
                    val pointerIdx = event.findPointerIndex(cameraPointerId)
                    if (pointerIdx != -1) {
                        val currentX = event.getX(pointerIdx)
                        val currentY = event.getY(pointerIdx)

                        val deltaX = currentX - lastCameraX
                        val deltaY = currentY - lastCameraY

                        lastCameraX = currentX
                        lastCameraY = currentY

                        // Фильтр микротряски пальца (deadzone меньше 0.5 пикселя)
                        if (abs(deltaX) > 0.5f || abs(deltaY) > 0.5f) {
                            // Нормализуем значение в диапазон [-1.0f .. 1.0f]
                            // Ось Y инвертирована, так как в Android координаты Y растут вниз
                            val stickX = (deltaX * swipeSensitivity).coerceIn(-1.0f, 1.0f)
                            val stickY = (-deltaY * swipeSensitivity).coerceIn(-1.0f, 1.0f)

                            sendJoystickEvent(playerIndex, NativeAnalog.RStick, stickX, stickY)

                            // Если палец замер на месте без движения, через 40мс сбрасываем стик в 0,
                            // чтобы камера не продолжала бесконечное вращение
                            cameraResetHandler.removeCallbacks(resetStickRunnable)
                            cameraResetHandler.postDelayed(resetStickRunnable, 40)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerId == cameraPointerId) {
                    cameraPointerId = -1
                    cameraResetHandler.removeCallbacks(resetStickRunnable)
                    sendJoystickEvent(playerIndex, NativeAnalog.RStick, 0f, 0f)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cameraPointerId = -1
                cameraResetHandler.removeCallbacks(resetStickRunnable)
                sendJoystickEvent(playerIndex, NativeAnalog.RStick, 0f, 0f)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraResetHandler.removeCallbacks(resetStickRunnable)
    }

    /** Forwards to [NativeInput.onOverlayButtonEvent], also feeding [MacroRecorder] when a
     *  macro is being recorded - the single chokepoint every button/dpad/stick-click event in
     *  this class goes through. */
    private fun sendButtonEvent(port: Int, button: NativeButton, action: Int) {
        MacroRecorder.recordButtonEvent(port, button, action)
        NativeInput.onOverlayButtonEvent(port, button, action)
    }

    /** Same as [sendButtonEvent], for [NativeInput.onOverlayJoystickEvent]. */
    private fun sendJoystickEvent(port: Int, stick: NativeAnalog, x: Float, y: Float) {
        MacroRecorder.recordJoystickEvent(port, stick, x, y)
        NativeInput.onOverlayJoystickEvent(port, stick, x, y)
    }

    private fun playHaptics(event: MotionEvent) {
        if (BooleanSetting.HAPTIC_FEEDBACK.getBoolean()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP ->
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
            }
        }
    }

    private fun isTouchInputConsumed(track_id: Int): Boolean {
        for (button in overlayButtons) {
            if (button.trackId == track_id) {
                return true
            }
        }
        for (dpad in overlayDpads) {
            if (dpad.trackId == track_id) {
                return true
            }
        }
        for (joystick in overlayJoysticks) {
            if (joystick.trackId == track_id) {
                return true
            }
        }
        return false
    }

    private fun onTouchWhileEditing(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()

        for (button in overlayButtons) {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        button.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        buttonBeingConfigured = button
                        buttonBeingConfigured!!.onConfigureTouch(event)
                        touchStartX = event.getX(pointerIndex)
                        touchStartY = event.getY(pointerIndex)
                        hasMoved = false
                    }

                MotionEvent.ACTION_MOVE -> if (buttonBeingConfigured != null) {
                    val moveDistance = sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        buttonBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (buttonBeingConfigured === button) {
                    if (!hasMoved) {
                        showScaleDialog(
                            buttonBeingConfigured,
                            null,
                            null,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        saveControlPosition(
                            buttonBeingConfigured!!.overlayControlData.id,
                            buttonBeingConfigured!!.bounds.centerX(),
                            buttonBeingConfigured!!.bounds.centerY(),
                            individuaScale = buttonBeingConfigured!!.overlayControlData.individualScale,
                            layout
                        )
                    }
                    buttonBeingConfigured = null
                }
            }
        }

        for (dpad in overlayDpads) {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        dpad.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        dpadBeingConfigured = dpad
                        dpadBeingConfigured!!.onConfigureTouch(event)
                        touchStartX = event.getX(pointerIndex)
                        touchStartY = event.getY(pointerIndex)
                        hasMoved = false
                    }

                MotionEvent.ACTION_MOVE -> if (dpadBeingConfigured != null) {
                    val moveDistance = sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        dpadBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (dpadBeingConfigured === dpad) {
                    if (!hasMoved) {
                        // This was a click, show scale dialog for dpad
                        showScaleDialog(
                            null,
                            dpadBeingConfigured,
                            null,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        // This was a move, save position
                        saveControlPosition(
                            OverlayControl.COMBINED_DPAD.id,
                            dpadBeingConfigured!!.bounds.centerX(),
                            dpadBeingConfigured!!.bounds.centerY(),
                            individuaScale = dpadBeingConfigured!!.individualScale,
                            layout
                        )
                    }
                    dpadBeingConfigured = null
                }
            }
        }

        for (joystick in overlayJoysticks) {
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> if (joystickBeingConfigured == null &&
                    joystick.bounds.contains(fingerPositionX, fingerPositionY)
                ) {
                    joystickBeingConfigured = joystick
                    joystickBeingConfigured!!.onConfigureTouch(event)
                    touchStartX = event.getX(pointerIndex)
                    touchStartY = event.getY(pointerIndex)
                    hasMoved = false
                }

                MotionEvent.ACTION_MOVE -> if (joystickBeingConfigured != null) {
                    val moveDistance = sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        joystickBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (joystickBeingConfigured != null) {
                    if (!hasMoved) {
                        showScaleDialog(
                            null,
                            null,
                            joystickBeingConfigured,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        saveControlPosition(
                            joystickBeingConfigured!!.prefId,
                            joystickBeingConfigured!!.bounds.centerX(),
                            joystickBeingConfigured!!.bounds.centerY(),
                            individuaScale = joystickBeingConfigured!!.individualScale,
                            layout
                        )
                    }
                    joystickBeingConfigured = null
                }
            }
        }

        return true
    }

    private fun addOverlayControls(layout: OverlayLayout) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val overlayControlData = NativeConfig.getOverlayControlData()
        for (data in overlayControlData) {
            if (!data.enabled) {
                continue
            }

            val position = data.positionFromLayout(layout)
            when (data.id) {
                OverlayControl.BUTTON_A.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_a,
                            R.drawable.facebutton_a_depressed,
                            NativeButton.A,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_B.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_b,
                            R.drawable.facebutton_b_depressed,
                            NativeButton.B,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_X.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_x,
                            R.drawable.facebutton_x_depressed,
                            NativeButton.X,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_Y.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_y,
                            R.drawable.facebutton_y_depressed,
                            NativeButton.Y,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_PLUS.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_plus,
                            R.drawable.facebutton_plus_depressed,
                            NativeButton.Plus,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_MINUS.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_minus,
                            R.drawable.facebutton_minus_depressed,
                            NativeButton.Minus,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_HOME.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_home,
                            R.drawable.facebutton_home_depressed,
                            NativeButton.Home,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_CAPTURE.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_screenshot,
                            R.drawable.facebutton_screenshot_depressed,
                            NativeButton.Capture,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_L.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.l_shoulder,
                            R.drawable.l_shoulder_depressed,
                            NativeButton.L,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_R.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.r_shoulder,
                            R.drawable.r_shoulder_depressed,
                            NativeButton.R,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_ZL.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.zl_trigger,
                            R.drawable.zl_trigger_depressed,
                            NativeButton.ZL,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_ZR.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.zr_trigger,
                            R.drawable.zr_trigger_depressed,
                            NativeButton.ZR,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_STICK_L.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.button_l3,
                            R.drawable.button_l3_depressed,
                            NativeButton.LStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_STICK_R.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.button_r3,
                            R.drawable.button_r3_depressed,
                            NativeButton.RStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.STICK_L.id -> {
                    overlayJoysticks.add(
                        initializeOverlayJoystick(
                            context,
                            windowSize,
                            R.drawable.joystick_range,
                            R.drawable.joystick,
                            R.drawable.joystick_depressed,
                            NativeAnalog.LStick,
                            NativeButton.LStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.STICK_R.id -> {
                    overlayJoysticks.add(
                        initializeOverlayJoystick(
                            context,
                            windowSize,
                            R.drawable.joystick_range,
                            R.drawable.joystick,
                            R.drawable.joystick_depressed,
                            NativeAnalog.RStick,
                            NativeButton.RStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.COMBINED_DPAD.id -> {
                    overlayDpads.add(
                        initializeOverlayDpad(
                            context,
                            windowSize,
                            R.drawable.dpad_standard,
                            R.drawable.dpad_standard_cardinal_depressed,
                            R.drawable.dpad_standard_diagonal_depressed,
                            position
                        )
                    )
                }
            }
        }
    }

    fun refreshControls(gameless: Boolean = false) {
        // Store gameless mode if set to true
        if (gameless) {
            gamelessMode = true
        }

        // Remove all the overlay buttons from the HashSet.
        overlayButtons.clear()
        overlayDpads.clear()
        overlayJoysticks.clear()

        // Add all the enabled overlay items back to the HashSet.
        if (gamelessMode || BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()) {
            addOverlayControls(layout)
        }
        invalidate()
    }

    private fun saveControlPosition(
        id: String,
        x: Int,
        y: Int,
        individuaScale: Float,
        layout: OverlayLayout
    ) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val min = windowSize.first
        val max = windowSize.second
        val overlayControlData = NativeConfig.getOverlayControlData()
        val data = overlayControlData.firstOrNull { it.id == id }
        val newPosition = Pair((x - min.x).toDouble() / max.x, (y - min.y).toDouble() / max.y)

        when (layout) {
            OverlayLayout.Landscape -> data?.landscapePosition = newPosition
            OverlayLayout.Portrait -> data?.portraitPosition = newPosition
            OverlayLayout.Foldable -> data?.foldablePosition = newPosition
        }

        data?.individualScale = individuaScale

        NativeConfig.setOverlayControlData(overlayControlData)
    }

    fun setIsInEditMode(editMode: Boolean) {
        inEditMode = editMode
        if (!editMode) {
            scaleDialog?.dismiss()
            scaleDialog = null
            gamelessMode = false
        }

        // Сброс свайпа камеры при смене режима
        cameraPointerId = -1
        cameraResetHandler.removeCallbacks(resetStickRunnable)

        invalidate()
    }

    fun isGamelessMode(): Boolean = gamelessMode

    private fun showScaleDialog(
        button: InputOverlayDrawableButton?,
        dpad: InputOverlayDrawableDpad?,
        joystick: InputOverlayDrawableJoystick?,
        x: Int, y: Int
    ) {
        val overlayControlData = NativeConfig.getOverlayControlData()
        // prevent dialog from being spam opened
        scaleDialog?.dismiss()

        when {
            button != null -> {
                val buttonData =
                    overlayControlData.firstOrNull { it.id == button.overlayControlData.id }
                if (buttonData != null) {
                    scaleDialog =
                        OverlayScaleDialog(context, button.overlayControlData) { newScale ->
                            saveControlPosition(
                                button.overlayControlData.id,
                                button.bounds.centerX(),
                                button.bounds.centerY(),
                                individuaScale = newScale,
                                layout
                            )
                            refreshControls()
                        }

                    scaleDialog?.showDialog(x, y, button.bounds.width(), button.bounds.height())
                }
            }

            dpad != null -> {
                val dpadData =
                    overlayControlData.firstOrNull { it.id == OverlayControl.COMBINED_DPAD.id }
                if (dpadData != null) {
                    scaleDialog = OverlayScaleDialog(context, dpadData) { newScale ->
                        saveControlPosition(
                            OverlayControl.COMBINED_DPAD.id,
                            dpad.bounds.centerX(),
                            dpad.bounds.centerY(),
                            newScale,
                            layout
                        )

                        refreshControls()
                    }

                    scaleDialog?.showDialog(x, y, dpad.bounds.width(), dpad.bounds.height())
                }
            }

            joystick != null -> {
                val joystickData = overlayControlData.firstOrNull { it.id == joystick.prefId }
                if (joystickData != null) {
                    scaleDialog = OverlayScaleDialog(context, joystickData) { newScale ->
                        saveControlPosition(
                            joystick.prefId,
                            joystick.bounds.centerX(),
                            joystick.bounds.centerY(),
                            individuaScale = newScale,
                            layout
                        )

                        refreshControls()
                    }

                    scaleDialog?.showDialog(x, y, joystick.bounds.width(), joystick.bounds.height())
                }
            }
        }
    }

    /**
     * Applies and saves all default values for the overlay
     */
    private fun populateDefaultConfig() {
        val newConfig = OverlayControl.entries.map { it.toOverlayControlData() }
        NativeConfig.setOverlayControlData(newConfig.toTypedArray())
        NativeConfig.saveGlobalConfig()
    }

    /**
     * Checks if any new controls were added to OverlayControl that do not exist within deserialized
     * config and adds / saves them if necessary
     *
     * @param overlayControlData Overlay control data from [NativeConfig.getOverlayControlData]
     */
    private fun checkForNewControls(overlayControlData: Array<OverlayControlData>) {
        val missingControls = mutableListOf<OverlayControlData>()
        OverlayControl.entries.forEach { defaultControl ->
            val controlData = overlayControlData.firstOrNull { it.id == defaultControl.id }
            if (controlData == null) {
                missingControls.add(defaultControl.toOverlayControlData())
            }
        }

        if (missingControls.isNotEmpty()) {
            NativeConfig.setOverlayControlData(
                arrayOf(*overlayControlData, *(missingControls.toTypedArray()))
            )
            NativeConfig.saveGlobalConfig()
        }
    }

    fun resetLayoutVisibilityAndPlacement() {
        defaultOverlayPositionByLayout(layout)

        val overlayControlData = NativeConfig.getOverlayControlData()
        overlayControlData.forEach {
            it.enabled = OverlayControl.from(it.id)?.defaultVisibility == true
            it.individualScale = OverlayControl.from(it.id)?.defaultIndividualScaleResource!!
        }
        NativeConfig.setOverlayControlData(overlayControlData)

        refreshControls()
    }

    fun resetIndividualControlScale() {
        val overlayControlData = NativeConfig.getOverlayControlData()
        overlayControlData.forEach { data ->
            val defaultControlData = OverlayControl.from(data.id) ?: return@forEach
            data.individualScale = defaultControlData.defaultIndividualScaleResource
        }
        NativeConfig.setOverlayControlData(overlayControlData)
        NativeConfig.saveGlobalConfig()
        refreshControls()
    }

    fun applyPreset(preset: OverlayPreset) {
        resetLayoutVisibilityAndPlacement()
        resetIndividualControlScale()

        when (preset) {
            OverlayPreset.Default -> {}

            OverlayPreset.Big -> {
                val overlayControlData = NativeConfig.getOverlayControlData()
                overlayControlData.forEach { it.individualScale = BIG_PRESET_SCALE }
                NativeConfig.setOverlayControlData(overlayControlData)
            }

            OverlayPreset.Swapped -> {
                val overlayControlData = NativeConfig.getOverlayControlData()
                val dpad = overlayControlData.firstOrNull { it.id == OverlayControl.COMBINED_DPAD.id }
                val stickL = overlayControlData.firstOrNull { it.id == OverlayControl.STICK_L.id }
                if (dpad != null && stickL != null) {
                    val dpadLandscape = dpad.landscapePosition
                    val dpadPortrait = dpad.portraitPosition
                    val dpadFoldable = dpad.foldablePosition
                    dpad.landscapePosition = stickL.landscapePosition
                    dpad.portraitPosition = stickL.portraitPosition
                    dpad.foldablePosition = stickL.foldablePosition
                    stickL.landscapePosition = dpadLandscape
                    stickL.portraitPosition = dpadPortrait
                    stickL.foldablePosition = dpadFoldable
                }
                NativeConfig.setOverlayControlData(overlayControlData)
            }
        }

        NativeConfig.saveGlobalConfig()
        refreshControls()
    }

    private fun defaultOverlayPositionByLayout(layout: OverlayLayout) {
        val overlayControlData = NativeConfig.getOverlayControlData()
        for (data in overlayControlData) {
            val defaultControlData = OverlayControl.from(data.id) ?: continue
            val position = defaultControlData.getDefaultPositionForLayout(layout)
            when (layout) {
                OverlayLayout.Landscape -> data.landscapePosition = position
                OverlayLayout.Portrait -> data.portraitPosition = position
                OverlayLayout.Foldable -> data.foldablePosition = position
            }
        }
        NativeConfig.setOverlayControlData(overlayControlData)
    }

    override fun isInEditMode(): Boolean {
        return inEditMode
    }

    companion object {

        const val OVERLAY_VERSION = 1
        private const val BIG_PRESET_SCALE = 1.4f

        private const val LANDSCAPE_OVERLAY_VERSION = 1
        private const val PORTRAIT_OVERLAY_VERSION = 1
        private const val FOLDABLE_OVERLAY_VERSION = 1
        val overlayLayoutVersions = listOf(
            LANDSCAPE_OVERLAY_VERSION,
            PORTRAIT_OVERLAY_VERSION,
            FOLDABLE_OVERLAY_VERSION
        )

        private fun getBitmap(context: Context, drawableId: Int, scale: Float): Bitmap {
            val vectorDrawable = ContextCompat.getDrawable(context, drawableId) as VectorDrawable

            val bitmap = Bitmap.createBitmap(
                (vectorDrawable.intrinsicWidth * scale).toInt(),
                (vectorDrawable.intrinsicHeight * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )

            val dm = context.resources.displayMetrics
            val minScreenDimension = min(dm.widthPixels, dm.heightPixels)

            val maxBitmapDimension = max(bitmap.width, bitmap.height)
            val bitmapScale = scale * minScreenDimension / maxBitmapDimension

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * bitmapScale).toInt(),
                (bitmap.height * bitmapScale).toInt(),
                true
            )

            val canvas = Canvas(scaledBitmap)
            vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
            vectorDrawable.draw(canvas)
            return scaledBitmap
        }

        private fun getSafeScreenSize(
            context: Context,
            screenSize: Pair<Int, Int>
        ): Pair<Point, Point> {
            val windowMetrics = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(context as Activity)
            var maxX = screenSize.first.toFloat()
            var maxY = screenSize.second.toFloat()
            var minX = 0
            var minY = 0

            var cutoutLeft = 0
            var cutoutBottom = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = context.windowManager.currentWindowMetrics.windowInsets.displayCutout
                if (insets != null) {
                    if (insets.boundingRectTop.bottom != 0 &&
                        insets.boundingRectTop.bottom > maxY / 2
                    ) {
                        maxY = insets.boundingRectTop.bottom.toFloat()
                    }
                    if (insets.boundingRectRight.left != 0 &&
                        insets.boundingRectRight.left > maxX / 2
                    ) {
                        maxX = insets.boundingRectRight.left.toFloat()
                    }

                    minX = insets.boundingRectLeft.right - insets.boundingRectLeft.left
                    minY = insets.boundingRectBottom.top - insets.boundingRectBottom.bottom

                    cutoutLeft = insets.boundingRectRight.right - insets.boundingRectRight.left
                    cutoutBottom = insets.boundingRectTop.top - insets.boundingRectTop.bottom
                }
            }

            if (maxX.toInt() != windowMetrics.bounds.width()) minX += cutoutLeft
            if (maxY.toInt() != windowMetrics.bounds.height()) minY += cutoutBottom
            if (minX > 0 && maxX.toInt() == windowMetrics.bounds.width()) {
                maxX -= (minX * 2)
            } else if (minX > 0) {
                maxX -= minX
            }
            if (minY > 0 && maxY.toInt() == windowMetrics.bounds.height()) {
                maxY -= (minY * 2)
            } else if (minY > 0) {
                maxY -= minY
            }

            return Pair(Point(minX, minY), Point(maxX.toInt(), maxY.toInt()))
        }

        private fun initializeOverlayButton(
            context: Context,
            windowSize: Pair<Point, Point>,
            defaultResId: Int,
            pressedResId: Int,
            button: NativeButton,
            overlayControlData: OverlayControlData,
            position: Pair<Double, Double>
        ): InputOverlayDrawableButton {
            val res = context.resources

            var scale: Float = when (overlayControlData.id) {
                OverlayControl.BUTTON_HOME.id,
                OverlayControl.BUTTON_CAPTURE.id,
                OverlayControl.BUTTON_PLUS.id,
                OverlayControl.BUTTON_MINUS.id -> 0.07f

                OverlayControl.BUTTON_L.id,
                OverlayControl.BUTTON_R.id,
                OverlayControl.BUTTON_ZL.id,
                OverlayControl.BUTTON_ZR.id -> 0.26f

                OverlayControl.BUTTON_STICK_L.id,
                OverlayControl.BUTTON_STICK_R.id -> 0.155f

                else -> 0.11f
            }
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            scale *= overlayControlData.individualScale.let { if (it > 0f) it else 1f }

            val defaultStateBitmap = getBitmap(context, defaultResId, scale)
            val pressedStateBitmap = getBitmap(context, pressedResId, scale)
            val overlayDrawable = InputOverlayDrawableButton(
                res,
                defaultStateBitmap,
                pressedStateBitmap,
                button,
                overlayControlData
            )

            val min = windowSize.first
            val max = windowSize.second

            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            overlayDrawable.setBounds(
                drawableX - (width / 2),
                drawableY - (height / 2),
                drawableX + (width / 2),
                drawableY + (height / 2)
            )

            overlayDrawable.setPosition(
                drawableX - (width / 2),
                drawableY - (height / 2)
            )
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }

        private fun initializeOverlayDpad(
            context: Context,
            windowSize: Pair<Point, Point>,
            defaultResId: Int,
            pressedOneDirectionResId: Int,
            pressedTwoDirectionsResId: Int,
            position: Pair<Double, Double>
        ): InputOverlayDrawableDpad {
            val res = context.resources

            val overlayControlData = NativeConfig.getOverlayControlData()
            val dpadData = overlayControlData.firstOrNull { it.id == OverlayControl.COMBINED_DPAD.id }

            var scale = 0.25f
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            if (dpadData != null) {
                scale *= dpadData.individualScale.let { if (it > 0f) it else 1f }
            }

            val defaultStateBitmap =
                getBitmap(context, defaultResId, scale)
            val pressedOneDirectionStateBitmap = getBitmap(context, pressedOneDirectionResId, scale)
            val pressedTwoDirectionsStateBitmap =
                getBitmap(context, pressedTwoDirectionsResId, scale)

            val overlayDrawable = InputOverlayDrawableDpad(
                res,
                defaultStateBitmap,
                pressedOneDirectionStateBitmap,
                pressedTwoDirectionsStateBitmap
            )

            val min = windowSize.first
            val max = windowSize.second

            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            overlayDrawable.setBounds(
                drawableX - (width / 2),
                drawableY - (height / 2),
                drawableX + (width / 2),
                drawableY + (height / 2)
            )

            overlayDrawable.setPosition(drawableX - (width / 2), drawableY - (height / 2))
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }

        private fun initializeOverlayJoystick(
            context: Context,
            windowSize: Pair<Point, Point>,
            resOuter: Int,
            defaultResInner: Int,
            pressedResInner: Int,
            joystick: NativeAnalog,
            button: NativeButton,
            overlayControlData: OverlayControlData,
            position: Pair<Double, Double>
        ): InputOverlayDrawableJoystick {
            val res = context.resources

            var scale = 0.3f
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            scale *= overlayControlData.individualScale.let { if (it > 0f) it else 1f }

            val bitmapOuter = getBitmap(context, resOuter, scale)
            val bitmapInnerDefault = getBitmap(context, defaultResInner, 1.0f)
            val bitmapInnerPressed = getBitmap(context, pressedResInner, 1.0f)

            val min = windowSize.first
            val max = windowSize.second

            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val outerScale = 1.66f

            val outerSize = bitmapOuter.width
            val outerRect = Rect(
                drawableX - (outerSize / 2),
                drawableY - (outerSize / 2),
                drawableX + (outerSize / 2),
                drawableY + (outerSize / 2)
            )
            val innerRect =
                Rect(0, 0, (outerSize / outerScale).toInt(), (outerSize / outerScale).toInt())

            val overlayDrawable = InputOverlayDrawableJoystick(
                res,
                bitmapOuter,
                bitmapInnerDefault,
                bitmapInnerPressed,
                outerRect,
                innerRect,
                joystick,
                button,
                overlayControlData.id
            )

            overlayDrawable.setPosition(drawableX, drawableY)
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }
    }
}
