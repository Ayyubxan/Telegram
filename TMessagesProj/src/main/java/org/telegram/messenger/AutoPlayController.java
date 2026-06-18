package org.telegram.messenger;

import android.content.Context;
import android.media.AudioManager;

import java.util.ArrayList;

// FORK MOD (auto play): when an incoming message replies to an audio message (voice or music)
// and its text is "play", automatically play that audio — even if the chat isn't open.
public class AutoPlayController {

    public static void checkNewMessages(int account, ArrayList<MessageObject> messages, boolean scheduled, int mode) {
        if (scheduled || mode != 0 || messages == null || messages.isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject mo = messages.get(i);
            // Radio mode: auto-play every incoming voice message.
            if (mo != null && !mo.isOut() && mo.isVoice() && RadioMode.isEnabled(mo.getDialogId())) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!MediaController.getInstance().isPlayingMessage(mo)) {
                        maxMediaVolume();
                        MediaController.getInstance().playMessage(mo);
                    }
                }, 150);
            }
            if (!isPlayCommand(mo)) {
                continue;
            }
            // The replied message may still be loading (loadReplyMessagesForMessages is async),
            // so try right away and once more shortly after.
            AndroidUtilities.runOnUIThread(() -> tryPlay(mo), 150);
            AndroidUtilities.runOnUIThread(() -> tryPlay(mo), 1200);
        }
    }

    private static boolean isPlayCommand(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || !mo.isReply()) {
            return false;
        }
        String text = mo.messageOwner.message;
        return text != null && text.trim().equalsIgnoreCase("play");
    }

    private static void tryPlay(MessageObject mo) {
        MessageObject replied = mo.replyMessageObject;
        if (replied == null) {
            return;
        }
        if (replied.isVoice() || replied.isMusic()) {
            if (!MediaController.getInstance().isPlayingMessage(replied)) {
                MediaController.getInstance().playMessage(replied);
            }
        }
    }

    // FORK MOD (radio mode): crank the media volume to max before auto-playing.
    private static void maxMediaVolume() {
        try {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
