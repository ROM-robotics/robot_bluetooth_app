#!/bin/bash
# =============================================================
# Bluetooth MAC Address to QR Code Generator (Zenity GUI)
# =============================================================
# Dependencies: zenity, qrencode
# Install: sudo apt install zenity qrencode
# =============================================================

# --- Check dependencies ---
for cmd in zenity qrencode; do
    if ! command -v "$cmd" &> /dev/null; then
        zenity --error --title="Missing Dependency" \
            --text="'$cmd' is not installed.\n\nPlease install it:\n  sudo apt install $cmd" \
            --width=350 2>/dev/null || \
            echo "Error: '$cmd' is not installed. Install with: sudo apt install $cmd"
        exit 1
    fi
done

# --- MAC Address input dialog ---
MAC_ADDRESS=$(zenity --entry \
    --title="Bluetooth QR Code Generator" \
    --text="Bluetooth MAC Address ထည့်ပါ\n(e.g. AA:BB:CC:DD:EE:FF)" \
    --entry-text="" \
    --width=400 2>/dev/null)

# User cancelled
if [[ $? -ne 0 ]] || [[ -z "$MAC_ADDRESS" ]]; then
    exit 0
fi

# --- Validate MAC address format ---
MAC_REGEX="^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$"
if [[ ! "$MAC_ADDRESS" =~ $MAC_REGEX ]]; then
    zenity --error --title="Invalid MAC Address" \
        --text="MAC Address format မှားနေပါတယ်။\n\nမှန်ကန်သော format: AA:BB:CC:DD:EE:FF" \
        --width=350 2>/dev/null
    exit 1
fi

# --- Normalize to uppercase with colons ---
MAC_ADDRESS=$(echo "$MAC_ADDRESS" | tr 'a-f-' 'A-F:')

# --- Generate QR code as temporary PNG ---
TEMP_QR="/tmp/bt_qr_${MAC_ADDRESS//:/}.png"
qrencode -o "$TEMP_QR" -s 10 -m 2 --foreground=000000 --background=FFFFFF "$MAC_ADDRESS"

if [[ $? -ne 0 ]]; then
    zenity --error --title="QR Generation Failed" \
        --text="QR Code generate မအောင်မြင်ပါ။" \
        --width=300 2>/dev/null
    exit 1
fi

# --- Show QR code with Save / Close buttons ---
zenity --question \
    --title="QR Code - $MAC_ADDRESS" \
    --text="<b>MAC Address:</b> $MAC_ADDRESS\n\n" \
    --icon-name="" \
    --window-icon="$TEMP_QR" \
    --ok-label="💾 Save PNG" \
    --cancel-label="Close" \
    --width=300 2>/dev/null &
QUESTION_PID=$!

# Show QR image in a separate viewer
eog "$TEMP_QR" 2>/dev/null &
VIEWER_PID=$!

# Wait for the dialog
wait $QUESTION_PID
DIALOG_RESULT=$?

# Kill image viewer
kill $VIEWER_PID 2>/dev/null

# --- Save PNG if user clicked Save ---
if [[ $DIALOG_RESULT -eq 0 ]]; then
    SAVE_PATH=$(zenity --file-selection \
        --save \
        --confirm-overwrite \
        --title="QR Code PNG ကို Save လုပ်ပါ" \
        --filename="$HOME/bt_qr_${MAC_ADDRESS//:/}.png" \
        --file-filter="PNG files (*.png)|*.png" \
        --file-filter="All files|*" \
        --width=600 --height=400 2>/dev/null)

    if [[ $? -eq 0 ]] && [[ -n "$SAVE_PATH" ]]; then
        # Ensure .png extension
        [[ "$SAVE_PATH" != *.png ]] && SAVE_PATH="${SAVE_PATH}.png"
        cp "$TEMP_QR" "$SAVE_PATH"
        zenity --info --title="Saved!" \
            --text="QR Code ကို အောင်မြင်စွာ save လုပ်ပြီးပါပြီ။\n\n<b>$SAVE_PATH</b>" \
            --width=400 2>/dev/null
    fi
fi

# --- Cleanup ---
rm -f "$TEMP_QR"
exit 0
