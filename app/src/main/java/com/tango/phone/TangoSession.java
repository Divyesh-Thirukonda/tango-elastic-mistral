package com.tango.phone;

import android.content.Context;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** Android invokes this when the user uses the system assistant gesture/button. */
public final class TangoSession extends VoiceInteractionSession {
    private final Context context;

    public TangoSession(Context context) {
        super(context);
        this.context = context;
    }

    @Override public void onPrepareShow(Bundle arguments, int flags) {
        super.onPrepareShow(arguments, flags);
        setUiEnabled(false);
    }

    @Override public void onShow(Bundle arguments, int flags) {
        super.onShow(arguments, flags);
        try { startAssistantActivity(PhoneController.listenIntent(context)); }
        catch (RuntimeException error) { PhoneController.triggerListen(context); }
        finally { finish(); }
    }
}
